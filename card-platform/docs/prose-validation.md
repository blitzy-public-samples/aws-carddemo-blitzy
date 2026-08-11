# Prose Validation

This report is the Rule 5 validation of every document and slide deck this engagement authored. It records what was measured, what the measurement found, and what a writer should change next. Every verdict below is the output of a measurement the build repeats.

An earlier revision published CLEAN for all nineteen targets while its own table reported hundreds of over-length sentences. It also named fourteen of the twenty-two principles incorrectly. Both faults are corrected here. The names are the rule's own, and `PresentationAndProseContractTest` re-measures every target on every build.

## Methodology

- Every target is Technical, so the Asimov agent governs. Both principle sets apply to every target: the twelve Vonnegut principles V1 through V12, and the ten Asimov principles A1 through A10.
- V2, V3, V6, and V7 carry the highest weight, because the rule raises them for technical input.
- V1 and V5 carry reduced weight, because the rule lowers them for the same input type.
- Three results record a judgement: **Pass**, **Soft violation**, and **Hard violation**. A fourth, **Not measured**, records a principle no mechanical check covers. It is not a pass.
- Five principles are measured. A sentence over thirty words is a soft violation of V3 and A2. A paragraph over five sentences is a soft violation of V2. A buzzword is a hard violation of V5 and A6.
- The other seventeen principles read **Not measured**. No mechanical check for them is claimed, so no pass is claimed either.
- Counts count offending passages rather than principles. One over-length sentence is one soft violation, even though it offends two principles at once.
- The thresholds come from the rule. CLEAN is zero hard and at most three soft. NEEDS WORK is one to three hard, or four or more soft. ROUGH DRAFT is four or more hard.
- B1 through B5 are the blog rules. They are not applied, because no target is a blog post.
- Scoring reads prose only. Fenced code blocks, table rows, headings, horizontal rules and blockquoted passages are dropped, and an inline code span collapses to the single token `CODE`.
- Only the new `Modernized card platform` section of the repository-root guide is scored. Only headings, body copy, bullets, metric labels, and table cells of the deck are scored.
- The oracle is `PresentationAndProseContractTest` in the equivalence module. It re-measures all nineteen targets and fails when a verdict, a count, a principle result or a published rewrite disagrees with what it measures.

## Measurement

One row per target. `Paragraphs` counts the prose blocks scoring reads and `Sentences` counts the sentences in them. The last three columns are the violations those numbers give.

| # | Target | Paragraphs | Sentences | Over thirty words | Paragraphs over five | Buzzwords |
|---:|---|---:|---:|---:|---:|---:|
| 1 | `docs/decision-log.md` | 40 | 120 | 11 | 1 | 0 |
| 2 | `docs/traceability-matrix.md` | 37 | 104 | 2 | 2 | 0 |
| 3 | `docs/architecture-before-after.md` | 49 | 135 | 0 | 2 | 0 |
| 4 | `docs/event-flow.md` | 133 | 355 | 15 | 4 | 0 |
| 5 | `docs/data-model.md` | 140 | 366 | 17 | 7 | 0 |
| 6 | `docs/onboarding.md` | 186 | 491 | 8 | 2 | 0 |
| 7 | `docs/suggested-next-tasks.md` | 341 | 797 | 46 | 3 | 0 |
| 8 | `docs/business-rule-flags.md` | 47 | 155 | 8 | 2 | 0 |
| 9 | `docs/equivalence-results.md` | 85 | 238 | 12 | 2 | 0 |
| 10 | `docs/prose-validation.md` | 183 | 246 | 0 | 0 | 0 |
| 11 | `README.md` | 123 | 235 | 10 | 6 | 0 |
| 12 | `services/authorization-service/README.md` | 177 | 404 | 28 | 5 | 0 |
| 13 | `services/ledger-posting-service/README.md` | 139 | 305 | 6 | 6 | 0 |
| 14 | `services/fraud-detection-service/README.md` | 111 | 272 | 15 | 3 | 0 |
| 15 | `services/notification-service/README.md` | 155 | 355 | 24 | 7 | 0 |
| 16 | `services/account-service/README.md` | 151 | 362 | 20 | 6 | 0 |
| 17 | `services/card-service/README.md` | 149 | 407 | 16 | 10 | 0 |
| 18 | `../README.md` | 24 | 45 | 1 | 1 | 0 |
| 19 | `presentation/executive-summary.html` | 109 | 115 | 0 | 0 | 0 |

## Summary

