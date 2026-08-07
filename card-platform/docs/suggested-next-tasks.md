# Suggested Next Tasks

Every item below was discovered during this migration and left outside its scope. None is performed by this engagement. Each task states what to change, where to start, what to verify afterwards, and whether the change moves an outcome. The findings behind them are indexed in [Business Rule Flags](business-rule-flags.md), and the reasoning for each choice this migration made is in the [decision log](decision-log.md).

## Correctness decisions requiring a human

### Widen the credit-limit working precision

- **Change:** Widen the working precision in `CreditLimitRule` so a cycle balance at or above one billion stops losing its high-order digit. Losing that digit turns a decline into an approval, because the comparison approves whenever the limit is greater than or equal to the working value.
- **Where:** `WS-TEMP-BAL PIC S9(09)V99` at `app/cbl/CBTRN02C.cbl:L187`. Two operands feed it: `ACCT-CURR-CYC-CREDIT` at `app/cpy/CVACT01Y.cpy:L13` and `ACCT-CURR-CYC-DEBIT` at `:L14`. It is compared against `ACCT-CREDIT-LIMIT` at `:L8`, at `app/cbl/CBTRN02C.cbl:L407`. All three of those are `PIC S9(10)V99`, one integer digit wider.
- **Check:** The constructed boundary case in `AuthorizationDecisionEquivalenceTest` must flip from reproducing the source approval to declining, and every existing equivalence assertion must be re-baselined against the widened rule.
- **Behavior change:** Yes, which is why only the project owner can authorise it. Register item 7.

### Correct the refund sign convention

- **Change:** Decide the intended treatment of a negative amount, then correct the accumulator routing in `AccountBalanceUpdater` and the formula in `CreditLimitRule` together.
- **Where:** `app/cbl/CBTRN02C.cbl:L551` adds a negative amount to `ACCT-CURR-CYC-DEBIT`, making that accumulator more negative, and the credit-limit formula subtracts it at `:L404`. A refund therefore raises the tested balance and tightens the next authorization.
- **Check:** A refund followed by an authorization must leave available credit no tighter than before the refund, unless the project owner chooses that policy deliberately.
- **Behavior change:** Yes. Register item 6.

### Decide whether current balance belongs in the limit rule

- **Change:** Establish the intended relationship between `ACCT-CURR-BAL` and the two cycle accumulators, then revise `CreditLimitRule` if the intent differs from what the source does.
- **Where:** `app/cbl/CBTRN02C.cbl:L403-L407` works from the two accumulators alone and ignores the current balance entirely. Two notions of balance therefore coexist in one account record with no documented relationship between them.
- **Check:** Re-baseline the posting and authorization equivalence suites against the approved intent.
- **Behavior change:** Yes. Register item 5.

### Confirm the target meaning of reason 109

- **Change:** Confirm that turning an account-update failure into a retried consumer failure that ends on the dead-letter topic is the treatment the project owner wants.
- **Where:** `app/cbl/CBTRN02C.cbl:L556-L558` assigns reason 109 on a failed account rewrite, after the category balance has already been written, and no source statement ever inspects it. In the source it therefore changes nothing.
- **Check:** Force the account write to fail and confirm one `DeadLetterEnvelope` carrying the reason, rather than a silently swallowed failure.
- **Behavior change:** Already made and already declared. What remains is the owner's confirmation. Register item 9.

## Validations deliberately not added

These checks are reasonable improvements, but each changes source-equivalent outcomes.

### Add card-number checksum validation

- **Change:** Add a Luhn check to the card service's validation layer.
- **Where:** The source validates a card number only as sixteen numeric digits. `app/cbl/COCRDUPC.cbl:L193-L194` carries the condition name and its message, and `:L784` is the second site, which tests the filter field for numeric content alone.
- **Check:** Update `ValidationEquivalenceTest` deliberately rather than incidentally, and confirm the six verbatim card messages still reproduce character for character.
- **Behavior change:** Yes. A card number that passes today would begin failing. Register item 24.

### Add card-status authorization

