package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.ACCOUNTS;
import static dev.erencivril.ledger.persistence.jooq.Tables.RISK_DECISIONS;
import static dev.erencivril.ledger.persistence.jooq.Tables.RISK_DECISION_HITS;

import dev.erencivril.ledger.application.Ports.NewRiskDecision;
import dev.erencivril.ledger.application.Ports.PayeeFacts;
import dev.erencivril.ledger.application.Ports.RiskRepository;
import dev.erencivril.ledger.application.Ports.SenderHistory;
import dev.erencivril.ledger.domain.AccountKind;
import dev.erencivril.ledger.domain.risk.RiskAction;
import dev.erencivril.ledger.domain.risk.RiskDecision;
import dev.erencivril.ledger.domain.risk.RuleHit;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record3;

final class JooqRiskRepository implements RiskRepository {

    private final DSLContext dsl;

    JooqRiskRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<PayeeFacts> payeeFacts(UUID senderId, UUID payeeId) {
        return dsl.select(ACCOUNTS.OWNER, ACCOUNTS.CREATED_AT)
                .from(ACCOUNTS)
                .where(ACCOUNTS.ID.eq(payeeId))
                .and(ACCOUNTS.KIND.eq(AccountKind.CUSTOMER.name()))
                .fetchOptional()
                .map(row -> new PayeeFacts(row.value1(), row.value2().toInstant(), paidBefore(senderId, payeeId)));
    }

    /** Only a COMPLETED transfer makes a payee known: a held or rejected one has not proved anything yet. */
    private boolean paidBefore(UUID senderId, UUID payeeId) {
        return dsl.fetchOne("""
                SELECT EXISTS(SELECT 1 FROM risk_features_payee WHERE sender_id = ? AND payee_id = ?)
                    OR EXISTS(SELECT 1 FROM transfers WHERE source_account_id = ? AND destination_account_id = ?
                        AND kind = 'TRANSFER' AND status = 'COMPLETED') AS known
                """, senderId, payeeId, senderId, payeeId).get("known", Boolean.class);
    }

    @Override
    public SenderHistory senderHistory(UUID senderId, Instant since) {
        // Union the read model with not-yet-projected payments. One SQL snapshot, no double counting,
        // and a stopped poller does not quietly erase the sender's payment history.
        var row = dsl.fetchOne("""
                SELECT count(*) AS n, avg(amount) AS average FROM (
                    SELECT amount FROM risk_features_payments WHERE sender_id = ? AND completed_at >= CAST(? AS timestamptz)
                    UNION ALL
                    SELECT t.source_amount FROM transfers t WHERE t.source_account_id = ?
                        AND t.kind = 'TRANSFER' AND t.status = 'COMPLETED'
                        AND coalesce(t.resolved_at, t.created_at) >= CAST(? AS timestamptz)
                        AND NOT EXISTS(SELECT 1 FROM risk_features_payments p WHERE p.transfer_id = t.id)
                ) history
                """, senderId, since.atOffset(ZoneOffset.UTC), senderId, since.atOffset(ZoneOffset.UTC));
        return new SenderHistory(row.get("n", Integer.class), row.get("average", java.math.BigDecimal.class));
    }

    @Override
    public void insertDecision(NewRiskDecision decision) {
        dsl.insertInto(RISK_DECISIONS)
                .set(RISK_DECISIONS.ID, decision.id())
                .set(RISK_DECISIONS.TRANSFER_ID, decision.transferId())
                .set(RISK_DECISIONS.SOURCE_ACCOUNT_ID, decision.sourceAccountId())
                .set(RISK_DECISIONS.DESTINATION_ACCOUNT_ID, decision.destinationAccountId())
                .set(RISK_DECISIONS.AMOUNT, decision.amount().amount())
                .set(RISK_DECISIONS.CURRENCY, decision.amount().currency().name())
                .set(RISK_DECISIONS.AMOUNT_USD, decision.amountUsd())
                .set(RISK_DECISIONS.ACTION, decision.decision().action().name())
                .set(RISK_DECISIONS.RULE_SET_VERSION, decision.decision().ruleSetVersion())
                .set(RISK_DECISIONS.STEP_UP_CONFIRMED, decision.stepUpConfirmed())
                .set(RISK_DECISIONS.FIRST_PAYMENT, decision.firstPayment())
                .set(RISK_DECISIONS.PAYEE_AGE_SECONDS, decision.payeeAgeSeconds())
                .set(RISK_DECISIONS.CREATED_AT, decision.createdAt().atOffset(ZoneOffset.UTC))
                .execute();
        List<RuleHit> hits = decision.decision().hits();
        for (int i = 0; i < hits.size(); i++) {
            dsl.insertInto(RISK_DECISION_HITS)
                    .set(RISK_DECISION_HITS.DECISION_ID, decision.id())
                    .set(RISK_DECISION_HITS.POSITION, i + 1)
                    .set(RISK_DECISION_HITS.RULE_ID, hits.get(i).ruleId())
                    .set(RISK_DECISION_HITS.ACTION, hits.get(i).action().name())
                    .set(RISK_DECISION_HITS.DETAIL, hits.get(i).detail())
                    .execute();
        }
    }

    @Override
    public Optional<RiskDecision> findDecision(UUID transferId) {
        Optional<Record3<UUID, String, Integer>> row = dsl.select(
                        RISK_DECISIONS.ID, RISK_DECISIONS.ACTION, RISK_DECISIONS.RULE_SET_VERSION)
                .from(RISK_DECISIONS)
                .where(RISK_DECISIONS.TRANSFER_ID.eq(transferId))
                .fetchOptional();
        return row.map(r -> {
            List<RuleHit> hits = new ArrayList<>(dsl.select(
                            RISK_DECISION_HITS.RULE_ID, RISK_DECISION_HITS.ACTION, RISK_DECISION_HITS.DETAIL)
                    .from(RISK_DECISION_HITS)
                    .where(RISK_DECISION_HITS.DECISION_ID.eq(r.value1()))
                    .orderBy(RISK_DECISION_HITS.POSITION)
                    .fetch(h -> new RuleHit(h.value1(), RiskAction.valueOf(h.value2()), h.value3())));
            return new RiskDecision(RiskAction.valueOf(r.value2()), hits, r.value3());
        });
    }
}
