package net.prorrogam.idhm.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PREFIX_KEY = "prefix";

    private final TranslationManager translations;
    private final Locale defaultLocale;
    private final Locale forcedLocale;
    private final boolean cascadeByPrefix;

    private final Map<TemplateKey, String> rawCache = new ConcurrentHashMap<>();

    private final Map<Locale, TagResolver> prefixCache = new ConcurrentHashMap<>();

    public MessageService(TranslationManager translations,
                          Locale defaultLocale,
                          Locale forcedLocale,
                          boolean cascadeByPrefix) {
        this.translations = translations;
        this.defaultLocale = defaultLocale;
        this.forcedLocale = forcedLocale;
        this.cascadeByPrefix = cascadeByPrefix;
    }

    public void invalidateCache() {
        rawCache.clear();
        prefixCache.clear();
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(resolve(sender, key, resolvers));
    }

    public Component resolve(CommandSender sender, String key, TagResolver... resolvers) {
        Locale locale = resolveLocale(sender);
        String raw = rawTemplateFor(locale, key);
        if (raw == null) {
            return Component.text("<" + key + ">");
        }

        TagResolver prefix = prefixResolverFor(locale);
        TagResolver combined = resolvers.length == 0
                ? prefix
                : TagResolver.resolver(prefix, TagResolver.resolver(resolvers));

        return MINI.deserialize(raw, combined);
    }

    private TagResolver prefixResolverFor(Locale locale) {
        return prefixCache.computeIfAbsent(locale, loc -> {
            String raw = rawTemplateFor(loc, PREFIX_KEY);
            if (raw == null || raw.isBlank()) {
                // Prefix disabled or missing: expand to nothing.
                return TagResolver.resolver(
                        PREFIX_KEY,
                        Tag.selfClosingInserting(Component.empty())
                );
            }
            Component prefixComponent = MINI.deserialize(raw);
            return TagResolver.resolver(
                    PREFIX_KEY,
                    Tag.selfClosingInserting(prefixComponent)
            );
        });
    }

    private String rawTemplateFor(Locale locale, String key) {
        List<Locale> candidates = LocaleResolver.candidates(
                locale, defaultLocale, cascadeByPrefix);
        for (Locale candidate : candidates) {
            TemplateKey cacheKey = new TemplateKey(candidate, key);
            String cached = rawCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            String raw = translations.lookup(candidate, key);
            if (raw != null) {
                rawCache.put(cacheKey, raw);
                return raw;
            }
        }
        return null;
    }

    private Locale resolveLocale(CommandSender sender) {
        if (forcedLocale != null) {
            return forcedLocale;
        }
        if (sender instanceof Player player) {
            return player.locale();
        }
        return defaultLocale;
    }

    private record TemplateKey(Locale locale, String key) {}
}