package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Turns "move this much from A to B" into a balanced journal, or rejects it. Pure logic: no I/O,
 * so it can be tested exhaustively and mutation-tested.
 */
public final class TransferPlanner {

    /** An upper bound well below the numeric(30,3) column, so overflow is never the first failure. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");

    private final FxRateTable rates;
    private final TreasuryAccounts treasury;

    public TransferPlanner(FxRateTable rates, TreasuryAccounts treasury) {
        this.rates = rates;
        this.treasury = treasury;
    }

    /**
     * @param amount denominated in the source account's currency
     */
    public TransferPlan plan(Account source, Account destination, Money amount) {
        validate(source, destination, amount);
        return build(source, destination, amount, source.debit(amount));
    }

    /**
     * The same as {@link #plan} except that it does not check that the source can pay. For the atomic
     * strategy, where the UPDATE statement itself refuses an overdraft. The balances in the returned plan
     * are then meaningless and must be ignored; the journal and the amounts are valid.
     */
    public TransferPlan planWithoutFundsCheck(Account source, Account destination, Money amount) {
        validate(source, destination, amount);
        return build(source, destination, amount, source.debitWithoutFundsCheck(amount));
    }

    private static void validate(Account source, Account destination, Money amount) {
        if (source.id().equals(destination.id())) {
            throw new DomainException.SameAccount(source.id());
        }
        requirePositiveAndBounded(amount);
        if (amount.currency() != source.currency()) {
            throw new DomainException.CurrencyMismatch(
                    "Amount is in " + amount.currency() + " but the source account holds " + source.currency());
        }
        if (source.kind() != AccountKind.CUSTOMER || destination.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException("Transfers are only allowed between customer accounts");
        }
    }

    private TransferPlan build(Account source, Account destination, Money amount, Account debited) {
        if (source.currency() == destination.currency()) {
            Journal journal = new Journal(List.of(
                    new JournalLine(source.id(), amount.negate()), new JournalLine(destination.id(), amount)));
            return new TransferPlan(amount, amount, null, journal, debited.balance(), destination.credit(amount).balance());
        }

        FxRate rate = rates.find(source.currency(), destination.currency())
                .orElseThrow(() -> new DomainException.NoFxRate(source.currency(), destination.currency()));
        Money converted = rate.convert(amount);
        if (converted.isZero()) {
            throw new DomainException.InvalidAmount(
                    amount + " converts to zero " + destination.currency() + " and cannot be transferred");
        }
        // Each currency balances on its own: the customer side is mirrored by the treasury account.
        Journal journal = new Journal(List.of(
                new JournalLine(source.id(), amount.negate()),
                new JournalLine(treasury.idFor(amount.currency()), amount),
                new JournalLine(treasury.idFor(converted.currency()), converted.negate()),
                new JournalLine(destination.id(), converted)));
        return new TransferPlan(
                amount, converted, rate.rate(), journal, debited.balance(), destination.credit(converted).balance());
    }

    /** The journal for funding a new account: the treasury pays, the account receives. */
    public Journal openingDeposit(UUID accountId, Money amount) {
        requirePositiveAndBounded(amount);
        return new Journal(List.of(
                new JournalLine(treasury.idFor(amount.currency()), amount.negate()),
                new JournalLine(accountId, amount)));
    }

    private static void requirePositiveAndBounded(Money amount) {
        if (!amount.isPositive()) {
            throw new DomainException.InvalidAmount("amount must be greater than zero");
        }
        if (amount.amount().compareTo(MAX_AMOUNT) > 0) {
            throw new DomainException.InvalidAmount("amount must not exceed " + MAX_AMOUNT.toPlainString());
        }
    }
}
