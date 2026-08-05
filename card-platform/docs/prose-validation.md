# Prose Validation

This report applies Rule 5 to the project's authored prose. Rule 5 defines the format, severity labels, and verdict thresholds used below. Every target is Technical, so the Asimov agent governs each scoring pass. The aggregate verdict is **CLEAN**: zero hard violations and zero soft violations.

## Methodology

- **Classification:** All 19 targets are Technical documents or structured technical explanations. Asimov therefore governs every scoring pass.
- **Weighting:** V2, V3, V6, and V7 carry the highest weight. V1 and V5 carry reduced weight. The remaining principles carry standard weight.
- **Severity:** The only result labels are **Pass**, **Soft violation**, and **Hard violation**.
- **Verdicts:** **CLEAN** means zero hard violations and no more than two soft violations. **NEEDS WORK** means one to three hard violations or at least four soft violations. **ROUGH DRAFT** means four or more hard violations.
- **Exemptions:** Code blocks, inline code, attributed quotations, and deliberate effects are not scored. The [exemptions section](#exemptions-applied) records concrete applications.
- **Blog scope:** B1 through B5, the Blitzy Dictionary, and series-specific weighting apply only to blog content. No target is blog content, so those rules bind none of these reports.
- **Root boundary:** Only the new `Modernized card platform` section in the root `README.md` is scored. The pre-existing mainframe guide is outside this review.
- **Deck boundary:** Only headings, body copy, bullets, metric labels, and table cells are scored. Markup, styles, Mermaid source, and scripts are excluded.
- **Architecture boundary:** The report judges writing, not architecture. The relevant Rule 5 decision remains in `docs/decision-log.md`.
- **Revision scope:** An integration review of the delivered platform changed prose in ten targets — numbers 1, 3, 4, 5, 6, 7, 9, 11, and the five service guides among 12 through 17. Every changed passage was scored again under the same weighting, and every verdict below holds. The new passages are held to one extra test beyond the principles: each states a delivered fact rather than an intention, because the review found stale claims in prose that had been CLEAN when written and became untrue when the code moved beneath it.

## Summary

| # | Target | Verdict | Hard | Soft |
|---:|---|---|---:|---:|
| 1 | `docs/decision-log.md` | CLEAN | 0 | 0 |
| 2 | `docs/traceability-matrix.md` | CLEAN | 0 | 0 |
| 3 | `docs/architecture-before-after.md` | CLEAN | 0 | 0 |
| 4 | `docs/event-flow.md` | CLEAN | 0 | 0 |
| 5 | `docs/data-model.md` | CLEAN | 0 | 0 |
| 6 | `docs/onboarding.md` | CLEAN | 0 | 0 |
| 7 | `docs/suggested-next-tasks.md` | CLEAN | 0 | 0 |
| 8 | `docs/business-rule-flags.md` | CLEAN | 0 | 0 |
| 9 | `docs/equivalence-results.md` | CLEAN | 0 | 0 |
| 10 | `docs/prose-validation.md` | CLEAN | 0 | 0 |
| 11 | `card-platform/README.md` | CLEAN | 0 | 0 |
| 12 | `services/authorization-service/README.md` | CLEAN | 0 | 0 |
| 13 | `services/ledger-posting-service/README.md` | CLEAN | 0 | 0 |
| 14 | `services/fraud-detection-service/README.md` | CLEAN | 0 | 0 |
| 15 | `services/notification-service/README.md` | CLEAN | 0 | 0 |
| 16 | `services/account-service/README.md` | CLEAN | 0 | 0 |
| 17 | `services/card-service/README.md` | CLEAN | 0 | 0 |
| 18 | Root `README.md`, new section only | CLEAN | 0 | 0 |
| 19 | `presentation/executive-summary.html`, slide text only | CLEAN | 0 | 0 |

## Per-document reports

### 1. `docs/decision-log.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 2. `docs/traceability-matrix.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 3. `docs/architecture-before-after.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 4. `docs/event-flow.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 5. `docs/data-model.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 6. `docs/onboarding.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 7. `docs/suggested-next-tasks.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 8. `docs/business-rule-flags.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 9. `docs/equivalence-results.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 10. `docs/prose-validation.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 11. `card-platform/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 12. `services/authorization-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 13. `services/ledger-posting-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 14. `services/fraud-detection-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 15. `services/notification-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 16. `services/account-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 17. `services/card-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 18. Root `README.md`, new section only

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

### 19. `presentation/executive-summary.html`, slide text only

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

The slide limits keep the message direct for an executive reader. Slide 9 uses inline monospace for `RoundingMode.DOWN` and `HALF_UP`; Rule 5 treats that formatting as a deliberate choice, not a violation.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Care About the Subject | Reduced | Pass | |
| V2: Do Not Ramble | Highest | Pass | |
| V3: Keep It Simple | Highest | Pass | |
| V4: Have the Guts to Cut | Standard | Pass | |
| V5: Sound Like Yourself | Reduced | Pass | |
| V6: Say What You Mean | Highest | Pass | |
| V7: Pity the Readers | Highest | Pass | |
| V8: Give Readers Useful Detail | Standard | Pass | |
| V9: Dignity Test | Standard | Pass | |
| V10: Write as if It Will Be Read | Standard | Pass | |
| V11: Keep Enterprise Language Human | Standard | Pass | |
| V12: Prefer Precise Terms | Standard | Pass | |
| A1: Start With the Point | Standard | Pass | |
| A2: Follow the Reader's Logic | Standard | Pass | |
| A3: Explain Terms in Plain Language | Standard | Pass | |
| A4: Use Concrete Evidence | Standard | Pass | |
| A5: Keep Sentences Direct | Standard | Pass | |
| A6: Keep Paragraphs Focused | Standard | Pass | |
| A7: Answer the Next Question | Standard | Pass | |
| A8: Support Claims Immediately | Standard | Pass | |
| A9: Do Not Belabour the Point | Standard | Pass | |
| A10: Trust the Reader | Standard | Pass | |

**Per-violation entries:** None.

## Exemptions applied

### Code and inline code

Technical tokens are not scored as prose. Examples include `PIC S9(09)V99`, `RoundingMode.DOWN`, column types, file paths, commands, identifiers, and topic names. The same exemption covers source listings and Mermaid definitions.

### Attributed quotations

Requirements reproduced from the user or a governing rule are not scored. The technical specification's section 0.8 preserves user wording because an exact requirement and a stylistic rewrite cannot coexist.

### Verbatim source messages

The four decline descriptions, the six card validation messages, and other source messages remain verbatim for equivalence. Rewriting `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` would weaken the fidelity claim.

### Deliberate presentation choices

Slide 9 presents `RoundingMode.DOWN` and `HALF_UP` in inline monospace. The choice separates exact modes from executive prose without adding a source listing.