| # | Target | Verdict | Hard | Soft |
|---:|---|---|---:|---:|
| 1 | `docs/decision-log.md` | NEEDS WORK | 0 | 12 |
| 2 | `docs/traceability-matrix.md` | NEEDS WORK | 0 | 4 |
| 3 | `docs/architecture-before-after.md` | CLEAN | 0 | 2 |
| 4 | `docs/event-flow.md` | NEEDS WORK | 0 | 19 |
| 5 | `docs/data-model.md` | NEEDS WORK | 0 | 24 |
| 6 | `docs/onboarding.md` | NEEDS WORK | 0 | 10 |
| 7 | `docs/suggested-next-tasks.md` | NEEDS WORK | 0 | 49 |
| 8 | `docs/business-rule-flags.md` | NEEDS WORK | 0 | 10 |
| 9 | `docs/equivalence-results.md` | NEEDS WORK | 0 | 14 |
| 10 | `docs/prose-validation.md` | CLEAN | 0 | 0 |
| 11 | `README.md` | NEEDS WORK | 0 | 16 |
| 12 | `services/authorization-service/README.md` | NEEDS WORK | 0 | 33 |
| 13 | `services/ledger-posting-service/README.md` | NEEDS WORK | 0 | 12 |
| 14 | `services/fraud-detection-service/README.md` | NEEDS WORK | 0 | 18 |
| 15 | `services/notification-service/README.md` | NEEDS WORK | 0 | 31 |
| 16 | `services/account-service/README.md` | NEEDS WORK | 0 | 26 |
| 17 | `services/card-service/README.md` | NEEDS WORK | 0 | 26 |
| 18 | `../README.md` | CLEAN | 0 | 2 |
| 19 | `presentation/executive-summary.html` | CLEAN | 0 | 0 |

Across the nineteen targets the measurement finds 239 sentences over thirty words, 69 paragraphs over five sentences and 0 buzzword uses. That is 4 CLEAN, 15 NEEDS WORK and 0 ROUGH DRAFT.

## Content binding

Each digest is the SHA-256 of one target's bytes, in lower-case hexadecimal. It covers the whole file even where only part of the file is scored, because a change outside the scored slice should still send a reader back to the text. `PresentationAndProseContractTest.theProseReportIsBoundToTheTextItScored` recomputes all eighteen of them on every build.

| # | Target | SHA-256 of the text scored |
|---:|---|---|
| 1 | `docs/decision-log.md` | `6c9981c083fae0089369d4fea5039a3768a72db1b05f8b8c8cdc65cd8082b780` |
| 2 | `docs/traceability-matrix.md` | `0d285c5e93ec42ea55ab1f61af34a650a5fa389f5e1cb978b3c6d08aa02bccbe` |
| 3 | `docs/architecture-before-after.md` | `ee2d6b4e2613babbfdd60086cc70bbed34b57b56d4ddb09e75d8c8d2a281d579` |
| 4 | `docs/event-flow.md` | `b593e70d284babaa1a84e9f34a33c77757734602504087a82eb9a5b71525ba49` |
| 5 | `docs/data-model.md` | `0614f8def009c58ecc6c597afe55a45d68d6ba21b651d3488d377c89f07d2159` |
| 6 | `docs/onboarding.md` | `2ee1a7c19b5c28382e4ed55f51f8647de6000888f7bf81529a6001b4d603824d` |
| 7 | `docs/suggested-next-tasks.md` | `fb85110e51d127e8953c426ba9d354a24e48aa2e80deaf2a56a542868da4cdf3` |
| 8 | `docs/business-rule-flags.md` | `9707a805dc4412dd9e367f6560d8090513de354e36851c30471ef4747c4c686d` |
| 9 | `docs/equivalence-results.md` | `30adc72fbee294bda69feb645907bceb94e7a9198165378601acb218849c9932` |
| 10 | `docs/prose-validation.md` | This report carries no digest: a file cannot publish the digest of its own bytes |
| 11 | `README.md` | `e7cbad3c9fac39505bb2864c69e8eabaa03497e157253b2eddd0fc12ccbbcbb0` |
| 12 | `services/authorization-service/README.md` | `3ec13eb8c2d4009c9c44a031a509c8c45902c5c662f98e02ec9dec820c511f39` |
| 13 | `services/ledger-posting-service/README.md` | `4b69ed340491f0f6b437a931cb505e47f82747250545590743f28924cae3cd57` |
| 14 | `services/fraud-detection-service/README.md` | `d5b8adbc6f9bec408c1bd1fcfa9753c8f56c6a1b2056f46418c4b59e9dfd41f1` |
| 15 | `services/notification-service/README.md` | `84f154940b55a7127b242443752988ea115a9a4d80cf4310c12042cd67af1fb3` |
| 16 | `services/account-service/README.md` | `d39fae13c3baa5af2e87819f3b1a2ad750d669a267d2f4ca1c09615dc45de9df` |
| 17 | `services/card-service/README.md` | `68b0ce42bf148d242436768db4e64a9368b31ccd84aa78d52d27c4d5e028d052` |
| 18 | `../README.md` | `d7847e427bc11c311c31a97e1ef2147caf220813fe957ebc5f02c83558e33068` |
| 19 | `presentation/executive-summary.html` | `bf635958527e92cb47277440c94d94f8a5542fd399e97b1d05165515c3e9bb61` |

