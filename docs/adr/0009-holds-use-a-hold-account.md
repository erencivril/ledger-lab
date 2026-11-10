# 0009. A held transfer moves the money into a hold account

Status: accepted.

## Context

When the risk engine says HOLD, a person has to decide. The transfer cannot just stay in a queue with the money untouched: the sender could spend it in the meantime and the release would then fail. It also cannot pay the payee at once and try to claw it back.

## Decision

Use the ledger hold pattern. There is one `HOLD` account per currency. Placing a hold debits the sender and credits the hold account in one balanced journal; the transfer row is `PENDING`. Release debits the hold account and credits the payee (through the treasury if the currencies differ). Reject debits the hold account and credits the sender. Each step is its own database transaction with its own balanced journal.

- The hold account has no stored balance, like the treasury. Its balance is the sum of its entries, and the audit checks that it equals the sum of the `PENDING` transfers.
- A cross-currency hold works out the conversion and freezes it on the transfer row when the hold is placed. Release pays exactly that amount. A release cannot fail because the rate table changed or a rate is missing.
- Release and reject lock the transfer row (`SELECT ... FOR UPDATE`) first, then the one customer account they change. Two people deciding at once take turns and the second sees a transfer that is no longer `PENDING`, which is a `409 transfer_not_pending`. This serialises decisions on one hold; placement separately follows the two-account ordering protocol.
- Placement locks BOTH source and destination with `FOR UPDATE` in the shared ascending UUID order, whichever ordinary strategy is configured. Transfer insertion implicitly takes foreign-key key-share locks on both accounts: locking only the sender can deadlock even though only its balance changes. `HoldLockRegressionSpec` forces opposite HOLD and mixed ordinary/HOLD schedules without sleeps and checks for zero `40P01`, not merely eventual retry success.
- A payee only counts as "known" after a `COMPLETED` payment. A held or rejected one proves nothing.
- The HTTP answer for a held transfer is `202`. The money has left the sender, the payee has not been paid.

## Consequences

- The ledger audit passes at every moment, including while transfers wait. A crash between the hold and the decision loses nothing: the money is in the hold account and the `PENDING` row says whose it is.
- Nothing releases or rejects a hold by itself. There is no timeout, no expiry worker and no authorisation on the endpoints (the project has no authentication at all). A real service would need all three.
- The sender cannot spend money that is on hold, which is the intent.

## Evidence

`TransferPlannerHoldSpec`, `HoldServiceSpec`, `TransferServiceRiskSpec` (unit), and `RiskHoldIntegrationSpec`: hold, release, reject, double decision, three concurrent decisions on one hold (exactly one wins), cross-currency, a crash between hold and release, and `assertLedgerConsistent` including the hold-versus-pending check.
