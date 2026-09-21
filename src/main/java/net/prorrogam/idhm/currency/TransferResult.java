package net.prorrogam.idhm.currency;

import java.math.BigDecimal;

public record TransferResult(
        BigDecimal senderBalance,
        BigDecimal receiverBalance
) {}
