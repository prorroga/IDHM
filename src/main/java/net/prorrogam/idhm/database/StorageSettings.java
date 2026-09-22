package net.prorrogam.idhm.database;

import net.prorrogam.idhm.config.LoadResult;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record StorageSettings(
        SqlDialect dialect,
        String url,
        String username,
        String password,
        int poolSize,
        String tablePrefix
) {

    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[a-z0-9_]*$");

    public StorageSettings {
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url must not be blank");
        if (username == null) throw new IllegalArgumentException("username must not be null");
        if (password == null) throw new IllegalArgumentException("password must not be null");
        if (tablePrefix == null) throw new IllegalArgumentException("tablePrefix must not be null");

        if (!PREFIX_PATTERN.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException(
                    "tablePrefix must match " + PREFIX_PATTERN.pattern() + ", got: " + tablePrefix);
        }

        if (dialect.singleConnection() && poolSize != 1) {
            poolSize = 1;
        }

        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1, got " + poolSize);
        }
    }

    public static LoadResult<StorageSettings> from(String type, String url,
                                                   String username, String password,
                                                   int poolSize, String tablePrefix) {
        SqlDialect dialect = SqlDialect.fromId(type);
        if (dialect == null) {
            String valid = Arrays.stream(SqlDialect.values())
                    .map(SqlDialect::id)
                    .collect(Collectors.joining(", "));
            return LoadResult.fail(List.of(
                    "Unknown storage type '" + type + "', expected one of: " + valid));
        }

        if (url == null || url.isBlank()) {
            return LoadResult.fail(List.of(
                    "storage.url must not be blank (dialect: " + dialect.id() + ")"));
        }

        if (poolSize < 1) {
            return LoadResult.fail(List.of(
                    "storage.pool-size must be >= 1, got " + poolSize));
        }

        String finalUsername = username == null ? "" : username;
        String finalPassword = password == null ? "" : password;
        String finalPrefix = tablePrefix == null ? "" : tablePrefix;

        if (!PREFIX_PATTERN.matcher(finalPrefix).matches()) {
            return LoadResult.fail(List.of(
                    "storage.table-prefix must match " + PREFIX_PATTERN.pattern()
                            + ", got: '" + finalPrefix + "'"));
        }

        List<String> warnings = List.of();
        if (dialect.singleConnection() && poolSize != 1) {
            warnings = List.of("storage.pool-size forced to 1 for " + dialect.id()
                    + " (single-writer database). Got: " + poolSize);
        }

        StorageSettings settings = new StorageSettings(
                dialect, url, finalUsername, finalPassword, poolSize, finalPrefix);
        return LoadResult.ok(settings, warnings);
    }
}