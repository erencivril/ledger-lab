package dev.erencivril.ledger.benchmark;

/** The measurements of one run of one strategy on one scenario. */
record RunResult(
        long committed,
        long rejectedInsufficientFunds,
        long abortedAfterRetries,
        long retries,
        double seconds,
        double p50Millis,
        double p99Millis) {

    double committedPerSecond() {
        return committed / seconds;
    }
}
