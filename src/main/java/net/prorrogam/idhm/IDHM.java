package net.prorrogam.idhm;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.prorrogam.idhm.config.ConfigManager;
import net.prorrogam.idhm.config.ConfigVersion;
import net.prorrogam.idhm.config.LoadResult;
import net.prorrogam.idhm.command.IdhmCommand;
import net.prorrogam.idhm.currency.CurrencyLoader;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.SqlStorage;
import net.prorrogam.idhm.database.StorageManager;
import net.prorrogam.idhm.database.StorageSettings;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.economy.LeaderboardCache;
import net.prorrogam.idhm.hook.IDHMPlaceholderExpansion;
import net.prorrogam.idhm.listener.PlayerJoinListener;
import net.prorrogam.idhm.listener.PlayerQuitListener;
import net.prorrogam.idhm.util.FoliaDetector;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;
import net.prorrogam.idhm.hook.VaultHook;
import java.util.logging.Level;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class IDHM extends JavaPlugin {

    private static final String DEFAULT_EXPECTED_CONFIG_VERSION = "1";
    private static final long MIN_INTERVAL_SECONDS = 10;

    private volatile CurrencyRegistry currencyRegistry;
    private volatile StorageManager storageManager;
    private volatile EconomyService economyService;
    private volatile LeaderboardCache leaderboardCache;
    private SchedulerUtil.Task flushTask;
    private SchedulerUtil.Task leaderboardTask;

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
            failAndDisable("Failed to load config.yml", configResult);
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
            failAndDisable("Failed to load currencies", currencyResult);
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
            failAndDisable("Failed to load storage settings", storageResult);
            return;
        }
        logWarnings(storageResult.warnings());

        SqlStorage storage = null;
        try {
            storage = new SqlStorage(storageResult.valueOrThrow());
            this.storageManager = new StorageManager(storage, getLogger());
        } catch (Exception e) {
            if (storage != null) {
                storage.close();
            }
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

        long flushInterval = clampInterval(
                config.saveIntervalSeconds(),
                "save-interval-seconds");
        this.flushTask = SchedulerUtil.runAsyncRepeating(
                this,
                () -> economyService.flushDirty(),
                flushInterval, flushInterval);

        long leaderboardLifetime = 0;
        if (config.leaderboardEnabled()) {
            this.leaderboardCache = new LeaderboardCache(
                    currencyRegistry, storageManager, economyService, getLogger());
            this.leaderboardCache.refresh();
            leaderboardLifetime = clampInterval(
                    config.leaderboardCacheLifetime(),
                    "leaderboard.cache-lifetime");
            this.leaderboardTask = SchedulerUtil.runAsyncRepeating(
                    this,
                    () -> leaderboardCache.refresh(),
                    leaderboardLifetime, leaderboardLifetime);
        }

        try {
            VaultHook.register(this, economyService, currencyRegistry);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to register Vault hook", t);
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new IDHMPlaceholderExpansion(this).register();
                getLogger().info("Registered PlaceholderAPI expansion 'idhm'.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING,
                        "Failed to register PlaceholderAPI expansion", t);
            }
        }

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    new IdhmCommand(this, currencyRegistry, economyService,
                            leaderboardCache).build(),
                    "IDHM economy command",
                    List.of("eco", "economy")
            );
        });

        getLogger().info("IDHM enabled with " + currencyRegistry.size()
                + " currenc" + (currencyRegistry.size() == 1 ? "y" : "ies")
                + " (default: '" + currencyRegistry.defaultCurrency().id() + "')"
                + (leaderboardCache != null
                ? ", leaderboard every " + leaderboardLifetime + "s"
                : ", leaderboard off"));
    }

    @Override
    public void onDisable() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
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

    public LeaderboardCache getLeaderboardCache() {
        return leaderboardCache;
    }

    private long clampInterval(long seconds, String configKey) {
        if (seconds < MIN_INTERVAL_SECONDS) {
            getLogger().warning(configKey + "=" + seconds
                    + " is below the minimum of " + MIN_INTERVAL_SECONDS
                    + "s; using " + MIN_INTERVAL_SECONDS + "s.");
            return MIN_INTERVAL_SECONDS;
        }
        return seconds;
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

    private void failAndDisable(String prefix, LoadResult<?> result) {
        logWarnings(result.warnings());
        logErrors(prefix, result.errors());
        getServer().getPluginManager().disablePlugin(this);
    }
}