- **Change:** Add a decline rule that reads `CARD-ACTIVE-STATUS`.
- **Where:** The posting program never opens the card file. Its six `SELECT` statements sit at `app/cbl/CBTRN02C.cbl:L29`, `:L34`, `:L40`, `:L46`, `:L51` and `:L57`. They name the daily feed, the transaction file, the cross-reference, the reject file, the account file and the category-balance file. `app/jcl/POSTTRAN.jcl` confirms the omission by never allocating the card dataset.
- **Check:** Extend the declined-event schema's enumerated reason list additively, and confirm `SchemaBackwardCompatibilityTest` still passes so an existing consumer keeps reading the old payload.
- **Behavior change:** Yes, and it needs a new decline reason code, which makes it an event-schema change as well. Register item 3.

### Add account-status authorization

- **Change:** Add a decline rule for `ACCT-ACTIVE-STATUS`.
- **Where:** The field exists at `app/cpy/CVACT01Y.cpy:L6` and no program tests it before posting, so a closed account still posts.
- **Check:** The same as the card-status task: an additive reason code, a passing backward-compatibility test, and a re-baselined equivalence suite.
- **Behavior change:** Yes, with the same schema consequence as the card-status task. Register item 4.

## Interest and cycle ownership

### Migrate interest calculation

- **Change:** Move interest processing only after its batch boundary is approved for migration. Two slices of the program were already carried over: `InterestCalculationEquivalenceTest` verifies the rate rules, and the account service reproduces the cycle-counter reset at `app/cbl/CBACT04C.cbl:L353-L354`. The computation itself is not migrated.
- **Where:** The computation is at `app/cbl/CBACT04C.cbl:L464-L465`, and it accumulates at `L467`. Three things a migrator meets on the way:
  - **The same precision narrowing lives here.** `WS-MONTHLY-INT` and `WS-TOTAL-INT` are both `PIC S9(09)V99` at `L168-L169`, and the total lands in `ACCT-CURR-BAL PIC S9(10)V99` at `L352`. That is the shape of register item 7 in a second program.
  - **A rate lookup that misses substitutes a literal.** `1200-GET-INTEREST-RATE` at `L415` accepts the record-not-found status alongside the normal one at `L416-L419`. It then moves the literal `'DEFAULT'` into the group key at `L436` and re-reads at `L438`. An account whose disclosure group is absent is charged the default group's rate, not zero.
  - **Fees are an empty paragraph.** `1400-COMPUTE-FEES` at `L518` carries the comment `To be implemented` at `L519` and does nothing else. Anyone migrating interest inherits a fee obligation the source never wrote.
- **Check:** Extend `InterestCalculationEquivalenceTest` from verifying rate resolution to verifying computed interest per account, including one account whose group is absent and one whose monthly total crosses the ninth integer digit.
- **Behavior change:** Yes. Register item 11.

### Assign a production cycle-close owner

