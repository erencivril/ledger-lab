package dev.erencivril.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
    private final HoldAccounts holds;

    /** A planner that cannot hold transfers: asking it to throws. */
    public TransferPlanner(FxRateTable rates, TreasuryAccounts treasury) {
        this(rates, treasury, new HoldAccounts(Map.of()));
    }

    public TransferPlanner(FxRateTable rates, TreasuryAccounts treasury, HoldAccounts holds) {
        this.rates = rates;
        this.treasury = treasury;
        this.holds = holds;
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

        FxRate rate = rateFor(source.currency(), destination.currency());
        Money converted = convert(rate, amount, destination.currency());
        Journal journal = new Journal(crossCurrencyLines(source.id(), destination.id(), amount, converted));
        return new TransferPlan(
                amount, converted, rate.rate(), journal, debited.balance(), destination.credit(converted).balance());
    }

    private FxRate rateFor(Currency from, Currency to) {
        return rates.find(from, to).orElseThrow(() -> new DomainException.NoFxRate(from, to));
    }

    private static Money convert(FxRate rate, Money amount, Currency to) {
        Money converted = rate.convert(amount);
        if (converted.isZero()) {
            throw new DomainException.InvalidAmount(
                    amount + " converts to zero " + to + " and cannot be transferred");
        }
        return converted;
    }

    /**
     * Each currency balances on its own: the customer side is mirrored by the treasury account. {@code from}
     * is the account that gives {@code amount}; for a direct transfer that is the sender, for a release it is
     * the hold account.
     */
    private List<JournalLine> crossCurrencyLines(UUID from, UUID to, Money amount, Money converted) {
        return List.of(
                new JournalLine(from, amount.negate()),
                new JournalLine(treasury.idFor(amount.currency()), amount),
                new JournalLine(treasury.idFor(converted.currency()), converted.negate()),
                new JournalLine(to, converted));
    }

    /**
     * Takes the money out of the sender and parks it in the hold account of the same currency. The sender
     * must be able to pay now, and the conversion that the release will need is worked out and frozen now,
     * so a release cannot fail later because of the rate table.
     */
    public HoldPlan planHold(Account source, Account destination, Money amount) {
        validate(source, destination, amount);
        Account debited = source.debit(amount);
        Money destinationAmount = amount;
        BigDecimal fxRate = null;
        if (source.currency() != destination.currency()) {
            FxRate rate = rateFor(source.currency(), destination.currency());
            destinationAmount = convert(rate, amount, destination.currency());
            fxRate = rate.rate();
        }
        Journal journal = new Journal(List.of(
                new JournalLine(source.id(), amount.negate()),
                new JournalLine(holds.idFor(amount.currency()), amount)));
        return new HoldPlan(amount, destinationAmount, fxRate, journal, debited.balance());
    }

    /** Pays the destination from the hold account, using the amounts frozen when the transfer was held. */
    public ReleasePlan planRelease(Transfer transfer, Account destination) {
        Money held = transfer.sourceAmount();
        Money payout = transfer.destinationAmount();
        UUID holdAccount = holds.idFor(held.currency());
        List<JournalLine> lines = held.currency() == payout.currency()
                ? List.of(new JournalLine(holdAccount, held.negate()), new JournalLine(destination.id(), payout))
                : crossCurrencyLines(holdAccount, destination.id(), held, payout);
        return new ReleasePlan(new Journal(lines), destination.credit(payout).balance());
    }

    /** Gives the held money back to the sender. */
    public ReleasePlan planReject(Transfer transfer, Account source) {
        Money held = transfer.sourceAmount();
        Journal journal = new Journal(List.of(
                new JournalLine(holds.idFor(held.currency()), held.negate()), new JournalLine(source.id(), held)));
        return new ReleasePlan(journal, source.credit(held).balance());
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
