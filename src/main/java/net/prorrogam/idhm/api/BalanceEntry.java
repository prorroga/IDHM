package net.prorrogam.idhm.api;

import java.math.BigDecimal;

public record BalanceEntry(
        String uuid,
        String name,
        BigDecimal balance
) {}
