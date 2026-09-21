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

/**
 * Loads and exposes the config.yml file.
 * <p>
 * The user file is created from the jar-packaged default if missing.
 * All reads return null on missing paths; callers decide what to do.
 */
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
        return safeGetString(Route.from("___version___"));
    }

    public String defaultCurrencyId() {
        return safeGetString(Route.from("settings", "default-currency"));
    }

    public SequenceNode currenciesNode() {
        return document.getSequenceOrNull(Route.from("currencies"));
    }

    public SectionNode languageNode() {
        return document.getSectionOrNull(Route.from("language"));
    }

    /**
     * Reads a string at the given route, returning null if the path is
     * missing or the value is not a scalar string.
     */
    private String safeGetString(Route route) {
        try {
            return document.get(String.class, route);
        } catch (Exception e) {
            return null;
        }
    }
}