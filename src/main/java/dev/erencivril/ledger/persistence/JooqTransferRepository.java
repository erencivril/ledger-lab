package dev.erencivril.ledger.persistence;

import static dev.erencivril.ledger.persistence.jooq.Tables.TRANSFERS;

import dev.erencivril.ledger.application.Ports.TransferRepository;
import dev.erencivril.ledger.domain.Currency;
import dev.erencivril.ledger.domain.Money;
import dev.erencivril.ledger.domain.Transfer;
import dev.erencivril.ledger.domain.TransferKind;
import dev.erencivril.ledger.persistence.jooq.tables.records.TransfersRecord;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

final class JooqTransferRepository implements TransferRepository {

    private final DSLContext dsl;

    JooqTransferRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Transfer transfer) {
        dsl.insertInto(TRANSFERS)
                .set(TRANSFERS.ID, transfer.id())
                .set(TRANSFERS.KIND, transfer.kind().name())
                .set(TRANSFERS.SOURCE_ACCOUNT_ID, transfer.sourceAccountId())
                .set(TRANSFERS.DESTINATION_ACCOUNT_ID, transfer.destinationAccountId())
                .set(TRANSFERS.SOURCE_AMOUNT, transfer.sourceAmount().amount())
                .set(TRANSFERS.SOURCE_CURRENCY, transfer.sourceAmount().currency().name())
                .set(TRANSFERS.DESTINATION_AMOUNT, transfer.destinationAmount().amount())
                .set(TRANSFERS.DESTINATION_CURRENCY, transfer.destinationAmount().currency().name())
                .set(TRANSFERS.FX_RATE, transfer.fxRate())
                .set(TRANSFERS.CREATED_AT, transfer.createdAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Override
    public Optional<Transfer> find(UUID id) {
        return dsl.selectFrom(TRANSFERS).where(TRANSFERS.ID.eq(id)).fetchOptional(JooqTransferRepository::toTransfer);
    }

    private static Transfer toTransfer(TransfersRecord row) {
        return new Transfer(
                row.getId(),
                TransferKind.valueOf(row.getKind()),
                row.getSourceAccountId(),
                row.getDestinationAccountId(),
                new Money(row.getSourceAmount(), Currency.parse(row.getSourceCurrency())),
                new Money(row.getDestinationAmount(), Currency.parse(row.getDestinationCurrency())),
                row.getFxRate(),
                row.getCreatedAt().toInstant());
    }
}
