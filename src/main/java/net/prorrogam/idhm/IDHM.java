package net.prorrogam.idhm;

import net.prorrogam.idhm.util.FoliaDetector;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class IDHM extends JavaPlugin {

    private static final String DEFAULT_EXPECTED_CONFIG_VERSION = "1";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Properties buildInfo = loadBuildInfo();
        String pluginVersion = buildInfo.getProperty("plugin-version", "unknown");
        String expectedConfigVersion = buildInfo.getProperty(
                "config-version", DEFAULT_EXPECTED_CONFIG_VERSION);

        String platform = FoliaDetector.IS_FOLIA ? "Folia" : "Paper/Spigot";
        getLogger().info("IDHM " + pluginVersion + " enabling on " + platform
                + " (expected config version: " + expectedConfigVersion + ")");

        SchedulerUtil.runAsync(this, () ->
                getLogger().info("Async scheduler is ready."));

        // TODO phase 2A: load ConfigManager, verify ConfigVersion,
        // load CurrencyRegistry with CurrencyLoader.
    }

    @Override
    public void onDisable() {
        getLogger().info("IDHM disabled.");
    }

    private Properties loadBuildInfo() {
        Properties props = new Properties();
        try (InputStream in = getClassLoader().getResourceAsStream("idhm.properties")) {
            if (in == null) {
                getLogger().warning("idhm.properties not found in JAR, using defaults.");
                return props;
            }
            props.load(in);
        } catch (IOException e) {
            getLogger().warning("Failed to read idhm.properties: " + e.getMessage());
        }
        return props;
    }
}
