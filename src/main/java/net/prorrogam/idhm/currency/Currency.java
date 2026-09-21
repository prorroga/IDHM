package net.prorrogam.idhm.currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Definición inmutable de una moneda.
 * <p>
 * <b>Contratos de null:</b>
 * <ul>
 *   <li>{@code maxBalance == null} significa <b>sin límite</b>. No es un
 *       error. El código consumidor debe tratar null como "ilimitado",
 *       no como "0".</li>
 *   <li>{@code defaultBalance == null} se normaliza a {@code ZERO}. Una
 *       cuenta nueva siempre tiene saldo inicial; 0 si no se especifica.</li>
 *   <li>{@code commands == null} se normaliza a lista vacía.</li>
 * </ul>
 * <p>
 * <b>Nota sobre la asimetría intencional:</b> {@code defaultBalance} se
 * normaliza a cero porque "0" es un saldo inicial válido. {@code maxBalance}
 * se deja pasar como null porque "0" sería un tope que impide cualquier
 * saldo positivo, cosa que no tiene sentido como default. Esta asimetría
 * es deliberada.
 * <p>
 * <b>Sin validación de contenido:</b> este record no valida que {@code id}
 * cumpla un patrón, ni que los formatos sean correctos. Esa responsabilidad
 * pertenece al parser de configuración.
 */
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
        // maxBalance: null se preserva como "sin límite". No normalizar.
    }

    public String getPrimaryCommand() {
        return commands.isEmpty() ? id : commands.get(0);
    }
}
