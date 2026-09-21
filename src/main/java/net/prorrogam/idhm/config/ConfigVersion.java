package net.prorrogam.idhm.config;

import java.util.List;

/**
 * Version check for config.yml. For now it only warns — real migration is
 * a future phase. Never fails: this always returns {@code ok(...)}.
 * <p>
 * The value inside the result is the version the caller should carry forward:
 * the sentinel when the config has no version at all, or {@code foundVersion}
 * on a mismatch (we don't overwrite, since we haven't migrated anything yet).
 */
public final class ConfigVersion {

    /**
     * Sentinel used when the config has no version field. Chosen so that a
     * config without a version is detectable as "pre-migration" in a future
     * migration phase. Do not change this to a real version number.
     */
    private static final String NO_VERSION_SENTINEL = "0";

    private ConfigVersion() {}

    /**
     * Checks the config version against the expected one.
     * @param foundVersion    version read from config.yml; may be null or blank
     * @param expectedVersion version the plugin currently expects; must be non-null
     * @return always a successful result; the value is the version to carry
     * forward (the sentinel if none was found, otherwise {@code foundVersion})
     */
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