## Per-document reports

### 1. `docs/decision-log.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 12 soft violations.

**Measured:** 11 sentences over thirty words, 1 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 10 sentences (entry 1.2) |
| V3: Keep it simple | Raised | Soft violation | It found a replica table no delivered path read or… (61 words, entry 1.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | It found a replica table no delivered path read or… (61 words, entry 1.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**1.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 61 words.

> It found a replica table no delivered path read or wrote while a migration comment said card events kept it current, a refusal that answered a name in a case the source does not store, a column whose domain lived in one writer alone, and four statements describing a route, an event and a serialization path the code does not have.

**Rewrite** — 51 words, 16% shorter:

> It found four defects. A replica table no path read or wrote, though a comment claimed events kept it current. A refusal answering a name the source does not store. A column whose domain lived in one writer. Four statements naming a route, an event and a path the code lacks.

**Why the rewrite is better:** Announcing the count first, then giving each defect its own sentence, lets a reader take one item at a time.

**1.2 — V2: Do not ramble** — soft violation, 10 sentences in one paragraph.

> These cover the notification history route, the account surface and the card surface. … source never tests, and eight complete seeded card numbers in published examples.

**Rewrite** — split into paragraphs of 5 and 5 sentences:

> Start a new paragraph at "A read schema narrower than the rows it returns, required".

**Why the rewrite is better:** 10 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 5 sentences carry one claim and the last 5 carry the next.

### 2. `docs/traceability-matrix.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 4 soft violations.

**Measured:** 2 sentences over thirty words, 2 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 6 sentences (entry 2.2) |
| V3: Keep it simple | Raised | Soft violation | Rule-mandated document covers the documents Rules 1 to 5 require… (37 words, entry 2.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | Rule-mandated document covers the documents Rules 1 to 5 require… (37 words, entry 2.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**2.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 37 words.

> Rule-mandated document covers the documents Rules 1 to 5 require and Rule 3 document the per-module readme Rule 3 requires; a document’s authority is a rule, and the members in its cell are the evidence it cites.

**Rewrite** — 31 words, 16% shorter:

> **Rule-mandated document** covers the documents Rules 1 to 5 require, and **Rule 3 document** the per-module readme. A document's authority is a rule, and its cell names the evidence it cites.

**Why the rewrite is better:** The label definitions and the point about authority are two thoughts, and the semicolon hid the join.

**2.2 — V2: Do not ramble** — soft violation, 6 sentences in one paragraph.

> A citation is not automatically provenance, and two groups of rows say so in … so every fraud path is classified additive however many members it cites.

**Rewrite** — split into paragraphs of 3 and 3 sentences:

> Start a new paragraph at "Every path under `services/fraud-detection-service` names members too, and none of".

**Why the rewrite is better:** 6 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 3 sentences carry one claim and the last 3 carry the next.

### 3. `docs/architecture-before-after.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 2 soft violations.

**Measured:** 0 sentences over thirty words, 2 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 9 sentences (entry 3.1) |
| V3: Keep it simple | Raised | Pass | — |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Pass | — |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**3.1 — V2: Do not ramble** — soft violation, 9 sentences in one paragraph.

> One target construct has no row above, because it has no legacy construct to … A dead-lettered message is a failure the platform survived.

**Rewrite** — split into paragraphs of 4 and 5 sentences:

> Start a new paragraph at "There is no cleanup, and no record of the individual".

**Why the rewrite is better:** 9 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 5 carry the next.

### 4. `docs/event-flow.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 19 soft violations.

**Measured:** 15 sentences over thirty words, 4 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 8 sentences (entry 4.2) |
| V3: Keep it simple | Raised | Soft violation | A single transaction spanning the pass held a connection and… (53 words, entry 4.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | A single transaction spanning the pass held a connection and… (53 words, entry 4.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**4.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 53 words.

> A single transaction spanning the pass held a connection and every row lock of the batch for the sum of its broker waits, and it rolled its own claim back when the process died — which is precisely the case the stranded-claim recovery was written for, and which it could therefore never observe.

**Rewrite** — 42 words, 21% shorter:

> A single transaction spanning the pass held a connection and every row lock for the sum of its broker waits. It also rolled its own claim back when the process died, which is the one case the stranded-claim recovery could never observe.

**Why the rewrite is better:** Splitting the two consequences apart puts the recovery paradox in a sentence of its own, where a reader can see it.

**4.2 — V2: Do not ramble** — soft violation, 8 sentences in one paragraph.

> A record no attempt can apply is the one case that ordering does not … environment, so the requirement is a start-up check rather than a comment.

**Rewrite** — split into paragraphs of 4 and 4 sentences:

> Start a new paragraph at "The acknowledgement mode is `MANUAL_IMMEDIATE` in all five consuming services,".

**Why the rewrite is better:** 8 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 4 carry the next.

### 5. `docs/data-model.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 24 soft violations.

**Measured:** 17 sentences over thirty words, 7 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 9 sentences (entry 5.2) |
| V3: Keep it simple | Raised | Soft violation | Both questions remain worth answering, and the answer is an… (41 words, entry 5.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | Both questions remain worth answering, and the answer is an… (41 words, entry 5.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**5.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 41 words.

> Both questions remain worth answering, and the answer is an endpoint with a bound on it rather than an index waiting for one; `suggested-next-tasks.md` carries the task, and the index belongs in the same change as the query that reads it.

**Rewrite** — 33 words, 20% shorter:

> Both questions remain worth answering. The answer is an endpoint with a bound, not an index waiting for one. `suggested-next-tasks.md` carries the task, and the index belongs with the query that reads it.

**Why the rewrite is better:** The semicolon joined a design decision to a filing note, and the two read better apart.

**5.2 — V2: Do not ramble** — soft violation, 9 sentences in one paragraph.

> Nine named constraints hold the shape the columns alone cannot. … decided outcome that publishes nothing is the one row that names neither.

**Rewrite** — split into paragraphs of 4 and 5 sentences:

> Start a new paragraph at "No row can claim an outcome it does not explain.".

**Why the rewrite is better:** 9 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 5 carry the next.

### 6. `docs/onboarding.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 10 soft violations.

**Measured:** 8 sentences over thirty words, 2 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 8 sentences (entry 6.2) |
| V3: Keep it simple | Raised | Soft violation | The assessment sits in the `assessments` array of one page,… (44 words, entry 6.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | The assessment sits in the `assessments` array of one page,… (44 words, entry 6.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**6.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 44 words.

> The assessment sits in the `assessments` array of one page, beside a `nextPageExists` that is false while the account holds only this one; a longer history answers a `nextCursor` as well, and returning it in the `X-Fraud-Cursor` header asks for the page after it.

**Rewrite** — 35 words, 20% shorter:

> The assessment sits in the `assessments` array of one page, beside a `nextPageExists` that is false while this is the only one. A longer history also answers a `nextCursor`, which the `X-Fraud-Cursor` header sends back.

**Why the rewrite is better:** One sentence now describes the single-page answer and the next describes the paged one.

**6.2 — V2: Do not ramble** — soft violation, 8 sentences in one paragraph.

> The script builds all six images from source, then loads them the way the … The same check catches `kustomization.yaml` drifting from the version in `pom.xml`.

**Rewrite** — split into paragraphs of 4 and 4 sentences:

> Start a new paragraph at "`KIND_CLUSTER_NAME` and `MINIKUBE_PROFILE` select a cluster other than the default.".

**Why the rewrite is better:** 8 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 4 carry the next.

### 7. `docs/suggested-next-tasks.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 49 soft violations.

**Measured:** 46 sentences over thirty words, 3 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 8 sentences (entry 7.2) |
| V3: Keep it simple | Raised | Soft violation | The decision this task asks for is not a technical… (46 words, entry 7.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | The decision this task asks for is not a technical… (46 words, entry 7.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**7.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 46 words.

> The decision this task asks for is not a technical one the platform is entitled to make: how long a posted transaction is retained is fixed by the jurisdiction the deployment operates in, and deleting one early is a worse failure than keeping it too long.

**Rewrite** — 37 words, 20% shorter:

> This task asks for a decision the platform is not entitled to make. How long a posted transaction is retained is fixed by the jurisdiction the deployment operates in, and deleting one early is the worse failure.

**Why the rewrite is better:** Naming the point first and the reason second removes the colon and nine words with it.

**7.2 — V2: Do not ramble** — soft violation, 8 sentences in one paragraph.

> Where: Nothing implements one today: no controller, no event, no service and no repository … `services/card-service` additionally holds the card row itself.

**Rewrite** — split into paragraphs of 4 and 4 sentences:

> Start a new paragraph at "An erasure has to reach five stores across four schemas.".

**Why the rewrite is better:** 8 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 4 carry the next.

### 8. `docs/business-rule-flags.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 10 soft violations.

**Measured:** 8 sentences over thirty words, 2 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 6 sentences (entry 8.2) |
| V3: Keep it simple | Raised | Soft violation | The requirements asked for exactly this: *"Flag any business rule… (40 words, entry 8.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | The requirements asked for exactly this: *"Flag any business rule… (40 words, entry 8.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**8.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 40 words.

> The requirements asked for exactly this: *"Flag any business rule in the original COBOL that is ambiguous, undocumented, or inconsistent (CardDemo intentionally varies coding style across modules) rather than guessing — surface it in the tech spec for human review."*

**Rewrite** — 32 words, 20% shorter:

> The requirements are explicit. *"Flag any business rule in the original COBOL that is ambiguous, undocumented, or inconsistent ... rather than guessing — surface it in the tech spec for human review."*

**Why the rewrite is better:** The quotation keeps its wording, the attribution becomes its own short sentence, and the parenthetical aside is marked as elided rather than carried.

**8.2 — V2: Do not ramble** — soft violation, 6 sentences in one paragraph.

> What this platform does instead. … committed, so a rollback leaves neither the count nor the line behind.

**Rewrite** — split into paragraphs of 3 and 3 sentences:

> Start a new paragraph at "A store that dropped a digit writes one `WARN` line".

**Why the rewrite is better:** 6 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 3 sentences carry one claim and the last 3 carry the next.

### 9. `docs/equivalence-results.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 14 soft violations.

**Measured:** 12 sentences over thirty words, 2 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 7 sentences (entry 9.2) |
| V3: Keep it simple | Raised | Soft violation | A test cannot measure the run it is part of:… (49 words, entry 9.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | A test cannot measure the run it is part of:… (49 words, entry 9.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**9.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 49 words.

> A test cannot measure the run it is part of: the report for its own class does not exist while it executes, and its own module's integration phase has not started, so an in-build check ends up comparing one published figure against another and passes whenever both move together.

**Rewrite** — 40 words, 18% shorter:

> A test cannot measure the run it is part of. Its own class has no report yet, and its module's integration phase has not started. An in-build check therefore compares one published figure against another, and passes whenever both move.

**Why the rewrite is better:** The claim, the evidence and the consequence are three steps, and three sentences let a reader follow them in order.

**9.2 — V2: Do not ramble** — soft violation, 7 sentences in one paragraph.

> Each expected file is loaded by the class its own provenance header names, and … lists every file, its row count and the class that reads it.

**Rewrite** — split into paragraphs of 3 and 4 sentences:

> Start a new paragraph at "Thirteen of the fourteen files are read through one reader".

**Why the rewrite is better:** 7 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 3 sentences carry one claim and the last 4 carry the next.

### 10. `docs/prose-validation.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Pass | — |
| V3: Keep it simple | Raised | Pass | — |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Pass | — |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

None. This target has no measured violation.

### 11. `README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 16 soft violations.

**Measured:** 10 sentences over thirty words, 6 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 12 sentences (entry 11.2) |
| V3: Keep it simple | Raised | Soft violation | No clock bound relates the capture moment to the clock of the authorization service… (40 words, entry 11.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | No clock bound relates the capture moment to the clock of the authorization service… (40 words, entry 11.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**11.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 40 words.

> No clock bound relates the capture moment to the clock of the authorization service: reject code 0103, which compares the account expiry against the first ten characters of the capture moment, is the whole of the test applied to it.

**Rewrite** — 31 words, 23% shorter:

> No clock bound relates the capture moment to the clock of this service. Reject code 0103 compares the account expiry against its first ten characters, and that is the whole test.

**Why the rewrite is better:** The original nests the one test inside the statement of the absence. Two sentences carry one fact each.

**11.2 — V2: Do not ramble** — soft violation, 12 sentences in one paragraph.

> Two filters run in front of every route in all six services. … records the shared-store ceiling and the forwarded-header setting a proxied deployment needs.

**Rewrite** — split into paragraphs of 6 and 6 sentences:

> Start a new paragraph at "Reads are untouched, which is why the health probe carries".

**Why the rewrite is better:** 12 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 6 sentences carry one claim and the last 6 carry the next.

### 12. `services/authorization-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 33 soft violations.

**Measured:** 28 sentences over thirty words, 5 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 7 sentences (entry 12.2) |
| V3: Keep it simple | Raised | Soft violation | `domain/CallerEntitlement` compares the caller against the resolved account… (40 words, entry 12.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | `domain/CallerEntitlement` compares the caller against the resolved account… (40 words, entry 12.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**12.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 40 words.

> `domain/CallerEntitlement` compares the caller against the resolved account immediately after the cross-reference read and before a transaction identifier is allocated, which is where the comparison has to happen because an account-only request has no card until that read has run.

**Rewrite** — 29 words, 28% shorter:

> `domain/CallerEntitlement` compares the caller against the resolved account after the cross-reference read and before an identifier is allocated. An account-only request has no card until that read has run.

**Why the rewrite is better:** Where the comparison happens and why are two facts. The second reads better as its own sentence.

**12.2 — V2: Do not ramble** — soft violation, 7 sentences in one paragraph.

> Every business route requires HTTP Basic authentication. … on this service is reachable at all, because `anyRequest().denyAll()` closes the chain.

**Rewrite** — split into paragraphs of 3 and 4 sentences:

> Start a new paragraph at "Every other route in this platform is ownership-scoped; this one".

**Why the rewrite is better:** 7 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 3 sentences carry one claim and the last 4 carry the next.

### 13. `services/ledger-posting-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 12 soft violations.

**Measured:** 6 sentences over thirty words, 6 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 7 sentences (entry 13.2) |
| V3: Keep it simple | Raised | Soft violation | To publish onto another transport, replace that bean with one… (40 words, entry 13.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | To publish onto another transport, replace that bean with one… (40 words, entry 13.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**13.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 40 words.

> To publish onto another transport, replace that bean with one wrapping the new client and leave `OutboxRelay` untouched — it depends on the template alone, so the outbox contract, the claim protocol and the abandonment route all survive the substitution.

**Rewrite** — 31 words, 23% shorter:

> To publish onto another transport, replace that bean and leave `OutboxRelay` untouched. It depends on the template alone, so the outbox contract, the claim protocol and the abandonment route all survive.

**Why the rewrite is better:** The instruction and the reason it holds are separate, and the dash hid the join.

**13.2 — V2: Do not ramble** — soft violation, 7 sentences in one paragraph.

> A spent consumer record reaches its own source topic plus `.DLT`, and what is … rejected, so republishing it would move unvalidated bytes onto a second topic.

**Rewrite** — split into paragraphs of 3 and 4 sentences:

> Start a new paragraph at "The value becomes a 134-character fixed-width diagnostic holding the four".

**Why the rewrite is better:** 7 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 3 sentences carry one claim and the last 4 carry the next.

### 14. `services/fraud-detection-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 18 soft violations.

**Measured:** 15 sentences over thirty words, 3 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 8 sentences (entry 14.2) |
| V3: Keep it simple | Raised | Soft violation | A page number is refused rather than served because an… (50 words, entry 14.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | A page number is refused rather than served because an… (50 words, entry 14.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**14.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 50 words.

> A page number is refused rather than served because an offset is reached by reading and discarding every row before it, so the work would grow with the page asked for rather than with the page returned: page 1,000,000 at 200 rows would walk 200,000,000 entries to answer with 200.

**Rewrite** — 40 words, 20% shorter:

> A page number is refused because an offset is reached by reading and discarding every row before it. The work would then grow with the page asked for: page 1,000,000 at 200 rows walks 200,000,000 entries to answer with 200.

**Why the rewrite is better:** The rule comes first and the arithmetic that proves it second, which is the order a reader needs.

**14.2 — V2: Do not ramble** — soft violation, 8 sentences in one paragraph.

> `fraud_assessment` and `velocity_window` are the two business tables here, and each declared its horizon … ledger services, so both horizons are privacy horizons and not only housekeeping.

**Rewrite** — split into paragraphs of 4 and 4 sentences:

> Start a new paragraph at "Nothing reads a window once its span elapses, so every".

**Why the rewrite is better:** 8 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 4 carry the next.

### 15. `services/notification-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 31 soft violations.

**Measured:** 24 sentences over thirty words, 7 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 8 sentences (entry 15.2) |
| V3: Keep it simple | Raised | Soft violation | `observed_at` is the ordering guard rather than a purge key… (48 words, entry 15.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | `observed_at` is the ordering guard rather than a purge key… (48 words, entry 15.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**15.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 48 words.

> `observed_at` is the ordering guard rather than a purge key — `messaging/CustomerContextChangedConsumer` refuses an event older than the row it would overwrite — and `V6__subject_request_posture.sql` removed the earlier claim that it served an erasure request, because no export or erasure workflow exists on this platform, here or upstream.

**Rewrite** — 36 words, 25% shorter:

> `observed_at` is the ordering guard rather than a purge key, and `messaging/CustomerContextChangedConsumer` refuses an event older than the row it would overwrite. `V6__subject_request_posture.sql` dropped the earlier erasure claim, because no such workflow exists here or upstream.

**Why the rewrite is better:** Two dashes held a second clause inside a first, and separating them shortens both.

**15.2 — V2: Do not ramble** — soft violation, 8 sentences in one paragraph.

> Figure 1 shows the four topics reaching four consumer groups, the duplicate claim they … The paired platform-wide before-and-after views are in architecture, before and after.

**Rewrite** — split into paragraphs of 4 and 4 sentences:

> Start a new paragraph at "A posted transaction does both: it upserts its row and".

**Why the rewrite is better:** 8 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 4 carry the next.

### 16. `services/account-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 26 soft violations.

**Measured:** 20 sentences over thirty words, 6 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 12 sentences (entry 16.2) |
| V3: Keep it simple | Raised | Soft violation | On the after side each cylinder is one table inside… (86 words, entry 16.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | On the after side each cylinder is one table inside… (86 words, entry 16.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**16.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 86 words.

> On the after side each cylinder is one table inside this service's private schema, which no other service reads. - A thin solid arrow is control passing from one step to the next. - A dotted arrow is a read of stored data. - A thick arrow is the short-circuit path taken when a hop finds nothing, labelled with the source line that branches on the before side. - The dotted arrow between the two subgraphs marks the correspondence, and the correspondence is partial by design.

**Rewrite** — 68 words, 21% shorter:

> On the after side each cylinder is one table inside this service's private schema. A thin arrow is control passing to the next step. A dotted arrow is a read of stored data. A thick arrow is the short-circuit path a hop takes when it finds nothing, labelled with the source line that branches. The dotted arrow between the subgraphs marks a correspondence that is partial by design.

**Why the rewrite is better:** The legend items lost their line starts when the paragraph was reflowed, so five entries read as one sentence. Each belongs on a line of its own.

**16.2 — V2: Do not ramble** — soft violation, 12 sentences in one paragraph.

> No interest computation. … dependency. - No COBOL compiler, emulator or mainframe connector on the classpath.

**Rewrite** — split into paragraphs of 6 and 6 sentences:

> Start a new paragraph at "The card record, its `CARD-CVV-CD` and every card-number checksum question".

**Why the rewrite is better:** 12 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 6 sentences carry one claim and the last 6 carry the next.

### 17. `services/card-service/README.md`

**Overall verdict:** **NEEDS WORK** — 0 hard violations, 26 soft violations.

**Measured:** 16 sentences over thirty words, 10 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 11 sentences (entry 17.2) |
| V3: Keep it simple | Raised | Soft violation | The binding constraint reads: *"Do not modify or require changes… (47 words, entry 17.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | The binding constraint reads: *"Do not modify or require changes… (47 words, entry 17.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**17.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 47 words.

> The binding constraint reads: *"Do not modify or require changes to the original COBOL source as a prerequisite — the new services should consume the behavior (documented via the tech spec / reverse-engineering output) of the COBOL programs listed above, not call into the mainframe at runtime."*

**Rewrite** — 39 words, 17% shorter:

> The binding constraint has two halves. *"Do not modify or require changes to the original COBOL source as a prerequisite."* The services *"consume the behavior ... of the COBOL programs listed above, not call into the mainframe at runtime."*

**Why the rewrite is better:** The quotation keeps its wording and splits at the dash the original used, so each half reads on its own.

**17.2 — V2: Do not ramble** — soft violation, 11 sentences in one paragraph.

> A contended update gives up rather than waiting, and the two 409 outcomes mean … would have offered a retry for a grant no retry can repair.

**Rewrite** — split into paragraphs of 5 and 6 sentences:

> Start a new paragraph at "`LOCK_NOT_ACQUIRED` therefore means the row never came back held; `UPDATE_FAILED_AFTER_LOCK`".

**Why the rewrite is better:** 11 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 5 sentences carry one claim and the last 6 carry the next.

### 18. `../README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 2 soft violations.

**Measured:** 1 sentences over thirty words, 1 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Soft violation | one paragraph of 6 sentences (entry 18.2) |
| V3: Keep it simple | Raised | Soft violation | The two supporting services publish only when their own state… (43 words, entry 18.1) |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Soft violation | The two supporting services publish only when their own state… (43 words, entry 18.1) |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

**18.1 — V3: Keep it simple** and **A2: Short Words, Simple Structures** — soft violation, 43 words.

> The two supporting services publish only when their own state changes, which keeps the replicas the decision reads current, and no service calls another over HTTP. card-platform/docs/architecture-before-after.md holds the same pair at full size, with every consumer group and every outbox relay named.

**Rewrite** — 34 words, 21% shorter:

> The two supporting services publish only when their own state changes, which keeps the replicas the decision reads current. No service calls another over HTTP, and `architecture-before-after.md` holds the same pair at full size.

**Why the rewrite is better:** Three claims shared one sentence, and two sentences carry them with nine fewer words.

**18.2 — V2: Do not ramble** — soft violation, 7 sentences in one paragraph.

> A client calls one Representational State Transfer (REST) endpoint, `POST /authorizations`, which only `authorization-service` … the three calls another, and none of them blocks the authorization response.

**Rewrite** — split into paragraphs of 4 and 3 sentences:

> Start a new paragraph at "`authorization-service` is the only service that writes the decision.".

**Why the rewrite is better:** 7 sentences in one block ask a reader to hold the whole argument at once. The break lands where the paragraph changes subject, so the first 4 sentences carry one claim and the last 3 carry the next.

### 19. `presentation/executive-summary.html`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

**Rule 4 body words:** every one of the sixteen slides is inside the ceiling of forty, the heaviest at thirty-nine. The count is every visible word of a slide. A heading, an eyebrow, a table caption, a header cell, a body cell, a metric card and an icon label all count against it. Only the source a diagram is generated from and the decorative slide number are left out. Four slides stood at 103, 94, 88 and 43 words while the count excluded tables, headings and metric grids, and `PresentationAndProseContractTest.bodyWordCount` counts them now.

| Principle number and name | Weight | Result | Worst offender quoted |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Not measured | — |
| V2: Do not ramble | Raised | Pass | — |
| V3: Keep it simple | Raised | Pass | — |
| V4: Have the guts to cut | Standard | Not measured | — |
| V5: Sound like yourself | Reduced | Pass | — |
| V6: Say what you mean | Raised | Not measured | — |
| V7: Pity the reader | Raised | Not measured | — |
| V8: Start close to the end | Standard | Not measured | — |
| V9: The Dignity Test | Standard | Not measured | — |
| V10: The Indifference Detector | Standard | Not measured | — |
| V11: The Indianapolis Test | Standard | Not measured | — |
| V12: Humor as Trust Signal | Standard | Not measured | — |
| A1: Plate Glass Clarity | Standard | Not measured | — |
| A2: Short Words, Simple Structures | Standard | Pass | — |
| A3: Logical Sequence | Standard | Not measured | — |
| A4: Ideas Carry the Weight | Standard | Not measured | — |
| A5: Conversational Informality | Standard | Not measured | — |
| A6: No Ornamental Language | Standard | Pass | — |
| A7: Functional Dialogue | Standard | Not measured | — |
| A8: Anticipate Reader Questions | Standard | Not measured | — |
| A9: Efficiency Over Polish | Standard | Not measured | — |
| A10: Respect the Reader's Intelligence | Standard | Not measured | — |

**Per-violation entries:**

None. This target has no measured violation.

## Exemptions applied

- **Code and inline code.** Fenced blocks are dropped and an inline span becomes one token, so `PIC S9(09)V99` counts as one word rather than a clause a reader has to parse.
- **Attributed quotations.** A blockquoted passage is skipped, which is how this report quotes an offender without scoring itself on it. Two inline quotations do stay scored, because they sit inside a sentence: the requirements passage in the flagged-rule register, and the binding constraint in the card guide. Both come from the technical specification's section 0.8 and both keep their wording.
- **Verbatim source messages.** A reject text such as `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` is reproduced exactly, because a service that softens it stops matching the source.
- **Deliberate presentation choices.** Slide 9 presents `RoundingMode.DOWN` and `HALF_UP` as inline monospace rather than as a code block, because the audience is an executive one.
- **Repetition across sibling guides.** Six service guides answer the same questions in the same order. Repetition between them is a navigation aid rather than rambling, so V2 is scored within a document and not across the set.

## Where the measurement lands against the plan

The Agent Action Plan set CLEAN as the target verdict for every authored document. The measurement does not reach it, and this report publishes the measurement rather than the target.

Sixteen of the nineteen targets carry at least one sentence over thirty words. The reference material is where they gather: a migration table, a register of flagged rules and a task list are read by lookup rather than end to end. Each entry above names the passage and gives a worked replacement, so the writing work is enumerated rather than deferred. The verdicts move when the text moves, because the build measures the text rather than this page.
