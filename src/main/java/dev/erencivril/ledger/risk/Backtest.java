package dev.erencivril.ledger.risk;

import dev.erencivril.ledger.domain.risk.RiskAction;
import dev.erencivril.ledger.domain.risk.RiskDecision;
import dev.erencivril.ledger.domain.risk.RiskEngine;
import java.util.Comparator;
import java.util.List;

/** Evaluates a fixed set without fitting anything. An intervention is STEP_UP, HOLD or BLOCK, not WARN. */
public final class Backtest {
    public record Metrics(int ruleSetVersion, int tp, int fp, int tn, int fn, int k, double precisionAtK) {
        public double precision() { return ratio(tp, tp + fp); }
        public double recall() { return ratio(tp, tp + fn); }
    }
    private record Scored(SyntheticData.Sample sample, RiskDecision decision) {}
    private Backtest() {}

    public static Metrics evaluate(RiskEngine engine, List<SyntheticData.Sample> samples, int k) {
        if (k <= 0) throw new IllegalArgumentException("k must be positive");
        List<Scored> scored = samples.stream().map(s -> new Scored(s, engine.assess(s.context()))).toList();
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (Scored row : scored) {
            boolean positive = row.decision().action().atLeast(RiskAction.STEP_UP);
            if (row.sample().harmful()) {
                if (positive) tp++; else fn++;
            } else {
                if (positive) fp++; else tn++;
            }
        }
        // There is no learned score: this is a deterministic review queue ordering, with stable ties.
        Comparator<Scored> ranking = Comparator.<Scored>comparingInt(s -> s.decision().action().ordinal()).reversed()
                .thenComparing(Comparator.<Scored>comparingInt(s -> s.decision().hits().size()).reversed())
                .thenComparingInt(s -> s.sample().id());
        int effectiveK = Math.min(k, scored.size());
        long harmfulAtK = scored.stream().sorted(ranking).limit(effectiveK).filter(s -> s.sample().harmful()).count();
        return new Metrics(engine.ruleSetVersion(), tp, fp, tn, fn, effectiveK, ratio(harmfulAtK, effectiveK));
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : (double) numerator / denominator;
    }
}
