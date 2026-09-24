package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.StorageManager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class LeaderboardCache {

    private static final int TOP_SIZE = 100;
    private static final long MERGED_TTL_MILLIS = 1_000L;

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final EconomyService economy;
    private final Logger logger;

    private final Map<String, AtomicReference<Snapshot>> snapshots =
            new ConcurrentHashMap<>();

    private final Map<String, MergedEntry> mergedCache = new ConcurrentHashMap<>();

    public record Snapshot(List<BalanceEntry> entries) {
        public static final Snapshot EMPTY = new Snapshot(List.of());
    }

    private record MergedEntry(List<BalanceEntry> entries, long expiresAtMillis) {
        boolean isFresh(long now) {
            return now < expiresAtMillis;
        }
    }

    public LeaderboardCache(CurrencyRegistry registry,
                            StorageManager storageManager,
                            EconomyService economy,
                            Logger logger) {
        this.registry = registry;
        this.storageManager = storageManager;
        this.economy = economy;
        this.logger = logger;

        for (Currency currency : registry.all()) {
            snapshots.put(currency.id(), new AtomicReference<>(Snapshot.EMPTY));
        }
    }

    public CompletableFuture<Void> refresh() {
        CompletableFuture<?>[] futures = registry.all().stream()
                .map(currency -> refreshCurrency(currency.id()))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    public List<BalanceEntry> top(String currencyId) {
        return top(currencyId, Integer.MAX_VALUE);
    }

    public List<BalanceEntry> top(String currencyId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<BalanceEntry> merged = mergedView(currencyId);
        if (limit >= merged.size()) {
            return merged;
        }
        return merged.subList(0, limit);
    }

    public int rank(UUID playerId, String currencyId) {
        List<BalanceEntry> merged = mergedView(currencyId);
        String uuid = playerId.toString();
        for (int i = 0; i < merged.size(); i++) {
            if (merged.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }
        return 0;
    }

    private List<BalanceEntry> mergedView(String currencyId) {
        long now = System.currentTimeMillis();
        MergedEntry cached = mergedCache.get(currencyId);

        if (cached != null && cached.isFresh(now)) {
            return cached.entries();
        }

        List<BalanceEntry> computed = computeMerged(currencyId);
        mergedCache.put(currencyId, new MergedEntry(computed, now + MERGED_TTL_MILLIS));
        return computed;
    }

    private List<BalanceEntry> computeMerged(String currencyId) {
        AtomicReference<Snapshot> ref = snapshots.get(currencyId);
        List<BalanceEntry> dbSnapshot = ref != null ? ref.get().entries() : List.of();

        Map<UUID, Map<String, BigDecimal>> live = economy.cachedBalances();

        Map<String, BalanceEntry> merged = new LinkedHashMap<>();
        for (BalanceEntry entry : dbSnapshot) {
            merged.put(entry.uuid(), entry);
        }
        live.forEach((uuid, balances) -> {
            BigDecimal balance = balances.get(currencyId);
            if (balance == null) {
                return;
            }
            String name = economy.cachedName(uuid);
            if (name == null) {
                BalanceEntry dbEntry = merged.get(uuid.toString());
                if (dbEntry == null) {
                    return;
                }
                name = dbEntry.name();
            }
            merged.put(uuid.toString(),
                    new BalanceEntry(uuid.toString(), name, balance));
        });

        return merged.values().stream()
                .filter(e -> e.balance().signum() > 0)
                .sorted((a, b) -> b.balance().compareTo(a.balance()))
                .toList();
    }

    private CompletableFuture<Void> refreshCurrency(String currencyId) {
        AtomicReference<Snapshot> ref = snapshots.get(currencyId);
        if (ref == null) {
            return CompletableFuture.completedFuture(null);
        }

        return storageManager.submit(s -> s.topBalances(currencyId, TOP_SIZE))
                .thenAccept(entries -> {
                    ref.set(new Snapshot(List.copyOf(entries)));
                    mergedCache.remove(currencyId);
                })
                .exceptionally(ex -> {
                    logger.warning("Failed to refresh leaderboard for '"
                            + currencyId + "': " + ex.getMessage());
                    return null;
                });
    }
}