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
        return commands.isEmpty() ? id : commands.getFirst();
    }

    /**
     * Returns a copy of this currency with a different command list.
     * <p>
     * Keeps the 16-field record immutable while avoiding manual
     * reconstruction at every call site. If a new field is added to
     * the record, only this method needs updating.
     */
    public Currency withCommands(List<String> newCommands) {
        return new Currency(
                id, name, symbol, defaultBalance, maxBalance,
                payable, decimal, maxDecimals, vault, local, balanceShorthand,
                format, formatShort, decimalFormat, decimalFormatShort,
                List.copyOf(newCommands));
    }
}
