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

### Decide the ceiling of the reserved cycle exposure

- **Change:** Decide what the authorization service's own reservation columns should do when the exposure they hold passes ten integer digits. Either store them at that width, as the posting path now does, or widen them and declare the deviation.
- **Where:** `domain/CycleExposureReservation` sums the effective pending figure and the approved amount, and `pending_cycle_credit` and `pending_cycle_debit` at `src/main/resources/db/migration/V7__cycle_exposure_reservation.sql:L67` and `:L70` are `NUMERIC(12,2)`. Eleven approvals at the `PIC S9(09)V99` maximum inside one reservation lifetime take the sum past that width, and the store would then answer SQLSTATE 22003 and the endpoint would answer 500 rather than a decision. No copybook field sits behind either column, so unlike `category_balance` this one may legitimately be widened, as `velocity_window.total_amount` was.
- **Check:** Twelve consecutive maximum-magnitude approvals on one account inside the reservation lifetime must answer a decision every time, and no request may answer 500.
- **Behavior change:** Either arm changes what the credit-limit rule reads at that magnitude, which is why the owner picks the arm. Register item 66 records the same store on the posting path.

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

### Remove the stored card verification value

- **Change:** Answer the question the formal exception is open on, and where the answer is yes, run the destruction procedure. The discovery work is done: the procedure is written, and it names every artifact each edit touches. What remains is a decision only a project owner can make.
- **Where:** `CARD-CVV-CD PIC 9(03)` at `app/cpy/CVACT02Y.cpy:L7` is part of the card record and `app/data/ASCII/carddata.txt` carries a value for all 50 rows, which is why the column exists. No application path reads it: there is no accessor, the field carries `@JsonIgnore`, `toString` withholds it, `applyUpdate` never changes it, and no event schema declares a property for it.
- **Why it is still here:** Sections 0.4.1 and 0.6.4 of the plan require the value to be persisted and never emitted, and section 0.2.2 places payment-card industry controls outside the engagement. Removal is therefore not available to this engagement, so the value is held under a formal exception instead. `card-service/V11__card_verification_value_exception.sql` records it, with four compensating controls: encryption at rest, no reader anywhere, build-enforced audit of both, and this procedure.
- **Procedure:** `card-platform/services/card-service/README.md`, under "Stored card verification value", carries it as one migration and four edits. The migration drops the constraint and the column and restates the table comment. The edits reach `CardEntity`, three card-service tests, four equivalence contracts and four documents. It also states what the procedure does not reach. The fifty literals in an applied `V2__seed.sql` stay, because editing an applied migration stops Flyway and they are values the read-only source repository already carries in the clear.
- **Check:** After the migration, `CardRepositoryIT` still loads all 50 seeded rows, `CardholderDataExposureTest` requires the absence, and `CardSeedEquivalenceTest` compares the remaining columns against the fixture. `SubjectDataGovernanceContractTest` moves from holding the exception to holding the removal. Confirm no equivalence assertion reads the value, which none does today.
- **Behavior change:** None to any business rule, because nothing reads the column. It changes what the card record holds, so the traceability matrix records `CARD-CVV-CD` as a deliberate omission rather than a mapped column. Departure D4 in [business rule flags](business-rule-flags.md) carries the full argument.

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

- **Change:** Define an authoritative event or operator workflow for a `card_xref` row that no card update reaches: one missing from a replica, and one belonging to a card nobody edits. Two replicas remain, in the authorization and card services; the account service now holds the account-to-customer pair in `account_customer_link` and no card, so a repairing owner has one fewer copy to reconcile and one copy that cannot be reconciled by card number at all.
- **Where:** The card service now compares its own copy on the path it owns. `domain/CardCrossReferenceReconciler` runs inside the transaction of every `PUT /cards/{cardToken}` that commits, confirms an agreeing mapping, corrects a diverged one against `CARD-ACCT-ID PIC 9(11)` at `app/cpy/CVACT02Y.cpy:L6`, and carries the three outcomes on `carddemo.card.xref.agreed`, `carddemo.card.xref.corrected` and `carddemo.card.xref.missing`. Two gaps are left for an owner. A card nobody updates is never compared, so a divergence on an untouched card stands until something touches it — a sweep or a scheduled comparison is the shape that closes it. A card number holding no replica row cannot gain one here: `XREF-CUST-ID PIC 9(09)` at `app/cpy/CVACT03Y.cpy:L6` is mandatory and `app/cpy/CVACT02Y.cpy` carries no customer identifier, so the row is reported and not created. The authorization replica is refreshed in its observation column alone, because `CardUpdated` carries a masked number and that table is keyed on the full one.
- **Check:** Move one replica row off its card's account, update that card, and read `carddemo.card.xref.corrected`. Then do the same to a card the workflow never updates and confirm nothing corrects it, which is the residual this task owns.
- **Behavior change:** Operational correction only. No source-equivalent card-update result moves.

### Give the retention sweeps an operational review

- **Change:** Review each shipped retention horizon against a real data-protection policy, and decide which tables a regulator would forbid this platform to purge at all.
- **Where:** All six services now run a scheduled `domain/RetentionSweep`, and every horizon any schema comment declares has a caller: `equivalence-tests/.../RetentionSweepContractTest.everyDeclaredHorizonHasASweepThatAppliesIt` fails the build in both directions, on a declared horizon nothing applies and on a purge nothing declares. Seventeen horizons are covered across the six schemas, including the business tables a security review found unenforced — `fraud_assessment`, `velocity_window` and `rejected_transaction`. What no engineer can settle is whether the shipped numbers are the right numbers: every one is a demonstration default, and `notification` additionally sweeps three history tables that hold cardholder-facing records.
- **Check:** State each horizon's owner and legal basis. Prove that a sweep never removes a row a later replay or audit needs, and that a failed sweep delays deletion without failing a delivery.
- **Behavior change:** Data lifecycle only. Changing a horizon changes no business rule.

### Give the decision table the bounded operator reads its indexes were built for

- **Change:** Add two bounded, authenticated reads over `authorization_decision` — one account's recent decisions, and one operator's — and restore an index with each, in the same change as the query that reads it.
- **Where:** `AuthorizationDecisionRepository` declared four read methods with no production caller: `findByTransactionId`, `findByAccountIdOrderByDecidedAtDesc`, `findByActorOrderByDecidedAtDesc` and `findByOrderByDecidedAtDesc`. Two composite indexes existed to serve them, `ix_authorization_decision_account_decided` and `ix_authorization_decision_actor`, so every authorization on the hot write path maintained two index entries nothing read. A performance review found both, and `src/main/resources/db/migration/V18__authorization_decision_index_pruning.sql` withdrew the methods and the indexes together. `ix_authorization_decision_decided_at` stays, because the bounded retention delete reads it. What was withdrawn is the unread implementation, not the requirement: an operator investigating a disputed decline has a real question, and today the only answer is direct SQL.
- **Check:** Follow the keyset cursor `card-service` already ships rather than an offset page, so no deep page is reachable. Decide the authorization model first — a decision row names an actor, so one operator reading another's decisions is a policy question, not a query question. Prove the index is used by the shipped statement rather than assumed, and confirm no external audit tooling was depending on the two dropped indexes before this lands.
- **Behavior change:** Additive. No decision the service takes changes, and the retention sweep is unaffected.

### Give the account service the retryable status the card and ledger services answer

- **Change:** Map a dependency the account service cannot reach to `503` and document it, as `card-service` and `ledger-posting-service` already do. Measured live against the running stack with the database paused: `GET /cards/{cardToken}` answers `503` with `The card store is not reachable, so this request may be retried`. `GET /accounts/{accountId}` answers `500` with `This request could not be completed. Nothing was changed.`
- **Where:** `CardApiExceptionHandler.onDatastoreUnreachable` and `LedgerApiExceptionHandler.onDatastoreUnreachable` name `DataAccessResourceFailureException`, `CannotCreateTransactionException` and `QueryTimeoutException`. `AccountApiExceptionHandler` has no equivalent arm, so those three reach its fault arm.
- **Check:** Pause the database container and call each read of the account service. Each must answer `503`, `openapi.yaml` must declare that status on all four operations, and `ApiProblem.title` must admit its reason phrase.
- **Behavior change:** Yes for a caller that retried on `500`. The account document currently declares `500` and the service answers `500`, so nothing published is untrue today.

