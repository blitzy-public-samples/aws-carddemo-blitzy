# Suggested Next Tasks

Every item below was discovered during this migration and left outside its scope. None is performed by this engagement. Each task states what to change, where to start, and what to verify afterwards. The source findings remain indexed in [Business Rule Flags](business-rule-flags.md).

## Correctness decisions requiring a human

### Widen the credit-limit working precision

- **Change:** Decide whether `CreditLimitRule` should stop reproducing the lost high-order digit.
- **Where:** `WS-TEMP-BAL PIC S9(09)V99` is at `app/cbl/CBTRN02C.cbl:L187`; source operands use `S9(10)V99` at `app/cpy/CVACT01Y.cpy:L8` and `L13-L14`.
- **Check:** Re-baseline the synthetic one-billion boundary. The corrected rule should decline the transaction that source-equivalent narrowing approves.
- **Behavior change:** Yes. Register item 7.

### Correct the refund sign convention

- **Change:** Define how a negative amount should affect cycle debit and available credit.
- **Where:** `app/cbl/CBTRN02C.cbl:L551` adds the negative amount; line 404 subtracts that accumulator.
- **Check:** A refund followed by another authorization must not reduce available credit unless the owner chooses that policy.
- **Behavior change:** Yes. Register item 6.

### Decide whether current balance belongs in the limit rule

- **Change:** Define the relationship between `ACCT-CURR-BAL` and the two cycle accumulators.
- **Where:** `app/cbl/CBTRN02C.cbl:L403-L407` ignores current balance.
- **Check:** Re-baseline authorization and posting equivalence against the approved business rule.
- **Behavior change:** Yes. Register item 5.

### Confirm the target meaning of reason 109

- **Change:** Confirm that an account-update failure should retry and then reach the dead-letter topic.
- **Where:** `app/cbl/CBTRN02C.cbl:L556-L558` assigns reason 109 after an earlier write, and no source statement checks it.
- **Check:** Force the target write failure and verify one governed `DeadLetterEnvelope`.
- **Behavior change:** Already additive; owner confirmation remains. Register item 9.

## Validations deliberately not added

These checks are reasonable improvements, but each changes source-equivalent outcomes.

### Add card-number checksum validation

- **Change:** Add a Luhn rule to the card validation layer.
- **Where:** `app/cbl/COCRDUPC.cbl:L193-L194` and line 784 check only 16 numeric digits.
- **Check:** Update validation and schema tests deliberately while preserving existing source messages.
- **Behavior change:** Yes. Register item 24.

### Add card-status authorization

- **Change:** Add a decline rule that reads card active status.
- **Where:** `app/cbl/CBTRN02C.cbl:L29-L57` selects six files without the card file; `app/jcl/POSTTRAN.jcl` also omits it.
- **Check:** Add an additive decline reason and keep old event schemas readable.
- **Behavior change:** Yes. Register item 3.

### Add account-status authorization

- **Change:** Add a decline rule for `ACCT-ACTIVE-STATUS`.
- **Where:** The field exists at `app/cpy/CVACT01Y.cpy:L6`, but source posting never tests it.
- **Check:** Extend the decline schema and equivalence baseline under an owner-approved rule.
- **Behavior change:** Yes. Register item 4.

## Interest and cycle ownership

### Migrate interest calculation

- **Change:** Move interest processing only after its batch boundary is approved for migration.
- **Where:** Rate fallback is at `app/cbl/CBACT04C.cbl:L415-L460`; computation and accumulation are at `L462-L470`.
- **Check:** Extend `InterestCalculationEquivalenceTest` from rate resolution to computed account results.
- **Behavior change:** Yes. Register item 11.

### Assign a production cycle-close owner

- **Change:** Decide whether a scheduler, operator, or another bounded service triggers cycle close.
- **Where:** The account endpoint reproduces only `app/cbl/CBACT04C.cbl:L353-L354`.
- **Check:** Demonstrate that every account resets before its cycle accumulators cause persistent declines.
- **Behavior change:** Operational ownership only.

## Projection and lifecycle work

### Backfill notification customer context before cutover

- **Change:** Add a controlled backfill that publishes or imports the current customer context before live traffic starts.
- **Where:** `CustomerContextChanged` keeps `cardholder_context` current after mutations, but the table starts empty and unchanged fixture customers emit no event.
- **Check:** Backfill all fixture accounts, then render a posted transaction without first editing its customer.
- **Behavior change:** No authorization change; the notification read model becomes complete at cutover.

### Define cross-reference repair ownership

