package net.prorrogam.idhm.currency;

import java.math.BigDecimal;
import java.util.List;

public record Currency(
        String id,
        String name,
        String symbol,
        BigDecimal defaultBalance,
        BigDecimal maxBalance,
        boolean payable,
        boolean decimal,
        int maxDecimals,
        boolean vault,
        boolean local,
        boolean balanceShorthand,
        String format,
        String formatShort,
        String decimalFormat,
        String decimalFormatShort,
        List<String> commands
) {

    public Currency {
        commands = commands == null ? List.of() : List.copyOf(commands);
        defaultBalance = defaultBalance == null ? BigDecimal.ZERO : defaultBalance;
    }

    public String getPrimaryCommand() {
        return commands.isEmpty() ? id : commands.get(0);
    }
}
