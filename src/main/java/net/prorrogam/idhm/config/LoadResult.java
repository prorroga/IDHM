package net.prorrogam.idhm.config;

import java.util.List;

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

    public static <T> LoadResult<T> fail(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "LoadResult.fail requires at least one error. "
                            + "Use ok() if the load succeeded.");
        }
        return fail(errors, List.of());
    }

    public static <T> LoadResult<T> fail(List<String> errors, List<String> warnings) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "LoadResult.fail requires at least one error. "
                            + "Use ok() if the load succeeded.");
        }
        return new LoadResult<>(null, warnings, errors);
    }
}
