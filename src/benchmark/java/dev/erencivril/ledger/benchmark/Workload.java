package dev.erencivril.ledger.benchmark;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The list of transfers a scenario will attempt, generated up front from a fixed seed. Every strategy
 * gets exactly the same list, so the strategies are compared on identical work.
 */
final class Workload {

    /** One transfer to attempt. The indexes point into the list of accounts the runner opened. */
    record Operation(int fromIndex, int toIndex, BigDecimal amount) {}

    enum Scenario {
        /** 200 accounts, every transfer between two accounts picked uniformly at random. */
        NORMAL("normal: 200 accounts, pairs picked at random"),
        /** Account 0 is hot: 80% of transfers have it as the source or the destination (half each). */
        HOT("hot: 80% of transfers touch one account");

        final String description;

        Scenario(String description) {
            this.description = description;
        }
    }

    static final int ACCOUNT_COUNT = 200;
    static final int HOT_PERCENT = 80;

    private Workload() {}

    static List<Operation> generate(Scenario scenario, int count, long seed) {
        Random random = new Random(seed);
        List<Operation> operations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from;
            int to;
            if (scenario == Scenario.HOT && random.nextInt(100) < HOT_PERCENT) {
                int other = 1 + random.nextInt(ACCOUNT_COUNT - 1);
                boolean hotIsSource = random.nextBoolean();
                from = hotIsSource ? 0 : other;
                to = hotIsSource ? other : 0;
            } else {
                from = random.nextInt(ACCOUNT_COUNT);
                do {
                    to = random.nextInt(ACCOUNT_COUNT);
                } while (to == from);
            }
            // 0.01 to 5.00
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(500), 2);
            operations.add(new Operation(from, to, amount));
        }
        return operations;
    }
}
