package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.StorageManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class LeaderboardCache {

    private static final int TOP_SIZE = 100;

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final Logger logger;

    private final Map<String, AtomicReference<List<BalanceEntry>>> snapshots =
            new ConcurrentHashMap<>();

    public LeaderboardCache(CurrencyRegistry registry,
                            StorageManager storageManager,
                            Logger logger) {
        this.registry = registry;
        this.storageManager = storageManager;
        this.logger = logger;

        for (Currency currency : registry.all()) {
            snapshots.put(currency.id(), new AtomicReference<>(List.of()));
        }
    }

    public CompletableFuture<Void> refresh() {
        CompletableFuture<?>[] futures = registry.all().stream()
                .map(currency -> refreshCurrency(currency.id()))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    public List<BalanceEntry> top(String currencyId) {
        AtomicReference<List<BalanceEntry>> ref = snapshots.get(currencyId);
        return ref != null ? ref.get() : List.of();
    }

    public List<BalanceEntry> top(String currencyId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<BalanceEntry> snapshot = top(currencyId);
        if (limit >= snapshot.size()) {
            return snapshot;
        }
        return snapshot.subList(0, limit);
    }

    private CompletableFuture<Void> refreshCurrency(String currencyId) {
        AtomicReference<List<BalanceEntry>> ref = snapshots.get(currencyId);

        return storageManager.submit(s -> s.topBalances(currencyId, TOP_SIZE))
                .thenAccept(entries -> ref.set(List.copyOf(entries)))
                .exceptionally(ex -> {
                    logger.warning("Failed to refresh leaderboard for '"
                            + currencyId + "': " + ex.getMessage());
                    return null;
                });
    }
}