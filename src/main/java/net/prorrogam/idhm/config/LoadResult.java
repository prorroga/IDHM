package net.prorrogam.idhm.config;

import java.util.List;

/**
 * Result of a load operation that can fail in an expected way.
 * <p>
 * {@code warnings} are non-fatal issues; {@code errors} are fatal.
 * <p>
 * <b>Contract:</b> {@code value} is {@code null} if and only if
 * {@code errors} is non-empty. After a {@link #success()} that returns
 * true, {@link #value()} never returns {@code null}.
 */
public record LoadResult<T>(
        T value,
        List<String> warnings,
        List<String> errors
) {

    public LoadResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);

        if (errors.isEmpty() && value == null) {
            throw new IllegalArgumentException(
                    "LoadResult.ok requires a non-null value. Use fail() if the load failed.");
        }
        if (!errors.isEmpty() && value != null) {
            throw new IllegalArgumentException(
                    "LoadResult.fail does not accept a value. Received: " + value);
        }
    }

    public boolean success() {
        return errors.isEmpty();
    }

    /**
     * @return the value if {@link #success()}.
     * @throws IllegalStateException if the load failed.
     */
    public T valueOrThrow() {
        if (!success()) {
            throw new IllegalStateException(
                    "LoadResult failed (" + errors.size() + " errors): " + errors);
        }
        return value;
    }

    public static <T> LoadResult<T> ok(T value, List<String> warnings) {
        return new LoadResult<>(value, warnings, List.of());
    }

    public static <T> LoadResult<T> ok(T value) {
        return new LoadResult<>(value, List.of(), List.of());
    }

    /**
     * @throws IllegalArgumentException if {@code errors} is null or empty.
     *         A failure without a reason is not a failure; use {@link #ok}
     *         if the load succeeded.
     */
    public static <T> LoadResult<T> fail(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "LoadResult.fail requires at least one error. "
                            + "Use ok() if the load succeeded.");
        }
        return fail(errors, List.of());
    }

    /**
     * @throws IllegalArgumentException if {@code errors} is null or empty.
     */
    public static <T> LoadResult<T> fail(List<String> errors, List<String> warnings) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "LoadResult.fail requires at least one error. "
                            + "Use ok() if the load succeeded.");
        }
        return new LoadResult<>(null, warnings, errors);
    }
}
