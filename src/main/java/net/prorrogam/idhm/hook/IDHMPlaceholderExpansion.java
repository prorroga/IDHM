package net.prorrogam.idhm.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.prorrogam.idhm.IDHM;
import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.economy.LeaderboardCache;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class IDHMPlaceholderExpansion extends PlaceholderExpansion {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;

    private final IDHM plugin;
    private final AtomicLong lastErrorLogAt = new AtomicLong(0L);

    public IDHMPlaceholderExpansion(IDHM plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "idhm";
    }

    @Override
    public @NotNull String getAuthor() {
        return "prorroga";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        try {
            if (params.startsWith("balance_")) {
                return handleBalance(player, params.substring("balance_".length()));
            }
            if (params.startsWith("rank_")) {
                return handleRank(player, params.substring("rank_".length()));
            }
            if (params.startsWith("top_")) {
                return handleTop(params.substring("top_".length()));
            }
            if (params.startsWith("currency_")) {
                return handleCurrency(params.substring("currency_".length()));
            }
            return null;
        } catch (Exception e) {
            logErrorThrottled("Error resolving placeholder '" + params + "'", e);
            return null;
        }
    }

    private String handleBalance(OfflinePlayer player, String afterType) {
        StringBuilder rest = new StringBuilder();
        Currency currency = resolveCurrencyPrefix(afterType, rest);
        if (currency == null) {
            return null;
        }

        BigDecimal balance;
        if (player == null) {
            balance = currency.defaultBalance();
        } else {
            UUID uuid = player.getUniqueId();
            balance = economy().isOnline(uuid)
                    ? economy().balance(uuid, currency.id())
                    : currency.defaultBalance();
        }

        return switch (rest.toString()) {
            case "" -> currency.raw(balance);
            case "formatted" -> currency.formatted(balance);
            case "commas" -> currency.commas(balance);
            case "integer" -> currency.integer(balance);
            default -> null;
        };
    }

    private String handleRank(OfflinePlayer player, String afterType) {
        LeaderboardCache leaderboard = leaderboard();
        if (leaderboard == null) {
            return "";
        }
        StringBuilder rest = new StringBuilder();
        Currency currency = resolveCurrencyPrefix(afterType, rest);
        if (currency == null || rest.length() > 0) {
            return null;
        }
        if (player == null) {
            return "0";
        }
        return String.valueOf(leaderboard.rank(player.getUniqueId(), currency.id()));
    }

    private String handleTop(String afterType) {
        LeaderboardCache leaderboard = leaderboard();
        if (leaderboard == null) {
            return "";
        }
        StringBuilder rest = new StringBuilder();
        Currency currency = resolveCurrencyPrefix(afterType, rest);
        if (currency == null) {
            return null;
        }
        String remaining = rest.toString();
        if (remaining.isEmpty()) {
            return null;
        }

        String[] parts = remaining.split("_");
        if (parts.length < 2) {
            return null;
        }

        int position;
        try {
            position = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (position < 1) {
            return null;
        }

        String field = String.join("_", Arrays.copyOfRange(parts, 1, parts.length));

        List<BalanceEntry> top = leaderboard.top(currency.id(), position);
        if (top.size() < position) {
            return "";
        }
        BalanceEntry entry = top.get(position - 1);
        BigDecimal amount = entry.balance();

        return switch (field) {
            case "name" -> entry.name();
            case "amount" -> currency.raw(amount);
            case "amount_formatted" -> currency.formatted(amount);
            case "amount_commas" -> currency.commas(amount);
            case "amount_integer" -> currency.integer(amount);
            default -> null;
        };
    }

    private String handleCurrency(String afterType) {
        StringBuilder rest = new StringBuilder();
        Currency currency = resolveCurrencyPrefix(afterType, rest);
        if (currency == null) {
            return null;
        }
        return switch (rest.toString()) {
            case "name" -> currency.name();
            case "symbol" -> currency.symbol();
            case "max" -> {
                BigDecimal max = currency.maxBalance();
                yield max != null ? currency.raw(max) : "-1";
            }
            case "default" -> currency.raw(currency.defaultBalance());
            default -> null;
        };
    }

    private Currency resolveCurrencyPrefix(String text, StringBuilder rest) {
        CurrencyRegistry registry = registry();
        if (registry == null) {
            return null;
        }
        Currency best = null;
        int bestLen = -1;
        for (Currency currency : registry.all()) {
            String id = currency.id();
            boolean match = text.equals(id) || text.startsWith(id + "_");
            if (match && id.length() > bestLen) {
                best = currency;
                bestLen = id.length();
            }
        }
        if (best == null) {
            return null;
        }
        rest.setLength(0);
        if (text.length() > best.id().length()) {
            rest.append(text, best.id().length() + 1, text.length());
        }
        return best;
    }

    private EconomyService economy() {
        return plugin.getEconomyService();
    }

    private LeaderboardCache leaderboard() {
        return plugin.getLeaderboardCache();
    }

    private CurrencyRegistry registry() {
        return plugin.getCurrencyRegistry();
    }

    private void logErrorThrottled(String message, Throwable t) {
        long now = System.currentTimeMillis();
        long last = lastErrorLogAt.get();
        if (now - last < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        if (lastErrorLogAt.compareAndSet(last, now)) {
            plugin.getLogger().log(Level.WARNING, message, t);
        }
    }
}