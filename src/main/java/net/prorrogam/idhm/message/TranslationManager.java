package net.prorrogam.idhm.message;

import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.YamlNode;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class TranslationManager {

    private static final List<String> BUNDLED_LOCALES = List.of("en", "es");

    private static final String RESOURCE_TEMPLATE = "translations/%s/messages.yml";
    private static final String DISK_DIR = "translations";
    private static final String MESSAGES_FILE = "messages.yml";

    private final Plugin plugin;
    private final Logger logger;
    private final SparrowYaml yaml;
    private final Map<String, TranslationKey> keys = new ConcurrentHashMap<>();

    public TranslationManager(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.yaml = SparrowYaml.builder().build();
    }

    public void load() {
        keys.clear();

        for (String localeName : BUNDLED_LOCALES) {
            loadFromResource(localeName);
        }

        loadFromDisk();
    }

    public String lookup(Locale locale, String key) {
        TranslationKey tk = keys.get(key);
        if (tk == null) {
            return null;
        }
        return tk.get(locale);
    }

    private void loadFromResource(String localeName) {
        String path = String.format(RESOURCE_TEMPLATE, localeName);
        try {
            YamlDocument doc = yaml.loadFromResource(path);
            if (doc == null) {
                logger.warning("Translation resource is empty: " + path);
                return;
            }
            Locale locale = parseLocale(localeName);
            int count = registerAll(locale, doc);
            logger.info("Loaded " + count + " translation keys for locale '"
                    + localeName + "' from JAR.");
        } catch (Exception e) {
            logger.warning("Failed to load translation '" + path
                    + "': " + e.getMessage());
        }
    }

    private void loadFromDisk() {
        Path baseDir = plugin.getDataFolder().toPath().resolve(DISK_DIR);
        if (!Files.isDirectory(baseDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(baseDir)) {
            entries.filter(Files::isDirectory).forEach(localeDir -> {
                Path file = localeDir.resolve(MESSAGES_FILE);
                if (!Files.isRegularFile(file)) {
                    return;
                }
                String localeName = localeDir.getFileName().toString();
                try {
                    YamlDocument doc = yaml.load(file);
                    if (doc == null) {
                        return;
                    }
                    Locale locale = parseLocale(localeName);
                    int count = registerAll(locale, doc);
                    logger.info("Loaded " + count + " translation keys for locale '"
                            + localeName + "' from disk.");
                } catch (Exception e) {
                    logger.warning("Failed to load translation '" + file
                            + "': " + e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.warning("Failed to scan translations dir: " + e.getMessage());
        }
    }

    private int registerAll(Locale locale, YamlDocument doc) {
        int count = 0;
        Map<Object, YamlNode<?>> values = doc.value();
        for (Object rawKey : values.keySet()) {
            String key = String.valueOf(rawKey);
            String text = readString(doc, key);
            if (text == null || text.isEmpty()) {
                continue;
            }
            keys.computeIfAbsent(key, k -> new TranslationKey())
                    .put(locale, text);
            count++;
        }
        return count;
    }

    private String readString(YamlDocument doc, String key) {
        try {
            return doc.get(String.class, (Object) key);
        } catch (Exception e) {
            return null;
        }
    }

    static Locale parseLocale(String name) {
        String[] parts = name.split("_", 2);
        if (parts.length == 1) {
            return Locale.of(parts[0].toLowerCase(Locale.ROOT));
        }
        return Locale.of(
                parts[0].toLowerCase(Locale.ROOT),
                parts[1].toUpperCase(Locale.ROOT));
    }
}