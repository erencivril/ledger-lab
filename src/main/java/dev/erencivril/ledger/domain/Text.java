package dev.erencivril.ledger.domain;

/** Validates UTF-16 without normalising, trimming or conflating absent and empty text. */
public final class Text {
    private Text() {}

    public static void requireWellFormed(String value, String field) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new DomainException.InvalidText(field);
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new DomainException.InvalidText(field);
            }
        }
    }
}
