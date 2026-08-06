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

### Extend the cardholder-context bootstrap beyond the fixture accounts

- **Change:** Add a controlled backfill for accounts the repository fixtures do not contain, so a real cutover starts with a complete renderer projection.
- **Where:** `V2__seed.sql` in the notification service now bootstraps `cardholder_context` with one row per fixture account, read from `app/data/ASCII/custdata.txt` and resolved through `app/data/ASCII/cardxref.txt`. That closes the demo gap. An account outside those fifty still has no row until the account service publishes its first `CustomerContextChanged`, and a real migration carries far more than fifty accounts.
- **Check:** Import a customer set larger than the fixture, then render an alert for one of the imported accounts without first editing its customer record. No render may report an absent projection.
- **Behavior change:** No authorization change. The notification read model becomes complete for a population the fixtures do not describe.

### Define cross-reference repair ownership

- **Change:** Define an authoritative event or operator workflow for a missing or mismatched `card_xref` replica.
- **Where:** `CardUpdateService` currently increments `carddemo.card.xref.divergence` and cannot reconstruct customer identifier data from the card row.
- **Check:** Repair one divergent row without changing the source-equivalent card-update result.
- **Behavior change:** Operational correction only.

### Give the retention sweeps an operational review

- **Change:** Review each shipped retention horizon against a real data-protection policy, and decide which tables a regulator would forbid this platform to purge at all.
- **Where:** All six services now run a scheduled `domain/RetentionSweep` that deletes only published outbox rows and expired processed-event markers within its own schema, so no horizon is documentation-only. What no engineer can settle is whether the shipped numbers are the right numbers: every one is a demonstration default, and `notification` additionally sweeps three history tables that hold cardholder-facing records.
- **Check:** State each horizon's owner and legal basis. Prove that a sweep never removes a row a later replay or audit needs, and that a failed sweep delays deletion without failing a delivery.
- **Behavior change:** Data lifecycle only. Changing a horizon changes no business rule.

## Security migration

### Define legacy credential import

- **Change:** Define how plaintext `USRSEC` records become encoded service credentials during a real migration.
- **Where:** The source comparison is plaintext at `app/cbl/COSGN00C.cbl:L223`; the target refuses hashes without a `{bcrypt}` prefix.
- **Check:** Import a test record, authenticate with the original password, and verify that no plaintext password remains.
- **Behavior change:** Security migration, not authorization-rule parity.

### Turn over the card-token key

- **Change:** Replace the demo card-token key with one generated for the deployment, and re-derive every token already written down. A card token is an `HmacSHA256` code taken under `CARD_TOKEN_SECRET`, so a token belongs to one key and one version and does not survive a change to either.
- **Where:** `com.carddemo.cobol.PanMasker.cardToken` derives it. Three artifacts carry tokens derived under the shipped key: the fifty `card_token` literals in `services/card-service/src/main/resources/db/migration/V2__seed.sql`, the `SCOPE_CARD` authority in `.env.example` and `deploy/k8s/30-configmap.yaml`, and any `statement_transaction`, `notification_log` or `authorization_decision` row an earlier run stored. The key itself is `CARD_TOKEN_SECRET` in `.env.example` and `deploy/k8s/31-secret.example.yaml`, mirrored to the build by the `carddemo.card-token.secret` property in `pom.xml`.
- **Procedure:** Generate a key of at least 32 characters. Raise `CARD_TOKEN_VERSION` in the same change, so the rollover is recorded in the token itself and a stored value can be told from a current one. Re-derive the fifty seed literals and the granted authority under the new key and version. Re-key or discard stored rows: the notification read model and the notification log are rebuilt from replayed events, and `authorization_decision.card_token` is a diagnostic column an operator may choose to leave at its previous version. Apply the key, the version, the seed and the authority together.
- **Check:** `CardTokenKeyContractTest` fails while any of the four artifacts still names the previous key or version, and `CardRepositoryIT.everySeededTokenMatchesTheJavaDerivation` fails while a seeded literal does not match the derivation. Both passing is the signal that the turnover is complete.
- **Behavior change:** None to any business rule. Card identity is ADDITIVE in full: `app/cpy/CVACT02Y.cpy` declares no token field and no source program derives one.

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

## Work the integration remediation surfaced

Three items were measured while resolving review findings and left deliberately outside the scope of those fixes. Each names the reason it was not folded in.

### Count a notification dead letter after its route, not before

- **Change:** Move the increment of `carddemo.notification.records.dead.lettered` to after the delegating recoverer returns, and add a separate increment for a refused publication.
- **Where:** The `CountingRecoverer` inside `services/notification-service/.../config/KafkaConsumerConfig.java` increments before it routes, so a diagnostic the broker refused still reads as dead-lettered. The ledger and fraud recoverers were corrected to count after the delegate returns, and separately before a rethrow, under review finding MN-18. Notification was not named by that finding and already had a per-record terminal series distinct from its per-attempt series, so it was recorded rather than changed.
- **Check:** Point the recoverer at a topic its principal cannot write and assert the published series stays at zero while a refusal series moves.
- **Behavior change:** None. A metric reads correctly where it previously over-reported by one on a refusal.

