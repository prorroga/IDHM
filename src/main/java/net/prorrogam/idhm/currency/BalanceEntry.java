package net.prorrogam.idhm.currency;

import java.math.BigDecimal;

public record BalanceEntry(
        String uuid,
        String name,
        BigDecimal balance
) {}