## Security migration

### Review the pinned Pod Security profile version

- **Change:** Decide, on a schedule rather than on a failure, whether `pod-security.kubernetes.io/enforce-version` should move past `v1.25`, and move `audit-version` and `warn-version` with it.
- **Where:** `card-platform/deploy/k8s/00-namespace.yaml` pins all three modes of the `restricted` profile to `v1.25`.
- **Why it is still here:** `latest` was refused because it lets a control-plane upgrade tighten the policy under a manifest nobody edited. A cluster upgrade then becomes six Pods failing admission for a reason that is not in this repository. The cost of pinning is the mirror image. A control the Kubernetes project adds to `restricted` for a good reason does not reach this namespace until somebody raises the version. Nothing detects that, because a policy that is not applied produces no signal.
- **Procedure:** Read the policy-versioning table in the Kubernetes Pod Security Standards documentation for changes to `restricted` after the pinned version. Raise all three labels together, apply to a cluster running the target release, and confirm the six Deployments still admit. The six already set `runAsNonRoot`, `allowPrivilegeEscalation: false`, all capabilities dropped, `seccompProfile: RuntimeDefault` and a read-only root filesystem, so a new requirement is what a failure would name.
- **Check:** `kubectl -n carddemo rollout restart` the six Deployments after raising the version and confirm every Pod reaches ready. A refusal names the control that was added, which is the whole point of moving deliberately.
- **Behavior change:** None until the version is raised. Raising it can refuse a Pod that previously admitted, which is the intended effect.

### Publish, sign and verify the six service images

- **Change:** Publish the six service images to a registry the deployment owns, pin every Deployment by the digest the push reports, sign each image, and verify the signature at admission.
- **Where:** `deploy/k8s/kustomization.yaml` holds the six references in one place and `deploy/k8s/40-authorization-service.yaml` through `45-card-service.yaml` carry `imagePullPolicy: Never`. `.github/workflows/ci.yml` builds the six images in its container stage and scans them, its push-only provenance stage records a statement over the six Java archives, and neither pushes an image. The two upstream images in `10-kafka.yaml` and `20-postgres.yaml` are already digest-pinned and show the shape the six should reach.
- **Why it is still here:** A locally built image has no manifest digest: `docker inspect` reports an empty `RepoDigests` list until the image is pushed, so a digest reference would name something no node could resolve. Publishing, signing and verifying need a registry, a signing key and an admission controller, and section 0.2.2 of the plan places production hardening out of scope while section 0.8.5 records the instruction to keep infrastructure configuration minimal and swappable. The residual risk is that the six tags stay mutable: bytes tagged `carddemo/authorization-service:1.0.0-SNAPSHOT` on one node are whatever an operator loaded there, and no manifest notices a different build under the same name.
- **Procedure:** Add a push step to the container stage, scoped to the events that should publish. Replace each `newTag` in `kustomization.yaml` with `digest: sha256:<digest>` and each `newName` with the registry, which `kustomize edit set image` does one entry at a time. Remove `imagePullPolicy: Never` from the six Deployments once a digest is pulled rather than preloaded, because a digest that is never pulled is never verified. Sign in the pipeline with the same identity the provenance statement already uses, and add an admission policy that refuses an unsigned image.
- **Check:** Apply with a digest and confirm the Pods run. Retag different bytes under the old tag and confirm nothing changes, which is the property the digest buys. Present an unsigned image and confirm admission refuses it.
- **Behavior change:** Deployment only. No service code, schema or event changes, and the Compose path for the local demo is untouched.

### Build the data-subject export and erasure workflow

- **Change:** Automate the documented subject-request procedure, so a cardholder's request can be answered without an operator and proved afterwards. The discovery half is delivered. `docs/data-model.md`, under "Subject data: purpose, retention, export and erasure", carries the retention policy, every store a request reaches, the four-hop lookup, the export rules and the ordered erasure. What is missing is the entry point, the propagation and the completion record.
- **Where:** Nothing implements one today: no controller, no event, no service and no repository on any of the six services performs an export or an erasure, and a security review found the account schema promising "erase on request" anyway. Four migrations replaced every one of the six statements that had made a promise with the actual position. `account-service/V7__account_customer_link.sql` covers the `customer` table and `account_customer_link`, and `account-service/V8__subject_request_posture.sql` covers the `customer.social_security_number` column. `authorization-service/V11__subject_request_posture.sql` and `card-service/V4__subject_request_posture.sql` cover the two `card_xref` replicas, and `notification-service/V6__subject_request_posture.sql` covers `cardholder_context`. An erasure has to reach five stores across four schemas. `account_service.customer` is the only table on this platform that describes an identifiable person, and `account_service.account_customer_link` pairs an account with a customer. The other three are the `card_xref` replicas the authorization and card services hold, and `notification_service.cardholder_context`, the read model that carries ten cardholder fields keyed by account. `services/card-service` additionally holds the card row itself.
- **Why it is still here:** It is a platform capability rather than a comment. It needs an authenticated and authorized entry point, and a legal-hold exception so a record under investigation is not destroyed. It needs an auditable tombstone proving what was removed and when, and an idempotent event that reaches every replica and read model exactly once however often it is redelivered. It also needs a completion report a data-protection officer can read, which the documented procedure lists as six recorded facts and deliberately keeps outside this platform. Section 0.2.2 of the plan excludes production hardening from this engagement, and a workflow that erases financial records is not something to ship half-built: an erasure that reaches four of five stores is worse than none, because it reports success.
- **Two things the automation inherits from the procedure:** the order, and the two stores no statement reaches. Erasing a projection before the row that feeds it lets a later `CustomerContextChanged` restore it, so the propagation has to be ordered rather than fanned out. A retained broker record and a backup age out rather than being erased, so a completion has to either wait out `KAFKA_LOG_RETENTION_HOURS` or record which backups predate it.
- **Procedure:** Add the entry point to the account service, which owns the customer row. Publish a `CustomerErased` event keyed on the customer identifier, and have every service holding a replica consume it with the same processed-event guard its other listeners use. Write the tombstone in the account schema before publishing, in the same transaction, so the outbox pattern this platform already uses carries the propagation. Report completion by reading each replica back rather than by counting acknowledgements.
- **Check:** Erase one fixture customer. Then query all five stores directly and find no row, and find one tombstone naming all five. Redeliver the event and confirm nothing changes and nothing fails. Run the equivalence suite afterwards and confirm the remaining forty-nine fixture customers are untouched.
- **Behavior change:** New capability. No existing business rule changes, and no authorization outcome changes, because an erased customer's accounts are erased with them.

### Deliver the rendered cardholder alert

- **Change:** Give the notification service a delivery port, so a rendered alert reaches the cardholder it was written for.
- **Where:** `services/notification-service/src/main/java/com/carddemo/notification/domain/NotificationService.java` renders a document and returns it to its caller. Every caller is a Kafka listener, and every listener discards the returned text. A security review found the stored record described as a delivery attempt while no transport existed, and `db/migration/V5__rendered_not_delivered.sql` corrected that: the column is `rendered_at`, the table's `outcome` column carries `RENDERED_NOT_SENT`, and `ck_notification_log_outcome` permits no other value. The service's own README lists mail and short-message gateways under deliberate non-additions.
- **Why it is still here:** Section 0.1.1 of the plan specifies this service as "rendering a cardholder alert", so a transport is a new capability rather than a correction. It also needs decisions no engineer can make alone: which channel a cardholder consented to, and what happens to an alert that cannot be delivered. It also needs a limit on how long an undelivered alert may be retried before it is stale enough to be misleading.
- **Procedure:** Add a port interface beside `messaging/EventPublisherPort`, so the transport is swappable the way the event bus is. Widen `ck_notification_log_outcome` to permit `SENT` and `FAILED`, and widen `NotificationLogEntity` in the same commit — the constraint is deliberately narrow so that widening it is visible. Record the attempt count and the failure reason, and route an exhausted alert to the dead-letter topic this service already writes.
- **Check:** Render an alert and prove it arrived at a test transport. Prove that a transport failure leaves `outcome` reporting the failure rather than reporting success, and that no row ever reports `SENT` when the transport was never called.
- **Behavior change:** New capability. Every existing row keeps its meaning, because `RENDERED_NOT_SENT` stays a permitted value.

### Define legacy credential import

