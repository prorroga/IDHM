package net.prorrogam.idhm.economy;

import java.math.BigDecimal;

public final class InsufficientFundsException extends EconomyException {

    private final String currencyId;
    private final BigDecimal available;
    private final BigDecimal requested;

    public InsufficientFundsException(String currencyId, BigDecimal available,
                                      BigDecimal requested) {
        super("Insufficient funds in '" + currencyId + "': have "
                + available.toPlainString() + ", need " + requested.toPlainString());
        this.currencyId = currencyId;
        this.available = available;
        this.requested = requested;
    }

    public String currencyId() { return currencyId; }
    public BigDecimal available() { return available; }
    public BigDecimal requested() { return requested; }
}
