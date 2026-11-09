package dev.erencivril.ledger.http;

/** The request could not be understood: bad JSON, a missing field, a malformed id. */
final class BadRequest extends RuntimeException {

    private final String code;

    BadRequest(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
