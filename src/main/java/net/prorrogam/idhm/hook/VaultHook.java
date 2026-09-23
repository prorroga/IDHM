package net.prorrogam.idhm.hook;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.prorrogam.idhm.IDHM;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.economy.EconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class VaultHook implements Economy {

    private final IDHM plugin;
    private final EconomyService economy;
    private final Currency currency;
    private final AtomicBoolean offlineWarningLogged = new AtomicBoolean(false);

    public VaultHook(IDHM plugin, EconomyService economy, Currency currency) {
        this.plugin = plugin;
        this.economy = economy;
        this.currency = currency;
    }

    public static void register(IDHM plugin, EconomyService economy,
                                CurrencyRegistry registry) {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        Currency vaultCurrency = registry.all().stream()
                .filter(Currency::vault)
                .findFirst()
                .orElse(null);
        if (vaultCurrency == null) {
            plugin.getLogger().info(
                    "No currency has 'vault: true'; Vault hook skipped.");
            return;
        }
        VaultHook hook = new VaultHook(plugin, economy, vaultCurrency);
        plugin.getServer().getServicesManager().register(
                Economy.class, hook, plugin, ServicePriority.Normal);
        plugin.getLogger().info("Registered Vault provider for currency '"
                + vaultCurrency.id() + "' (priority: NORMAL).");
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "IDHM";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return currency.maxDecimals();
    }

    @Override
    public String format(double amount) {
        BigDecimal bd = BigDecimal.valueOf(amount)
                .setScale(currency.maxDecimals(), RoundingMode.HALF_UP);
        DecimalFormat df;
        try {
            df = new DecimalFormat(currency.decimalFormat());
        } catch (IllegalArgumentException e) {
            df = new DecimalFormat("#,##0.00");
        }
        String amountStr = df.format(bd);
        return currency.format()
                .replace("%amount%", amountStr)
                .replace("%symbol%", plain(currency.symbol()))
                .replace("%currency%", plain(currency.name()));
    }

    @Override
    public String currencyNamePlural() {
        return plain(currency.name());
    }

    @Override
    public String currencyNameSingular() {
        return plain(currency.name());
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerName);
        return getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (economy.isOnline(player.getUniqueId())) {
            warnOfflineOnce();
        }
        return economy.balance(player.getUniqueId(), currency.id()).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerName);
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdraw(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdraw(player, amount);
    }

    private EconomyResponse withdraw(OfflinePlayer player, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) {
            return failure(player, "Invalid amount: " + amount);
        }
        if (economy.isOnline(player.getUniqueId())) {
            return failure(player, "Offline withdrawals are not supported.");
        }
        try {
            BigDecimal newBalance = economy.withdrawSync(
                    player.getUniqueId(), currency.id(),
                    BigDecimal.valueOf(amount));
            return new EconomyResponse(amount, newBalance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, null);
        } catch (RuntimeException e) {
            return failure(player, e.getMessage());
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerName);
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return deposit(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return deposit(player, amount);
    }

    private EconomyResponse deposit(OfflinePlayer player, double amount) {
        if (amount < 0 || !Double.isFinite(amount)) {
            return failure(player, "Invalid amount: " + amount);
        }
        if (economy.isOnline(player.getUniqueId())) {
            return failure(player, "Offline deposits are not supported.");
        }
        try {
            BigDecimal newBalance = economy.depositSync(
                    player.getUniqueId(), currency.id(),
                    BigDecimal.valueOf(amount));
            return new EconomyResponse(amount, newBalance.doubleValue(),
                    EconomyResponse.ResponseType.SUCCESS, null);
        } catch (RuntimeException e) {
            return failure(player, e.getMessage());
        }
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return true;
    }

    private EconomyResponse failure(OfflinePlayer player, String reason) {
        double current = economy.balance(player.getUniqueId(), currency.id())
                .doubleValue();
        return new EconomyResponse(0, current,
                EconomyResponse.ResponseType.FAILURE, reason);
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0,
                EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "IDHM does not support bank accounts.");
    }

    private String plain(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replaceAll("(?i)[&§]x([&§][0-9a-f]){6}", "")
                .replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    private void warnOfflineOnce() {
        if (offlineWarningLogged.compareAndSet(false, true)) {
            plugin.getLogger().log(Level.WARNING,
                    "Vault getBalance was called for an offline player. "
                            + "IDHM is cache-only for reads: returning the "
                            + "currency default. This warning is logged once per session.");
        }
    }
}