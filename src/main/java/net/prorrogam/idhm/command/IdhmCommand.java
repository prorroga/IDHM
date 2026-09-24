package net.prorrogam.idhm.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.prorrogam.idhm.IDHM;
import net.prorrogam.idhm.api.BalanceEntry;
import net.prorrogam.idhm.config.ReloadResult;
import net.prorrogam.idhm.currency.Currency;
import net.prorrogam.idhm.currency.CurrencyRegistry;
import net.prorrogam.idhm.economy.EconomyService;
import net.prorrogam.idhm.economy.InsufficientFundsException;
import net.prorrogam.idhm.economy.LeaderboardCache;
import net.prorrogam.idhm.economy.MaxBalanceException;
import net.prorrogam.idhm.message.MessageService;
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

    private final IDHM plugin;

    public IdhmCommand(IDHM plugin) {
        this.plugin = plugin;
    }

    // Lecturas frescas: el reload puede cambiar estos campos en el plugin.

    private CurrencyRegistry registry()    { return plugin.getCurrencyRegistry(); }
    private EconomyService economy()       { return plugin.getEconomyService(); }
    private LeaderboardCache leaderboard() { return plugin.getLeaderboardCache(); }
    private MessageService messages()      { return plugin.getMessageService(); }

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
                .then(buildReload())
                .build();
    }

    // /idhm help

    private LiteralArgumentBuilder<CommandSourceStack> buildHelp() {
        return Commands.literal("help")
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    reply(sender, Messages.HELP_HEADER);
                    reply(sender, Messages.HELP_LINE,
                            Placeholder.unparsed("subcommand", "balance [player] [currency]"),
                            Placeholder.unparsed("description", "Check a balance"));
                    reply(sender, Messages.HELP_LINE,
                            Placeholder.unparsed("subcommand", "pay <player> <amount> [currency]"),
                            Placeholder.unparsed("description", "Pay another player"));
                    reply(sender, Messages.HELP_LINE,
                            Placeholder.unparsed("subcommand", "top [currency]"),
                            Placeholder.unparsed("description", "Show the leaderboard"));
                    reply(sender, Messages.HELP_LINE,
                            Placeholder.unparsed("subcommand", "reload"),
                            Placeholder.unparsed("description", "Reload messages and intervals"));
                    return Command.SINGLE_SUCCESS;
                });
    }

    // /idhm balance [player] [currency]

    private LiteralArgumentBuilder<CommandSourceStack> buildBalance() {
        return Commands.literal("balance")
                .requires(src -> src.getSender().hasPermission("idhm.balance"))
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    showBalance(player, player.getUniqueId(), player.getName(), defaultCurrency());
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
                    if (leaderboard() == null) {
                        reply(ctx.getSource().getSender(), Messages.LEADERBOARD_DISABLED);
                        return Command.SINGLE_SUCCESS;
                    }
                    showTop(ctx.getSource().getSender(), defaultCurrency(), 10);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("currency", StringArgumentType.word())
                        .suggests(this::suggestCurrencies)
                        .executes(ctx -> {
                            if (leaderboard() == null) {
                                reply(ctx.getSource().getSender(),
                                        Messages.LEADERBOARD_DISABLED);
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
                                        .executes(ctx -> runPay(ctx,
                                                resolveCurrency(ctx, "currency"))))));
    }

    private int runPay(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        Player sender = requirePlayer(ctx);
        Player target = resolveTarget(ctx, "target");
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            throw aborted(ctx, Messages.PAY_SELF);
        }
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        TagResolver amountTag   = Placeholder.unparsed("amount", formatAmount(currency, amount));
        TagResolver currencyTag = Placeholder.unparsed("currency", currency.name());

        economy().transferAsync(sender.getUniqueId(), target.getUniqueId(),
                        currency.id(), amount)
                .thenAccept(result -> {
                    replyAsync(sender, Messages.PAY_SENT,
                            amountTag, currencyTag,
                            Placeholder.unparsed("player", target.getName()));
                    replyAsync(target, Messages.PAY_RECEIVED,
                            amountTag, currencyTag,
                            Placeholder.unparsed("player", sender.getName()));
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
                                        .executes(ctx -> runGive(ctx,
                                                resolveCurrency(ctx, "currency"))))));
    }

    private int runGive(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        economy().depositAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_GIVE,
                        Placeholder.unparsed("amount", formatAmount(currency, amount)),
                        Placeholder.unparsed("currency", currency.name()),
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("balance", formatAmount(currency, newBalance))))
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
                                        .executes(ctx -> runTake(ctx,
                                                resolveCurrency(ctx, "currency"))))));
    }

    private int runTake(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, false);

        economy().withdrawAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_TAKE,
                        Placeholder.unparsed("amount", formatAmount(currency, amount)),
                        Placeholder.unparsed("currency", currency.name()),
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("balance", formatAmount(currency, newBalance))))
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
                                        .executes(ctx -> runSet(ctx,
                                                resolveCurrency(ctx, "currency"))))));
    }

    private int runSet(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");
        BigDecimal amount = parseAmount(ctx, "amount", currency, true);

        economy().setBalanceAsync(target.getUniqueId(), currency.id(), amount)
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_SET,
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("currency", currency.name()),
                        Placeholder.unparsed("amount", formatAmount(currency, newBalance))))
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
                                .executes(ctx -> runReset(ctx,
                                        resolveCurrency(ctx, "currency")))));
    }

    private int runReset(CommandContext<CommandSourceStack> ctx, Currency currency)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Player target = resolveTarget(ctx, "target");

        economy().setBalanceAsync(target.getUniqueId(), currency.id(),
                        currency.defaultBalance())
                .thenAccept(newBalance -> replyAsync(sender, Messages.ADMIN_RESET,
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("currency", currency.name()),
                        Placeholder.unparsed("amount", formatAmount(currency, newBalance))))
                .exceptionally(ex -> {
                    handleAsyncError(sender, ex, currency, "reset");
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    // /idhm reload

    private LiteralArgumentBuilder<CommandSourceStack> buildReload() {
        return Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission("idhm.admin.reload"))
                .executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    // El reload toca estado global (tasks, campos del plugin).
                    // Lo corremos en el hilo global de Folia; la respuesta se
                    // agenda en el scheduler del sender si es un jugador.
                    SchedulerUtil.runGlobal(plugin, () -> {
                        ReloadResult result = plugin.reload();
                        if (result.success()) {
                            replyAsync(sender, Messages.RELOAD_SUCCESS);
                            return;
                        }
                        replyAsync(sender, Messages.RELOAD_FAILED);
                        for (String error : result.errors()) {
                            plugin.getLogger().warning("reload: " + error);
                            replyAsync(sender, Messages.RELOAD_ERROR_LINE,
                                    Placeholder.unparsed("error", error));
                        }
                    });
                    return Command.SINGLE_SUCCESS;
                });
    }

    // Shared rendering

    private void showBalance(CommandSender sender, UUID uuid, String name, Currency currency) {
        BigDecimal balance = economy().balance(uuid, currency.id());
        boolean self = sender instanceof Player p && p.getUniqueId().equals(uuid);
        String key = self ? Messages.BALANCE_SELF : Messages.BALANCE_OTHER;
        reply(sender, key,
                Placeholder.unparsed("player", name),
                Placeholder.unparsed("amount", formatAmount(currency, balance)),
                Placeholder.unparsed("currency", currency.name()));
    }

    private void showTop(CommandSender sender, Currency currency, int limit) {
        List<BalanceEntry> entries = leaderboard().top(currency.id(), limit);
        if (entries.isEmpty()) {
            reply(sender, Messages.TOP_EMPTY,
                    Placeholder.unparsed("currency", currency.name()));
            return;
        }
        reply(sender, Messages.TOP_HEADER,
                Placeholder.unparsed("currency", currency.name()));
        int position = 1;
        for (BalanceEntry entry : entries) {
            reply(sender, Messages.TOP_ENTRY,
                    Placeholder.unparsed("position", String.valueOf(position)),
                    Placeholder.unparsed("player", entry.name()),
                    Placeholder.unparsed("amount", formatAmount(currency, entry.balance())));
            position++;
        }
    }

    // Argument resolution

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            throw aborted(ctx, Messages.PLAYER_ONLY);
        }
        return player;
    }

    private Player resolveTarget(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver =
                ctx.getArgument(name, PlayerSelectorArgumentResolver.class);
        List<Player> resolved = resolver.resolve(ctx.getSource());
        if (resolved.isEmpty()) {
            throw aborted(ctx, Messages.INVALID_PLAYER,
                    Placeholder.unparsed("player", name));
        }
        return resolved.getFirst();
    }

    private Currency resolveCurrency(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, name).toLowerCase();
        Currency currency = registry().get(id);
        if (currency == null) {
            throw aborted(ctx, Messages.UNKNOWN_CURRENCY,
                    Placeholder.unparsed("currency", id));
        }
        return currency;
    }

    private BigDecimal parseAmount(CommandContext<CommandSourceStack> ctx, String name,
                                   Currency currency, boolean allowZero)
            throws CommandSyntaxException {
        String raw = StringArgumentType.getString(ctx, name);
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw aborted(ctx, Messages.INVALID_AMOUNT,
                    Placeholder.unparsed("amount", raw));
        }
        boolean invalidSign = allowZero ? amount.signum() < 0 : amount.signum() <= 0;
        if (invalidSign || amount.stripTrailingZeros().scale() > currency.maxDecimals()) {
            throw aborted(ctx, Messages.INVALID_AMOUNT,
                    Placeholder.unparsed("amount", raw));
        }
        return amount;
    }

    private Currency defaultCurrency() {
        return registry().defaultCurrency();
    }

    private CompletableFuture<Suggestions> suggestCurrencies(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Currency currency : registry().all()) {
            if (currency.id().startsWith(remaining)) {
                builder.suggest(currency.id());
            }
        }
        return builder.buildFuture();
    }

    // Messaging helpers

    private void reply(CommandSender sender, String key, TagResolver... resolvers) {
        messages().send(sender, key, resolvers);
    }

    private void replyAsync(CommandSender sender, String key, TagResolver... resolvers) {
        if (sender instanceof Player player) {
            SchedulerUtil.runForEntity(plugin, player,
                    () -> messages().send(player, key, resolvers));
        } else {
            messages().send(sender, key, resolvers);
        }
    }

    /**
     * Construye una {@link CommandSyntaxException} cuyo mensaje ya está
     * resuelto en el locale del sender y serializado a texto plano.
     *
     * <p>Brigadier en Paper 1.21 renderiza las excepciones como texto plano,
     * por lo que aquí se pierde el color de MiniMessage. La localización sí
     * se conserva porque {@link MessageService#resolve} se evalúa antes de
     * crear la excepción.</p>
     */
    private CommandSyntaxException aborted(CommandContext<CommandSourceStack> ctx,
                                           String key, TagResolver... resolvers) {
        CommandSender sender = ctx.getSource().getSender();
        Component component = messages().resolve(sender, key, resolvers);
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        return new SimpleCommandExceptionType(new LiteralMessage(plain)).create();
    }

    private void handleAsyncError(CommandSender sender, Throwable ex,
                                  Currency currency, String reason) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        TagResolver currencyTag = Placeholder.unparsed("currency", currency.name());
        if (cause instanceof InsufficientFundsException) {
            replyAsync(sender, insufficientFundsKey(reason), currencyTag);
        } else if (cause instanceof MaxBalanceException) {
            replyAsync(sender, maxBalanceKey(reason), currencyTag);
        } else {
            replyAsync(sender, Messages.GENERIC_ERROR);
            plugin.getLogger().log(Level.WARNING,
                    "Error in /idhm " + reason + ": " + cause.getMessage(), cause);
        }
    }

    private String insufficientFundsKey(String reason) {
        return "pay".equals(reason) ? Messages.PAY_INSUFFICIENT : Messages.ADMIN_INSUFFICIENT;
    }

    private String maxBalanceKey(String reason) {
        return "pay".equals(reason) ? Messages.PAY_RECEIVER_MAX : Messages.ADMIN_MAX_BALANCE;
    }

    // Formatting

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
}