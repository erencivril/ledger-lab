package dev.erencivril.ledger.http;

import dev.erencivril.ledger.application.ApplicationException;
import dev.erencivril.ledger.domain.DomainException;

/** The one JSON error shape: {"code": "...", "message": "..."} plus an HTTP status. */
public record ApiError(int status, String code, String message, Long retryAfterSeconds) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, code, message, null);
    }

    /**
     * Status policy: 400 when the request itself is malformed or has an invalid value, 404 for unknown
     * resources, 409 for idempotency key reuse, 422 when the request is well-formed but a business rule
     * refuses it, 429 for the velocity limit.
     */
    public static ApiError from(Throwable error) {
        return switch (error) {
            case BadRequest e -> of(400, e.code(), e.getMessage());
            case DomainException e -> fromDomain(e);
            case ApplicationException e -> fromApplication(e);
            default -> of(500, "internal_error", "Something went wrong");
        };
    }

    private static ApiError fromDomain(DomainException e) {
        return switch (e) {
            case DomainException.InvalidAmount x -> of(400, x.code(), x.getMessage());
            case DomainException.UnsupportedCurrency x -> of(400, x.code(), x.getMessage());
            case DomainException.InvalidOwner x -> of(400, x.code(), x.getMessage());
            case DomainException.SameAccount x -> of(422, x.code(), x.getMessage());
            case DomainException.InsufficientFunds x -> of(422, x.code(), x.getMessage());
            case DomainException.CurrencyMismatch x -> of(422, x.code(), x.getMessage());
            case DomainException.NoFxRate x -> of(422, x.code(), x.getMessage());
            case DomainException.UnbalancedJournal x -> of(500, "internal_error", "Something went wrong");
        };
    }

    private static ApiError fromApplication(ApplicationException e) {
        return switch (e) {
            case ApplicationException.AccountNotFound x -> of(404, x.code(), x.getMessage());
            case ApplicationException.TransferNotFound x -> of(404, x.code(), x.getMessage());
            case ApplicationException.IdempotencyKeyReused x -> of(409, x.code(), x.getMessage());
            case ApplicationException.VelocityLimitExceeded x ->
                new ApiError(429, x.code(), x.getMessage(), x.retryAfterSeconds());
        };
    }
}
