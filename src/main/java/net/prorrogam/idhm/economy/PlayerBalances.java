package net.prorrogam.idhm.economy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

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
