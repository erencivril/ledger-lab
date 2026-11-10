package dev.erencivril.ledger.risk;

import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.risk.NameMatcher;
import dev.erencivril.ledger.domain.risk.RiskContext;
import dev.erencivril.ledger.domain.risk.VelocitySnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Invented labelled observations, not real customer data and not evidence of real-world detection quality. */
public final class SyntheticData {
    public enum Pattern { NORMAL, SCAM, MULE }
    public record Sample(int id, Pattern pattern, RiskContext context) {
        public boolean harmful() { return pattern != Pattern.NORMAL; }
    }
    private SyntheticData() {}

    public static List<Sample> generate(int count, long seed) {
        if (count < 0 || count > 1_000_000) throw new IllegalArgumentException("count must be 0..1000000");
        Random random = new Random(seed);
        List<Sample> samples = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            double draw = random.nextDouble();
            Pattern pattern = draw < 0.80 ? Pattern.NORMAL : draw < 0.92 ? Pattern.SCAM : Pattern.MULE;
            boolean harmful = pattern != Pattern.NORMAL;
            // Distributions overlap deliberately: legitimate large first payments and quiet harmful payments exist.
            boolean first = random.nextDouble() < (harmful ? 0.75 : 0.20);
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(harmful ? 400000 : 180000), 2);
            long ageHours = random.nextDouble() < (harmful ? 0.60 : 0.08)
                    ? random.nextInt(72) : 100 + random.nextInt(10000);
            int history = random.nextInt(20);
            BigDecimal average = history == 0 ? null : BigDecimal.valueOf(1000 + random.nextInt(80000), 2);
            boolean mismatch = random.nextDouble() < (harmful ? 0.30 : 0.02);
            boolean sanctioned = harmful && random.nextDouble() < 0.05;
            int hourly = pattern == Pattern.MULE ? 4 + random.nextInt(20) : random.nextInt(6);
            int daily = hourly + random.nextInt(pattern == Pattern.MULE ? 50 : 12);
            VelocitySnapshot velocity = new VelocitySnapshot(true, hourly, amount.multiply(BigDecimal.valueOf(hourly)),
                    daily, amount.multiply(BigDecimal.valueOf(daily)));
            RiskContext context = new RiskContext(new Money(amount, Currency.USD), amount, first,
                    Duration.ofHours(ageHours), history, average, false,
                    NameMatcher.check(mismatch ? "Unrelated Name" : "Example Customer", "Example Customer"),
                    sanctioned, velocity);
            samples.add(new Sample(i, pattern, context));
        }
        return List.copyOf(samples);
    }
}
