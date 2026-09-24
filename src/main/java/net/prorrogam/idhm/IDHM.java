package net.prorrogam.idhm;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.prorrogam.idhm.command.IdhmCommand;
import net.prorrogam.idhm.config.ConfigManager;
import net.prorrogam.idhm.config.ConfigVersion;
import net.prorrogam.idhm.config.LoadResult;
import net.prorrogam.idhm.config.ReloadResult;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyLoader;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.database.SqlStorage;
import net.prorrogam.idhm.database.StorageManager;
import net.prorrogam.idhm.database.StorageSettings;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.economy.LeaderboardCache;
import net.prorrogam.idhm.hook.IDHMPlaceholderExpansion;
import net.prorrogam.idhm.hook.VaultHook;
import net.prorrogam.idhm.listener.PlayerJoinListener;
import net.prorrogam.idhm.listener.PlayerQuitListener;
import net.prorrogam.idhm.message.MessageService;
import net.prorrogam.idhm.message.TranslationManager;
import net.prorrogam.idhm.util.FoliaDetector;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;

public final class IDHM extends JavaPlugin {

    private static final String DEFAULT_EXPECTED_CONFIG_VERSION = "1";
    private static final long MIN_INTERVAL_SECONDS = 10;

    private volatile CurrencyRegistry currencyRegistry;
    private volatile StorageManager storageManager;
    private volatile EconomyService economyService;
    private volatile LeaderboardCache leaderboardCache;
    private volatile MessageService messageService;
    private volatile StorageSettings activeStorageSettings;
    private String expectedConfigVersion = DEFAULT_EXPECTED_CONFIG_VERSION;
    private SchedulerUtil.Task flushTask;
    private SchedulerUtil.Task leaderboardTask;

