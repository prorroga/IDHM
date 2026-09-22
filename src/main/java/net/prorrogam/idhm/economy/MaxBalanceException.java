package net.prorrogam.idhm.economy;

import java.math.BigDecimal;

public final class MaxBalanceException extends EconomyException {

    private final String currencyId;
    private final BigDecimal candidate;
    private final BigDecimal max;

    public MaxBalanceException(String currencyId, BigDecimal candidate, BigDecimal max) {
        super("Max balance exceeded for '" + currencyId + "': would be "
                + candidate.toPlainString() + ", max is " + max.toPlainString());
        this.currencyId = currencyId;
        this.candidate = candidate;
        this.max = max;
    }

    public String currencyId() { return currencyId; }
    public BigDecimal candidate() { return candidate; }
    public BigDecimal max() { return max; }
}
