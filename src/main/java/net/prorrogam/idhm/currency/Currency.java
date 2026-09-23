package net.prorrogam.idhm.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
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

    public String raw(BigDecimal value) {
        return normalize(value).stripTrailingZeros().toPlainString();
    }

    public String formatted(BigDecimal value) {
        String amount = decimal(decimalFormat, normalize(value));
        return format
                .replace("%amount%", amount)
                .replace("%symbol%", symbol)
                .replace("%currency%", name);
    }

    public String formattedShort(BigDecimal value) {
        String amount = decimal(decimalFormatShort, normalize(value));
        return formatShort
                .replace("%amount%", amount)
                .replace("%symbol%", symbol)
                .replace("%currency%", name);
    }

    public String commas(BigDecimal value) {
        return decimal("#,##0", normalize(value));
    }

    public String integer(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).toPlainString();
    }

    private String decimal(String pattern, BigDecimal value) {
        try {
            return new DecimalFormat(pattern).format(value);
        } catch (IllegalArgumentException e) {
            return value.stripTrailingZeros().toPlainString();
        }
    }
}