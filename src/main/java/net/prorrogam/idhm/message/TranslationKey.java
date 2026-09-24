package net.prorrogam.idhm.message;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationKey {

    private final Map<Locale, String> translations = new ConcurrentHashMap<>();

    public void put(Locale locale, String text) {
        translations.put(locale, text);
    }

    public String get(Locale locale) {
        return translations.get(locale);
    }
}