- **Change:** Define an authoritative event or operator workflow for a missing or mismatched `card_xref` replica.
- **Where:** `CardUpdateService` currently increments `carddemo.card.xref.divergence` and cannot reconstruct customer identifier data from the card row.
- **Check:** Repair one divergent row without changing the source-equivalent card-update result.
- **Behavior change:** Operational correction only.

### Implement retention jobs

- **Change:** Add owned purge jobs for outbox, processed-event, fraud, notification, and diagnostic retention policies.
- **Where:** Each service migration records a retention window and purge key in table comments, but no scheduler deletes rows.
- **Check:** Purge only terminal or expired rows and preserve durable financial records.
- **Behavior change:** Data lifecycle only.

## Security migration

### Define legacy credential import

- **Change:** Define how plaintext `USRSEC` records become encoded service credentials during a real migration.
- **Where:** The source comparison is plaintext at `app/cbl/COSGN00C.cbl:L223`; the target refuses hashes without a `{bcrypt}` prefix.
- **Check:** Import a test record, authenticate with the original password, and verify that no plaintext password remains.
- **Behavior change:** Security migration, not authorization-rule parity.

## Source hygiene

The following tasks modify the read-only legacy tree. They belong to the source owner, not this engagement.

### Remove the dead duplicate copybook

- **Change:** Delete `app/cpy/UNUSED1Y.cpy`.
- **Where:** It duplicates the widths of `app/cpy/CSUSR01Y.cpy:L17-L23` and has no reference.
- **Check:** Confirm a repository-wide search finds no include before deletion.
- **Behavior change:** No. Register item 26.

### Reconcile the customer copybook fork

- **Change:** Converge `CVCUS01Y.cpy` and `CUSTREC.cpy`, then repoint the statement program.
- **Where:** The only field-name difference is at line 19; `app/cbl/CBSTM03A.CBL:L55` uses the fork.
- **Check:** Compile every including program and parse the 500-byte customer fixture.
- **Behavior change:** No intended behavior change. Register item 12.

### Remove orphan CICS definitions

- **Change:** Remove program `COCRDSEC` and transaction `CDV1`.
- **Where:** `app/csd/CARDDEMO.CSD:L211`, `L388`, and `L390`; no matching source program exists.
- **Check:** Install the resource definitions after removal.
- **Behavior change:** Dead configuration only. Register item 17.

### Correct expiry identifiers in the source

- **Change:** Correct `ACCT-EXPIRAION-DATE` and `CARD-EXPIRAION-DATE`.
- **Where:** `app/cpy/CVACT01Y.cpy:L11` and `app/cpy/CVACT02Y.cpy:L9`.
- **Check:** Compile every program referencing either field.
- **Behavior change:** No.

## Test and fixture depth

### Add a source fixture that reaches the precision boundary

- **Change:** Add an owner-approved fixed-width account and transaction fixture whose cycle value reaches one billion.
- **Where:** Current fixtures cannot reach register item 7; the suite therefore uses a synthetic record.
- **Check:** The source-derived fixture and the synthetic case must produce the same narrowed result.
- **Behavior change:** Test data only.

### Capture a controlled original processing timestamp

- **Change:** Capture original output under a known source clock and compiler configuration.
- **Where:** Current parity normalizes the hundredths field at `app/cbl/CBTRN02C.cbl:L173-L174` and the four zeros at line 701.
- **Check:** Compare all 26 timestamp characters under the controlled clock, while retaining normalized tests for ordinary runs.
- **Behavior change:** Test evidence only.

### Add an ASCII security-user fixture

- **Change:** Add a text twin for `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`.
- **Where:** The binary record follows `app/cpy/CSUSR01Y.cpy`; no file exists under `app/data/ASCII/`.
- **Check:** Load each record at 80 bytes and retire constructed signon fixtures where appropriate.
- **Behavior change:** Test data only.

## Documentation

### Correct the legacy sample-data path

- **Change:** Correct the stale `main/-/data/EBCDIC/` path in the root `README.md`.
- **Where:** The unchanged legacy instructions name it at line 65; the repository path is `app/data/EBCDIC/`.
- **Check:** Resolve every legacy sample-data link after the source owner approves the edit.
- **Behavior change:** Documentation only.

That path remains unchanged here because Rule 3 required a surgical modernization section, not unrelated edits to accurate legacy instructions.

## Informational register items

Some register entries explain the source without suggesting a change. Items 18 through 22 document specification conflicts, abandoned menu intent, unreachable role logic, stale identity moves, and over-allocated presentation arrays.