- **Change:** Decide whether a scheduler, an operator, or another bounded service triggers cycle close in a real deployment. Today it is an endpoint a caller invokes, because the only source code that zeroes the accumulators sits in a program the project owner placed out of scope.
- **Where:** The account endpoint reproduces `app/cbl/CBACT04C.cbl:L353-L354` and nothing else from that program.
- **Check:** Demonstrate that every account resets before its accumulators cause persistent declines. Without a reset owner, available credit shrinks with every authorization until all of them decline, which is [pitfall 5 in the onboarding guide](onboarding.md#5-cycle-counters-need-an-explicit-reset-owner).
- **Behavior change:** Operational ownership only. No rule changes.

## Projection and lifecycle work

### Extend the cardholder-context bootstrap beyond the fixture accounts

- **Change:** Add a controlled backfill for accounts the repository fixtures do not contain, so a real cutover starts with a complete renderer projection.
- **Where:** `services/notification-service/src/main/resources/db/migration/V2__seed.sql` now bootstraps `cardholder_context` with one row per fixture account, read from `app/data/ASCII/custdata.txt` and resolved through `app/data/ASCII/cardxref.txt`. That closes the demo gap. An account outside those fifty still has no row until the account service publishes its first `CustomerContextChanged`, and a real migration carries far more than fifty accounts.
- **Check:** Import a customer set larger than the fixture, then render an alert for one of the imported accounts without first editing its customer record. No render may report an absent projection.
- **Behavior change:** No authorization change. The notification read model becomes complete for a population the fixtures do not describe.

### Define cross-reference repair ownership

- **Change:** Define an authoritative event or operator workflow for a missing or mismatched `card_xref` replica.
- **Where:** No path compares the two copies. `app/cbl/COCRDUPC.cbl` reads `*COPY CVACT03Y.` commented out at `:L356` and its three file operations at `:L1383`, `:L1428` and `:L1478` touch only the card file, so `CardUpdateService` reproduces no cross-reference access and the card service's `card_xref` replica has neither a reader nor a writer. A repairing owner also needs `XREF-CUST-ID PIC 9(09)` at `app/cpy/CVACT03Y.cpy:L6`, which the card row does not carry, and a meter of its own once it exists.
- **Check:** Repair one divergent row without changing the source-equivalent card-update result.
- **Behavior change:** Operational correction only.

### Give the retention sweeps an operational review

- **Change:** Review each shipped retention horizon against a real data-protection policy, and decide which tables a regulator would forbid this platform to purge at all.
- **Where:** All six services now run a scheduled `domain/RetentionSweep` that deletes only published outbox rows and expired processed-event markers within its own schema, so no horizon is documentation-only. What no engineer can settle is whether the shipped numbers are the right numbers: every one is a demonstration default, and `notification` additionally sweeps three history tables that hold cardholder-facing records.
- **Check:** State each horizon's owner and legal basis. Prove that a sweep never removes a row a later replay or audit needs, and that a failed sweep delays deletion without failing a delivery.
- **Behavior change:** Data lifecycle only. Changing a horizon changes no business rule.

## Security migration

### Define legacy credential import

- **Change:** Define how plaintext `USRSEC` records become encoded service credentials during a real migration. Of every task on this list, this is the one whose case for changing is strongest.
- **Where:** The source compares the stored and supplied password directly at `app/cbl/COSGN00C.cbl:L223`. Hashing that comparison would have broken equivalence, so it was preserved and kept out of the authorization path. The platform's own credentials are already hashed: it refuses any stored value without a `{bcrypt}` prefix, which is why the open work is the import rather than the encoder.
- **Check:** Import a test record, authenticate with the original password, and confirm no plaintext password survives anywhere. An unmigrated record must fail only in the way the chosen import strategy intends.
- **Behavior change:** Yes, but to credential storage rather than to any authorization rule, so no equivalence assertion moves.

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
- **Where:** Its six fields clone the widths of the security user record at `app/cpy/CSUSR01Y.cpy:L17-L23` byte for byte, with every name rewritten to an unused-prefixed one. Its version stamp is later than the copybook it clones, so it is an abandoned copy rather than the original.
- **Check:** A repository-wide search for the copybook name and for its record name must return no reference before deletion. Today the only hit is the definition itself.
- **Behavior change:** No. Nothing includes it. Register item 26.

### Reconcile the customer copybook fork

- **Change:** Converge `app/cpy/CVCUS01Y.cpy` and `app/cpy/CUSTREC.cpy`, then repoint the statement program at the surviving copy. `CVCUS01Y` was adopted as canonical here.
- **Where:** The two carry identical field lists and identical Picture clauses. The sole difference is one field name at line 19 of each, `CUST-DOB-YYYY-MM-DD` against `CUST-DOB-YYYYMMDD`. One file is indented with literal tab characters, and their version stamps are one second apart, which is what makes the pair a fork rather than a design. `app/cbl/CBSTM03A.CBL:L55` is the single binding to the non-canonical copy.
- **Check:** Every program that includes either copybook still compiles, and the customer fixture still parses at 500 bytes per record.
- **Behavior change:** None intended. The two layouts are already byte-identical. Register item 12.

### Remove orphan CICS definitions

- **Change:** Remove the Customer Information Control System (CICS) definitions of program `COCRDSEC` and transaction `CDV1`.
- **Where:** `DEFINE PROGRAM(COCRDSEC)` at `app/csd/CARDDEMO.CSD:L211`, and `DEFINE TRANSACTION(CDV1)` at `:L388` naming `PROGRAM(COCRDSEC)` at `:L390`. `COCRDSEC` is absent from all 28 members of `app/cbl/`, so neither definition can ever load a program.
- **Check:** The resource definition file still installs after the removal.
- **Behavior change:** Dead configuration only. Register item 17.

### Correct expiry identifiers in the source

- **Change:** Correct the transposed spelling in `ACCT-EXPIRAION-DATE` and `CARD-EXPIRAION-DATE`. The target column and field names already read `expiration`, and the traceability matrix records the rename.
- **Where:** `app/cpy/CVACT01Y.cpy:L11` and `app/cpy/CVACT02Y.cpy:L9`.
- **Check:** Every program referencing either field still compiles.
- **Behavior change:** No. Nothing in the source depends on the identifier text.

### Reconcile the customer sample data with the area-code table

- **Change:** Decide whether the shipped customer sample data or the area-code reference table is
  authoritative, then move one of them. This is a decision, not a defect: both sides are reproduced
  faithfully and they disagree with each other in the source.
- **Where:** `app/data/ASCII/custdata.txt` carries 100 telephone numbers across 50 customers, and 45
  of them name an area code that `app/cpy/CSLKPCDY.cpy` does not admit under
  `88 VALID-GENERAL-PURP-CODE` at L521 — 43 distinct codes, among them `002`, `034`, `050`, `075` and
  `493`. `1260-EDIT-US-PHONE-NUM` at `app/cbl/COACTUPC.cbl:L2225` is the edit that reads that
  condition, and `UsPhoneAreaCodes` holds all 490 distinct literals of the copybook with none missing
  and none added.
- **Why it matters:** The account update requires a present block to be complete, so a caller that
  reads a seeded customer, changes one field and echoes the rest back is refused on a telephone number
  it never touched, with `Phone Number 2: Not valid North America general purpose area code`. Customer
  `000000050` is one such row. The refusal is correct on both counts and still surprises anyone driving
  a round-trip update against the shipped data.
- **Check:** Whichever side moves, re-run the account validation equivalence tests and drive a
  round-trip update of every seeded customer.
- **Behavior change:** Editing the reference table would admit area codes the source refuses, which
  breaks equivalence. Editing the sample data changes fixture content the equivalence suite reads.
  Either way the decision belongs to the source owner.

## Test and fixture depth

### Add a source fixture that reaches the precision boundary

- **Change:** Add an owner-approved fixed-width account and transaction fixture whose cycle value reaches one billion.
- **Where:** No record among the 300 in `app/data/ASCII/dailytran.txt` reaches that magnitude, so the boundary of register item 7 is unreachable from the shipped data. `AuthorizationDecisionEquivalenceTest` therefore constructs the record instead of loading it, and [equivalence results](equivalence-results.md) states the gap. A fixture under `app/data/` belongs to the source owner.
- **Check:** The source-derived fixture and the constructed case must produce the same narrowed result, so that the constructed case can be read as a stand-in rather than as an assumption.
- **Behavior change:** Test data only.

### Capture a controlled original processing timestamp

- **Change:** Capture original output under a known source clock and compiler configuration, so a full-width timestamp comparison becomes possible.
- **Where:** `DB2-FORMAT-TS PIC X(26)` at `app/cbl/CBTRN02C.cbl:L159` redefines into a two-digit fractional field and a four-character remainder at `:L173-L174`, and `:L701` moves the literal `'0000'` into that remainder. The field therefore carries two significant fractional digits, and the harness truncates to hundredths before comparing.
- **Check:** Compare all 26 characters under the controlled clock. Keep the truncating comparison for ordinary runs, so a raw comparison never fails a suite for a reason unrelated to the logic under test.
- **Behavior change:** Test evidence only. Register item 8.

### Add an ASCII security-user fixture

- **Change:** Add a text twin for `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`, so signon fixtures can be loaded rather than constructed.
- **Where:** `README.md:L148` lists that member as fixed-block, 80 bytes, laid out by `app/cpy/CSUSR01Y.cpy`, and names no equivalent text file. The nine files under `app/data/ASCII/` confirm the absence: every other binary member has a text twin, and this one does not.
- **Check:** Load each record at 80 bytes against the copybook widths, then retire the constructed signon fixtures the suite builds today.
- **Behavior change:** Test data only. This task adds a file under `app/data/`, so it belongs to the source owner.

## Documentation

### Correct the legacy sample-data path

- **Change:** Correct the stale `main/-/data/EBCDIC/` path in the root `README.md`.
- **Where:** The unchanged legacy instructions name it at `README.md:L144`; the repository path is `app/data/EBCDIC/`. Line 144 is where the sentence sits after this engagement added its modernization section above it, so a reader of the original file finds the same sentence at line 65.
- **Check:** Resolve every legacy sample-data link after the source owner approves the edit.
- **Behavior change:** Documentation only.

That path stays as it is here for a scope reason, not an oversight. Correcting it has nothing to do with this migration, so it was recorded rather than folded into a scope the project owner deliberately constrained. Rule 3 also asks a contributor to fill gaps rather than edit content that is already accurate for its own audience. Apart from this one path, the legacy instructions are accurate.

## Work the integration remediation surfaced

Three items were measured while resolving review findings and left deliberately outside the scope of those fixes. Each names the reason it was not folded in.

### Count a notification dead letter after its route, not before

- **Change:** Move the increment of `carddemo.notification.records.dead.lettered` to after the delegating recoverer returns, and add a separate increment for a refused publication.
- **Where:** The `CountingRecoverer` inside `services/notification-service/src/main/java/com/carddemo/notification/config/KafkaConsumerConfig.java` increments before it routes, so a diagnostic the broker refused still reads as dead-lettered. The ledger and fraud recoverers were corrected to count after the delegate returns, and separately before a rethrow, under review finding MN-18. Notification was not named by that finding and already had a per-record terminal series distinct from its per-attempt series, so it was recorded rather than changed.
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
- **Where:** `PRINTABLE_TEXT_PATTERN`, declared as `^[ -~]*$` on `TransactionAuthorized` and `TransactionPosted` in `libs/event-contracts`, and applied again to the free-text fields of the authorization, account and customer update requests. The event records enforce it on construction, so a posted event carrying an accented character is dead-lettered rather than rendered. The reason lies downstream: the notification renderers reproduce the column-oriented layout at `app/cbl/CBSTM03A.CBL:L86-L159`, where every field occupies counted character positions. Register item D2.
- **Check:** Publish an event carrying an accented merchant name and confirm the intended outcome, whether that is a dead letter or a transliterated alert.
- **Behavior change:** Transliteration would put a value in a cardholder-facing alert that no source field held, which is why the decision belongs to a person.

## Work the fraud vertical-slice QA pass surfaced

A runtime pass over the fraud detection service left five decisions a person should confirm. The
behaviour of each is fixed and tested; what is open is the policy behind it. The [decision
log](decision-log.md) carries the reasoning for every choice named here.

### Make the velocity bucket unit finer, or accept the stated span

- **Change:** Either derive the bucket unit from `carddemo.fraud.risk.velocity-window-minutes` so a configured width is honoured literally, or confirm that a span covering the configured width and at most one bucket more is the intended reading.
- **Where:** `VelocityWindowEntity.WINDOW_BUCKET` declares the unit as one hour, `RiskScoringService` truncates an event time to it when it writes a row, and `VelocityRule` truncates the span start to it when it reads. A finer unit means more rows per account and a migration for the rows already stored.
- **Check:** Publish authorizations across a bucket boundary and confirm that the span the rule reads matches the span the configured width names.
- **Behavior change:** A finer unit narrows the history every verdict is based on, so a burst that triggers today may not trigger afterwards. That is a scoring-policy change, which is why it belongs to a person.

### Decide whether the velocity accumulator should saturate rather than widen again

- **Change:** Confirm that `NUMERIC(15,2)` is enough headroom for one bucket, or replace the ceiling with a saturating add that reports the ceiling instead of failing the statement.
- **Where:** `velocity_window.total_amount` after `services/fraud-detection-service/src/main/resources/db/migration/V3__velocity_total_headroom.sql`, the matching `VelocityWindowEntity.TOTAL_AMOUNT_PRECISION`, and the database-side addition in `VelocityWindowRepository.addAuthorization`. Fifteen digits hold ten thousand maximum-magnitude authorizations in one bucket, and `authorization_count` is an `INTEGER`, so the count reaches its own ceiling first.
- **Check:** Accumulate past the ceiling in one bucket and confirm the intended outcome, whether that is a refused statement or a saturated total.
- **Behavior change:** Saturating would report a total that is not the total, which changes what the amount threshold means at the extreme.

### Confirm that a refund raises the velocity total

- **Change:** Confirm that accumulating amount **magnitude** is the intended fraud reading, or accumulate the signed amount so a refund lowers the total.
- **Where:** `RiskScoringService` records `amount().abs()`, `VelocityRule` and `AmountAnomalyRule` compare magnitudes, and `ck_velocity_window_total_nonnegative` refuses a negative total. Nothing in `app/cbl/` counts velocity, so no source rule is behind either reading. The refund sign convention the **source** carries is a separate item, at `app/cbl/CBTRN02C.cbl:L551`, and is registered under [business-rule flags](business-rule-flags.md).
- **Check:** Publish a refund after a purchase of the same amount and confirm the intended total: `0.00` for signed accumulation, twice the amount for magnitude.
- **Behavior change:** Signed accumulation would let a refund cancel a purchase out of the window, so alternating charges and refunds would raise no amount signal at all.

### Bound the outbox publication window in the account service the way fraud now does

- **Change:** Decide whether `account-service` should adopt the relationship fraud now holds between its producer delivery window and the time budget of one relay pass.
- **Where:** `services/account-service/src/main/resources/application.yml` sets `delivery.timeout.ms` to 120000 while `carddemo.outbox.relay.max-duration-ms` is 5000, and `services/account-service/src/main/java/com/carddemo/account/outbox/OutboxRelay.java` bounds a send by the time left in the pass. That service also carries its own `publish-timeout`, so its timing is not the same shape as fraud's and a copied change would be careless. The fraud finding and its fix are recorded in the [decision log](decision-log.md).
- **Check:** Freeze the broker while a pending row is relayed, restore it, and count the records that reach the topic for that row.
- **Behavior change:** A duplicate publication is harmless to a consumer that records processed event identifiers, and every consumer on this platform does. The change lowers how often one occurs.

### Decide whether publication should become exactly once, or stay at least once

- **Change:** Confirm that at-least-once publication with consumer-side suppression is the intended guarantee, or adopt transactional publication with `read_committed` consumers across the platform.
- **Where:** `services/*/src/main/java/**/outbox/OutboxRelay.java` and every consumer's `processed_event` claim. The fraud relay now resolves a send inside the tick that issued it, which removes the long window a duplicate used to arrive through. What remains belongs to the protocol: a broker that appends a record and loses the acknowledgement leaves the relay to attempt again, and `enable.idempotence` does not span two `send` calls. Measured on a frozen broker, one of three rows reached the topic twice with both copies carrying the same `eventId`; the [decision log](decision-log.md) records the measurement and [event flow](event-flow.md) states the guarantee.
- **Check:** Freeze the broker mid-send, restore it, and confirm that every extra copy carries an `eventId` already on the topic and that the consuming service holds one `processed_event` marker for it.
- **Behavior change:** Transactional publication would add a transactional producer to every relay and `read_committed` to every consumer, and it still would not make a database write atomic with a broker write, so both the outbox and the marker transaction would stay.

## Informational register items

Some register entries explain the source without suggesting a change. Items 18 through 22 document specification conflicts, abandoned menu intent, unreachable role logic, stale identity moves, and over-allocated presentation arrays.
