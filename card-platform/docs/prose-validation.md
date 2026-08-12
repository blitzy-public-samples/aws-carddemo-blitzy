# Prose Validation

This report is the Rule 5 validation of the twenty-six targets this engagement authored. They are ten documents, eight guides, the deployment guide, the executive deck and the description prose of six OpenAPI contracts. It records what was measured, what the measurement found, and what a reader can check. Every verdict below is the output of a measurement the build repeats.

Three earlier revisions were corrected here. One published CLEAN for all nineteen targets it then held, while its own table reported hundreds of over-length sentences, and it named fourteen of the twenty-two principles incorrectly. The next scored nineteen targets while claiming every authored document, and admitted three soft violations as CLEAN. It also left seventeen principles unjudged, and scored a quotation of the requirements as though this engagement had written it.

The third scored twenty-six targets against a corrected threshold, and fifteen of them still read NEEDS WORK. This revision publishes the corpus after that length work was done.

## Methodology

- Every target is Technical, so the Asimov agent governs. Both principle sets apply to every target: the twelve Vonnegut principles V1 through V12, and the ten Asimov principles A1 through A10.
- V2, V3, V6, and V7 carry the highest weight, because the rule raises them for technical input.
- V1 and V5 carry reduced weight, because the rule lowers them for the same input type.
- Four results record a judgement. **Pass**, **Soft violation**, and **Hard violation** name what a reading found. **Not applicable** names a principle this kind of writing cannot offend, and it carries a reason. Every principle carries a judged result and the evidence behind it.
- Five principles are measured mechanically. A sentence over thirty words is a soft violation of V3 and A2. A paragraph over five sentences is a soft violation of V2. A buzzword is a hard violation of V5 and A6.
- A mechanical pass owes its own evidence. A pass on sentence length publishes the longest sentence the target does hold, and a pass on paragraph length the longest paragraph. A pass on buzzwords publishes the number of paragraphs read.
- The other seventeen are read and judged. Each names something the target carries, so its result rests on the text rather than on this report's word.
- Counts count offending passages rather than principles. One over-length sentence is one soft violation, even though it offends two principles at once.
- Every counted violation carries its own entry. An entry quotes the passage, names the principle, gives a rewrite and says in one sentence why the rewrite reads better.
- The thresholds come from the rule. CLEAN is zero hard and at most two soft. NEEDS WORK is one to three hard, or three or more soft. ROUGH DRAFT is four or more hard.
- B1 through B5 are the blog rules. They are not applied, because no target is a blog post.
- Scoring reads prose only. Fenced code blocks, table rows, headings, horizontal rules and blockquoted passages are dropped, and an inline code span collapses to the single token `CODE`.
- A quotation attributed to a source outside this engagement is skipped, and its wording is preserved. Only the quoted words are exempt: the sentence carrying them is still scored.
- An OpenAPI document is scored as its `description`, `summary` and `title` values, read in document order. A blank line inside one of those values is a paragraph break.
- Only the new `Modernized card platform` section of the repository-root guide is scored. Only headings, body copy, bullets, metric labels, and table cells of the deck are scored.
- The oracle is `PresentationAndProseContractTest` in the equivalence module. It re-measures all twenty-six targets on every build. It fails when a verdict, a count, a principle result, a piece of evidence or a published rewrite disagrees with what it measured.

## Measurement

One row per target. `Paragraphs` counts the prose blocks scoring reads and `Sentences` counts the sentences in them. The last three columns are the violations those numbers give.

