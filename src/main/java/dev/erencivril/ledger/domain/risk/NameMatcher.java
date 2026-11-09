package dev.erencivril.ledger.domain.risk;

import java.text.Normalizer;
import java.util.Locale;

/** A local name comparison, not a connection to any verification service. */
public final class NameMatcher {
    public enum Grade { MATCH, CLOSE, NO_MATCH, NOT_PROVIDED }
    public record Result(Grade grade, double similarity) {}
    private NameMatcher() {}

    public static String normalise(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT).replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }

    public static Result check(String supplied, String owner) {
        if (supplied == null) {
            return new Result(Grade.NOT_PROVIDED, 0);
        }
        String a = normalise(supplied);
        String b = normalise(owner);
        if (a.isEmpty() || b.isEmpty()) {
            return new Result(Grade.NO_MATCH, 0);
        }
        if (a.equals(b)) {
            return new Result(Grade.MATCH, 1);
        }
        int[] left = a.codePoints().toArray();
        int[] right = b.codePoints().toArray();
        int[] previous = new int[right.length + 1];
        for (int j = 0; j <= right.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length; i++) {
            int[] current = new int[right.length + 1];
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                int cost = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        double score = 1d - (double) previous[right.length] / Math.max(left.length, right.length);
        return new Result(score >= 0.8d ? Grade.CLOSE : Grade.NO_MATCH, score);
    }
}
