package net.prorrogam.idhm.currency;

import net.momirealms.sparrow.yaml.node.SectionNode;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.momirealms.sparrow.yaml.route.Route;
import net.prorrogam.idhm.config.LoadResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses and validates the 'currencies' section of config.yml.
 * <p>
 * Convention: problems that affect a single currency but don't prevent
 * the plugin from working go to {@code warnings}. Only fatal conditions
 * (missing section, no valid currencies, unresolvable default, broken
 * registry) go to {@code errors}.
 */
public final class CurrencyLoader {

    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private static final int MAX_COMMAND_LENGTH = 32;
    private static final int MAX_COMMANDS = 16;

    private static final BigDecimal DEFAULT_BALANCE = BigDecimal.ZERO;
    private static final int DEFAULT_MAX_DECIMALS = 2;
    private static final String DEFAULT_FORMAT = "%symbol%%amount% %currency%";
    private static final String DEFAULT_FORMAT_SHORT = "%symbol%%amount%";
    private static final String DEFAULT_DECIMAL_FORMAT = "#,##0.00";

    private CurrencyLoader() {}

    public static LoadResult<CurrencyRegistry> load(SequenceNode node, String defaultId) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (node == null) {
            errors.add("'currencies' section is missing or not a list");
            return LoadResult.fail(errors);
        }

