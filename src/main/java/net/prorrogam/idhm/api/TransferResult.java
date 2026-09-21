package net.prorrogam.idhm.api;

import java.math.BigDecimal;

public record TransferResult(
        BigDecimal senderBalance,
        BigDecimal receiverBalance
) {}