### Give the ledger's feed-reject path a runtime entry point, or state that it has none

- **Change:** Decide whether this platform should ingest a daily transaction feed at all. If it should, add the entry point that supplies a `FeedTransaction`. If it should not, say so in the ledger's guide so a reader does not look for a caller that was never intended.
- **Where:** `domain/RejectRecorder` reproduces `2500-WRITE-REJECT-REC` at `app/cbl/CBTRN02C.cbl:L446-L465` and writes the 430-byte reject row plus one `TransactionDeclined`. Its input type was narrowed to `FeedTransaction` under review finding CR-03, because refusing a `TransactionAuthorized` would reverse a decision the authorization service owns. No delivered listener or route supplies a feed record, so the path is exercised only by its tests.
- **Check:** Either a delivered entry point produces a reject row and one declined event for a source-invalid feed record, or the guide states the absence and the parity test remains the only caller.
- **Behavior change:** Adding an ingestion path adds a capability. Documenting the absence changes nothing.

### Revisit card's dead-letter durability if its publisher port becomes asynchronous

- **Change:** If `EventPublisherPort.publish` in the card service ever returns a future rather than blocking, replace the rollback-based durability with the explicit `dead_letter_state` column the account service uses.
- **Where:** Card's relay publishes its abandonment diagnostic inside the same `TransactionTemplate` as the abandonment itself, and its port blocks, so a broker refusal propagates and rolls the abandonment back with it. The row then returns to the claim query with its attempt count unchanged, which is why card needed no migration. Account's port returns a `CompletionStage` and its sweep catches and continues, so it needs the column. Both models are recorded in the [decision log](decision-log.md).
- **Check:** Refuse the dead-letter publication and assert the row is claimable again with its attempt count unchanged.
- **Behavior change:** None today. The note exists because the guarantee depends on a property of the port, not on the relay.

## Work the runtime QA pass surfaced

Three items were measured while resolving runtime findings and confirmed rather than changed. Each names the reason the current behaviour was kept and what a person has to decide.

### Unify the failure-metric discriminator across the six services

- **Change:** Pick one tag name for the per-attempt failure family and one name for the per-record terminal family, and apply both to every service.
- **Where:** Notification publishes `carddemo.notification.failures` discriminated by `failure.kind` and `carddemo.notification.records.dead.lettered` by the same tag. The ledger and fraud publish `carddemo.<service>.failures` discriminated by `stage` and `carddemo.<service>.dead.letters` by `outcome`. Every series is bounded and pre-registered, and each service's naming is deliberate: notification tags by what failed, the other two by the stage that failed. The [decision log](decision-log.md) records why the divergence was left in place.
- **Check:** One Prometheus query spans every service's failure count, and one spans every service's terminal count. Each service's `ObservabilityConfig` test still asserts a bounded tag set.
- **Behavior change:** A renamed series. A deployment already collecting the current names has to be updated with the code, which is why this is a task rather than a fix.

### Decide whether a broker outage should mark every container unhealthy

- **Change:** Confirm that a Kafka outage marking all six containers unhealthy is the intended operational signal, or move the broker check to a health group the Compose health check does not read.
- **Where:** `management.endpoint.health.group.readiness.include` names `kafka` in all six services, and the Compose health check probes `/actuator/health/readiness`. Liveness stays up, and Docker restarts an exited container rather than an unhealthy one, so the shipped `restart: unless-stopped` cannot loop on an outage. The [decision log](decision-log.md) records the reasoning for keeping the check in readiness.
- **Check:** Stop the broker and confirm the intended reading of `docker compose ps` and of both Kubernetes probes.
- **Behavior change:** None unless the grouping changes. Moving the check would let traffic reach an instance that cannot consume.

### Confirm the printable-text contract for cardholder-facing values

- **Change:** Confirm that refusing a non-ASCII merchant name or description is intended, or add a transliteration step ahead of rendering.
- **Where:** The notification contract constrains rendered text with `^[ -~]*$`, because the renderers reproduce the column-oriented layout at `app/cbl/CBSTM03A.CBL:L86-L159` where each field occupies fixed character positions. A record carrying a multi-byte character is dead-lettered rather than rendered misaligned.
- **Check:** Publish an event carrying an accented merchant name and confirm the intended outcome, whether that is a dead letter or a transliterated alert.
- **Behavior change:** Transliteration would put a value in a cardholder-facing alert that no source field held, which is why the decision belongs to a person.

## Informational register items

Some register entries explain the source without suggesting a change. Items 18 through 22 document specification conflicts, abandoned menu intent, unreachable role logic, stale identity moves, and over-allocated presentation arrays.