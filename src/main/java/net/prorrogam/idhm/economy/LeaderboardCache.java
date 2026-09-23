package net.prorrogam.idhm.economy;

import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.StorageManager;

import java.math.BigDecimal;
import java.util.HashMap;
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

    private final CurrencyRegistry registry;
    private final StorageManager storageManager;
    private final EconomyService economy;
    private final Logger logger;

    private final Map<String, AtomicReference<Snapshot>> snapshots =
            new ConcurrentHashMap<>();

    public record Snapshot(
            List<BalanceEntry> entries,
            Map<UUID, Integer> ranks
    ) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
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
                return;
            }
            merged.put(uuid.toString(),
                    new BalanceEntry(uuid.toString(), name, balance));
        });

        return merged.values().stream()
                .filter(e -> e.balance().signum() > 0)
                .sorted((a, b) -> b.balance().compareTo(a.balance()))
                .limit(limit)
                .toList();
    }

    public int rank(UUID playerId, String currencyId) {
        AtomicReference<Snapshot> ref = snapshots.get(currencyId);
        if (ref == null) {
            return 0;
        }
        Integer rank = ref.get().ranks().get(playerId);
        return rank != null ? rank : 0;
    }

    private CompletableFuture<Void> refreshCurrency(String currencyId) {
        AtomicReference<Snapshot> ref = snapshots.get(currencyId);

        return storageManager.submit(s -> s.topBalances(currencyId, TOP_SIZE))
                .thenAccept(entries -> {
                    List<BalanceEntry> immutable = List.copyOf(entries);
                    Map<UUID, Integer> ranks = new HashMap<>();
                    int position = 1;
                    for (BalanceEntry entry : immutable) {
                        try {
                            ranks.put(UUID.fromString(entry.uuid()), position);
                        } catch (IllegalArgumentException ignored) {
                            // Skip malformed UUIDs; they shouldn't exist.
                        }
                        position++;
                    }
                    ref.set(new Snapshot(immutable, Map.copyOf(ranks)));
                })
                .exceptionally(ex -> {
                    logger.warning("Failed to refresh leaderboard for '"
                            + currencyId + "': " + ex.getMessage());
                    return null;
                });
    }
}