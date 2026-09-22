package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.Storage;
import net.prorrogam.idhm.database.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class EconomyService {

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, PlayerBalances> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> currencyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loadGates = new ConcurrentHashMap<>();

    public EconomyService(CurrencyRegistry registry,
                          StorageManager storageManager,
                          Logger logger) {
        this.registry = registry;
        this.storageManager = storageManager;
        this.logger = logger;
    }

    public void markOnline(UUID uuid, String name) {
        onlinePlayers.add(uuid);
        playerNames.put(uuid, name);
    }

    public void markOffline(UUID uuid) {
        onlinePlayers.remove(uuid);
    }

    public void loadPlayer(UUID uuid) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> existing = loadGates.putIfAbsent(uuid, gate);
        if (existing != null) {
            return; // A load is already in flight.
        }

        storageManager.submit(s -> s.loadAllBalances(uuid))
                .whenComplete((balances, ex) -> {
                    try {
                        if (ex != null) {
                            logger.warning("Failed to load balances for " + uuid
                                    + ": " + ex.getMessage());
                            return;
                        }
                        if (!onlinePlayers.contains(uuid)) {
                            return;
                        }
                        // Build the container locally, then publish atomically.
                        PlayerBalances loaded = new PlayerBalances();
                        balances.forEach(loaded::put);
                        PlayerBalances prev = cache.putIfAbsent(uuid, loaded);
                        if (prev != null) {
                            balances.forEach(prev::putIfAbsent);
                        }
                    } finally {
                        loadGates.remove(uuid, gate);
                        gate.complete(null);
                    }
                });
    }

    public void unloadPlayer(UUID uuid, String playerName) {
        PlayerBalances pb = cache.remove(uuid);
        dirty.remove(uuid);
        playerNames.remove(uuid);
        if (pb == null) {
            return;
        }

        Map<String, BigDecimal> snapshot = pb.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }

        List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                .map(e -> new Storage.BalanceUpdate(
                        uuid, playerName, e.getKey(), e.getValue()))
                .toList();

        storageManager.submit(s -> {
            s.saveBalances(updates);
            return null;
        }).whenComplete((v, ex) -> {
            if (ex == null) {
                return;
            }
            logger.severe("Failed to flush balances for " + playerName
                    + " (" + uuid + ") on quit: " + ex.getMessage()
                    + ". Re-caching for retry.");
            // Re-insert for the periodic flush. putIfAbsent so we don't
            // overwrite anything a racing mutation may have added.
            PlayerBalances reinserted = cache.putIfAbsent(uuid, pb);
            if (reinserted == null) {
                dirty.add(uuid);
                playerNames.putIfAbsent(uuid, playerName);
            } else {
                snapshot.forEach(reinserted::putIfAbsent);
                dirty.add(uuid);
            }
        });
    }

    public void loadAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            markOnline(uuid, player.getName());
            loadPlayer(uuid);
        }
    }

    public void shutdown() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<UUID, PlayerBalances> entry : cache.entrySet()) {
            UUID uuid = entry.getKey();
            String name = playerNames.getOrDefault(uuid, uuid.toString());
            Map<String, BigDecimal> snapshot = entry.getValue().snapshot();
            if (snapshot.isEmpty()) {
                continue;
            }
            List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                    .map(e -> new Storage.BalanceUpdate(
                            uuid, name, e.getKey(), e.getValue()))
                    .toList();
            futures.add(storageManager.submit(s -> {
                s.saveBalances(updates);
                return null;
            }));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.severe("Shutdown flush failed: " + e.getMessage());
        }

        cache.clear();
        dirty.clear();
        onlinePlayers.clear();
        playerNames.clear();
        loadGates.clear();
    }

    public BigDecimal balance(UUID uuid, String currencyId) {
        Currency currency = registry.get(currencyId);
        if (currency == null) {
            return BigDecimal.ZERO;
        }
        PlayerBalances pb = cache.get(uuid);
        if (pb == null) {
            return currency.defaultBalance();
        }
        BigDecimal value = pb.get(currencyId);
        return value != null ? value : currency.defaultBalance();
    }

    public CompletableFuture<BigDecimal> depositAsync(UUID uuid, String currencyId,
                                                      BigDecimal amount, String reason) {
        CompletableFuture<Void> gate = loadGates.get(uuid);
        if (gate != null) {
            return gate.thenCompose(v -> depositAsync(uuid, currencyId, amount, reason));
        }

        Currency currency = registry.get(currencyId);
        if (currency == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown currency: " + currencyId));
        }
        if (amount.signum() <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Amount must be positive, got " + amount));
        }

        PlayerBalances pb = cache.get(uuid);
        if (pb != null) {
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            BigDecimal newBalance = pb.compute(currencyId, (id, current) -> {
                BigDecimal base = current != null ? current : currency.defaultBalance();
                BigDecimal candidate = base.add(amount);
                BigDecimal max = currency.maxBalance();
                if (max != null && candidate.compareTo(max) > 0) {
                    failure.set(new MaxBalanceException(currencyId, candidate, max));
                    return current;
                }
                return candidate;
            });
            if (failure.get() != null) {
                return CompletableFuture.failedFuture(failure.get());
            }
            dirty.add(uuid);
            return CompletableFuture.completedFuture(newBalance);
        }

        String name = resolvePlayerName(uuid);
        Object lock = currencyLocks.computeIfAbsent(currencyId, k -> new Object());
        return storageManager.submit(s -> {
            synchronized (lock) {
                BigDecimal current = s.loadBalance(uuid, currencyId);
                BigDecimal candidate = current.add(amount);
                BigDecimal max = currency.maxBalance();
                if (max != null && candidate.compareTo(max) > 0) {
                    throw new MaxBalanceException(currencyId, candidate, max);
                }
                s.saveBalance(uuid, name, currencyId, candidate);
                return candidate;
            }
        });
    }

    public CompletableFuture<BigDecimal> withdrawAsync(UUID uuid, String currencyId,
                                                       BigDecimal amount, String reason) {
        CompletableFuture<Void> gate = loadGates.get(uuid);
        if (gate != null) {
            return gate.thenCompose(v -> withdrawAsync(uuid, currencyId, amount, reason));
        }

        Currency currency = registry.get(currencyId);
        if (currency == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown currency: " + currencyId));
        }
        if (amount.signum() <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Amount must be positive, got " + amount));
        }

        PlayerBalances pb = cache.get(uuid);
        if (pb != null) {
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            BigDecimal newBalance = pb.compute(currencyId, (id, current) -> {
                BigDecimal base = current != null ? current : currency.defaultBalance();
                if (base.compareTo(amount) < 0) {
                    failure.set(new InsufficientFundsException(currencyId, base, amount));
                    return current;
                }
                return base.subtract(amount);
            });
            if (failure.get() != null) {
                return CompletableFuture.failedFuture(failure.get());
            }
            dirty.add(uuid);
            return CompletableFuture.completedFuture(newBalance);
        }

        String name = resolvePlayerName(uuid);
        Object lock = currencyLocks.computeIfAbsent(currencyId, k -> new Object());
        return storageManager.submit(s -> {
            synchronized (lock) {
                BigDecimal current = s.loadBalance(uuid, currencyId);
                if (current.compareTo(amount) < 0) {
                    throw new InsufficientFundsException(currencyId, current, amount);
                }
                BigDecimal candidate = current.subtract(amount);
                s.saveBalance(uuid, name, currencyId, candidate);
                return candidate;
            }
        });
    }

    private String resolvePlayerName(UUID uuid) {
        String name = playerNames.get(uuid);
        return name != null ? name : uuid.toString();
    }
}
