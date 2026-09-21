package net.prorrogam.idhm.currency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable registry of currencies, keyed by id.
 * <p>
 * Only way in is {@link #from(List, String)}. After that, nothing changes:
 * no adds, no removes, no reordering. Shared across threads as-is (that's
 * why it's immutable in the first place — Folia).
 * <p>
 * Iteration order of {@link #all()} follows the order the currencies came
 * in, so the config.yml order is preserved.
 */
public final class CurrencyRegistry {

    private final Map<String, Currency> byId;
    private final Currency defaultCurrency;
    private final List<Currency> allCurrencies;

    private CurrencyRegistry(Map<String, Currency> byId, Currency defaultCurrency) {
        this.byId = byId;
        this.defaultCurrency = defaultCurrency;
        this.allCurrencies = List.copyOf(byId.values());
    }

    /**
     * Builds the registry from the list of currencies loaded from config.
     * <p>
     * Order matters: the iteration order of the resulting registry matches
     * the list. The default currency is the one named by {@code defaultId},
     * or the first in the list if {@code defaultId} is null/blank.
     *
     * @param currencies currencies to register; must be non-empty
     * @param defaultId  id of the default currency, or null/blank to use the first
     * @throws IllegalArgumentException if the list is null or empty, if any
     *         currency has a null/blank id, if two currencies share the same id,
     *         or if {@code defaultId} doesn't match any currency
     */
    public static CurrencyRegistry from(List<Currency> currencies, String defaultId) {
        if (currencies == null || currencies.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot build CurrencyRegistry from empty currency list");
        }

        Map<String, Currency> map = new LinkedHashMap<>();
        for (Currency currency : currencies) {
            String id = currency.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "Currency with null or blank id: " + currency);
            }
            if (map.putIfAbsent(id, currency) != null) {
                throw new IllegalArgumentException(
                        "Duplicate currency id: " + id);
            }
        }

        Currency defaultCurrency;
        if (defaultId != null && !defaultId.isBlank()) {
            defaultCurrency = map.get(defaultId);
            if (defaultCurrency == null) {
                throw new IllegalArgumentException(
                        "Default currency not found: " + defaultId);
            }
        } else {
            defaultCurrency = map.values().iterator().next();
        }

        return new CurrencyRegistry(Collections.unmodifiableMap(map), defaultCurrency);
    }

    /**
     * @return the currency with the given id, or {@code null} if not present
     */
    public Currency get(String id) {
        return byId.get(id);
    }

    /**
     * @return the currency marked as default; never null
     */
    public Currency defaultCurrency() {
        return defaultCurrency;
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    /**
     * @return all currencies, in insertion order
     */
    public List<Currency> all() {
        return allCurrencies;
    }

    public int size() {
        return byId.size();
    }
}