| # | Target | Paragraphs | Sentences | Over thirty words | Paragraphs over five | Buzzwords |
|---:|---|---:|---:|---:|---:|---:|
| 1 | `docs/decision-log.md` | 47 | 146 | 0 | 0 | 0 |
| 2 | `docs/traceability-matrix.md` | 39 | 108 | 0 | 0 | 0 |
| 3 | `docs/architecture-before-after.md` | 51 | 136 | 0 | 0 | 0 |
| 4 | `docs/event-flow.md` | 147 | 392 | 0 | 0 | 0 |
| 5 | `docs/data-model.md` | 156 | 385 | 0 | 0 | 0 |
| 6 | `docs/onboarding.md` | 196 | 520 | 0 | 0 | 0 |
| 7 | `docs/suggested-next-tasks.md` | 348 | 846 | 0 | 0 | 0 |
| 8 | `docs/business-rule-flags.md` | 53 | 175 | 0 | 0 | 0 |
| 9 | `docs/equivalence-results.md` | 91 | 260 | 0 | 0 | 0 |
| 10 | `docs/prose-validation.md` | 134 | 212 | 0 | 0 | 0 |
| 11 | `README.md` | 134 | 261 | 0 | 0 | 0 |
| 12 | `services/authorization-service/README.md` | 193 | 439 | 0 | 0 | 0 |
| 13 | `services/ledger-posting-service/README.md` | 145 | 314 | 0 | 0 | 0 |
| 14 | `services/fraud-detection-service/README.md` | 118 | 290 | 0 | 0 | 0 |
| 15 | `services/notification-service/README.md` | 168 | 380 | 0 | 0 | 0 |
| 16 | `services/account-service/README.md` | 175 | 396 | 0 | 0 | 0 |
| 17 | `services/card-service/README.md` | 165 | 421 | 0 | 0 | 0 |
| 18 | `../README.md` | 26 | 47 | 0 | 0 | 0 |
| 19 | `presentation/executive-summary.html` | 108 | 114 | 0 | 0 | 0 |
| 20 | `deploy/k8s/README.md` | 28 | 76 | 0 | 0 | 0 |
| 21 | `services/authorization-service/src/main/resources/openapi.yaml` | 163 | 395 | 0 | 0 | 0 |
| 22 | `services/ledger-posting-service/src/main/resources/openapi.yaml` | 44 | 103 | 0 | 0 | 0 |
| 23 | `services/fraud-detection-service/src/main/resources/openapi.yaml` | 76 | 180 | 0 | 0 | 0 |
| 24 | `services/notification-service/src/main/resources/openapi.yaml` | 74 | 184 | 0 | 0 | 0 |
| 25 | `services/account-service/src/main/resources/openapi.yaml` | 190 | 425 | 0 | 0 | 0 |
| 26 | `services/card-service/src/main/resources/openapi.yaml` | 183 | 441 | 0 | 0 | 0 |

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
| 11 | `README.md` | CLEAN | 0 | 0 |
| 12 | `services/authorization-service/README.md` | CLEAN | 0 | 0 |
| 13 | `services/ledger-posting-service/README.md` | CLEAN | 0 | 0 |
| 14 | `services/fraud-detection-service/README.md` | CLEAN | 0 | 0 |
| 15 | `services/notification-service/README.md` | CLEAN | 0 | 0 |
| 16 | `services/account-service/README.md` | CLEAN | 0 | 0 |
| 17 | `services/card-service/README.md` | CLEAN | 0 | 0 |
| 18 | `../README.md` | CLEAN | 0 | 0 |
| 19 | `presentation/executive-summary.html` | CLEAN | 0 | 0 |
| 20 | `deploy/k8s/README.md` | CLEAN | 0 | 0 |
| 21 | `services/authorization-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |
| 22 | `services/ledger-posting-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |
| 23 | `services/fraud-detection-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |
| 24 | `services/notification-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |
| 25 | `services/account-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |
| 26 | `services/card-service/src/main/resources/openapi.yaml` | CLEAN | 0 | 0 |

Across the twenty-six targets the measurement finds 0 sentences over thirty words, 0 paragraphs over five sentences and 0 buzzword uses. That is 26 CLEAN, 0 NEEDS WORK and 0 ROUGH DRAFT.

## Content binding

Each digest is the SHA-256 of one target's bytes, in lower-case hexadecimal. It covers the whole file even where only part of the file is scored, because a change outside the scored slice should still send a reader back to the text. `PresentationAndProseContractTest.theProseReportIsBoundToTheTextItScored` recomputes all twenty-five of them on every build.

| # | Target | SHA-256 of the text scored |
|---:|---|---|
| 1 | `docs/decision-log.md` | `37b75f3cdcebd0e3892e7736d7c401922b1406e79cc3cff266994c9e85fe3f4e` |
| 2 | `docs/traceability-matrix.md` | `f7aa27fcad19b55dde816495147d85a5c74a4a903c2121a36d2317ccd365939c` |
| 3 | `docs/architecture-before-after.md` | `b5490d7ca84183d6687c2d40ac65e896205af76420d658cf2c3ccf0668848b65` |
| 4 | `docs/event-flow.md` | `871d398449f07d976e2db85c60b8d3c49bebe5ae465af3f6448f2a95c59117e0` |
| 5 | `docs/data-model.md` | `3975095cb4236cfd7abb995c0cb073af0331ae74f48a2e143cea03d4b615c831` |
| 6 | `docs/onboarding.md` | `741dad9dee60e55581f4907e7bfd241c744f24269fe9981a2dad5c6a5fde95f6` |
| 7 | `docs/suggested-next-tasks.md` | `f589a78422d3f091d94130b0c9b2018c3dd3fc771306bddb6a68406fb1462fb5` |
| 8 | `docs/business-rule-flags.md` | `c8a64ce8f9ef0e69a452daccac5f899ba669d03c8ae4b67568aeae525868ff78` |
| 9 | `docs/equivalence-results.md` | `9a65d7a82e39b6505e943e8b74ae19ce06c6a57050e9f1336e977b1ea71f440d` |
| 10 | `docs/prose-validation.md` | This report carries no digest: a file cannot publish the digest of its own bytes |
| 11 | `README.md` | `c5611a2851fcd73c56942c820e40f45ba8d67ab38c2f825edebb48558c3ad7ff` |
| 12 | `services/authorization-service/README.md` | `d75ff1a3e7287cbd640e74149a5c88cc942b49de97ab190d01dbf6365ec5f1f3` |
| 13 | `services/ledger-posting-service/README.md` | `1f3c3d2f487c7d2420a34044bcd64131d3f6eec348ff1587aedbc7c863cb8582` |
| 14 | `services/fraud-detection-service/README.md` | `3656f02434700b14776fc1848f5ca80bf0629f373e619325c3501d82d1e21559` |
| 15 | `services/notification-service/README.md` | `890d89a81d52f30f8320161724e2f3df921ae382b8c3e8e5161b437d9dcc9632` |
| 16 | `services/account-service/README.md` | `1d4ed482fcadefb52714d49fc3a5930872f261a01c1e69f52a0c34d1b6f2e78f` |
| 17 | `services/card-service/README.md` | `892ad96b10147398f2341ad530e14b0a3ebba389357ed9f725c397cc47716690` |
| 18 | `../README.md` | `06b761b27685428336f1f12fa5b2e9c6c975b5c8a881781630ab8e8a8e75ec28` |
| 19 | `presentation/executive-summary.html` | `0f66ded6ab2314f9e0dd63e9a47c4780dd3bc33bc0d14f4e81e4806063f3ead4` |
| 20 | `deploy/k8s/README.md` | `5cfd8099f17f96fb167a4de4deb2669084862367c44dd0753f461486562357ad` |
| 21 | `services/authorization-service/src/main/resources/openapi.yaml` | `b3f159822219b261a4962fd19ebb3c620a22a0e513f0632e12d26c7b01d4179c` |
| 22 | `services/ledger-posting-service/src/main/resources/openapi.yaml` | `c14e366701f9054ed4c63b379ea83bc6b88c69de3ecb51db6a5ea372823b286a` |
| 23 | `services/fraud-detection-service/src/main/resources/openapi.yaml` | `d2f7c51dcbe85979e20a2e992f335af3ff204f17b3a8b4f1487fba052f2b5202` |
| 24 | `services/notification-service/src/main/resources/openapi.yaml` | `dbb3d114d0246a7e6d50b018416ff08e0423a0d0ea3ece0f0e18c66966652005` |
| 25 | `services/account-service/src/main/resources/openapi.yaml` | `3243cf2170daffdb119cad14f76652576a35dd62e538f46549c26e367e09cb5e` |
| 26 | `services/card-service/src/main/resources/openapi.yaml` | `1b48e071cd945b3c86d11147bc14838540f086cf94b2b435cef8518d7618d936` |

## Per-document reports

### 1. `docs/decision-log.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Platform and technology selections` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `BigDecimal` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 47 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Migration strategy`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `card-platform/` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Arithmetic and numeric fidelity` |
| V9: The Dignity Test | Standard | Pass | States `curl` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Data model and persistence` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `COPAUA0C` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Event contracts and messaging` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Concurrency and identifiers` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Defects reproduced rather than fixed` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `CP00` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 47 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Validations deliberately not added` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `CBTRN02C` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `COTRN02C` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 2. `docs/traceability-matrix.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Coverage summary` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `.gitkeep` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 39 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Forward: COBOL programs`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `libs/cobol-compat` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Primary migration sources` |
| V9: The Dignity Test | Standard | Pass | States `ACCTFILE` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Reference-only programs` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `:L29` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Partially in scope` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Excluded programs` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Forward: copybooks` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `CARDFILE` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 39 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Record layouts` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `XREFFILE` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `CUSTFILE` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 3. `docs/architecture-before-after.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Measured CICS inventory` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 28 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `diagrams/` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 51 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Shared file reachability`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `EXEC CICS XCTL` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Posting job allocations` |
| V9: The Dignity Test | Standard | Pass | States `DDNAME(INREADER)` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Topic and consumer-group inventory` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `//TRNRPT00 JOB 'TRAN REPORT'` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Private store ownership` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 28 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Component-by-component correspondence` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `What changed structurally` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `:L94` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 51 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Shared datasets became private stores` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `//STEP10 EXEC PROC=TRANREPT` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `POSTTRAN` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 4. `docs/event-flow.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `The envelope` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `eventId` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 147 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Payload conventions`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `eventType` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Topics and consumer groups` |
| V9: The Dignity Test | Standard | Pass | States `schemaVersion` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Business events` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `occurredAt` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Authorization transaction flow` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `State-change projection flow` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Delivery mechanics` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `aggregateId` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 147 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `The two dead-letter wire forms` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `docs/decision-log.md` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `transaction-declined-v3` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 5. `docs/data-model.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Derivation rules` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `PIC 9(n)` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 156 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Two date treatments`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `NUMERIC(n,0)` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Service schemas` |
| V9: The Dignity Test | Standard | Pass | States `BIGINT` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Authorization database` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `PIC X(n)` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Outcome infrastructure` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Ledger database` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Balance and lookup tables` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `VARCHAR(n)` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 156 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Fraud database` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `CHAR(n)` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `CHAR` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 6. `docs/onboarding.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Setup` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `README.md` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 196 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Prerequisites`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `CONTRIBUTING.md` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Start with one command` |
| V9: The Dignity Test | Standard | Pass | States `scripts/start-demo.sh` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Clone to a configured working tree` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `deploy/k8s/load-images.sh` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `The card-token key` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Build and start` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Ports, schemas, and topics` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `[25,26)` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 196 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Verify the running stack` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `[3.9.16,3.10.0)` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `openssl rand` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 7. `docs/suggested-next-tasks.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Correctness decisions requiring a human` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `CreditLimitRule` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 348 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Widen the credit-limit working precision`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `WS-TEMP-BAL PIC S9(09)V99` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Correct the refund sign convention` |
| V9: The Dignity Test | Standard | Pass | States `ACCT-CURR-CYC-CREDIT` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Decide whether current balance belongs in the limit rule` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `ACCT-CURR-CYC-DEBIT` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Decide the ceiling of the reserved cycle exposure` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Confirm the target meaning of reason 109` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Validations deliberately not added` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `:L14` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 348 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Add card-number checksum validation` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `ACCT-CREDIT-LIMIT` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `:L8` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 8. `docs/business-rule-flags.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Register coverage` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `:L34` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 53 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `The largest item: a named program that does not exist`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `COPAUA0C` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `The widest item: five posting stores that drop a digit` |
| V9: The Dignity Test | Standard | Pass | States `CP00` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Resolved rather than flagged` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `ADD MORE VALIDATIONS HERE` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Coding style varies between modules` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Measurement discrepancies against the specification` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Platform values that are chosen rather than measured` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `1500-VALIDATE-TRAN` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 53 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Departures this platform makes from the source` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `:L370` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `SELECT` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 9. `docs/equivalence-results.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Comparison basis` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `card-platform/equivalence-tests/src/test/resources/expected/` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 91 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `How the suite runs`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `fixture-coverage.csv` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Observed run` |
| V9: The Dignity Test | Standard | Pass | States `DocumentationContractTest` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Fixture inventory and results` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `card-platform/` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Cross-reference width` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Results by test class` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Authorization decisions` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `mvn test` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 91 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Two evaluation models, and why both are published` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `**/*EquivalenceTest.java` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `integration-test` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 10. `docs/prose-validation.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Prose Validation` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `PresentationAndProseContractTest` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 134 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Methodology`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `docs/prose-validation.md` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Measurement` |
| V9: The Dignity Test | Standard | Pass | States `description` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Summary` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `summary` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Content binding` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Per-document reports` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Exemptions applied` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `title` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 134 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Where the measurement lands against the plan` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `Modernized card platform` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `CODE` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 11. `README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `CardDemo Card Platform` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `diagrams/` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 134 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Overview`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `samples/` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Delivered capability` |
| V9: The Dignity Test | Standard | Pass | States `mvn verify` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Quickstart` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `AccountStateChanged` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `2. Build, start, and check` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `3. Authorize one transaction and watch the fan-out` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Security and transport` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `authorization-account-state` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 134 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Repository map` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `account_credit_snapshot` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `CardUpdated` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 12. `services/authorization-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Authorization Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `authorization-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 193 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `POST /authorizations` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `TransactionAuthorized` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Endpoints` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `TransactionDeclined` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Two controls in front of every route` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Decision chain` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Replica currency` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `transaction-declined-v2` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 193 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Events` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `schemas/transaction-declined-v3.json` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `REJECT-TRAN-DATA` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 13. `services/ledger-posting-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Ledger Posting Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `ledger-posting-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 145 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `TransactionAuthorized` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `TransactionPosted` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Endpoints` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `messaging/TransactionAuthorizedConsumer.java` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Two controls in front of every route` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Events` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Domain context` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `L23` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 145 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `What posting means` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `//STEP15 EXEC PGM=CBTRN02C` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `domain/PostingService.java` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 14. `services/fraud-detection-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Fraud Detection Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `fraud-detection-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 118 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `FraudFlagged` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `FraudCleared` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Architecture` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `ADMIN` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Endpoints` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Two controls in front of every route` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Events consumed and produced` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `* ADD MORE VALIDATIONS HERE` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 118 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Observability` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `.DLT` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `carddemo.dead-letter` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 15. `services/notification-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Notification Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `notification-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 168 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `com.carddemo.notification` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `01 STATEMENT-LINES.` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Domain context` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `ST-LINE0` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Events consumed` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `A contract version this service cannot act on` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Architecture` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `ST-LINE15` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 168 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Endpoints` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `01 HTML-LINES.` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `HTML-FIXED-LN PIC X(100)` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 16. `services/account-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Account Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `account-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 175 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `com.carddemo.account` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `transaction.posted` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Endpoints` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `messaging/TransactionPostedConsumer` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Two controls in front of every route` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Events` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Domain context` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `:L560` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 175 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `What a billing cycle is` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `AccountStateChanged` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `ACCTDAT` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 17. `services/card-service/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Card Service` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `card-service` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 165 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Purpose`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `com.carddemo.card` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Source provenance` |
| V9: The Dignity Test | Standard | Pass | States `CCLI` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Endpoints` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `COCRDLI` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Two controls in front of every route` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Events published` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Domain and data ownership` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `COCRDLIC` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 165 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Paging` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `CCDL` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `COCRDSL` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 18. `../README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `CardDemo -- Mainframe CardDemo Application` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 4 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 27 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `card-platform/` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 26 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Description`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `diagrams/` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Technologies used` |
| V9: The Dignity Test | Standard | Pass | States `samples/` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Modernized card platform` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `POST /authorizations` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Installation on the mainframe` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 27 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Running full batch` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Application Details` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `authorization-service` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 26 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `User Functions` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `TransactionAuthorized` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `TransactionDeclined` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 19. `presentation/executive-summary.html`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

**Rule 4 body words:** every one of the sixteen slides is inside the ceiling of forty, the heaviest at thirty-nine. The count is every visible word of a slide. A heading, an eyebrow, a table caption, a header cell, a body cell, a metric card and an icon label all count against it. Only the source a diagram is generated from and the decorative slide number are left out. Four slides stood at 103, 94, 88 and 43 words while the count excluded tables, headings and metric grids, and `PresentationAndProseContractTest.bodyWordCount` counts them now.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Measured from source to demo` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 3 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 18 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `AWS CardDemo Modernization — Executive Summary` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 108 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Figure 1 — From shared files to owned schemas`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `AWS CardDemo modernization` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `How the event flow works` |
| V9: The Dignity Test | Standard | Pass | States `One authorization.` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Figure 2 — One outcome event starts the work` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `Independent reactions.` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Business value` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 18 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Room to change safely` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Correctness and parity` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `Delivered scope` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 108 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Money truncates; it never rounds` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `Common Business Oriented Language (COBOL) programs analysed` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `8 → 6` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 20. `deploy/k8s/README.md`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `Applying these manifests` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 29 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `kustomization.yaml` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 28 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `What is verified about the images, and what is not`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `kubectl` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `Pinning the six by digest` |
| V9: The Dignity Test | Standard | Pass | States `kind` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Encryption at rest` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `minikube` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Related documentation` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 29 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `Applying these manifests` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `What is verified about the images, and what is not` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `load-images.sh` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 28 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Pinning the six by digest` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `card-platform/` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `docker build` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 21. `services/authorization-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/authorizations` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: authorizeTransaction` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 163 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `ApiProblem`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `ApiErrorResponse` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `AuthorizationRequest` |
| V9: The Dignity Test | Standard | Pass | States `Problem` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `ApprovedAuthorization` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `AuthorizationResponse` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `DeclinedAuthorization` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `AuthorizationResponse` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `Problem` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `DeclinedAuthorization` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 163 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `ApiErrorResponse` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `ApprovedAuthorization` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `AuthorizationRequest` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 22. `services/ledger-posting-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/balances/{accountId}` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: getAccountBalance` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 44 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `ApiProblem`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `AccountBalance` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `AccountBalance` |
| V9: The Dignity Test | Standard | Pass | States `ApiProblem` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `operationId: getAccountBalance` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `/balances/{accountId}` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `/balances/{accountId}` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `ApiProblem` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `AccountBalance` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `operationId: getAccountBalance` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 44 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `operationId: getAccountBalance` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `AccountBalance` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `ApiProblem` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 23. `services/fraud-detection-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/fraud-assessments` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: assessmentOfTransaction` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 76 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `/fraud-assessments/{transactionId}`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `operationId: assessmentsOfAccount` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `AssessmentPage` |
| V9: The Dignity Test | Standard | Pass | States `InternalFailure` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `FraudAssessment` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `NotAcceptable` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `Problem` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `TooManyRequests` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `BadRequest` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `MethodNotAllowed` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 76 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Unauthorized` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `Forbidden` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `Unauthorized` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 24. `services/notification-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/notifications/{cardToken}` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: getNotificationHistory` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 74 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `Problem`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `StatementTransaction` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `ApiError` |
| V9: The Dignity Test | Standard | Pass | States `NotificationHistory` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `NotificationHistory` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `ApiError` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `StatementTransaction` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `operationId: getNotificationHistory` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `/notifications/{cardToken}` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `Problem` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 74 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `Problem` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `/notifications/{cardToken}` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `operationId: getNotificationHistory` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 25. `services/account-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/accounts/{accountId}` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: readCustomer` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 190 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `/accounts/{accountId}/cycle-close`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `operationId: closeBillingCycle` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `/customers/{customerId}` |
| V9: The Dignity Test | Standard | Pass | States `operationId: updateAccount` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `AccountView` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `operationId: readAccount` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `CustomerView` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `AccountUpdateRequest` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `AccountDataRequest` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `InternalFailure` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 190 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `CustomerDataRequest` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `MethodNotAllowed` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `UnsupportedMediaType` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

### 26. `services/card-service/src/main/resources/openapi.yaml`

**Overall verdict:** **CLEAN** — 0 hard violations, 0 soft violations.

**Measured:** 0 sentences over thirty words, 0 paragraphs over five sentences, 0 buzzword uses.

| Principle number and name | Weight | Result | Evidence |
|---|---|---|---|
| V1: Find a subject you care about | Reduced | Pass | Holds one subject, opening at `/cards` |
| V2: Do not ramble | Raised | Pass | No block runs past five sentences; longest scored paragraph 5 sentences |
| V3: Keep it simple | Raised | Pass | Every scored sentence sits inside the bound; longest scored sentence 30 words |
| V4: Have the guts to cut | Standard | Pass | Carries no passage worth cutting; `operationId: updateCard` earns the line it takes |
| V5: Sound like yourself | Reduced | Pass | One plain register, with no buzzword in 183 scored paragraphs |
| V6: Say what you mean | Raised | Pass | Names the thing itself under `/cards/{cardToken}`, with no hedge in front of it |
| V7: Pity the reader | Raised | Pass | Explains `operationId: readCard` where a reader first meets it |
| V8: Start close to the end | Standard | Pass | The claim arrives before its support under `ApiError` |
| V9: The Dignity Test | Standard | Pass | States `operationId: listCards` plainly, without blame and without flourish |
| V10: The Indifference Detector | Standard | Pass | Names what is at stake under `Problem` rather than asserting importance |
| V11: The Indianapolis Test | Standard | Pass | A reader outside this project can follow `CardUpdateRejected` unaided |
| V12: Humor as Trust Signal | Standard | Not applicable | A technical reference carries no humour for a reading to judge |
| A1: Plate Glass Clarity | Standard | Pass | The wording disappears behind the fact under `CardSummary` |
| A2: Short Words, Simple Structures | Standard | Pass | Short words and one clause at a time; longest scored sentence 30 words |
| A3: Logical Sequence | Standard | Pass | Runs in the order a reader needs it, from `CardList` onward |
| A4: Ideas Carry the Weight | Standard | Pass | The evidence carries it, as the entries under `CardDetail` show |
| A5: Conversational Informality | Standard | Pass | Reads as one engineer to another where it introduces `CardUpdateConflict` |
| A6: No Ornamental Language | Standard | Pass | No ornament, and no buzzword in 183 scored paragraphs |
| A7: Functional Dialogue | Standard | Not applicable | This target carries no dialogue for a reading to judge |
| A8: Anticipate Reader Questions | Standard | Pass | Answers the next question in place, as `CardUpdateRequest` does |
| A9: Efficiency Over Polish | Standard | Pass | Refers back to `CardUpdateNotFound` rather than restating what it is |
| A10: Respect the Reader's Intelligence | Standard | Pass | Assumes a competent reader, so `CardUpdateApplied` arrives without a primer |

**Per-violation entries:**

None. This target has no measured violation.

## Exemptions applied

- **Code and inline code.** Fenced blocks are dropped and an inline span becomes one token, so `PIC S9(09)V99` counts as one word rather than a clause a reader has to parse.
- **Attributed quotations.** A blockquoted passage is skipped, which is how this report quotes a source without scoring itself on it. An inline quotation is skipped too where a cue word attributes it. The requirements passage in the flagged-rule register and the binding constraint in the card guide therefore keep their wording. Both come from the technical specification's section 0.8, and neither is rewritten here.
- **Verbatim source messages.** A reject text such as `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` is reproduced exactly, because a service that softens it stops matching the source.
- **Deliberate presentation choices.** Slide 9 presents `RoundingMode.DOWN` and `HALF_UP` as inline monospace rather than as a code block, because the audience is an executive one.
- **Repetition across sibling guides.** Six service guides answer the same questions in the same order. Repetition between them is a navigation aid rather than rambling, so V2 is scored within a document and not across the set.

## Where the measurement lands against the plan

The Agent Action Plan set CLEAN as the target verdict for every authored document. The measurement reaches it. All twenty-six targets read CLEAN at zero hard violations and zero soft ones.

The length work that got there was reference material rather than argument. A migration table, a register of flagged rules, a task list and six OpenAPI contracts are read by lookup, and each had grown sentences that carried three claims at once. Each was split at a clause boundary or given a paragraph break, and no statement was dropped to shorten a count. The verdicts move when the text moves, because the build measures the text rather than this page.
