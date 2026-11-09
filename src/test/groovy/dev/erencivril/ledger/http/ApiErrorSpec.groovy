package dev.erencivril.ledger.http

import dev.erencivril.ledger.application.ApplicationException
import spock.lang.Specification

class ApiErrorSpec extends Specification {

    def "a lost race is a 409 that says it is safe to try again in a second"() {
        when:
        def error = ApiError.from(new ApplicationException.ConcurrentUpdate('x'))

        then:
        error.status() == 409
        error.code() == 'concurrent_update'
        error.retryAfterSeconds() == 1L
    }

    def "a database outage is a 503 with a retry hint and no internal detail in the message"() {
        when:
        def error = ApiError.from(new ApplicationException.StorageUnavailable(new java.sql.SQLException('connection to 10.0.0.5 refused')))

        then:
        error.status() == 503
        error.code() == 'storage_unavailable'
        error.retryAfterSeconds() == 1L
        !error.message().contains('10.0.0.5')
    }

    def "an idempotency conflict stays a 409 without a retry hint"() {
        when:
        def error = ApiError.from(new ApplicationException.IdempotencyKeyReused('k'))

        then:
        error.status() == 409
        error.retryAfterSeconds() == null
    }

    def "a blocked payment is a 403 that carries the reason codes"() {
        when:
        def error = ApiError.from(new ApplicationException.TransferBlocked(['SANCTIONED_PAYEE']))

        then:
        error.status() == 403
        error.code() == 'blocked_by_risk'
        error.reasonCodes() == ['SANCTIONED_PAYEE']
        error.retryAfterSeconds() == null
    }

    def "a payment that needs step-up is a 403 with its own code and the reason codes"() {
        when:
        def error = ApiError.from(new ApplicationException.StepUpRequired(['AMOUNT_DEVIATION']))

        then:
        error.status() == 403
        error.code() == 'step_up_required'
        error.reasonCodes() == ['AMOUNT_DEVIATION']
    }

    def "deciding a transfer twice is a 409"() {
        when:
        def error = ApiError.from(new dev.erencivril.ledger.domain.DomainException.TransferNotPending(
                UUID.randomUUID(), dev.erencivril.ledger.domain.TransferStatus.COMPLETED))

        then:
        error.status() == 409
        error.code() == 'transfer_not_pending'
        error.reasonCodes() == null
    }
}
