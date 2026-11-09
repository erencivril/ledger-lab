package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.IDEMPOTENCY_KEYS;

import dev.erencivril.ledger.application.Ports.IdempotencyRecord;
import dev.erencivril.ledger.application.Ports.IdempotencyRepository;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

final class JooqIdempotencyRepository implements IdempotencyRepository {

    private final DSLContext dsl;

    JooqIdempotencyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * INSERT ... ON CONFLICT DO NOTHING on the primary key. If a concurrent transaction is inserting the
     * same key, Postgres makes this statement wait until that transaction commits or rolls back, so the
     * answer is never "maybe".
     */
    @Override
    public boolean tryClaim(String key, String fingerprint) {
        return dsl.insertInto(IDEMPOTENCY_KEYS)
                        .set(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
                        .set(IDEMPOTENCY_KEYS.REQUEST_FINGERPRINT, fingerprint)
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return dsl.selectFrom(IDEMPOTENCY_KEYS)
                .where(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
                .fetchOptional(row -> new IdempotencyRecord(
                        row.getIdempotencyKey(), row.getRequestFingerprint(), row.getTransferId()));
    }

    @Override
    public void attach(String key, UUID transferId) {
        dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.TRANSFER_ID, transferId)
                .where(IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
                .execute();
    }
}
