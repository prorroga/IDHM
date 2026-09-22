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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Core economy service.
 * <p>
 * Owns the online-player balance cache, the dirty set, the per-player
 * locks, and the join gates. All mutations go through this class.
 * <p>
 * <b>Online only.</b> Deposit and withdraw require the player to have a
 * cached entry (i.e. to be online). Offline support is deferred.
 * <p>
 * <b>Threading (Folia):</b> reads use {@link ConcurrentHashMap} directly.
 * Writes acquire a per-player lock for cross-operation exclusion; the
 * critical section is pure in-memory arithmetic (nanoseconds). Database
 * I/O goes through {@link StorageManager} asynchronously.
 * <p>
 * <b>Blocking:</b> deposit/withdraw/transfer acquire a per-player lock
 * and execute the critical section on the caller thread. The section is
 * pure in-memory arithmetic; callers should not extend it with blocking
 * work.
 */
public final class EconomyService {

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, PlayerBalances> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> playerNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> playerLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> loadGates = new ConcurrentHashMap<>();

    // Set to true by shutdown(). Never reset: a new EconomyService
    // instance is created on every onEnable (including /reload), so this
    // flag is per-lifecycle.
    private volatile boolean shuttingDown = false;

    public EconomyService(CurrencyRegistry registry,
                          StorageManager storageManager,
                          Logger logger) {
        this.registry = registry;
        this.storageManager = storageManager;
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // Lifecycle: join / quit
    // ------------------------------------------------------------------

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
                        PlayerBalances loaded = new PlayerBalances();
                        balances.forEach(loaded::put);
                        PlayerBalances prev = cache.putIfAbsent(uuid, loaded);
                        if (prev != null) {
                            // Only reachable if a previous unloadPlayer failed
                            // and re-inserted its stale container.
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
            if (shuttingDown) {
                logger.severe("Failed to flush balances for " + playerName
                        + " (" + uuid + ") on quit, and the plugin is"
                        + " shutting down: data lost. Cause: " + ex.getMessage());
                return;
            }
            logger.severe("Failed to flush balances for " + playerName
                    + " (" + uuid + ") on quit: " + ex.getMessage()
                    + ". Re-caching for retry.");
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

    // ------------------------------------------------------------------
    // Reads (sync, cache-only)
    // ------------------------------------------------------------------

    /**
     * Returns the cached balance, or the currency's default if the player
     * is offline or has never held it.
     * <p>
     * <b>Never blocks.</b> Safe to call from any thread. Used by Vault.
     * <b>Cache-only:</b> if the player is offline, the returned value is
     * the currency default, not the real balance from the database.
     *
     * @throws IllegalArgumentException if {@code currencyId} is unknown
     */
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

    // ------------------------------------------------------------------
    // Writes (async API, sync implementation)
    // ------------------------------------------------------------------

    public CompletableFuture<BigDecimal> depositAsync(UUID uuid, String currencyId,
                                                      BigDecimal amount) {
        return mutateAsync(uuid, currencyId, amount, true);
    }

    public CompletableFuture<BigDecimal> withdrawAsync(UUID uuid, String currencyId,
                                                       BigDecimal amount) {
        return mutateAsync(uuid, currencyId, amount, false);
    }

    public CompletableFuture<TransferResult> transferAsync(UUID from, UUID to,
                                                           String currencyId,
                                                           BigDecimal amount) {
        // Gates: wait for any in-flight load of either player.
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
            // Late-gate: the load may have been installed between our
            // first gate check and the cache read.
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

    // ------------------------------------------------------------------
    // Internal: shared mutation logic
    // ------------------------------------------------------------------

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
        // Lock ordering by UUID to prevent deadlocks between two transfers
        // running in opposite directions.
        Object firstLock = lockFor(from.compareTo(to) < 0 ? from : to);
        Object secondLock = lockFor(from.compareTo(to) < 0 ? to : from);

        synchronized (firstLock) {
            synchronized (secondLock) {
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
                BigDecimal newTo = candidateTo;

                fromPb.put(currencyId, newFrom);
                toPb.put(currencyId, newTo);

                dirty.add(from);
                dirty.add(to);

                return new TransferResult(newFrom, newTo);
            }
        }
    }

    private Object lockFor(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new Object());
    }

    private String fallbackName(UUID uuid) {
        String id = uuid.toString();
        return "unknown-" + id.substring(0, 8);
    }
}