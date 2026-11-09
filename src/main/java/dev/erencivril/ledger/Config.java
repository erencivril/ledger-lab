package dev.erencivril.ledger;

import java.util.Map;

/** Runtime settings, all from environment variables with local-development defaults. */
public record Config(
        int port,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        int databasePoolSize,
        String redisUrl,
        int velocityMaxTransfers,
        int velocityWindowSeconds,
        boolean velocityFailOpen) {

    public static Config fromEnv(Map<String, String> env) {
        return new Config(
                intOf(env, "PORT", 8080),
                env.getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/ledger"),
                env.getOrDefault("DATABASE_USER", "ledger"),
                env.getOrDefault("DATABASE_PASSWORD", "ledger"),
                intOf(env, "DATABASE_POOL_SIZE", 10),
                env.getOrDefault("REDIS_URL", "redis://localhost:6379"),
                intOf(env, "VELOCITY_MAX_TRANSFERS", 10),
                intOf(env, "VELOCITY_WINDOW_SECONDS", 60),
                Boolean.parseBoolean(env.getOrDefault("VELOCITY_FAIL_OPEN", "true")));
    }

    private static int intOf(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + value + "'");
        }
    }
}
