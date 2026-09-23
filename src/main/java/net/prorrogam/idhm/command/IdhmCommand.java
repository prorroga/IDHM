package net.prorrogam.idhm.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.prorrogam.idhm.IDHM;
import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.economy.InsufficientFundsException;
import net.prorrogam.idhm.economy.LeaderboardCache;
import net.prorrogam.idhm.economy.MaxBalanceException;
import net.prorrogam.idhm.util.SchedulerUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class IdhmCommand {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final IDHM plugin;
    private final CurrencyRegistry registry;
    private final EconomyService economy;
    private final LeaderboardCache leaderboard;

    private static final SimpleCommandExceptionType PLAYER_ONLY =
            new SimpleCommandExceptionType(new LiteralMessage(Messages.PLAYER_ONLY));
    private static final SimpleCommandExceptionType SELF_PAY =
            new SimpleCommandExceptionType(new LiteralMessage(Messages.PAY_SELF));
    private static final DynamicCommandExceptionType UNKNOWN_CURRENCY =
            new DynamicCommandExceptionType(id -> new LiteralMessage(
                    Messages.UNKNOWN_CURRENCY.replace("{currency}", String.valueOf(id))));
    private static final DynamicCommandExceptionType INVALID_AMOUNT =
            new DynamicCommandExceptionType(raw -> new LiteralMessage(
                    Messages.INVALID_AMOUNT.replace("{amount}", String.valueOf(raw))));

    public IdhmCommand(IDHM plugin, CurrencyRegistry registry,
                       EconomyService economy, LeaderboardCache leaderboard) {
        this.plugin = plugin;
        this.registry = registry;
        this.economy = economy;
        this.leaderboard = leaderboard;
    }

    // Root

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("idhm")
                .then(buildHelp())
                .then(buildBalance())
                .then(buildTop())
                .then(buildPay())
                .then(buildGive())
                .then(buildTake())
                .then(buildSet())
                .then(buildReset())
                .build();
    }

    // /idhm help

    private LiteralArgumentBuilder<CommandSourceStack> buildHelp() {
        return Commands.literal("help")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    reply(sender, Messages.HELP_HEADER);
                    reply(sender, Messages.HELP_LINE, "subcommand", "balance [player] [currency]", "description", "Check a balance");
                    reply(sender, Messages.HELP_LINE, "subcommand", "pay <player> <amount> [currency]", "description", "Pay another player");
                    reply(sender, Messages.HELP_LINE, "subcommand", "top [currency]", "description", "Show the leaderboard");
                    return Command.SINGLE_SUCCESS;
                });
    }

    // /idhm balance [player] [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildBalance() {
        return Commands.literal("balance")
                .requires(src -> src.getSender().hasPermission("idhm.balance"))
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    Currency currency = defaultCurrency();
                    showBalance(player, player.getUniqueId(), player.getName(), currency);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("player", ArgumentTypes.player())
                        .requires(src -> src.getSender().hasPermission("idhm.balance.other"))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Player target = resolveTarget(ctx, "player");
                            showBalance(sender, target.getUniqueId(), target.getName(),
                                    defaultCurrency());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(this::suggestCurrencies)
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    Player target = resolveTarget(ctx, "player");
                                    Currency currency = resolveCurrency(ctx, "currency");
                                    showBalance(sender, target.getUniqueId(),
                                            target.getName(), currency);
                                    return Command.SINGLE_SUCCESS;
                                })));
    }

    // /idhm top [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildTop() {
        return Commands.literal("top")
                .requires(src -> src.getSender().hasPermission("idhm.top"))
                .executes(ctx -> {
                    if (leaderboard == null) {
                        reply(ctx.getSource().getSender(), Messages.LEADERBOARD_DISABLED);
                        return Command.SINGLE_SUCCESS;
                    }
                    showTop(ctx.getSource().getSender(), defaultCurrency(), 10);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("currency", StringArgumentType.word())
                        .suggests(this::suggestCurrencies)
                        .executes(ctx -> {
                            if (leaderboard == null) {
                                reply(ctx.getSource().getSender(), Messages.LEADERBOARD_DISABLED);
                                return Command.SINGLE_SUCCESS;
                            }
                            Currency currency = resolveCurrency(ctx, "currency");
                            showTop(ctx.getSource().getSender(), currency, 10);
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    // /idhm pay <player> <amount> [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildPay() {
        return Commands.literal("pay")
                .requires(src -> src.getSender().hasPermission("idhm.pay"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> runPay(ctx, defaultCurrency()))
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests(this::suggestCurrencies)
                                        .executes(ctx -> runPay(ctx, resolveCurrency(ctx, "currency"))))));
    }

    private int runPay(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        Player sender = requirePlayer(ctx);
        Player target = resolveTarget(ctx, "target");
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            throw SELF_PAY.create();
        }
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        economy.transferAsync(sender.getUniqueId(), target.getUniqueId(),
                        currency.id(), amount)
                .thenAccept(result -> {
                    replyAsync(sender, Messages.PAY_SENT,
                            "amount", formatAmount(currency, amount),
                            "currency", currency.name(),
                            "player", target.getName());
                    replyAsync(target, Messages.PAY_RECEIVED,
                            "amount", formatAmount(currency, amount),
                            "currency", currency.name(),
                            "player", sender.getName());
                })
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "pay");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    // /idhm give <player> <amount> [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildGive() {
        return Commands.literal("give")
                .requires(src -> src.getSender().hasPermission("idhm.admin.give"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> runGive(ctx, defaultCurrency()))
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests(this::suggestCurrencies)
                                        .executes(ctx -> runGive(ctx, resolveCurrency(ctx, "currency"))))));
    }

    private int runGive(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        economy.depositAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_GIVE,
                        "amount", formatAmount(currency, amount),
                        "currency", currency.name(),
                        "player", target.getName(),
                        "balance", formatAmount(currency, newBalance)))
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "give");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    // /idhm take <player> <amount> [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildTake() {
        return Commands.literal("take")
                .requires(src -> src.getSender().hasPermission("idhm.admin.take"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> runTake(ctx, defaultCurrency()))
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests(this::suggestCurrencies)
                                        .executes(ctx -> runTake(ctx, resolveCurrency(ctx, "currency"))))));
    }

    private int runTake(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        economy.withdrawAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_TAKE,
                        "amount", formatAmount(currency, amount),
                        "currency", currency.name(),
                        "player", target.getName(),
                        "balance", formatAmount(currency, newBalance)))
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "take");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    // /idhm set <player> <amount> [currency]
    private LiteralArgumentBuilder<CommandSourceStack> buildSet() {
        return Commands.literal("set")
                .requires(src -> src.getSender().hasPermission("idhm.admin.set"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("amount", StringArgumentType.word())
                                .executes(ctx -> runSet(ctx, defaultCurrency()))
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests(this::suggestCurrencies)
                                        .executes(ctx -> runSet(ctx, resolveCurrency(ctx, "currency"))))));
    }

    private int runSet(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, true);

        economy.setBalanceAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_SET,
                        "player", target.getName(),
                        "currency", currency.name(),
                        "amount", formatAmount(currency, newBalance)))
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "set");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    // /idhm reset <player> [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildReset() {
        return Commands.literal("reset")
                .requires(src -> src.getSender().hasPermission("idhm.admin.reset"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(ctx -> runReset(ctx, defaultCurrency()))
                        .then(Commands.argument("currency", StringArgumentType.word())
                                .suggests(this::suggestCurrencies)
                                .executes(ctx -> runReset(ctx, resolveCurrency(ctx, "currency")))));
    }

    private int runReset(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");

        economy.setBalanceAsync(target.getUniqueId(), currency.id(), currency.defaultBalance())
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_RESET,
                        "player", target.getName(),
                        "currency", currency.name(),
                        "amount", formatAmount(currency, newBalance)))
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "reset");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private void showBalance(CommandSender sender, UUID uuid, String name, Currency currency) {
        BigDecimal balance = economy.balance(uuid, currency.id());
        boolean self = sender instanceof Player p && p.getUniqueId().equals(uuid);
        String template = self ? Messages.BALANCE_SELF : Messages.BALANCE_OTHER;
        reply(sender, template,
                "player", name,
                "amount", formatAmount(currency, balance),
                "currency", currency.name());
    }

    private void showTop(CommandSender sender, Currency currency, int limit) {
        List<BalanceEntry> entries = leaderboard.top(currency.id(), limit);
        if (entries.isEmpty()) {
            reply(sender, Messages.TOP_EMPTY, "currency", currency.name());
            return;
        }
        reply(sender, Messages.TOP_HEADER, "currency", currency.name());
        int position = 1;
        for (BalanceEntry entry : entries) {
            reply(sender, Messages.TOP_ENTRY,
                    "position", String.valueOf(position),
                    "player", entry.name(),
                    "amount", formatAmount(currency, entry.balance()));
            position++;
        }
    }

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            throw PLAYER_ONLY.create();
        }
        return player;
    }

    private Player resolveTarget(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver =
                ctx.getArgument(name, PlayerSelectorArgumentResolver.class);
        return resolver.resolve(ctx.getSource()).getFirst();
    }

    private Currency resolveCurrency(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, name).toLowerCase();
        Currency currency = registry.get(id);
        if (currency == null) {
            throw UNKNOWN_CURRENCY.create(id);
        }
        return currency;
    }

    private BigDecimal parseAmount(CommandContext<CommandSourceStack> ctx, String name,
                                   Currency currency, boolean allowZero) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, name);
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw INVALID_AMOUNT.create(raw);
        }
        boolean invalidSign = allowZero ? amount.signum() < 0 : amount.signum() <= 0;
        if (invalidSign) {
            throw INVALID_AMOUNT.create(raw);
        }
        if (amount.stripTrailingZeros().scale() > currency.maxDecimals()) {
            throw INVALID_AMOUNT.create(raw);
        }
        return amount;
    }

    private Currency defaultCurrency() {
        return registry.defaultCurrency();
    }

    private CompletableFuture<Suggestions> suggestCurrencies(CommandContext<CommandSourceStack> ctx,
                                                             SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Currency currency : registry.all()) {
            if (currency.id().startsWith(remaining)) {
                builder.suggest(currency.id());
            }
        }
        return builder.buildFuture();
    }

    private void reply(CommandSender sender, String template, Object... kv) {
        sender.sendMessage(LEGACY.deserialize(format(template, kv)));
    }

    private void replyAsync(CommandSender sender, String template, Object... kv) {
        String message = format(template, kv);
        if (sender instanceof Player player) {
            SchedulerUtil.runForEntity(plugin, player, () ->
                    player.sendMessage(LEGACY.deserialize(message)));
        } else {
            sender.sendMessage(LEGACY.deserialize(message));
        }
    }

    private void handleAsyncError(CommandSender sender, Throwable ex,
                                  Currency currency, String reason) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof InsufficientFundsException) {
            replyAsync(sender, insufficientFundsMessage(reason), "currency", currency.name());
        } else if (cause instanceof MaxBalanceException) {
            replyAsync(sender, maxBalanceMessage(reason), "currency", currency.name());
        } else {
            replyAsync(sender, Messages.GENERIC_ERROR);
            plugin.getLogger().log(Level.WARNING,
                    "Error in /idhm " + reason + ": " + cause.getMessage(), cause);
        }
    }

    private String insufficientFundsMessage(String reason) {
        return "pay".equals(reason) ? Messages.PAY_INSUFFICIENT : Messages.ADMIN_INSUFFICIENT;
    }

    private String maxBalanceMessage(String reason) {
        return "pay".equals(reason) ? Messages.PAY_RECEIVER_MAX : Messages.ADMIN_MAX_BALANCE;
    }

    private String formatAmount(Currency currency, BigDecimal amount) {
        DecimalFormat df;
        try {
            df = new DecimalFormat(currency.decimalFormat());
        } catch (IllegalArgumentException e) {
            df = new DecimalFormat("#,##0.00");
        }
        String amountStr = df.format(amount);
        return currency.format()
                .replace("%amount%", amountStr)
                .replace("%symbol%", currency.symbol())
                .replace("%currency%", currency.name());
    }

    private static String format(String template, Object... kv) {
        if (kv.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < kv.length; i += 2) {
            result = result.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return result;
    }
}