        // 1. Parse each entry. Per-currency problems go to warnings.
        List<Currency> currencies = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            SectionNode entry = node.getSectionOrNull(Route.from(i));
            if (entry == null) {
                warnings.add("Entry #" + i + " in 'currencies' is not a mapping. Skipped.");
                continue;
            }
            Currency currency = parseEntry(entry, i, warnings);
            if (currency != null) {
                currencies.add(currency);
            }
        }

        currencies = discardDuplicates(currencies, warnings);

        currencies = resolveAliasCollisions(currencies, warnings);

        if (currencies.isEmpty()) {
            errors.add("No valid currencies were loaded from 'currencies'");
            return LoadResult.fail(errors, warnings);
        }

        String resolvedDefault = resolveDefault(currencies, defaultId, warnings, errors);
        if (resolvedDefault == null) {
            return LoadResult.fail(errors, warnings);
        }

        try {
            CurrencyRegistry registry = CurrencyRegistry.from(currencies, resolvedDefault);
            return LoadResult.ok(registry, warnings);
        } catch (IllegalArgumentException e) {
            errors.add("Failed to build currency registry: " + e.getMessage());
            return LoadResult.fail(errors, warnings);
        }
    }

    private static Currency parseEntry(SectionNode entry, int index,
                                       List<String> warnings) {
        String id = getString(entry, "id");
        if (id == null || id.isBlank()) {
            warnings.add("Entry #" + index + " is missing 'id'. Skipped.");
            return null;
        }
        id = id.trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            warnings.add("Entry #" + index + " has invalid id '" + id
                    + "': must match " + ID_PATTERN.pattern() + ". Skipped.");
            return null;
        }

        String name = getString(entry, "name");
        if (name == null || name.isBlank()) {
            name = id;
        }

        String symbol = getString(entry, "symbol");
        if (symbol == null) {
            symbol = "";
        }

        BigDecimal defaultBalance = getBigDecimal(entry, "default", DEFAULT_BALANCE);
        if (defaultBalance.signum() < 0) {
            warnings.add("Currency '" + id + "': 'default' cannot be negative ("
                    + defaultBalance + "). Skipped.");
            return null;
        }

        BigDecimal maxBalance = getBigDecimal(entry, "max", null);
        if (maxBalance != null) {
            if (maxBalance.compareTo(BigDecimal.valueOf(-1)) == 0) {
                maxBalance = null;
            } else if (maxBalance.signum() <= 0) {
                warnings.add("Currency '" + id + "': 'max' must be positive or -1 "
                        + "(no limit) when set (" + maxBalance + "). Skipped.");
                return null;
            } else if (defaultBalance.compareTo(maxBalance) > 0) {
                warnings.add("Currency '" + id + "': 'default' (" + defaultBalance
                        + ") exceeds 'max' (" + maxBalance + "). Skipped.");
                return null;
            }
        }

        //flags default true (opt-out)
        boolean payable = getBoolean(entry, "payable", true);
        boolean decimal = getBoolean(entry, "decimal", true);
        boolean balanceShorthand = getBoolean(entry, "balance-shorthand", true);

        //flags default false (opt-in)
        boolean vault = getBoolean(entry, "vault", false);
        boolean local = getBoolean(entry, "local", false);

        int maxDecimals = getInt(entry, "max-decimals", DEFAULT_MAX_DECIMALS);

        if (maxDecimals < 0 || maxDecimals > 8) {
            warnings.add("Currency '" + id + "': 'max-decimals' must be between 0 and 8 ("
                    + maxDecimals + "). Skipped.");
            return null;
        }
        if (!decimal && maxDecimals != 0) {
            warnings.add("Currency '" + id
                    + "': 'decimal' is false but 'max-decimals' is " + maxDecimals
                    + ". Normalized to 0.");
            maxDecimals = 0;
        }
        if (decimal && maxDecimals == 0) {
            warnings.add("Currency '" + id
                    + "': 'decimal' is true but 'max-decimals' is 0. "
                    + "Normalized to 'decimal: false'.");
            decimal = false;
        }

        String format = getString(entry, "format");
        if (format == null) format = DEFAULT_FORMAT;
        String formatShort = getString(entry, "format-short");
        if (formatShort == null) formatShort = DEFAULT_FORMAT_SHORT;
        String decimalFormat = getString(entry, "decimal-format");
        if (decimalFormat == null) decimalFormat = DEFAULT_DECIMAL_FORMAT;
        String decimalFormatShort = getString(entry, "decimal-format-short");
        if (decimalFormatShort == null) decimalFormatShort = DEFAULT_DECIMAL_FORMAT;

        List<String> commands = readCommands(entry, id, warnings);

        return new Currency(id, name, symbol, defaultBalance, maxBalance,
                payable, decimal, maxDecimals, vault, local, balanceShorthand,
                format, formatShort, decimalFormat, decimalFormatShort, commands);
    }

    private static List<String> readCommands(SectionNode entry, String id,
                                             List<String> warnings) {
        SequenceNode seq = entry.getSequenceOrNull(Route.from("commands"));
        if (seq == null || seq.size() == 0) {
            return List.of(id);
        }

        List<String> result = new ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            String cmd = seq.get(String.class, i);
            if (cmd == null) continue;
            String normalized = cmd.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;

            if (normalized.length() > MAX_COMMAND_LENGTH) {
                warnings.add("Currency '" + id + "': command '" + normalized
                        + "' exceeds " + MAX_COMMAND_LENGTH + " characters. Ignored.");
                continue;
            }
            if (!COMMAND_PATTERN.matcher(normalized).matches()) {
                warnings.add("Currency '" + id + "': command '" + normalized
                        + "' does not match " + COMMAND_PATTERN.pattern() + ". Ignored.");
                continue;
            }
            if (result.contains(normalized)) {
                warnings.add("Currency '" + id + "': duplicate command '"
                        + normalized + "'. Ignored.");
                continue;
            }
            result.add(normalized);

            if (result.size() >= MAX_COMMANDS) {
                warnings.add("Currency '" + id + "': reached the limit of " + MAX_COMMANDS
                        + " commands. Further entries ignored.");
                break;
            }
        }

        return result.isEmpty() ? List.of(id) : List.copyOf(result);
    }

    private static List<Currency> discardDuplicates(List<Currency> currencies,
                                                    List<String> warnings) {
        Map<String, List<Integer>> byId = new LinkedHashMap<>();
        for (int i = 0; i < currencies.size(); i++) {
            byId.computeIfAbsent(currencies.get(i).id(), k -> new ArrayList<>()).add(i);
        }

        Set<Integer> toRemove = new HashSet<>();
        for (Map.Entry<String, List<Integer>> e : byId.entrySet()) {
            if (e.getValue().size() > 1) {
                warnings.add("Duplicate currency id '" + e.getKey()
                        + "': entries " + e.getValue() + ". All discarded.");
                toRemove.addAll(e.getValue());
            }
        }

        if (toRemove.isEmpty()) {
            return currencies;
        }

        List<Currency> result = new ArrayList<>();
        for (int i = 0; i < currencies.size(); i++) {
            if (!toRemove.contains(i)) {
                result.add(currencies.get(i));
            }
        }
        return result;
    }

    private static List<Currency> resolveAliasCollisions(List<Currency> currencies,
                                                         List<String> warnings) {
        Map<String, List<Integer>> aliasToCurrencies = new LinkedHashMap<>();
        for (int i = 0; i < currencies.size(); i++) {
            for (String cmd : currencies.get(i).commands()) {
                aliasToCurrencies.computeIfAbsent(cmd, k -> new ArrayList<>()).add(i);
            }
        }

        Set<String> conflictingAliases = new HashSet<>();
        for (Map.Entry<String, List<Integer>> e : aliasToCurrencies.entrySet()) {
            if (e.getValue().size() > 1) {
                conflictingAliases.add(e.getKey());
                List<String> ids = new ArrayList<>();
                for (int idx : e.getValue()) {
                    ids.add(currencies.get(idx).id());
                }
                warnings.add("Alias '" + e.getKey() + "' collides between "
                        + ids + ". Alias will be ignored.");
            }
        }

        if (conflictingAliases.isEmpty()) {
            return currencies;
        }

        List<Currency> result = new ArrayList<>();
        for (Currency c : currencies) {
            List<String> filtered = new ArrayList<>();
            for (String cmd : c.commands()) {
                if (!conflictingAliases.contains(cmd)) {
                    filtered.add(cmd);
                }
            }
            if (filtered.isEmpty()) {
                warnings.add("Currency '" + c.id()
                        + "' has no commands left after alias resolution. Discarded.");
                continue;
            }
            result.add(c.withCommands(filtered));
        }
        return result;
    }

    private static String resolveDefault(List<Currency> currencies, String defaultId,
                                         List<String> warnings, List<String> errors) {
        if (defaultId == null || defaultId.isBlank()) {
            String fallback = currencies.getFirst().id();
            warnings.add("No 'settings.default-currency' specified. "
                    + "Using first currency: '" + fallback + "'.");
            return fallback;
        }

        for (Currency c : currencies) {
            if (c.id().equals(defaultId)) {
                return defaultId;
            }
        }

        errors.add("'settings.default-currency' points to '" + defaultId
                + "', which is not a valid currency.");
        return null;
    }

    private static String getString(SectionNode entry, String key) {
        return entry.get(String.class, key);
    }

    private static boolean getBoolean(SectionNode entry, String key, boolean fallback) {
        Boolean value = entry.getOrDefault(Boolean.class, fallback, key);
        return value != null ? value : fallback;
    }

    private static int getInt(SectionNode entry, String key, int fallback) {
        Integer value = entry.getOrDefault(Integer.class, fallback, key);
        return value != null ? value : fallback;
    }

    private static BigDecimal getBigDecimal(SectionNode entry, String key,
                                            BigDecimal fallback) {
        String raw = entry.get(String.class, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}