- **Change:** Define how plaintext `USRSEC` records become encoded service credentials during a real migration. Of every task on this list, this is the one whose case for changing is strongest.
- **Where:** The source compares the stored and supplied password directly at `app/cbl/COSGN00C.cbl:L223`. Hashing that comparison would have broken equivalence, so it was preserved and kept out of the authorization path. The platform's own credentials are already hashed: each service accepts `{bcrypt}` at a cost of at least ten or `{pbkdf2@SpringSecurity_v5_8}` and refuses every other encoding at start-up, which is why the open work is the import rather than the encoder.
- **Check:** Import a test record, authenticate with the original password, and confirm no plaintext password survives anywhere. An unmigrated record must fail only in the way the chosen import strategy intends.
- **Behavior change:** Yes, but to credential storage rather than to any authorization rule, so no equivalence assertion moves.

### Automate the cross-service half of a card-token rotation

- **Change:** Turn the three manual re-key statements of a card-token rotation into something a run performs. The state a rotation needs is now delivered. `V10__card_token_version_and_rotation.sql` gives `card` a `card_token_version` and a `card_token_provenance`, and `PanMasker.previousCardToken` derives what a stored row was called. `CardTokenReconciler` refuses to move a derived token unless `CARD_TOKEN_ROTATION_ENABLED` says so, and each moved card leaves a `card_token_rotation_mapping` row. What has no owner is applying that mapping to the two other schemas.
- **Where:** `services/card-service/README.md`, section *Rotating the card-token key*, carries the procedure an operator follows today. Its three statements update `statement_transaction.card_token` and `notification_log.card_token` in the notification service and `authorization_decision.card_token` in the authorization service. A fourth step reissues any `SCOPE_CARD` authority in `USER_SCOPES`. Each statement is idempotent and each is run by hand.
- **Why it is still here:** No service reads another service's schema, which is the boundary this platform exists to draw, so the automation cannot simply be a query. Two designs were rejected in the decision log for this reason: a card-service job reaching into the other two databases, and a mapping event each consumer applies. AAP section 0.2.2 also puts operational scaffolding of this kind out of scope for a demonstration.
- **Procedure:** Give each holding service an operator-only route that applies a mapping it is handed, rather than one that reads the card schema. Authorize it as its own scope, bound it to one rotation identifier, and make it idempotent on the value it matches so a repeat changes nothing. Then sequence the three calls from a script that reads the mapping once. Decide at the same time whether the notification read model should be rebuilt by replaying `transaction.posted` instead, which needs no mapping at all.
- **Check:** Rotate the key on a stack holding history for a card, then run the automation. A caller must reach that card's rows through `GET /notifications/{cardToken}` using a token derived under the new key. Then apply the mapping in reverse and reach them again under the old one. Neither direction may leave a row carrying a token nothing derives.
- **Behavior change:** None to any business rule. Card identity is ADDITIVE in full: `app/cpy/CVACT02Y.cpy` declares no token field and no source program derives one.

### Close the window a card-token rotation leaves open

- **Change:** Make a rotation atomic enough that no caller sees a card with no history. A rotation rewrites `card.card_token` in the card service and the two read-model columns are re-keyed afterwards. Between those two moments a history request for a re-keyed card finds no rows. The card service is restarted for the rotation, so its own window is a start-up; the notification service is serving throughout.
- **Where:** `CardTokenReconciler.reconcile` rewrites the card rows, `card_token_rotation_mapping` records each move, and the three statements in `services/card-service/README.md` close the gap. `NotificationHistoryController` is the route that answers nothing during the window.
- **Why it is still here:** Closing it needs one of two things. One is a dual-read in the notification service, which would have to hold a previous key it has no other reason to hold. The other is a coordinated cutover across two services, which is the automation the task above describes. A demonstration platform is a single operator on a loopback stack, so the window is minutes and nobody is watching.
- **Procedure:** Prefer making the reader tolerant over making the write atomic. Let the notification service resolve a supplied token through a mapping it was handed, for as long as a rotation is open, and answer from the row either token names. Retire the tolerance when the rotation closes. Record which rotation the tolerance belongs to, so an open one cannot be inherited by the next.
- **Check:** Apply a rotation half way, so the card rows are rewritten and the read models are not. A history request using a token derived under either key must then answer with the same rows. Today the new token answers with none.
- **Behavior change:** None. No source program serves a card history at all; `app/cbl/CBSTM03A.CBL` writes a statement file per cycle.

### Encrypt the cardholder stores at rest, and back them up

- **Change:** Enforce the encryption both persistent claims now declare, and give the volumes a backup and restore route. Half of this is delivered: each claim carries `carddemo.io/requires-encryption-at-rest` with what the volume holds, and `deploy/k8s/overlays/encrypted-storage` binds an encrypted storage class to both in one command. What is missing is enforcement and backups. Nothing at apply time refuses a base applied without the overlay, `docker-compose.yml` mounts plain named volumes by design, and no backup route exists at all.
- **Where:** The exposure is concentrated rather than spread. `services/card-service/src/main/resources/db/migration/V1__schema.sql` holds fifty full card numbers and fifty card verification values. `services/authorization-service` and `services/card-service` each hold a card-keyed `card_xref` replica. `services/account-service` holds the only table on this platform that describes an identifiable person, including a Social Security Number and a government-issued identifier. The account service no longer replicates card numbers at all: `V7__account_customer_link.sql` replaced that replica with the account-to-customer pair.
- **Why it is still here:** Section 0.2.2 of the plan excludes production hardening from this engagement by name — automated backup and restore, and "payment-card industry controls beyond the single documented masking deviation". Storage-level encryption and volume backup are deployment properties of the database and its volumes rather than application code. This platform ships one demonstration compose file and a minimal set of manifests, which the same section describes as deliberately minimal and swappable. A security review recorded the gap, and recording it here is the honest answer rather than shipping an encryption story a demo cannot support.
- **Procedure:** Provide a `StorageClass` whose provisioner encrypts, name it in the overlay, and apply the overlay rather than the base. `deploy/k8s/README.md`, under "Encryption at rest", carries the command and the sequence. It also carries the part that cannot be undone: a bound claim's class is immutable, so the overlay has to precede the first apply.
- **Then the two things the overlay does not cover:** An admission policy that refuses an unannotated or unencrypted claim turns the declaration into enforcement. A backup schedule with a proven restore covers the logical dump that volume encryption never reaches. Decide at the same time whether the card verification value should still be stored at all. `docs/business-rule-flags.md` entry D4 carries that decision, and the procedure that answers it yes is in the card service guide.
- **Check:** Read the raw volume or a snapshot of it with the database stopped, and find no card number, no verification value and no Social Security Number in cleartext. Restore a backup into an empty environment and run the equivalence suite against it.
- **Behavior change:** None. Encryption at rest is invisible to every query, every event and every business rule.

### Bound what a permanent duplicate-suppression claim costs

- **Change:** Decide how `processed_event` is kept affordable now that nothing deletes a row from it.
- **Where:** All six services hold that table. The migrations that made a claim permanent are authorization `V21`, ledger `V11`, fraud `V10`, notification `V8`, account `V11` and card `V9`, and each one names this task. One row holds a 36-character identifier, a topic name and a timestamp.
- **Why it is still here:** The size a bound is worth paying for is a measured volume, and this platform has no production volume to measure. A demo run costs kilobytes. The work also needs a partition-maintenance job, which is operational scaffolding section 0.2.2 of the plan excludes from this engagement.
- **Procedure:** Measure the growth rate first, from the count of consumed events per day. Then choose between two bounds that keep a claim permanent. Hash partitioning on `event_id` keeps the `(event_id, consumed_topic)` unique key intact and bounds how deep each partition's index gets. Moving older data to a cheaper tablespace bounds cost rather than volume. Prefer the task below wherever a side effect already has a natural key, because it removes the claim instead of storing it more cheaply.
- **What not to do:** Range partitioning on `processed_at` looks like the obvious answer and is the wrong one. PostgreSQL requires the partition key inside every unique constraint, so the key would become `(event_id, consumed_topic, processed_at)`. One claim would then be unique per partition rather than for ever, and detaching an old partition would expire the claim the horizon used to expire.
- **Check:** Write a claim, then confirm the same delivery is still suppressed after whatever the chosen bound does to the row. Read the catalogue and find `retention=permanent; purge_key=none` on every one of the six tables.
- **Behavior change:** None, for either bound. A claim that is stored differently suppresses the same redelivery.

