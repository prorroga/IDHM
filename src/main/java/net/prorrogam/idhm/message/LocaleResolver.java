package net.prorrogam.idhm.message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LocaleResolver {

    private LocaleResolver() {}

    public static List<Locale> candidates(Locale player,
                                          Locale fallback,
                                          boolean cascadeByPrefix) {
        List<Locale> result = new ArrayList<>(3);
        Set<Locale> seen = new HashSet<>(3);

        if (player != null && !player.getLanguage().isEmpty()
                && seen.add(player)) {
            result.add(player);

            if (cascadeByPrefix && !player.getCountry().isEmpty()) {
                Locale languageOnly = Locale.of(player.getLanguage());
                if (seen.add(languageOnly)) {
                    result.add(languageOnly);
                }
            }
        }

        if (fallback != null && seen.add(fallback)) {
            result.add(fallback);
        }

        return result;
    }
}
