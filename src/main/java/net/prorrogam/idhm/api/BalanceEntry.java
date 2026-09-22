package net.prorrogam.idhm.api;

import java.math.BigDecimal;
import java.util.Objects;

public record BalanceEntry(
        String uuid,
        String name,
        BigDecimal balance
) {
    public BalanceEntry {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(balance, "balance");
    }
}