    @Override
    public void onEnable() {
        Properties buildInfo = loadBuildInfo();
        String pluginVersion = buildInfo.getProperty("plugin-version", "unknown");
        this.expectedConfigVersion = buildInfo.getProperty(
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

        TranslationManager translationManager = new TranslationManager(this, getLogger());
        translationManager.load();
        this.messageService = new MessageService(
                translationManager,
                parseLocale(config.defaultLocale()),
                parseOptionalLocale(config.forcedLocale()),
                config.fallbackToPrefix());

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

        StorageSettings storageSettings = storageResult.valueOrThrow();
        this.activeStorageSettings = storageSettings;

        SqlStorage storage = null;
        try {
            storage = new SqlStorage(storageSettings);
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
                    new IdhmCommand(this).build(),
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

    /**
     * Recarga en caliente lo que es seguro recargar.
     *
     * <p>Estrategia "todo o nada": si algo estructural cambia (storage o
     * definición de monedas), se aborta sin tocar el estado actual y se
     * devuelve un fallo con la lista de incompatibilidades.</p>
     *
     * <p>Debe llamarse desde el hilo global de Folia (o el main thread en
     * Paper). {@code IdhmCommand} lo agenda con {@code SchedulerUtil.runGlobal}.</p>
     *
     * @return éxito sin errores, o fallo con >= 1 error legible
     */
    public ReloadResult reload() {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        LoadResult<ConfigManager> configResult = ConfigManager.load(configPath);
        if (!configResult.success()) {
            return ReloadResult.failure(configResult.errors());
        }
        logWarnings(configResult.warnings());
        ConfigManager newConfig = configResult.valueOrThrow();

        // Versión de config: solo warnings, nunca bloquea un reload.
        LoadResult<String> versionResult = ConfigVersion.check(
                newConfig.configVersion(), expectedConfigVersion);
        logWarnings(versionResult.warnings());

        // 1) Storage — si cambió algo, restart obligatorio.
        LoadResult<StorageSettings> storageResult = StorageSettings.from(
                newConfig.storageType(),
                newConfig.storageUrl(),
                newConfig.storageUsername(),
                newConfig.storagePassword(),
                newConfig.storagePoolSize(),
                newConfig.storageTablePrefix());
        if (!storageResult.success()) {
            return ReloadResult.failure(storageResult.errors());
        }
        StorageSettings newStorage = storageResult.valueOrThrow();
        if (!newStorage.equals(activeStorageSettings)) {
            return ReloadResult.failure(
                    "Storage settings changed; a server restart is required.");
        }

        // 2) Monedas — solo comprobamos compatibilidad estructural.
        LoadResult<CurrencyRegistry> currencyResult = CurrencyLoader.load(
                newConfig.currenciesNode(), newConfig.defaultCurrencyId());
        if (!currencyResult.success()) {
            return ReloadResult.failure(currencyResult.errors());
        }
        logWarnings(currencyResult.warnings());
        CurrencyRegistry newRegistry = currencyResult.valueOrThrow();
        List<String> currencyIssues = currencyIncompatibilities(
                currencyRegistry, newRegistry);
        if (!currencyIssues.isEmpty()) {
            return ReloadResult.failure(currencyIssues);
        }

        // --- A partir de aquí, todo se aplica. ---

        // Mensajes + locale.
        TranslationManager translationManager = new TranslationManager(this, getLogger());
        translationManager.load();
        this.messageService = new MessageService(
                translationManager,
                parseLocale(newConfig.defaultLocale()),
                parseOptionalLocale(newConfig.forcedLocale()),
                newConfig.fallbackToPrefix());

        // Intervalo de flush.
        long newFlushInterval = clampInterval(
                newConfig.saveIntervalSeconds(), "save-interval-seconds");
        if (flushTask != null) {
            flushTask.cancel();
        }
        this.flushTask = SchedulerUtil.runAsyncRepeating(
                this,
                () -> economyService.flushDirty(),
                newFlushInterval, newFlushInterval);

        // Leaderboard.
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        if (!newConfig.leaderboardEnabled()) {
            this.leaderboardCache = null;
        } else {
            if (leaderboardCache == null) {
                this.leaderboardCache = new LeaderboardCache(
                        currencyRegistry, storageManager, economyService, getLogger());
                leaderboardCache.refresh();
            }
            long newLifetime = clampInterval(
                    newConfig.leaderboardCacheLifetime(),
                    "leaderboard.cache-lifetime");
            this.leaderboardTask = SchedulerUtil.runAsyncRepeating(
                    this,
                    () -> leaderboardCache.refresh(),
                    newLifetime, newLifetime);
        }

        getLogger().info("IDHM configuration reloaded.");
        return ReloadResult.ok();
    }

    private static List<String> currencyIncompatibilities(
            CurrencyRegistry oldReg, CurrencyRegistry newReg) {
        List<String> issues = new ArrayList<>();

        if (!oldReg.defaultCurrency().id().equals(newReg.defaultCurrency().id())) {
            issues.add("default-currency changed; restart required.");
        }

        Map<String, Currency> oldById = new HashMap<>();
        for (Currency c : oldReg.all()) {
            oldById.put(c.id(), c);
        }
        Set<String> newIds = new HashSet<>();
        for (Currency c : newReg.all()) {
            newIds.add(c.id());
            Currency old = oldById.get(c.id());
            if (old == null) {
                issues.add("Currency '" + c.id() + "' added; restart required.");
                continue;
            }
            if (old.maxDecimals() != c.maxDecimals()) {
                issues.add("Currency '" + c.id()
                        + "' max-decimals changed; restart required.");
            }
        }
        for (Currency c : oldReg.all()) {
            if (!newIds.contains(c.id())) {
                issues.add("Currency '" + c.id() + "' removed; restart required.");
            }
        }
        return issues;
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

    public MessageService getMessageService() {
        return messageService;
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

    private static Locale parseLocale(String name) {
        String[] parts = name.split("_", 2);
        if (parts.length == 1) {
            return Locale.of(parts[0].toLowerCase(Locale.ROOT));
        }
        return Locale.of(
                parts[0].toLowerCase(Locale.ROOT),
                parts[1].toUpperCase(Locale.ROOT));
    }

    private static Locale parseOptionalLocale(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return parseLocale(name);
    }
}