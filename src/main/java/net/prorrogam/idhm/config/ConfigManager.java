package net.prorrogam.idhm.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.SectionNode;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.momirealms.sparrow.yaml.route.Route;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigManager {

    private static final String RESOURCE_NAME = "config.yml";

    private final YamlDocument document;

    private ConfigManager(YamlDocument document) {
        this.document = document;
    }

    public static LoadResult<ConfigManager> load(Path configPath) {
        List<String> warnings = new ArrayList<>();

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            SparrowYaml yaml = SparrowYaml.builder().build();

            if (!Files.exists(configPath)) {
                try {
                    YamlDocument defaults = yaml.loadFromResource(RESOURCE_NAME);
                    defaults.save(configPath);
                    warnings.add("Created default config at " + configPath);
                } catch (Exception e) {
                    return LoadResult.fail(List.of(
                            "Default resource '" + RESOURCE_NAME
                                    + "' not found in jar: " + e.getMessage()));
                }
            }

            YamlDocument document = yaml.load(configPath);
            return LoadResult.ok(new ConfigManager(document), warnings);

        } catch (IOException e) {
            return LoadResult.fail(List.of(
                    "Failed to read/write config file: " + e.getMessage()));
        } catch (Exception e) {
            return LoadResult.fail(List.of(
                    "Failed to parse config: " + e.getMessage()));
        }
    }

    public String configVersion() {
        return safeGetString("config-version");
    }

    public String defaultCurrencyId() {
        return safeGetString("settings", "default-currency");
    }

    public String storageType() {
        return safeGetString("storage", "type");
    }

    public String storageUrl() {
        return safeGetString("storage", "url");
    }

    public String storageUsername() {
        return safeGetString("storage", "username");
    }

    public String storagePassword() {
        return safeGetString("storage", "password");
    }

    public int storagePoolSize() {
        Integer value = safeGetInt("storage", "pool-size");
        return value != null ? value : 4;
    }

    public String storageTablePrefix() {
        String value = safeGetString("storage", "table-prefix");
        return value != null ? value : "";
    }

    public int saveIntervalSeconds() {
        Integer value = safeGetInt("settings", "save-interval-seconds");
        return value != null ? value : 300;
    }

    public SequenceNode currenciesNode() {
        return document.getSequenceOrNull(Route.from("currencies"));
    }

    public SectionNode languageNode() {
        return document.getSectionOrNull(Route.from("language"));
    }

    private String safeGetString(String... keys) {
        try {
            return document.get(String.class, (Object[]) keys);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer safeGetInt(String... keys) {
        try {
            return document.get(Integer.class, (Object[]) keys);
        } catch (Exception e) {
            return null;
        }
    }
}