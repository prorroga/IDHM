package net.prorrogam.idhm.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        return commands.isEmpty() ? id : commands.getFirst();
    }

    public Currency withCommands(List<String> newCommands) {
        return new Currency(
                id, name, symbol, defaultBalance, maxBalance,
                payable, decimal, maxDecimals, vault, local, balanceShorthand,
                format, formatShort, decimalFormat, decimalFormatShort,
                List.copyOf(newCommands));
    }

    public boolean accepts(BigDecimal value) {
        if (value == null) {
            return false;
        }
        if (value.signum() < 0) {
            return false;
        }
        if (!decimal && value.stripTrailingZeros().scale() > 0) {
            return false;
        }
        return value.stripTrailingZeros().scale() <= maxDecimals;
    }

    public BigDecimal normalize(BigDecimal value) {
        return value.setScale(maxDecimals, RoundingMode.HALF_UP);
    }
}