### Suppress a duplicate from the business record rather than from a claim

- **Change:** Where a side effect has a natural key, let a unique constraint on that key refuse the second application, and stop writing a claim for it.
- **Where:** Three consumers already have such a key. Ledger `TransactionEntity` and fraud `FraudAssessmentEntity` both answer `isNew` true for every instance, so a second insert of one transaction identifier refuses. Notification `statement_transaction` is keyed on card token and transaction, so a replay rewrites one row with the same values. Two paths have none: the account service adds an amount to a balance and a cycle accumulator, and `notification_log` takes a fresh identifier per render.
- **Why it is still here:** A constraint has to name something the schema already carries and that one event maps onto once. The ledger writes `transaction_category_balance` and `account_balance_projection` from one `TransactionAuthorized`, and neither carries the transaction identifier. `account` carries no column to collide on either. Adding one is a data-model change in four schemas rather than a setting, and the [decision log](decision-log.md) records why the claim carries the guarantee for now.
- **Procedure:** Add the transaction identifier to each table a side effect writes, inside a unique constraint. Let the constraint refuse the second application. Have the consumer recognise that violation as a duplicate and acknowledge, rather than fail. Keep the claim for the consumers whose effect still has no key.
- **Check:** Point a consumer group at the earliest offset in an environment whose claim store is empty, and confirm no balance, category balance or statement row moves.
- **Behavior change:** A duplicate would be refused by the datastore rather than skipped by the consumer, so a redelivery arrives as a constraint violation the consumer has to read as harmless.

### Bound the request rate across the cluster rather than per replica

- **Change:** Move the five request-rate ceilings behind a counter every replica shares, so a ceiling bounds the service rather than one process.
- **Where:** `services/*/src/main/java/**/config/RequestRateCeilingFilter.java` holds its windows in a `ConcurrentHashMap` inside the process, bounded at 10,000 keys. `deploy/k8s/4*.yaml` each declare one replica today, so the per-instance ceiling and the service ceiling are the same number; raise `replicas` to three and a source reaching every replica gets three times the nominal rate. The five limits are `API_RATE_WINDOW_SECONDS`, `API_RATE_REQUESTS_PER_WINDOW`, `API_RATE_WRITE_REQUESTS_PER_WINDOW`, `API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW` and `API_RATE_CONCURRENT_REQUESTS`, documented in `.env.example` and mirrored in `deploy/k8s/30-configmap.yaml`.
- **Why it is still here:** A shared counter needs a store every replica reaches. The plan's dependency inventory in section 0.5.1 names no cache or key-value store and excludes one by name for the fraud velocity window, on the ground that a demonstration should not need another container. Adding one for this control alone would contradict that, and the honest per-instance behaviour is documented in each service README, in the filter's own class comment and in the [decision log](decision-log.md).
- **Procedure:** Choose where the counter lives: a shared store, or an ingress layer that counts before any replica is reached. Keep the filter as the in-process floor rather than replacing it, so a service reached directly is still bounded. Keep the stage tags, because `carddemo.<service>.requests.throttled` is what makes the control visible.
- **Check:** Run three replicas behind one address, drive one source past the per-replica ceiling, and confirm the service refuses at the configured rate rather than at three times it. Confirm a replica reached directly still refuses.
- **Behavior change:** A caller that was reaching several replicas is refused sooner. No business rule and no event changes.

### Activate the trusted-proxy profile in a deployment that terminates at a proxy

- **Change:** Add `trusted-proxy` to `SPRING_PROFILES_ACTIVE` and set `TRUSTED_PROXY_ADDRESSES` to an expression matching the addresses your proxy sends from. The profile and the two filters it corrects are delivered; what remains is a deployment activating it.
- **Where:** Every service now states `forward-headers-strategy: none` in its `application.yml` and ships `src/main/resources/application-trusted-proxy.yml` beside it. The profile sets Tomcat's `native` strategy, points `server.tomcat.remoteip.internal-proxies` at the variable, and names the three headers the valve reads. `deploy/k8s/30-configmap.yaml` names the profile and the variable in the comment beside the cross-site keys. Two controls still read the connection rather than a header: `config/RequestRateCeilingFilter` keys its ceilings on `ServletRequest.getRemoteAddr()`, and `config/CrossSiteRequestFilter` compares `Origin` against the scheme, host and port the request arrived on.
- **Why it is still here:** No shipped path has a proxy in front of it. Compose publishes a loopback port and the cluster reaches each pod through a Service, so activating the profile in either would trust a header nothing rewrites. Section 0.2.2 of the plan excludes ingress and service-mesh configuration from this engagement, so the proxy this profile exists for is one a deployment adds.
- **Procedure:** Deploy the proxy and have it overwrite rather than append `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host`, and drop `Forwarded`. Activate the profile, set the expression to the proxy's own addresses and nothing wider, and confirm a request arriving from anywhere else keeps its own address. Then re-run the checks below, because both controls change meaning the moment the profile is active.
- **Check:** Behind the proxy, confirm two callers on different addresses hold separate rate-limit counters rather than sharing the proxy's, and confirm a state-changing request carrying the client-facing `Origin` is admitted while a foreign one is refused. Reaching a container directly, confirm a request declaring a forwarded address is still counted under its own.
- **Behavior change:** Ceilings begin to bound callers rather than the proxy, and the cross-site origin comparison begins to test the browser's origin rather than the address the proxy dialled. A proxy that appends leaves a client-supplied address in the position the valve reads, which no setting here can detect, so the three checks above are the acceptance criteria.

### Give a stack trace somewhere safe to go

- **Change:** Add a diagnostic sink that carries a redacted stack trace, and route the failure paths to it in addition to the ordinary line they already write.
- **Where:** Five exception handlers and one domain service record a failure as its type and the types of its causes: `services/card-service/src/main/java/com/carddemo/card/api/CardApiExceptionHandler.java`, `services/card-service/src/main/java/com/carddemo/card/domain/CardUpdateService.java`, `services/fraud-detection-service/src/main/java/com/carddemo/fraud/api/FraudApiExceptionHandler.java`, `services/ledger-posting-service/src/main/java/com/carddemo/ledger/api/LedgerApiExceptionHandler.java` and `services/notification-service/src/main/java/com/carddemo/notification/api/NotificationApiExceptionHandler.java`. None passes the throwable to the logger, because an attached throwable is rendered with its message and a message quotes the value a constraint refused, the statement a timeout cancelled, or the data-source URL with its user.
- **Why it is still here:** A second sink is a deployment concern rather than application code. It needs its own destination, its own retention and its own access control, and section 0.2.2 of the plan excludes that class of hardening from this engagement. This platform ships one structured console appender, so there is nowhere for a trace to go that is not the ordinary log.
- **Check:** Fail one request against a paused database. The ordinary line names the failure type and no value; the diagnostic record carries the frames with every message redacted; and only the operators who are meant to read the second one can.
- **Behavior change:** None to any business rule. What changes is how much detail an operator can reach, and from where.

### Close the two infrastructure image exceptions before they expire

- **Change:** Remove `.trivyignore.yaml` by removing the reason it exists. Move both pinned infrastructure images to references whose packages carry the published fixes, then delete the entries the move makes unnecessary.
- **Where:** `.trivyignore.yaml` holds 28 entries and every one expires on 2026-11-30. Fifteen are Go standard-library findings compiled into `usr/local/bin/gosu` in `postgres:18.4`, one of them critical. Thirteen are Alpine packages and bundled Java libraries in `apache/kafka:4.2.1`. The digests live in `docker-compose.yml`, `deploy/k8s/10-kafka.yaml` and `deploy/k8s/20-postgres.yaml`, and the image stage of `.github/workflows/ci.yml` reads them from there.
- **Why it is still here:** Section 0.5.1 of the plan pins both versions. It states why each newer image is deliberately not used, so raising a version is not a change this engagement may make. The alternative the review named is what shipped: a dated exception per package. Each entry states whether it can claim non-reachability, and each is accepted against a demonstration posture rather than a production one. Twenty-eight known-fixable findings therefore sit in a deployed image today.
- **Procedure:** Read the current upstream tags and find the first one whose package list carries the fixes. Update the tag and the digest in all three files together, because the image stage fails when the two deployment paths disagree. Run the stage and delete every entry that no longer suppresses anything. Where an entry is still needed, restate its expiry with the reason it moved rather than extending the date silently.
- **Check:** Scan both references with no exception file and find zero fixable high or critical findings. The canary step of the image stage fails at that point, which is the signal the exceptions have become unnecessary; delete the file and the step together. Then start the Compose stack and confirm the broker and the database still come up healthy.
- **Behavior change:** Infrastructure versions only. No service code, schema or event changes, though a major upgrade of either image is a compatibility question of its own.

