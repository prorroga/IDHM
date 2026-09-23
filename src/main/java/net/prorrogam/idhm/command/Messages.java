package net.prorrogam.idhm.command;

public final class Messages {

    private Messages() {}

    public static final String NO_PERMISSION = "You don't have permission to do that.";
    public static final String PLAYER_ONLY = "This command can only be used by players.";
    public static final String UNKNOWN_CURRENCY = "Unknown currency: {currency}";
    public static final String INVALID_AMOUNT = "Invalid amount: {amount}";
    public static final String INVALID_PLAYER = "Unknown player: {player}";
    public static final String GENERIC_ERROR = "An error occurred. Please try again.";

    // Balance
    public static final String BALANCE_SELF = "Your balance: {amount} {currency}";
    public static final String BALANCE_OTHER = "{player}'s balance: {amount} {currency}";

    // Pay
    public static final String PAY_SENT = "You sent {amount} {currency} to {player}.";
    public static final String PAY_RECEIVED = "You received {amount} {currency} from {player}.";
    public static final String PAY_SELF = "You cannot pay yourself.";
    public static final String PAY_INSUFFICIENT = "You don't have enough {currency}.";
    public static final String PAY_RECEIVER_MAX = "The receiver would exceed the max balance for {currency}.";

    // Admin
    public static final String ADMIN_GIVE = "Gave {amount} {currency} to {player}. New balance: {balance}";
    public static final String ADMIN_TAKE = "Took {amount} {currency} from {player}. New balance: {balance}";
    public static final String ADMIN_SET = "Set {player}'s {currency} balance to {amount}.";
    public static final String ADMIN_RESET = "Reset {player}'s {currency} balance to {amount}.";
    public static final String ADMIN_INSUFFICIENT = "The target doesn't have enough {currency}.";
    public static final String ADMIN_MAX_BALANCE = "The target would exceed the max balance for {currency}.";

    // Top
    public static final String TOP_HEADER = "--- Top {currency} ---";
    public static final String TOP_ENTRY = "{position}. {player} - {amount}";
    public static final String TOP_EMPTY = "No entries yet for {currency}.";
    public static final String TOP_LOADING = "The leaderboard is still loading. Try again in a moment.";
    public static final String LEADERBOARD_DISABLED = "The leaderboard is disabled in config.";

    // Help
    public static final String HELP_HEADER = "--- IDHM help ---";
    public static final String HELP_LINE = "/idhm {subcommand} - {description}";
}