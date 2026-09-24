package net.prorrogam.idhm.config;

import java.util.List;

public record ReloadResult(boolean success, List<String> errors) {

    public ReloadResult {
        errors = List.copyOf(errors);
        if (success && !errors.isEmpty()) {
            throw new IllegalArgumentException("ReloadResult: success with errors");
        }
        if (!success && errors.isEmpty()) {
            throw new IllegalArgumentException("ReloadResult: failure without errors");
        }
    }

    public static ReloadResult ok() {
        return new ReloadResult(true, List.of());
    }

    public static ReloadResult failure(String... errors) {
        return new ReloadResult(false, List.of(errors));
    }

    public static ReloadResult failure(List<String> errors) {
        return new ReloadResult(false, errors);
    }
}