### Answer the history secret findings where the commits that carry them live

- **Change:** Have the owner of this repository triage the 113 findings the history holds, rotate anything real, and shrink the reviewed baseline to whatever survives that review.
- **Where:** `.gitleaks-baseline.json` records all 113 by fingerprint with every value redacted. The scheduled scan of `.github/workflows/ci.yml` reads it, and no other scan does. The findings sit on 39 commits dated 2025-10-25 to 2026-08-08, none of them reachable from this branch.
- **Why it is still here:** Nothing in this working tree can rotate a credential on a ref it does not own. The commits belong to unrelated branches of a shared sample repository, which the scanner reads because it reads every ref. The triage that produced the baseline read every one of the 113 findings and found none reachable. Nine Kubernetes-manifest values decode to placeholder wording, and 72 are short bearer-token examples in documentation. That is enough to stop the gate failing on every push and not enough to close the question.
- **Procedure:** Ask the repository owner which branches those commits belong to and whether any value was ever live. Rotate anything that was, at the service that issued it, and record the rotation outside this repository. Then regenerate the baseline with `gitleaks git . --config card-platform/.gitleaks.toml --redact --report-format json --report-path card-platform/.gitleaks-baseline.json` and commit the smaller file.
- **Check:** Run the scheduled scan against the regenerated baseline and find it clean. Withhold one entry and confirm the canary step reports exactly that fingerprint, which is what proves the baseline still excuses only what it names. Confirm every remaining entry reads `REDACTED`.
- **Behavior change:** None to any service. A smaller baseline narrows what the scheduled scan excuses, so a finding removed from the file fails that scan until the commit carrying it is gone.

### Bring the presentation's three pinned libraries under the advisory review

- **Change:** Review the deck's three content-delivery pins for advisories on the same schedule the Maven graph is reviewed on, and raise a pin when one is published.
- **Where:** `presentation/executive-summary.html` pins reveal.js 5.1.0, lucide 0.460.0 and mermaid 11.16.1, each by version and by SHA-384 digest. The `supply-chain` stage of `.github/workflows/ci.yml` runs a scanner over the Maven dependency graph, which is where every other dependency of this platform lives. A browser library referenced by URL in one HTML file is in no graph that scanner reads.
- **Why it is still here:** A security review found the deck's Mermaid pin carrying nine advisories, and nothing in this build would have reported it. The pin was moved and every byte of all three libraries is now digested. That is a stronger answer to a tampered CDN and no answer at all to a vulnerability in the library itself. Closing it properly needs a scanner that reads a URL rather than a manifest, and a decision about what a finding in a slide deck should do to a build. Section 0.2.2 of the plan excludes production hardening, and a deck opened by one presenter is a narrower exposure than a deployed service.
- **Procedure:** Record the three pins in one machine-readable place, which the contract test already reads for two of them. Add a stage that resolves each pin against an advisory database and reports at the same severity threshold the image scan uses. Where a pin moves, recompute its digest, walk all sixteen slides in a browser, and update the digest the presentation test reads.
- **Check:** Plant a known-vulnerable version of one pin and confirm the stage reports it. Restore the pin and confirm the stage passes. Confirm the deck still draws all three diagrams and all seventeen icons afterwards.
- **Behavior change:** None to any service. A deck pin that moves is a visual change, which is why the check ends in a browser.

### Deliver the authenticated Transport Layer Security profile for the local stack

- **Change:** Add a Compose profile that encrypts and authenticates all three local transports. The stack could then be run by a second operator, or on a host shared with something else.
- **Where:** `docker-compose.yml` states the posture the current stack runs under and configures the three unencrypted forms: `SERVER_SSL_ENABLED` false, `KAFKA_SECURITY_PROTOCOL` `SASL_PLAINTEXT`, and `sslmode=require` on all six datasource URLs. The four values that change are already passed through rather than hard-coded. `deploy/k8s/30-configmap.yaml` shows what each becomes: `SASL_SSL`, `sslmode=verify-full` against a mounted authority, and Transport Layer Security on both ports.
- **Why it is still here:** The gap is a certificate authority rather than configuration. Every value the profile needs is already a variable, and none can be filled without a local authority, a certificate per container and a trust store each client mounts. This repository publishes no private key on purpose, so that material has to be generated on the operator's machine. Section 0.2.2 of the plan excludes production hardening and section 0.8.5 asks for minimal, swappable infrastructure, which a demonstration minting seven certificates is not. The posture is stated and mechanically enforced instead, over the nine synthetic fixtures.
- **Procedure:** Generate a local authority and one certificate per container into a git-ignored directory, the way the database already generates its own on first start. Bind-mount the authority into every service and the certificates into the database and the broker. Add a Compose profile that sets `SERVER_SSL_ENABLED` and `MANAGEMENT_SSL_ENABLED` true, with the keystore variables the shared block already passes through. The same profile sets `KAFKA_SECURITY_PROTOCOL` to `SASL_SSL` and every datasource to `sslmode=verify-full` against the mounted authority. Set `HEALTHCHECK_SCHEME` to https in the same profile, because a probe speaking the wrong scheme reports a healthy container unhealthy.
- **Check:** Start under the profile and confirm all eight containers reach healthy. Read one service over https with the authority and confirm it answers; read it over http and confirm it does not. Capture the broker connection and confirm no password is legible. Confirm the database refuses a client presenting no authority. Then start without the profile and confirm the unencrypted stack still works, because the profile is an addition rather than a replacement.
- **Behavior change:** Transport only. No service code, schema, event or business rule changes, and the certificates stay out of version control.

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

Three items were measured while resolving review findings. Two of them were partly resolved in the course of those fixes, and each says below what is delivered and what is left. The third is untouched. Each names the reason anything was left.

### Give notification and authorization a refused-diagnostic series

**The ordering half of this item is done and is no longer future work.** The notification and authorization recoverers each incremented their terminal counter before handing the record to the dead-letter route. A diagnostic the broker refused therefore still read as dead-lettered, and the series reported more records than the topic held. Both now increment after the route returns, which is what the ledger and fraud recoverers already did. The `ERROR` line naming the record and its four sanitized components is still written either way, so a refusal is never silent.

What remains is the second half, and it is an addition rather than a correction.

- **Change:** Add a counter for a diagnostic the broker refused, in the notification and authorization services, so a refusal is counted rather than only logged.
- **Where:** `CountingRecoverer` in `services/notification-service/src/main/java/com/carddemo/notification/config/KafkaConsumerConfig.java` and in `services/authorization-service/src/main/java/com/carddemo/authorization/config/KafkaConsumerConfig.java`. Each now counts only the published case. The ledger and fraud services carry both cases as one series tagged `outcome`, with values `published` and `failed`, and the account service carries two separately named series. Either shape would work here; picking one is part of the metric-naming unification below, which is why this was not folded in.
- **Check:** Point the recoverer at a topic its principal cannot write. The dead-lettered series must stay at zero and the new refusal series must move by one.
- **Behavior change:** A new series. Nothing an existing series reports changes.

### Decide whether this platform should ingest a daily transaction feed

**The documentation half is delivered.** The ledger guide now states that the feed-reject path has no runtime caller and is a parity and test path only. No reader has to hunt for a caller that was never intended. Only the decision and the implementation remain, and both are capability questions rather than corrections.

- **Change:** Decide whether this platform should ingest a daily transaction feed at all. If it should, add the entry point that supplies a `FeedTransaction`.
- **Where:** `domain/RejectRecorder` reproduces `2500-WRITE-REJECT-REC` at `app/cbl/CBTRN02C.cbl:L446-L465` and writes the 430-byte reject row plus one `TransactionDeclined`. Its input type is `FeedTransaction`, because refusing a `TransactionAuthorized` would reverse a decision the authorization service owns. No delivered listener or route supplies a feed record, and `ledger_rejected_transaction_count` is 0 in the published parity results for exactly that reason.
- **Check:** A delivered entry point produces one reject row and one declined event for a source-invalid feed record.
- **Behavior change:** Adding an ingestion path adds a capability. It changes nothing that exists today.

