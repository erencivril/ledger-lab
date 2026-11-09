package dev.erencivril.ledger.application;

import java.util.Random;

/**
 * How many times a transfer may be attempted when it loses a race, and how long to wait between attempts.
 *
 * <p>The wait is "full jitter": after the n-th failed attempt, wait a random time between 0 and
 * {@code min(1000, baseMillis * 2^(n-1))} milliseconds. The ceiling doubles so a crowd backs off; the
 * randomness stops all the losers from coming back at the same instant and colliding again.
 */
public record RetryPolicy(int maxAttempts, long baseMillis) {

    private static final long CEILING_CAP_MILLIS = 1000;
    private static final int MAX_DOUBLINGS = 20; // keeps the shift far away from overflow

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, got " + maxAttempts);
        }
        if (baseMillis < 1) {
            throw new IllegalArgumentException("baseMillis must be at least 1, got " + baseMillis);
        }
    }

    /** The longest possible wait after {@code failedAttempts} failures (1 for the first failure). */
    public long ceilingMillis(int failedAttempts) {
        int doublings = Math.min(failedAttempts - 1, MAX_DOUBLINGS);
        return Math.min(CEILING_CAP_MILLIS, baseMillis << doublings);
    }

    /** A random wait from 0 up to and including the ceiling. */
    public long jitterMillis(int failedAttempts, Random random) {
        return random.nextLong(ceilingMillis(failedAttempts) + 1);
    }
}
