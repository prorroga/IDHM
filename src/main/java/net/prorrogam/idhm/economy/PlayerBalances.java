package net.prorrogam.idhm.economy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * In-memory balances for a single online player.
 * <p>
 * Backed by a {@link ConcurrentHashMap}. Individual reads and writes are
 * thread-safe. Compound operations (read-modify-write) must use
 * {@link #compute} to be atomic.
 * <p>
 * The map is keyed by currency id. A missing key means the player has
 * never held that currency; callers should fall back to the currency's
 * default balance.
 * <p>
 * This class does not track which currencies have been modified since
 * the last flush. That is handled by {@code EconomyService}, which owns
 * the dirty set.
 */
public final class PlayerBalances {

    private final ConcurrentHashMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    public PlayerBalances() {
    }

    public PlayerBalances(Map<String, BigDecimal> initial) {
        if (initial != null) {
            initial.forEach((currencyId, amount) -> {
                if (currencyId != null && amount != null) {
                    balances.put(currencyId, amount);
                }
            });
        }
    }

    /**
     * @return the balance for the currency, or {@code null} if the player
     *         has never held it. Callers must handle null: in an economy
     *         context, null means "use the currency's default balance",
     *         not zero.
     */
    public BigDecimal get(String currencyId) {
        return balances.get(currencyId);
    }

    public void put(String currencyId, BigDecimal amount) {
        balances.put(currencyId, amount);
    }

    public boolean putIfAbsent(String currencyId, BigDecimal amount) {
        return balances.putIfAbsent(currencyId, amount) == null;
    }

    /**
     * Atomically applies an update to a currency balance.
     * <p>
     * The remapping function receives the current value (or {@code null}
     * if absent) and returns the new value.
     * <p>
     * <b>Important — null return deletes the entry:</b> per
     * {@link ConcurrentHashMap#compute} contract, if the remapper returns
     * {@code null}, the entry is removed. In an economy context this is
     * almost never what you want: to reject an update, return the current
     * value unchanged or throw; reserve {@code null} for intentional
     * deletion.
     * <p>
     * <b>Important — remapper may re-run:</b> under contention, the
     * remapping function may be invoked multiple times with the latest
     * observed value each time. It must be free of side effects. Only the
     * final successful return value is stored.
     */
    public BigDecimal compute(String currencyId,
                              BiFunction<String, BigDecimal, BigDecimal> remapper) {
        return balances.compute(currencyId, remapper);
    }

    public boolean isEmpty() {
        return balances.isEmpty();
    }

    public Map<String, BigDecimal> snapshot() {
        return Map.copyOf(balances);
    }
}
