package dev.erencivril.ledger.domain.risk;

import java.util.Set;

/** Entirely invented entries for exercising the refusal path. Not a real sanctions screening service. */
public final class MockSanctions {
    private static final Set<String> NAMES = Set.of("example restricted person", "synthetic blocked entity");
    private MockSanctions() {}
    public static boolean contains(String name) {
        return NAMES.contains(NameMatcher.normalise(name));
    }
}
