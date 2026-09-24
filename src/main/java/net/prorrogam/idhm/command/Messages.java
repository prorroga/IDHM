package net.prorrogam.idhm.command;

/**
 * Catálogo de claves de traducción usadas por {@link IdhmCommand}.
 * Los valores coinciden con las claves de
 * {@code translations/<locale>/messages.yml}.
 */
public final class Messages {

    private Messages() {}

    // Errors
    public static final String NO_PERMISSION    = "no-permission";
    public static final String PLAYER_ONLY      = "player-only";
    public static final String UNKNOWN_CURRENCY = "unknown-currency";
    public static final String INVALID_AMOUNT   = "invalid-amount";
    public static final String INVALID_PLAYER   = "invalid-player";
    public static final String GENERIC_ERROR    = "generic-error";

    // Balance
    public static final String BALANCE_SELF  = "balance-self";
    public static final String BALANCE_OTHER = "balance-other";

    // Pay
    public static final String PAY_SENT         = "payment-sent";
    public static final String PAY_RECEIVED     = "payment-received";
    public static final String PAY_SELF         = "pay-self";
    public static final String PAY_INSUFFICIENT = "insufficient-funds";
    public static final String PAY_RECEIVER_MAX = "pay-receiver-max";

    // Admin
    public static final String ADMIN_GIVE         = "admin-give";
    public static final String ADMIN_TAKE         = "admin-take";
    public static final String ADMIN_SET          = "admin-set";
    public static final String ADMIN_RESET        = "admin-reset";
    public static final String ADMIN_INSUFFICIENT = "admin-insufficient";
    public static final String ADMIN_MAX_BALANCE  = "admin-max-balance";

    // Top
    public static final String TOP_HEADER           = "top-header";
    public static final String TOP_ENTRY            = "top-entry";
    public static final String TOP_EMPTY            = "top-empty";
    public static final String TOP_LOADING          = "top-loading";
    public static final String LEADERBOARD_DISABLED = "leaderboard-disabled";

    // Help
    public static final String HELP_HEADER = "help-header";
    public static final String HELP_LINE   = "help-line";

    // Reload
    public static final String RELOAD_SUCCESS    = "reload";
    public static final String RELOAD_FAILED     = "reload-failed";
    public static final String RELOAD_ERROR_LINE = "reload-error-line";
}