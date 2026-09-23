package net.prorrogam.idhm.config;

import java.util.List;

public final class ConfigVersion {

    private static final String NO_VERSION_SENTINEL = "0";

    private ConfigVersion() {}

    public static LoadResult<String> check(String foundVersion, String expectedVersion) {
        if (foundVersion == null || foundVersion.isBlank()) {
            return LoadResult.ok(NO_VERSION_SENTINEL,
                    List.of("Config version not specified. Treated as version "
                            + NO_VERSION_SENTINEL + " (pre-migration)."));
        }

        if (foundVersion.equals(expectedVersion)) {
            return LoadResult.ok(foundVersion);
        }

        return LoadResult.ok(foundVersion,
                List.of("Config version mismatch (expected " + expectedVersion
                        + ", found " + foundVersion + "). Migration not yet implemented."));
    }
}