### Hold the two rollback-model relays to the await that makes them durable

**This section previously asked what to do if card's publisher port stopped blocking.** It never blocked: `EventPublisherPort.publish` answers `CompletionStage<Void>` in all four services that declare the seam, card's at `messaging/EventPublisherPort.java:L66`. The durability rests on the relay rather than on the port, and the item below is what that leaves open. Extending the durable column to these two relays is the [task above](#extend-the-durable-dead-letter-obligation-to-the-remaining-two-relays).

- **Change:** Keep a test on each rollback-model relay that fails if its abandonment diagnostic is ever dispatched without being awaited inside the transaction that abandoned the row.
- **Where:** `services/card-service` and `services/ledger-posting-service`. Each awaits its diagnostic inside the one transaction of a sweep that spans a send, so a refusal rolls back both the abandonment and the attempt just recorded and the row returns to the claim query with its attempt count unchanged. Remove the await and the row goes terminal with its only record nowhere, which no counter and no column would show. The other three relays cannot lose it that way, because `dead_letter_state` holds `REQUIRED` until an acknowledgement arrives.
- **Check:** Refuse the dead-letter publication and assert the row is claimable again with its attempt count unchanged.
- **Behavior change:** None. It pins a property both relays already have.

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

- **Change:** Either derive the bucket unit from `carddemo.fraud.risk.velocity-window-minutes`, so a configured width is honoured literally. Or confirm that a span covering the configured width and at most one bucket more is the intended reading.
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

### Decide whether publication should become exactly once, or stay at least once

- **Change:** Confirm that at-least-once publication with consumer-side suppression is the intended guarantee, or adopt transactional publication with `read_committed` consumers across the platform.
- **Where:** `services/*/src/main/java/**/outbox/OutboxRelay.java` and every consumer's `processed_event` claim. The fraud relay now resolves a send inside the tick that issued it, which removes the long window a duplicate used to arrive through. What remains belongs to the protocol: a broker that appends a record and loses the acknowledgement leaves the relay to attempt again, and `enable.idempotence` does not span two `send` calls. Measured on a frozen broker, one of three rows reached the topic twice with both copies carrying the same `eventId`; the [decision log](decision-log.md) records the measurement and [event flow](event-flow.md) states the guarantee.
- **Check:** Freeze the broker mid-send, restore it, and confirm that every extra copy carries an `eventId` already on the topic and that the consuming service holds one `processed_event` marker for it.
- **Behavior change:** Transactional publication would add a transactional producer to every relay and `read_committed` to every consumer. It still would not make a database write atomic with a broker write, so both the outbox and the marker transaction would stay.

## Work the Rules documentation review surfaced

One item was measured while reconciling the Rule 1 and Rule 2 documents against the delivered code. It is a real gap in the platform rather than a wording problem, so it belongs here instead of in a document.

### Extend the durable dead-letter obligation to the remaining two relays

- **Change:** Give the card and ledger-posting outbox relays the durable terminal obligation the authorization, account and fraud relays now carry, so an abandoned row of any service names itself on the dead-letter topic.
- **Where:** `services/card-service` and `services/ledger-posting-service`. Each needs the two columns of `V5__outbox_dead_letter_state.sql` in the fraud service, and the `owesDeadLetter` and `markDeadLetterPublished` pair on its own `OutboxEventEntity`. Each also needs a finder ordered by last attempt, and an owed-diagnostic pass at the head of its relay tick. Three of the five relays answer this way today and two do not, which is why the shipped migration headers say so rather than claiming the platform is uniform. The notification service is not in this list and never will be: it publishes nothing, so it holds no `outbox_event` table and no relay.
- **Check:** Drive one row of each service past `MAX_DELIVERY_ATTEMPTS` with the broker refusing, restore the broker, and confirm a diagnostic naming that row reaches `carddemo.dead-letter` on a later pass.
- **Behavior change:** None to any successful path. It adds two columns and one indexed read of no rows per pass to each of the two services. It turns an abandoned row from a log line that dies with the container into a record an operator can find.

**The authorization relay's terminal diagnostic is delivered and no longer a task here**. This section previously asked whether `authorization-service` should publish a governed `DeadLetterEnvelope` for a row it gives up on. The evidence was that its relay held no dead-letter topic, template or envelope. It holds all three today: `outbox/OutboxRelay.publishDeadLetter` sends one envelope to `carddemo.kafka.topics.dead-letter` naming the row through `failedEventId` and `failedEventType` and carrying no field of the payload, and the obligation that survives a refusal is the `dead_letter_state` column `owesDeadLetter` reads. A row keyed by a transaction identifier is named under the aggregate identifier `00000000000`, because the dead-letter document accepts eleven digits, so no shape of row is left unnamed.

## Work the backend review pass surfaced

### Drain the authorization service's retention sweep the way the other five now do

- **Change:** Give `RetentionSweep` the drain the other five services carry: loop each bounded delete until it returns fewer rows than its ceiling, under a finite per-table deadline.
- **Where:** `card-platform/services/authorization-service/src/main/java/com/carddemo/authorization/domain/RetentionSweep.java`. The shape to copy is in the ledger, fraud, card, notification and account services, each of which holds a `PURGE_BATCH_SIZE`, a `MAX_TABLE_DURATION` and one `transactionTemplate.execute` call inside a `while (true)` that breaks on a short batch.
- **Why now is not necessary:** Every one of the four deletes is already bounded, at `MARKER_PURGE_LIMIT` rows, so no single statement can grow with the table and nothing here can lock a schema for long. What the sweep does not do is come back for a second batch within the same run, so a schema that gathers more than that ceiling in an hour keeps a remainder until the next hour. At the volumes a demonstration reaches the ceiling is never met, and the AAP sets no throughput target to measure this against.
- **Check:** Insert more than `MARKER_PURGE_LIMIT` expired rows in one table, run one sweep, and confirm the table is empty afterwards rather than holding a remainder.
- **Behavior change:** None that is observable through any endpoint or event. It changes only how quickly expired rows leave the schema.

### Give Mockito its agent instead of letting it self-attach

- **Change:** Declare the Byte Buddy agent on the Surefire command line so Mockito's inline mock maker no longer attaches an agent to a running virtual machine.
- **Where:** `card-platform/pom.xml`, in the Surefire plugin configuration, and `.github/workflows/ci.yml` if a job needs the same argument. Mockito documents the `-javaagent` form under its inline mock maker.
- **Why now is not necessary:** The build succeeds. Each Surefire execution that mocks writes four `WARNING:` lines to its own output, thirteen occurrences across the reactor, saying an agent was loaded dynamically and that a future release will disallow it by default. None is a Maven `[WARNING]`, so the zero-warning build is unaffected, and nothing fails until a Java release turns the default off.
- **Check:** Run `mvn -B -ntp clean verify` and confirm the log carries no `A Java agent has been loaded dynamically` line and the test counts are unchanged.
- **Behavior change:** None. It changes how the mocking library instruments classes, not what any test asserts.

### Watch for the Kafka client's use of a restricted memory API

- **Change:** Track the Kafka client's migration off `sun.misc.Unsafe`, and raise `kafka-clients` when a release that no longer reaches for it is available.
- **Where:** `card-platform/pom.xml`, where `kafka-clients` is pinned to 4.2.1 to match the `apache/kafka:4.2.1` broker image both deployment paths run. Raising the client means raising the image in `docker-compose.yml`, `deploy/k8s/10-kafka.yaml` and the Testcontainers literals together, because a matched pair removes a class of demo-day failure.
- **Why now is not necessary:** The warning the earlier review recorded for `sun.misc.Unsafe::invokeCleaner` does not appear in the current build at all. A sweep of the verify log finds no occurrence of `sun.misc`, `invokeCleaner`, `restricted method` or `terminally deprecated`. It may return on a later Java release or a different code path, which is why this is recorded rather than closed.
- **Check:** Sweep a verify log for those four strings and confirm each returns nothing.
- **Behavior change:** None while nothing is raised.
## Work the configuration and infrastructure QA pass surfaced

### Publish the six service images for a remote cluster

- **Change:** Decide where a cluster that does not share this machine's Docker daemon should obtain the six images, then name that registry in the manifests and raise `imagePullPolicy` from `Never` to `IfNotPresent`, together with the credential the pull needs.
- **Where:** `deploy/k8s/40-authorization-service.yaml` through `45-card-service.yaml` name `carddemo/<service>:1.0.0-SNAPSHOT` with `imagePullPolicy: Never`. `deploy/k8s/load-images.sh` covers kind, minikube and Docker Desktop, which are the three runtimes that can be handed an image built locally, and it refuses any other context by name rather than half-supporting it. `KubernetesDeploymentContractTest.localServiceImagesMatchTheMavenVersionAndCanNeverBePulled` pins the current pair, so it moves with the decision.
- **Check:** Apply the manifests to a cluster with no access to the build machine's daemon and confirm every service Pod reaches `Running` without a preceding load step.
- **Behavior change:** No behaviour changes inside a service. It changes where an image comes from, and it introduces a registry credential the demonstration currently does not need.

### Keep the pinned action commits current

- **Change:** Adopt a mechanism that raises the seven action pins deliberately — a configured dependency-update tool that proposes one change per release, or a recurring review the project owner performs — and record which of the two is wanted.
- **Where:** seven action references are pinned, and each is named twice. It appears once as a `uses` key, with its release in a comment beside it, and once as a `PINNED_ACTIONS` entry in `ContinuousIntegrationWorkflowContractTest`. That entry also holds how many times the reference is used.

  | Action reference | Commit | Release | Used | File |
  |---|---|---|---:|---|
  | `actions/checkout` | `3d3c42e5aac5ba805825da76410c181273ba90b1` | v7.0.1 | 9 | `.github/workflows/ci.yml` |
  | `actions/upload-artifact` | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | v7.0.1 | 8 | `.github/workflows/ci.yml` |
  | `github/codeql-action/init` | `5595ccaf912efad79be6eef63a5619ff05969be3` | v4.37.6 | 1 | `.github/workflows/ci.yml` |
  | `github/codeql-action/analyze` | `5595ccaf912efad79be6eef63a5619ff05969be3` | v4.37.6 | 1 | `.github/workflows/ci.yml` |
  | `actions/dependency-review-action` | `2031cfc080254a8a887f58cffee85186f0e49e48` | v4.9.0 | 1 | `.github/workflows/ci.yml` |
  | `actions/attest-build-provenance` | `4d101475d8b20a2381f78447822ac1eab6504dd8` | v4.2.2 | 1 | `.github/workflows/ci.yml` |
  | `actions/setup-java` | `b6effb05e454b25005698d916606bdc6ffcbf961` | v5.7.0 | 1 | `.github/actions/setup-build-toolchain/action.yml` |

  Raising one reference is three edits made together: the `uses` key, the release comment beside it, and the `PINNED_ACTIONS` entry. The two CodeQL references share one commit, so a CodeQL release moves four values at once. `actions/setup-java` is the only reference outside the workflow, and the eight workflow steps that need a toolchain call the composite action instead of naming it. Nothing in this repository watches for a newer release. The [decision log](decision-log.md) records why a commit is named rather than a tag.
- **Check:** After adopting a mechanism, confirm that a new release of one action produces a proposal changing the `uses` key, the release comment and the `PINNED_ACTIONS` entry together. Confirm too that `ContinuousIntegrationWorkflowContractTest` fails while any one of the three still holds the old value.
- **Behavior change:** No. A pinned commit keeps running exactly what it runs today until someone raises it, which is the property the pin exists for.

### Review the published toolchain floors and the two pinned image tags

- **Change:** Re-exercise the stack on a newer Docker Engine, Docker Compose and OpenSSL, then raise the floors the guides publish. Decide at the same time whether `apache/kafka` and `postgres` should move.
- **Where:** the toolchain table in `card-platform/docs/onboarding.md` and the same table in `card-platform/README.md`, plus the prerequisite block in each of the six service guides. The two image tags and their digests live in `card-platform/docker-compose.yml` and under `card-platform/deploy/k8s/`.
- **Why it is still here:** Two of the five versions are refusals: the enforcer plugin rejects a build outside `[25,26)` and `[3.9.16,3.10.0)`. The other three constrain nothing here, so they are published as the versions the delivered stack was exercised on. That is honest, but it has no ceiling and nothing in the build notices a floor falling years behind. The image tags are a separate case. The broker tag is held equal to the Kafka client version the build resolves, so raising one means raising both.
- **Procedure:** Install the newer versions, run `CI=true mvn -B -ntp clean verify` and `docker compose up -d --build --wait`, then read every management port. Raise the floor in all eight documents together, because `RuleThreeDocumentationContractTest` asserts each one carries it. For the images, raise the client version in `pom.xml` and the broker tag together, refresh both digests, and confirm the Testcontainers literals still name a tag that exists.
- **Check:** The reactor, the Compose stack and the end-to-end authorization path all pass on the newer versions before the documents change, not after.
- **Behavior change:** None from raising a floor. A broker or database image change can move behaviour, which is why the equivalence suite runs before the tags do.

### Raise the runner image before it reaches end of life

- **Change:** Move the six `runs-on` keys to the next generally available Ubuntu image once `ubuntu-24.04` approaches the end of its life on GitHub's schedule. Confirm first that the Docker and Compose versions the integration, equivalence and container stages depend on are present on the new image.
- **Where:** the six `runs-on: ubuntu-24.04` keys in `.github/workflows/ci.yml`, and `RUNNER_IMAGE` in `ContinuousIntegrationWorkflowContractTest`, which asserts all six agree and refuses `ubuntu-latest`.
- **Check:** Run the whole workflow on the new image and read the integration and container stages, which are the two that start Compose. A missing or older Compose is what breaks first.
- **Behavior change:** No, provided the new image carries the same container tooling. It is listed because a retired image stops the pipeline outright rather than degrading it.

## Work the performance review surfaced

### Assign an archival owner to the ledger transaction table

- **Change:** Name the records-retention owner of the deployment, have that owner fix the statutory period a posted transaction is kept for, and choose the mechanism: archive the range then delete it, or detach a monthly partition. Then schedule whichever was chosen, and re-issue the `transaction` table comment with the period and the owner in place of `ARCHIVAL OWNER: UNASSIGNED`.
- **Where:** `card-platform/services/ledger-posting-service/src/main/resources/db/migration/`, as a new migration re-issuing the three `COMMENT ON TABLE` statements `V10__ledger_retention_owner.sql` wrote. The range key is `proc_ts`, which this service assigns at posting and which either mechanism would scan. `domain/RetentionSweep` is where a schedule would go, and it deliberately does not name this table today.
- **Why now is not necessary:** Nothing is broken and nothing is unbounded by accident. `transaction` grows by one row per posted transaction and is never deleted from, which is the correct behaviour for a financial record and what the source does: `app/cbl/CBTRN02C.cbl:L562-L579` writes the record and no program in `app/cbl/` removes one. At demonstration volume the table holds the three hundred rows of `app/data/ASCII/dailytran.txt` and a handful more. The decision this task asks for is not a technical one the platform is entitled to make: how long a posted transaction is retained is fixed by the jurisdiction the deployment operates in, and deleting one early is a worse failure than keeping it too long.
- **Check:** Read the three table comments and confirm each names an owner and a period rather than `UNASSIGNED`. Confirm `RetentionSweepContractTest.everyDeclaredHorizonHasASweepThatAppliesIt` still passes: the moment a comment names a real `purge_key` column beside a window, that test requires a sweep that applies it, which is the guard that keeps a stated period from being decorative.
- **Behavior change:** None until a schedule is added. `V9` changes no column, no index and no constraint; it records who owes the decision.

## Work the integration reconciliation pass surfaced

One item was measured while reconciling the five producing relays against each other and left outside
the scope of that pass, because closing it changes a service no finding named.

### Give the ledger relay the publisher port the other four producing services declare

- **Change:** Declare `messaging/EventPublisherPort` in `ledger-posting-service` with a Kafka adapter behind it, and inject the port into `outbox/OutboxRelay` in place of the template it holds today. Four of the five producing services answer this way; this one does not.
- **Where:** `services/ledger-posting-service/src/main/java/com/carddemo/ledger/outbox/OutboxRelay.java` holds `@Qualifier("ledgerEventKafkaTemplate") KafkaTemplate<String, Object>` and sends from two call sites, one for the business event and one for the abandonment diagnostic. The adapter to copy is `services/fraud-detection-service/src/main/java/com/carddemo/fraud/messaging/KafkaEventPublisher.java`, which checks the eleven-digit key and the two producer reliability settings at construction and hands the event record to the schema-validating serializer.
- **Check:** The relay's own tests keep every existing assertion about what reaches the broker client, because the adapter sits over the same template. Add the sibling assertion that the relay declares no field of a broker type, which every other producing service's test already makes.
- **Behavior change:** None. It moves one dependency behind the seam that exists so a managed event service can replace Kafka. The ledger was previously excused on the grounds that nothing there publishes outside its relay, and that is equally true of the fraud service, which now declares the port.

### Hand the six service archives from the compile stage to the container stage

- **Change:** Upload `card-platform/services/*/target/*.jar` from the compile stage of `.github/workflows/ci.yml`, and restore them in the container stage in place of the `mvn -B -ntp -DskipTests package` step it runs today. Add the download action to `PINNED_ACTIONS` in `ContinuousIntegrationWorkflowContractTest`, and add its artifact name to `EXPECTED_ARTIFACTS`.
- **Where:** `.github/workflows/ci.yml`, where the container stage's `Package the six service Java Archive files` step states in a comment why it packages rather than restoring, and `equivalence-tests/src/test/java/com/carddemo/equivalence/ContinuousIntegrationWorkflowContractTest.java`, which holds the pin register.
- **Why it was not done:** Every `uses:` key in that workflow names a full commit SHA with the release beside it, and the contract test reads that pin back, because a tag is a movable reference. Adding a download action means adding a commit this repository has to be able to verify rather than copy from memory, and no verified pin for one was available when the performance review was closed. The remaining duplication is bounded: the reactor is packaged a second time inside a single job, against the eighteen minutes of repeated test execution that review removed.
- **Check:** The restored directory holds exactly one `*.jar` per service, because the Boot plugin also leaves a `*.jar.original` beside it and each `Dockerfile` copies `target/*.jar` and refuses more than one archive. Assert that before the image build, so the failure names the artifact rather than arriving from inside Docker.
- **Behavior change:** None to any service. The images would carry the classes the compile stage checked instead of an identical set compiled again.

## Work the Rule 5 prose measurement surfaced

One item was measured while validating this repository's prose against Rule 5. It is left open because
closing it edits prose that is currently accurate rather than fixing a defect.

### Bring the targets the prose report marks NEEDS WORK to CLEAN

- **Change:** Shorten every sentence that runs past thirty words, and split every paragraph that runs past five sentences. `docs/prose-validation.md` publishes the count per target, names the worst offender in each, and gives a worked rewrite for it. The report records no hard violation anywhere, so the work is length alone.
- **Where:** Every target the prose report's summary table marks NEEDS WORK. This file holds the largest single share of over-length sentences, and `services/authorization-service/README.md` the next largest.
- **Check:** `PresentationAndProseContractTest` re-measures all nineteen targets on every build. Edit a target, run the equivalence module, then update the verdict and the two counts the report publishes for it. The test fails while the report and the measurement disagree.
- **Behavior change:** No. Every edit is to prose, and each statement's accuracy is held by the tests that already read these documents.

## Informational register items

Some register entries explain the source without suggesting a change. Items 18 through 22 document specification conflicts, abandoned menu intent, unreachable role logic, stale identity moves, and over-allocated presentation arrays. Five appended items read the same way. Item 32 records that five batch programs credited with loading data write nothing. Items 35 and 36 record a copied comment block and an out-of-order default in the card expiry edits. Item 37 records seven message constants that are declared and never reached. Item 40 records that the report screen submits the report procedure rather than the posting job.

## Work the observability review pass surfaced

### Harmonise the event-type tag key across services

- **Change:** Choose one spelling for the tag key naming an event type, and use it in all six services.
- **Where:** Five services tag `eventType`, declared for example at `services/authorization-service/src/main/java/com/carddemo/authorization/config/ObservabilityConfig.java`. The notification service tags `event.type`, declared in its own `ObservabilityConfig`. Each guide names the key its own service uses, so no guide is wrong today.
- **Check:** `LiveMeterInventoryContractTest` permits both spellings and requires each to be named in the guide of the service using it. Narrow its allow-list to the surviving spelling once the rename lands, so the retired one cannot return.
- **Behavior change:** Yes, for anyone reading the series. Renaming a tag key ends the old series and starts a new one, so a dashboard or alert rule grouping by the retired key stops matching. That is why it is a decision for an owner rather than a quiet correction.

### Decide whether a backlog should fail readiness

- **Change:** Decide whether an outbox backlog past a threshold should take a pod out of service, rather than only being reported.
- **Where:** `ReadinessHealthConfig` in the five relaying services reports `due`, `oldestDueAgeSeconds` and a `state` of `clear` or `behind`, against `BACKLOG_DUE_THRESHOLD` and `BACKLOG_AGE_THRESHOLD_SECONDS`. An abandoned row is the only condition that answers DOWN.
- **Check:** If the answer is yes, review the compose health check and both Kubernetes probes together. A backlog is usually the broker rather than the pod, so every replica would fail at once.
- **Behavior change:** Yes. It would let a broker outage remove every service instance, including the ones still recording what happened.

## Work the final integration review surfaced

### Close the one non-additive step in the released contract chain

- **Change:** Migrate `card.updated` consumers off the version-1 contract. Then either publish a version 3 that is additive over version 1, or declare version 1 unsupported once no retained record carries it.
- **Where:** `libs/event-contracts/src/main/resources/schemas/card-updated-v1.json` requires `embossedName`; `card-updated-v2.json` does not, and a producer publishes version 2. `contracts/released-contracts.json` records that step under `nonAdditiveOver`, and `SchemaBackwardCompatibilityTest.everyDeclaredNonAdditiveStepIsAClosedJustifiedSet` holds the declaration to exactly those two names.
- **Check:** The declared non-additive set holds one entry rather than two. No deployed consumer holds a document that a current producer's record would fail.
- **Behavior change:** No, for a consumer already on version 2. Yes for one still on version 1, which is the point of the task.

### Allowlist the extension keys an event may carry

- **Change:** Replace the name-based screen on the `extensions` object with a declared allowlist of permitted keys, published in the contract library beside the schemas.
- **Where:** `SensitiveEventProperties.FORBIDDEN_NAME_FRAGMENTS`, `FORBIDDEN_WHOLE_NAMES`, `CODE_CONTEXT_EXTENSION_FRAGMENTS` and `CREDENTIAL_CONTEXT_EXTENSION_FRAGMENTS` refuse every sensitive name this platform can enumerate, and every card-scheme alias for a verification value. A name nothing anticipated can still carry three digits, which no name-based rule can catch.
- **Check:** An extension key absent from the allowlist is refused on both ends of the wire. The schemas name the allowlist as the place a new key is declared.
- **Behavior change:** Yes, for a producer using an extension key nobody has declared. That is the intent.

## Work the walkthrough-weight review surfaced

### Extract the uniform cross-cutting plumbing into a shared runtime library

- **Change:** Move the seven classes every service repeats into one library beside `libs/event-contracts` and `libs/cobol-compat`. They are `config/SecurityConfig`, `CrossSiteRequestFilter`, `RequestRateCeilingFilter`, `CorrelationContextFilter`, `SafeProducerListener`, `ReadinessHealthConfig` and `StreamNameReport`. Each service keeps its own routes in its own `apiSecurity` method.
- **Where:** The `config` package of all six services. `card-platform/pom.xml` for the new module and its banned-dependency rule. The five contract tests that assert one property across six services.
- **Why it is a task rather than a change:** AAP 0.3.1 enumerates a `config` package inside each service and names these classes there. AAP 0.4.2 fixes the module graph at two libraries and six services. AAP 0.3.3 abstracts one seam and says no other is abstracted. Amending the plan is a human decision, and `docs/decision-log.md` records why the copies stand until then.
- **Check:** Six services start. The ban on a service depending on another still fails a violating build. The five contract tests read the shared classes rather than six copies.
- **Behavior change:** No. The classes are identical today, which is what makes the move mechanical.
