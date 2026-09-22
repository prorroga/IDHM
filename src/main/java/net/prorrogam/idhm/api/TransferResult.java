package net.prorrogam.idhm.api;

import java.math.BigDecimal;
import java.util.Objects;

public record TransferResult(
        BigDecimal senderBalance,
        BigDecimal receiverBalance
) {
    public TransferResult {
        Objects.requireNonNull(senderBalance, "senderBalance");
        Objects.requireNonNull(receiverBalance, "receiverBalance");
    }
}
