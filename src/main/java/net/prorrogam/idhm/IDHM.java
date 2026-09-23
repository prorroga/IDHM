package net.prorrogam.idhm;

import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.prorrogam.idhm.config.ConfigManager;
import net.prorrogam.idhm.config.ConfigVersion;
import net.prorrogam.idhm.config.LoadResult;
import net.prorrogam.idhm.currency.CurrencyLoader;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.SqlStorage;
import net.prorrogam.idhm.database.StorageManager;
import net.prorrogam.idhm.database.StorageSettings;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.listener.PlayerJoinListener;
import net.prorrogam.idhm.listener.PlayerQuitListener;
import net.prorrogam.idhm.util.FoliaDetector;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class IDHM extends JavaPlugin {

    private static final String DEFAULT_EXPECTED_CONFIG_VERSION = "1";

    private volatile CurrencyRegistry currencyRegistry;
    private volatile StorageManager storageManager;
    private volatile EconomyService economyService;
    private SchedulerUtil.Task flushTask;

    @Override
    public void onEnable() {
        Properties buildInfo = loadBuildInfo();
        String pluginVersion = buildInfo.getProperty("plugin-version", "unknown");
        String expectedConfigVersion = buildInfo.getProperty(
                "config-version", DEFAULT_EXPECTED_CONFIG_VERSION);

        String platform = FoliaDetector.IS_FOLIA ? "Folia" : "Paper/Spigot";
        getLogger().info("IDHM " + pluginVersion + " enabling on " + platform
                + " (expected config version: " + expectedConfigVersion + ")");

        Path configPath = getDataFolder().toPath().resolve("config.yml");
        LoadResult<ConfigManager> configResult = ConfigManager.load(configPath);
        if (!configResult.success()) {
            logWarnings(configResult.warnings());
            logErrors("Failed to load config.yml", configResult.errors());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logWarnings(configResult.warnings());
        ConfigManager config = configResult.valueOrThrow();

        String foundConfigVersion = config.configVersion();
        LoadResult<String> versionResult = ConfigVersion.check(
                foundConfigVersion, expectedConfigVersion);
        logWarnings(versionResult.warnings());

        SequenceNode currenciesNode = config.currenciesNode();
        String defaultCurrencyId = config.defaultCurrencyId();
        LoadResult<CurrencyRegistry> currencyResult = CurrencyLoader.load(
                currenciesNode, defaultCurrencyId);

        if (!currencyResult.success()) {
            logWarnings(currencyResult.warnings());
            logErrors("Failed to load currencies", currencyResult.errors());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logWarnings(currencyResult.warnings());
        this.currencyRegistry = currencyResult.valueOrThrow();

        LoadResult<StorageSettings> storageResult = StorageSettings.from(
                config.storageType(),
                config.storageUrl(),
                config.storageUsername(),
                config.storagePassword(),
                config.storagePoolSize(),
                config.storageTablePrefix()
        );
        if (!storageResult.success()) {
            logWarnings(storageResult.warnings());
            logErrors("Failed to load storage settings", storageResult.errors());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        logWarnings(storageResult.warnings());

        try {
            SqlStorage storage = new SqlStorage(storageResult.valueOrThrow());
            this.storageManager = new StorageManager(storage, getLogger());
        } catch (Exception e) {
            getLogger().severe("Failed to initialize storage: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.economyService = new EconomyService(
                currencyRegistry, storageManager, getLogger());

        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(economyService), this);
        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(economyService), this);

        economyService.loadAllOnline();

        long intervalSeconds = config.saveIntervalSeconds();
        if (intervalSeconds < 10) {
            intervalSeconds = 10;
        }
        this.flushTask = SchedulerUtil.runAsyncRepeating(
                this,
                () -> economyService.flushDirty(),
                intervalSeconds, intervalSeconds);

        SchedulerUtil.runAsync(this, () ->
                getLogger().info("Async scheduler is ready."));

        getLogger().info("IDHM enabled with " + currencyRegistry.size()
                + " currenc" + (currencyRegistry.size() == 1 ? "y" : "ies")
                + " (default: '" + currencyRegistry.defaultCurrency().id() + "')");
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (economyService != null) {
            economyService.shutdown();
        }
        if (storageManager != null) {
            storageManager.close();
        }
        getLogger().info("IDHM disabled.");
    }

    public CurrencyRegistry getCurrencyRegistry() {
        return currencyRegistry;
    }

    public EconomyService getEconomyService() {
        return economyService;
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

    private void logWarnings(List<String> warnings) {
        for (String warning : warnings) {
            getLogger().warning(warning);
        }
    }

    private void logErrors(String prefix, List<String> errors) {
        getLogger().severe(prefix + ":");
        for (String error : errors) {
            getLogger().severe("  - " + error);
        }
    }
}
