# Bounded APP reimbursement examples

`domain/ReimbursementCalculator` is a pure Java arithmetic example in GBP. It does not assess a claim, move money, call an external service, or implement regulatory compliance. It is separate from the transfer API and risk rules. A risk HOLD is not proof of APP fraud or entitlement to reimbursement.

## Sources and dates

Read on **2026-11-10**:

1. [PSR consumer protections](https://www.psr.org.uk/information-for-consumers/app-fraud-reimbursement-protections/). The protections apply to qualifying payments from **2024-10-07**. This live consumer page states the GBP 85,000 maximum, optional excess up to GBP 100, vulnerability protections and usual five-business-day reimbursement timeline. It describes an outcome within 35 business days when the clock is stopped.
2. [PSR PS25/5 consolidated policy, May 2025](https://www.psr.org.uk/media/rhelv4op/ps25-5-app-scams-reimbursement-consolidated-policy-statement-may-2025.pdf). Paragraphs **3.1-3.11** cover scope; **3.20-3.26** cover receiving PSP contributions; **3.31-3.39** cover excess and maximum reimbursement; **3.40-3.52** cover exceptions and vulnerability. This is the detailed public policy source used here, not a claim that no later scheme rule can exist.

The caller must establish a qualifying domestic Faster Payments APP claim by a covered individual, microenterprise or charity, at covered PSPs, for payments from the effective date, reported within the applicable 13-month window. The public framework also covers CHAPS, but this example does not implement CHAPS scheme rules. International payments, other rails, civil disputes, unlawful payments, own-account and multi-step questions require assessment outside this calculator. Fraud and gross negligence are externally established findings, not inferences from risk scores or ignored warnings. Gross negligence is a high bar; the exception and excess do not apply to consumers assessed as vulnerable in relation to the scam. Consumer fraud remains an exception.

## Supported arithmetic

Inputs are loss, an externally established `Eligibility`, scam-related vulnerability, and chosen excess. All amounts are GBP exact to a penny, nonnegative; chosen excess is between 0 and 100 inclusive. Missing inputs and invalid amounts are rejected. There is no implicit currency conversion or rounding of inputs.

- `OUT_OF_SCOPE` and `FRAUDULENT` return zero, no excess and no split.
- `GROSS_NEGLIGENCE` returns zero unless the consumer is vulnerable in relation to the scam. That exception cannot defeat the vulnerable consumer's otherwise eligible claim.
- Vulnerability makes the effective excess zero.
- For losses up to GBP 85,000: applied excess is `min(loss, effective excess)` and reimbursement is loss minus applied excess.
- For losses above GBP 85,000 with zero effective excess: reimbursement is GBP 85,000. The cap also applies to vulnerable consumers (PS25/5 3.36).
- **Loss above the cap with nonzero effective excess is explicitly rejected.** The example does not infer the interaction/order of cap and excess from the consumer summary. This is a modelling limitation, not a statement that such a claim is ineligible.

## Split, and why it is not always half of the consumer payment

The sending PSP reimburses the consumer first and then obtains the receiving PSP contribution (PS25/5 3.21-3.22). These are net costs, not two payments to the consumer.

A `Split` is returned **only** for a non-vulnerable otherwise eligible claim with chosen excess exactly GBP 100, a loss no greater than the cap, **one receiving PSP**, no recovered funds and no voluntary top-up. The receiving PSP's modelled contribution is half the reimbursement. For an odd penny, it rounds down and the sending PSP takes the remaining penny. This deterministic penny convention preserves the total; it is a local example convention, **not a PSR-prescribed rounding rule**.

Example: loss GBP 1,000, excess GBP 100 means consumer payment GBP 900, sending PSP net cost GBP 450 and receiving PSP contribution GBP 450. Loss GBP 100.03 gives consumer payment GBP 0.03, sending GBP 0.02 and receiving GBP 0.01.

A reduced or waived excess must not simply be split 50:50. PS25/5 **3.34** says liability splits assume the maximum GBP 100 excess; the sender cannot recover the amount it chose not to levy. Paragraphs **3.24-3.26** also describe contribution deductions and multiple receiving PSPs. To avoid pretending to implement full settlement rules, a reduced/waived-excess claim or a vulnerable claim returns `Optional.empty()` for its split. **Absent is not zero liability.** The consumer reimbursement is still calculated for the supported scenarios. Multiple PSPs, vulnerable settlement, recovered funds, multi-payment aggregation and voluntary payments above the mandatory cap are not modelled.

## Walkthrough and verification

```java
var calculator = new ReimbursementCalculator();
var result = calculator.calculate(new BigDecimal("1000.00"),
        ReimbursementCalculator.Eligibility.ELIGIBLE, false, new BigDecimal("100.00"));
// reimbursement = 900.00; appliedExcess = 100.00
// split = sendingPsp 450.00, receivingPsp 450.00
```

`ReimbursementCalculatorSpec` uses data tables for excess, the cap, penny boundaries, vulnerability, externally established exceptions, unsupported combinations and invalid inputs. It checks both PSP shares and their exact sum. It is included in the domain PIT target and its 90% mutation threshold.

No business-calendar, deadline or stop-clock engine is implemented. Five business days is the usual timeline, not an unconditional guaranteed deadline; 35 business days is the outer outcome timeline described by the source, not a blanket permission to delay all claims. Dates, evidence gathering, case management, scheme contribution disputes, bank holidays and Ombudsman outcomes are outside this example.
