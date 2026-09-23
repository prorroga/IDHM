package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.api.TransferResult;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.Storage;
import net.prorrogam.idhm.database.StorageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class EconomyService {

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, PlayerBalances> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loadGates = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    private static final int LOCK_STRIPES = 256;

    private final Object[] playerLocks = new Object[LOCK_STRIPES];

    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            playerLocks[i] = new Object();
        }
    }

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
            return;
        }

        storageManager.submit(s -> s.loadAllBalances(uuid)).whenComplete((balances, ex) -> {
            try {
                if (ex != null) {
                    logger.warning("Failed to load balances for " + uuid + ": " + ex.getMessage());
                    return;
                }
                if (!onlinePlayers.contains(uuid)) {
                    return;
                }
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
                .map(e -> new Storage.BalanceUpdate(uuid, playerName, e.getKey(), e.getValue()))
                .toList();

        storageManager.submit(s -> {
            s.saveBalances(updates);
            return null;
        }).whenComplete((v, ex) -> {
            if (ex == null) {
                return;
            }
            if (shuttingDown) {
                logger.severe("Failed to flush balances for " + playerName
                        + " (" + uuid + ") on quit, and the plugin is shutting down: data lost. Cause: "
                        + ex.getMessage());
                return;
            }
            logger.severe("Failed to flush balances for " + playerName
                    + " (" + uuid + ") on quit: " + ex.getMessage() + ". Re-caching for retry.");
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
        shuttingDown = true;

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<UUID, PlayerBalances> entry : cache.entrySet()) {
            UUID uuid = entry.getKey();
            String name = playerNames.getOrDefault(uuid, fallbackName(uuid));
            Map<String, BigDecimal> snapshot = entry.getValue().snapshot();
            if (snapshot.isEmpty()) {
                continue;
            }
            List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                    .map(e -> new Storage.BalanceUpdate(uuid, name, e.getKey(), e.getValue()))
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

    public CompletableFuture<Void> flushDirty() {
        if (dirty.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Set<UUID> batch = new HashSet<>(dirty);
        dirty.removeAll(batch);

        List<CompletableFuture<Object>> futures = new ArrayList<>();

        for (UUID uuid : batch) {
            PlayerBalances pb = cache.get(uuid);
            if (pb == null) {
                continue;
            }
            Map<String, BigDecimal> snapshot = pb.snapshot();
            if (snapshot.isEmpty()) {
                continue;
            }
            String name = playerNames.getOrDefault(uuid, fallbackName(uuid));
            List<Storage.BalanceUpdate> updates = snapshot.entrySet().stream()
                    .map(e -> new Storage.BalanceUpdate(uuid, name, e.getKey(), e.getValue()))
                    .toList();

            futures.add(storageManager.submit(s -> {
                s.saveBalances(updates);
                return null;
            }).whenComplete((v, ex) -> {
                if (ex != null) {
                    logger.warning("Periodic flush failed for " + uuid
                            + ": " + ex.getMessage() + ". Re-marking dirty.");
                    dirty.add(uuid);
                }
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public BigDecimal balance(UUID uuid, String currencyId) {
        Currency currency = registry.get(currencyId);
        if (currency == null) {
            throw new IllegalArgumentException("Unknown currency: " + currencyId);
        }
        PlayerBalances pb = cache.get(uuid);
        if (pb == null) {
            return currency.defaultBalance();
        }
        BigDecimal value = pb.get(currencyId);
        return value != null ? value : currency.defaultBalance();
    }

    public boolean isOnline(UUID uuid) {
        return onlinePlayers.contains(uuid);
    }

    public Map<UUID, Map<String, BigDecimal>> cachedBalances() {
        Map<UUID, Map<String, BigDecimal>> result = new ConcurrentHashMap<>();
        cache.forEach((uuid, pb) -> result.put(uuid, pb.snapshot()));
        return result;
    }

    public String cachedName(UUID uuid) {
        return playerNames.get(uuid);
    }

    public CompletableFuture<BigDecimal> depositAsync(UUID uuid, String currencyId,
                                                      BigDecimal amount) {
        return mutateAsync(uuid, currencyId, amount, true);
    }

    public CompletableFuture<BigDecimal> withdrawAsync(UUID uuid, String currencyId,
                                                       BigDecimal amount) {
        return mutateAsync(uuid, currencyId, amount, false);
    }

    public CompletableFuture<BigDecimal> setBalanceAsync(UUID uuid, String currencyId,
                                                         BigDecimal newValue) {
        CompletableFuture<Void> gate = loadGates.get(uuid);
        if (gate != null) {
            return gate.thenCompose(v -> setBalanceAsync(uuid, currencyId, newValue));
        }

        Currency currency = registry.get(currencyId);
        if (currency == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown currency: " + currencyId));
        }
        if (newValue.signum() < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Amount must not be negative, got " + newValue));
        }
        BigDecimal max = currency.maxBalance();
        if (max != null && newValue.compareTo(max) > 0) {
            return CompletableFuture.failedFuture(
                    new MaxBalanceException(currencyId, newValue, max));
        }

        PlayerBalances pb = cache.get(uuid);
        if (pb == null) {
            CompletableFuture<Void> lateGate = loadGates.get(uuid);
            if (lateGate != null) {
                return lateGate.thenCompose(v -> setBalanceAsync(uuid, currencyId, newValue));
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Player is not online: " + uuid));
        }

        Object lock = lockFor(uuid);
        try {
            synchronized (lock) {
                pb.put(currencyId, newValue);
                dirty.add(uuid);
                return CompletableFuture.completedFuture(newValue);
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public BigDecimal depositSync(UUID uuid, String currencyId, BigDecimal amount) {
        return mutateSync(uuid, currencyId, amount, true);
    }

    public BigDecimal withdrawSync(UUID uuid, String currencyId, BigDecimal amount) {
        return mutateSync(uuid, currencyId, amount, false);
    }

    private BigDecimal mutateSync(UUID uuid, String currencyId,
                                  BigDecimal amount, boolean deposit) {
        if (loadGates.containsKey(uuid)) {
            throw new IllegalStateException("Player is still loading");
        }
        Currency currency = registry.get(currencyId);
        if (currency == null) {
            throw new IllegalArgumentException("Unknown currency: " + currencyId);
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
        PlayerBalances pb = cache.get(uuid);
        if (pb == null) {
            throw new IllegalStateException("Player is not online: " + uuid);
        }
        Object lock = lockFor(uuid);
        synchronized (lock) {
            BigDecimal newBalance = pb.compute(currencyId, (id, current) -> {
                BigDecimal base = current != null ? current : currency.defaultBalance();
                if (deposit) {
                    BigDecimal candidate = base.add(amount);
                    BigDecimal max = currency.maxBalance();
                    if (max != null && candidate.compareTo(max) > 0) {
                        throw new MaxBalanceException(currencyId, candidate, max);
                    }
                    return candidate;
                } else {
                    if (base.compareTo(amount) < 0) {
                        throw new InsufficientFundsException(currencyId, base, amount);
                    }
                    return base.subtract(amount);
                }
            });
            dirty.add(uuid);
            return newBalance;
        }
    }

    public CompletableFuture<TransferResult> transferAsync(UUID from, UUID to,
                                                           String currencyId,
                                                           BigDecimal amount) {
        CompletableFuture<Void> fromGate = loadGates.get(from);
        CompletableFuture<Void> toGate = loadGates.get(to);
        if (fromGate != null || toGate != null) {
            CompletableFuture<Void> combined = CompletableFuture.allOf(
                    fromGate != null ? fromGate : CompletableFuture.completedFuture(null),
                    toGate != null ? toGate : CompletableFuture.completedFuture(null));
            return combined.thenCompose(v -> transferAsync(from, to, currencyId, amount));
        }

        if (from.equals(to)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Cannot transfer to self"));
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

        PlayerBalances fromPb = cache.get(from);
        PlayerBalances toPb = cache.get(to);
        if (fromPb == null || toPb == null) {
            CompletableFuture<Void> lateGateA = loadGates.get(from);
            CompletableFuture<Void> lateGateB = loadGates.get(to);
            if (lateGateA != null || lateGateB != null) {
                CompletableFuture<Void> combined = CompletableFuture.allOf(
                        lateGateA != null ? lateGateA : CompletableFuture.completedFuture(null),
                        lateGateB != null ? lateGateB : CompletableFuture.completedFuture(null));
                return combined.thenCompose(v -> transferAsync(from, to, currencyId, amount));
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Transfer requires both players online"));
        }

        try {
            TransferResult result = doTransfer(fromPb, toPb, from, to, currencyId, currency, amount);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<BigDecimal> mutateAsync(UUID uuid, String currencyId,
                                                      BigDecimal amount,
                                                      boolean deposit) {
        CompletableFuture<Void> gate = loadGates.get(uuid);
        if (gate != null) {
            return gate.thenCompose(v -> mutateAsync(uuid, currencyId, amount, deposit));
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
        if (pb == null) {
            CompletableFuture<Void> lateGate = loadGates.get(uuid);
            if (lateGate != null) {
                return lateGate.thenCompose(v -> mutateAsync(uuid, currencyId, amount, deposit));
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Player is not online: " + uuid));
        }

        Object lock = lockFor(uuid);
        try {
            synchronized (lock) {
                BigDecimal newBalance = pb.compute(currencyId, (id, current) -> {
                    BigDecimal base = current != null ? current : currency.defaultBalance();
                    if (deposit) {
                        BigDecimal candidate = base.add(amount);
                        BigDecimal max = currency.maxBalance();
                        if (max != null && candidate.compareTo(max) > 0) {
                            throw new MaxBalanceException(currencyId, candidate, max);
                        }
                        return candidate;
                    } else {
                        if (base.compareTo(amount) < 0) {
                            throw new InsufficientFundsException(currencyId, base, amount);
                        }
                        return base.subtract(amount);
                    }
                });
                dirty.add(uuid);
                return CompletableFuture.completedFuture(newBalance);
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private TransferResult doTransfer(PlayerBalances fromPb, PlayerBalances toPb,
                                      UUID from, UUID to, String currencyId,
                                      Currency currency, BigDecimal amount) {
        int idxFrom = Math.floorMod(from.hashCode(), LOCK_STRIPES);
        int idxTo = Math.floorMod(to.hashCode(), LOCK_STRIPES);
        int firstIdx = Math.min(idxFrom, idxTo);
        int secondIdx = Math.max(idxFrom, idxTo);

        synchronized (playerLocks[firstIdx]) {
            synchronized (playerLocks[secondIdx]) {
                BigDecimal fromBase = fromPb.get(currencyId);
                BigDecimal toBase = toPb.get(currencyId);
                if (fromBase == null) fromBase = currency.defaultBalance();
                if (toBase == null) toBase = currency.defaultBalance();

                if (fromBase.compareTo(amount) < 0) {
                    throw new InsufficientFundsException(currencyId, fromBase, amount);
                }
                BigDecimal candidateTo = toBase.add(amount);
                BigDecimal max = currency.maxBalance();
                if (max != null && candidateTo.compareTo(max) > 0) {
                    throw new MaxBalanceException(currencyId, candidateTo, max);
                }

                BigDecimal newFrom = fromBase.subtract(amount);

                fromPb.put(currencyId, newFrom);
                toPb.put(currencyId, candidateTo);

                dirty.add(from);
                dirty.add(to);

                return new TransferResult(newFrom, candidateTo);
            }
        }
    }

    private Object lockFor(UUID uuid) {
        return playerLocks[Math.floorMod(uuid.hashCode(), LOCK_STRIPES)];
    }

    private String fallbackName(UUID uuid) {
        String id = uuid.toString();
        return "unknown-" + id.substring(0, 8);
    }
}