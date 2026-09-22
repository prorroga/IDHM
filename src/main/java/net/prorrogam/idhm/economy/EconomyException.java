package net.prorrogam.idhm.economy;

/**
 * Base class for expected economy failures.
 * <p>
 * Not a {@link RuntimeException} subclass for programming errors: this
 * hierarchy represents business rule violations that callers are
 * expected to handle.
 */
public class EconomyException extends RuntimeException {

    public EconomyException(String message) {
        super(message);
    }
}
