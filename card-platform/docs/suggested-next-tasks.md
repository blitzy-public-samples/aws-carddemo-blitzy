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

### Remove the stored card verification value

- **Change:** Drop `card_verification_value` from the card service through a forward migration, remove the field and its mapping from `CardEntity`, remove the value from `V2__seed.sql`, and replace the `CardholderDataExposureTest` case that requires the column with one that requires its absence. Of every task on this list, this one and the credential import below are the two a security reviewer will ask about first.
- **Where:** `CARD-CVV-CD PIC 9(03)` at `app/cpy/CVACT02Y.cpy:L7` is part of the card record and `app/data/ASCII/carddata.txt` carries a value for all 50 rows, which is why the column exists. No application path reads it: there is no accessor, the field carries `@JsonIgnore`, `toString` withholds it, `applyUpdate` never changes it, and no event schema declares a property for it.
- **Why it is still here:** Sections 0.4.1 and 0.6.4 of the plan require the value to be persisted and never emitted, and section 0.2.2 places payment-card industry controls outside the engagement. The column is held by the record layout and by nothing else.
- **Check:** After the migration, `CardRepositoryIT` still loads all 50 seeded rows, `CardholderDataExposureTest` requires the absence, and `CardSeedEquivalenceTest` compares the remaining columns against the fixture. Confirm no equivalence assertion reads the value, which none does today.
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

- **Change:** Define an authoritative event or operator workflow for a missing or mismatched `card_xref` replica. Two replicas remain, in the authorization and card services; the account service now holds the account-to-customer pair in `account_customer_link` and no card, so a repairing owner has one fewer copy to reconcile and one copy that cannot be reconciled by card number at all.
- **Where:** No path compares the two copies. `app/cbl/COCRDUPC.cbl` reads `*COPY CVACT03Y.` commented out at `:L356` and its three file operations at `:L1383`, `:L1428` and `:L1478` touch only the card file, so `CardUpdateService` reproduces no cross-reference access and the card service's `card_xref` replica has neither a reader nor a writer. A repairing owner also needs `XREF-CUST-ID PIC 9(09)` at `app/cpy/CVACT03Y.cpy:L6`, which the card row does not carry, and a meter of its own once it exists.
- **Check:** Repair one divergent row without changing the source-equivalent card-update result.
- **Behavior change:** Operational correction only.

### Give the retention sweeps an operational review

- **Change:** Review each shipped retention horizon against a real data-protection policy, and decide which tables a regulator would forbid this platform to purge at all.
- **Where:** All six services now run a scheduled `domain/RetentionSweep`, and every horizon any schema comment declares has a caller: `equivalence-tests/.../RetentionSweepContractTest.everyDeclaredHorizonHasASweepThatAppliesIt` fails the build in both directions, on a declared horizon nothing applies and on a purge nothing declares. Seventeen horizons are covered across the six schemas, including the business tables a security review found unenforced — `fraud_assessment`, `velocity_window` and `rejected_transaction`. What no engineer can settle is whether the shipped numbers are the right numbers: every one is a demonstration default, and `notification` additionally sweeps three history tables that hold cardholder-facing records.
- **Check:** State each horizon's owner and legal basis. Prove that a sweep never removes a row a later replay or audit needs, and that a failed sweep delays deletion without failing a delivery.
- **Behavior change:** Data lifecycle only. Changing a horizon changes no business rule.

### Give the account service the retryable status the card and ledger services answer

- **Change:** Map a dependency the account service cannot reach to `503` and document it, as `card-service` and `ledger-posting-service` already do. Measured live against the running stack with the database paused: `GET /cards/{cardNumber}` answers `503` with `The card store is not reachable, so this request may be retried`, and `GET /accounts/{accountId}` answers `500` with `This request could not be completed. Nothing was changed.`
- **Where:** `CardApiExceptionHandler.onDatastoreUnreachable` and `LedgerApiExceptionHandler.onDatastoreUnreachable` name `DataAccessResourceFailureException`, `CannotCreateTransactionException` and `QueryTimeoutException`. `AccountApiExceptionHandler` has no equivalent arm, so those three reach its fault arm.
- **Check:** Pause the database container and call each read of the account service. Each must answer `503`, `openapi.yaml` must declare that status on all four operations, and `ApiProblem.title` must admit its reason phrase.
- **Behavior change:** Yes for a caller that retried on `500`. The account document currently declares `500` and the service answers `500`, so nothing published is untrue today.

## Security migration

### Publish, sign and verify the six service images

- **Change:** Publish the six service images to a registry the deployment owns, pin every Deployment by the digest the push reports, sign each image, and verify the signature at admission.
- **Where:** `deploy/k8s/kustomization.yaml` holds the six references in one place and `deploy/k8s/40-authorization-service.yaml` through `45-card-service.yaml` carry `imagePullPolicy: Never`. `.github/workflows/ci.yml` builds the six images in its container stage, scans them, records a provenance statement over the six Java archives, and pushes nothing. The two upstream images in `10-kafka.yaml` and `20-postgres.yaml` are already digest-pinned and show the shape the six should reach.
- **Why it is still here:** A locally built image has no manifest digest: `docker inspect` reports an empty `RepoDigests` list until the image is pushed, so a digest reference would name something no node could resolve. Publishing, signing and verifying need a registry, a signing key and an admission controller, and section 0.2.2 of the plan places production hardening out of scope while section 0.8.5 records the instruction to keep infrastructure configuration minimal and swappable. The residual risk is that the six tags stay mutable: bytes tagged `carddemo/authorization-service:1.0.0-SNAPSHOT` on one node are whatever an operator loaded there, and no manifest notices a different build under the same name.
- **Procedure:** Add a push step to the container stage, scoped to the events that should publish. Replace each `newTag` in `kustomization.yaml` with `digest: sha256:<digest>` and each `newName` with the registry, which `kustomize edit set image` does one entry at a time. Remove `imagePullPolicy: Never` from the six Deployments once a digest is pulled rather than preloaded, because a digest that is never pulled is never verified. Sign in the pipeline with the same identity the provenance statement already uses, and add an admission policy that refuses an unsigned image.
- **Check:** Apply with a digest and confirm the Pods run. Retag different bytes under the old tag and confirm nothing changes, which is the property the digest buys. Present an unsigned image and confirm admission refuses it.
- **Behavior change:** Deployment only. No service code, schema or event changes, and the Compose path for the local demo is untouched.

### Build the data-subject export and erasure workflow

- **Change:** Build the authenticated export and erasure workflow this platform does not have, so a cardholder's request can be answered and proved.
- **Where:** Nothing implements one today: no controller, no event, no service and no repository on any of the six services performs an export or an erasure, and a security review found the account schema promising "erase on request" anyway. Four migrations replaced every one of the six statements that had made a promise with the actual position: `account-service/V7__account_customer_link.sql` for the `customer` table and `account_customer_link`, `account-service/V8__subject_request_posture.sql` for the `customer.social_security_number` column, `authorization-service/V11__subject_request_posture.sql` and `card-service/V4__subject_request_posture.sql` for the two `card_xref` replicas, and `notification-service/V6__subject_request_posture.sql` for `cardholder_context`. An erasure has to reach five stores across four schemas: `account_service.customer`, the only table on this platform that describes an identifiable person; `account_service.account_customer_link`, which pairs an account with a customer; the `card_xref` replicas the authorization and card services hold; and `notification_service.cardholder_context`, the read model that carries ten cardholder fields keyed by account. `services/card-service` additionally holds the card row itself.
- **Why it is still here:** It is a platform capability rather than a comment. It needs an authenticated and authorized entry point, a legal-hold exception so a record under investigation is not destroyed, an auditable tombstone proving what was removed and when, an idempotent event that reaches every replica and read model exactly once however often it is redelivered, and a completion report a data-protection officer can read. Section 0.2.2 of the plan excludes production hardening from this engagement, and a workflow that erases financial records is not something to ship half-built: an erasure that reaches four of five stores is worse than none, because it reports success.
- **Procedure:** Add the entry point to the account service, which owns the customer row. Publish a `CustomerErased` event keyed on the customer identifier, and have every service holding a replica consume it with the same processed-event guard its other listeners use. Write the tombstone in the account schema before publishing, in the same transaction, so the outbox pattern this platform already uses carries the propagation. Report completion by reading each replica back rather than by counting acknowledgements.
- **Check:** Erase one fixture customer. Then query all five stores directly and find no row, and find one tombstone naming all five. Redeliver the event and confirm nothing changes and nothing fails. Run the equivalence suite afterwards and confirm the remaining forty-nine fixture customers are untouched.
- **Behavior change:** New capability. No existing business rule changes, and no authorization outcome changes, because an erased customer's accounts are erased with them.

### Deliver the rendered cardholder alert

- **Change:** Give the notification service a delivery port, so a rendered alert reaches the cardholder it was written for.
- **Where:** `services/notification-service/src/main/java/com/carddemo/notification/domain/NotificationService.java` renders a document and returns it to its caller. Every caller is a Kafka listener, and every listener discards the returned text. A security review found the stored record described as a delivery attempt while no transport existed, and `db/migration/V5__rendered_not_delivered.sql` corrected that: the column is `rendered_at`, the table's `outcome` column carries `RENDERED_NOT_SENT`, and `ck_notification_log_outcome` permits no other value. The service's own README lists mail and short-message gateways under deliberate non-additions.
- **Why it is still here:** Section 0.1.1 of the plan specifies this service as "rendering a cardholder alert", so a transport is a new capability rather than a correction. It also needs decisions no engineer can make alone: which channel a cardholder consented to, what happens to an alert that cannot be delivered, and how long an undelivered alert may be retried before it is stale enough to be misleading.
- **Procedure:** Add a port interface beside `messaging/EventPublisherPort`, so the transport is swappable the way the event bus is. Widen `ck_notification_log_outcome` to permit `SENT` and `FAILED`, and widen `NotificationLogEntity` in the same commit — the constraint is deliberately narrow so that widening it is visible. Record the attempt count and the failure reason, and route an exhausted alert to the dead-letter topic this service already writes.
- **Check:** Render an alert and prove it arrived at a test transport. Prove that a transport failure leaves `outcome` reporting the failure rather than reporting success, and that no row ever reports `SENT` when the transport was never called.
- **Behavior change:** New capability. Every existing row keeps its meaning, because `RENDERED_NOT_SENT` stays a permitted value.

### Define legacy credential import

- **Change:** Define how plaintext `USRSEC` records become encoded service credentials during a real migration. Of every task on this list, this is the one whose case for changing is strongest.
- **Where:** The source compares the stored and supplied password directly at `app/cbl/COSGN00C.cbl:L223`. Hashing that comparison would have broken equivalence, so it was preserved and kept out of the authorization path. The platform's own credentials are already hashed: each service accepts `{bcrypt}` at a cost of at least ten or `{pbkdf2@SpringSecurity_v5_8}` and refuses every other encoding at start-up, which is why the open work is the import rather than the encoder.
- **Check:** Import a test record, authenticate with the original password, and confirm no plaintext password survives anywhere. An unmigrated record must fail only in the way the chosen import strategy intends.
- **Behavior change:** Yes, but to credential storage rather than to any authorization rule, so no equivalence assertion moves.

### Re-key what a card-token rollover leaves behind

- **Change:** Give the two read models and the diagnostic column a way to survive a card-token rollover. The key itself no longer needs turning over: `.env.example` and `deploy/k8s/31-secret.example.yaml` ship placeholders, the authorization and card services refuse to start without a generated key, and `CardTokenReconciler` re-derives the fifty seeded `card_token` literals under that key before the card service accepts traffic. What has no owner is every token stored somewhere else.
- **Where:** `com.carddemo.cobol.PanMasker.cardToken` derives a token, and three tables and one authority hold one: `statement_transaction.card_token` and `notification_log.card_token` in the notification service, `authorization_decision.card_token` in the authorization service, and the `SCOPE_CARD` authority an operator appends to `USER_SCOPES` in `.env` or `deploy/k8s/30-configmap.yaml`. None of the three tables holds a card number, so none of them can re-derive its own rows.
- **Procedure:** Decide per table. The notification read model and the notification log are rebuilt from replayed events, so a rollover can discard and replay rather than re-key. `authorization_decision.card_token` is a diagnostic column, so an operator may leave it at its previous version provided the version is recorded beside it — which needs a column the table does not have yet. The authority is derived again from the card number using the command in `.env.example`. Raise `CARD_TOKEN_VERSION` in the same change as the key, so a stored value can be told from a current one.
- **Check:** After a rollover with a new key, `GET /notifications/{cardNumber}` must reach the rows an earlier run wrote for that card. It does not today: the route derives the token under the current key while the stored rows carry the previous one, and that is the gap.
- **Behavior change:** None to any business rule. Card identity is ADDITIVE in full: `app/cpy/CVACT02Y.cpy` declares no token field and no source program derives one.

### Encrypt the cardholder stores at rest, and back them up

- **Change:** Give every service schema encryption at rest, and give the volumes that hold them a backup and restore route. Neither exists today: `docker-compose.yml` mounts plain named volumes and `deploy/k8s/20-postgres.yaml` declares a plain `PersistentVolumeClaim`, so a copy of a volume, a snapshot or a lost disk yields readable cardholder data.
- **Where:** The exposure is concentrated rather than spread. `services/card-service/src/main/resources/db/migration/V1__schema.sql` holds fifty full card numbers and fifty card verification values, `services/authorization-service` and `services/card-service` each hold a card-keyed `card_xref` replica, and `services/account-service` holds the only table on this platform that describes an identifiable person, including a Social Security Number and a government-issued identifier. The account service no longer replicates card numbers at all: `V7__account_customer_link.sql` replaced that replica with the account-to-customer pair.
- **Why it is still here:** Section 0.2.2 of the plan excludes production hardening from this engagement by name — automated backup and restore, and "payment-card industry controls beyond the single documented masking deviation". Storage-level encryption and volume backup are deployment properties of the database and its volumes rather than application code, and this platform ships one demonstration compose file and a minimal set of manifests the same section describes as deliberately minimal and swappable. A security review recorded the gap, and recording it here is the honest answer rather than shipping an encryption story a demo cannot support.
- **Procedure:** Choose the storage-level control the target platform offers — an encrypted volume, an encrypted storage class, or a managed database with encryption enabled — and set it on the volume rather than in application configuration, so no column type and no query changes. Add a backup schedule and prove a restore. Decide at the same time whether the card verification value should still be stored at all; `docs/business-rule-flags.md` entry D4 carries that decision, and removing the column removes the most sensitive value from the store entirely.
- **Check:** Read the raw volume or a snapshot of it with the database stopped, and find no card number, no verification value and no Social Security Number in cleartext. Restore a backup into an empty environment and run the equivalence suite against it.
- **Behavior change:** None. Encryption at rest is invisible to every query, every event and every business rule.

### Give duplicate suppression a permanent record rather than a retention window

- **Change:** Decide whether the platform should suppress a duplicate delivery from a permanent property of the business transaction instead of from a marker with a horizon.
- **Where:** Every consumer claims a row in its own `processed_event` table, and `services/*/src/main/resources/application.yml` gives that row a horizon of 720 hours against 168 hours of broker log retention. Each service's `ProcessedEvent` properties record refuses a horizon under twice the broker figure at start-up, so the relationship is enforced rather than assumed. What it cannot cover is a window it does not know about: a backup restored from beyond the horizon, or an operator replaying a manually retained archive.
- **Why it is still here:** A permanent constraint has to name something the schema already carries and that one event maps onto once. The ledger writes both `transaction_category_balance` and `account_balance_projection` from one `TransactionAuthorized`, so uniqueness would have to cover a compound of account, type code, category code and transaction identifier rather than a single column, and the same event reaches three services that each derive something different from it. That is a data-model change across four schemas rather than a retention setting, and the [decision log](decision-log.md) records why the margin was chosen for now.
- **Procedure:** Choose the natural key per consumer — the transaction identifier is the candidate in ledger and notification — add it as a unique constraint on the table the side effect writes, and let the constraint rather than the marker refuse the second application. Keep the marker table for the consumers whose side effect has no natural key, and keep the start-up check for those.
- **Check:** Delete every `processed_event` row, reset a consumer group to the earliest offset, and confirm no balance, category balance or statement row moves. Today that replay applies each record a second time once the marker is gone.
- **Behavior change:** A duplicate would be refused by the datastore rather than skipped by the consumer, so a redelivery becomes a constraint violation the consumer has to recognise as harmless rather than a row it never wrote.

### Bound the request rate across the cluster rather than per replica

- **Change:** Move the five request-rate ceilings behind a counter every replica shares, so a ceiling bounds the service rather than one process.
- **Where:** `services/*/src/main/java/**/config/RequestRateCeilingFilter.java` holds its windows in a `ConcurrentHashMap` inside the process, bounded at 10,000 keys. `deploy/k8s/4*.yaml` each declare one replica today, so the per-instance ceiling and the service ceiling are the same number; raise `replicas` to three and a source reaching every replica gets three times the nominal rate. The five limits are `API_RATE_WINDOW_SECONDS`, `API_RATE_REQUESTS_PER_WINDOW`, `API_RATE_WRITE_REQUESTS_PER_WINDOW`, `API_RATE_AUTHENTICATION_FAILURES_PER_WINDOW` and `API_RATE_CONCURRENT_REQUESTS`, documented in `.env.example` and mirrored in `deploy/k8s/30-configmap.yaml`.
- **Why it is still here:** A shared counter needs a store every replica reaches. The plan's dependency inventory in section 0.5.1 names no cache or key-value store and excludes one by name for the fraud velocity window, on the ground that a demonstration should not need another container. Adding one for this control alone would contradict that, and the honest per-instance behaviour is documented in each service README, in the filter's own class comment and in the [decision log](decision-log.md).
- **Procedure:** Choose where the counter lives — a shared store, or an ingress layer that counts before any replica is reached — and keep the filter as the in-process floor rather than replacing it, so a service reached directly is still bounded. Keep the stage tags, because `carddemo.<service>.requests.throttled` is what makes the control visible.
- **Check:** Run three replicas behind one address, drive one source past the per-replica ceiling, and confirm the service refuses at the configured rate rather than at three times it. Confirm a replica reached directly still refuses.
- **Behavior change:** A caller that was reaching several replicas is refused sooner. No business rule and no event changes.

### Resolve the client address and host through the proxy in every deployment

- **Change:** Set `SERVER_FORWARD_HEADERS_STRATEGY=framework` wherever a proxy or load balancer terminates the connection, and decide which forwarding headers that proxy is trusted to write.
- **Where:** Nothing configures `server.forward-headers-strategy` today. Two controls read the connection rather than a header: `config/RequestRateCeilingFilter` keys its ceilings on `ServletRequest.getRemoteAddr()`, and `config/CrossSiteRequestFilter` compares `Origin` against the scheme, host and port the request arrived on. `deploy/k8s/30-configmap.yaml` carries a comment saying so beside the cross-site keys, and this platform ships no ingress, so the gap is a deployment one rather than a code one.
- **Why it is still here:** Reading a forwarding header a caller can write would let that caller choose its own rate-limit key and its own origin, which is worse than reading the wrong address. The strategy is therefore off by default and correct for the shipped Compose stack, where a caller reaches the container directly. Section 0.2.2 of the plan excludes ingress and service-mesh configuration from this engagement, so there is no proxy here to trust.
- **Procedure:** Deploy the proxy, have it overwrite rather than append `X-Forwarded-For` and `X-Forwarded-Proto`, set the strategy, and confirm no route accepts those headers from anywhere but the proxy. Then re-run the checks below, because both controls silently change meaning.
- **Check:** Behind the proxy, confirm two callers on different addresses hold separate rate-limit counters rather than sharing the proxy's, and confirm a state-changing request carrying the client-facing `Origin` is admitted while a foreign one is refused.
- **Behavior change:** Ceilings begin to bound callers rather than the proxy, and the cross-site origin comparison begins to test the browser's origin rather than the address the proxy dialled. A misconfigured proxy makes both controls trust a value a caller wrote, which is why the two checks above are the acceptance criteria.

### Give a stack trace somewhere safe to go

- **Change:** Add a diagnostic sink that carries a redacted stack trace, and route the failure paths to it in addition to the ordinary line they already write.
- **Where:** Five exception handlers and one domain service record a failure as its type and the types of its causes: `services/card-service/src/main/java/com/carddemo/card/api/CardApiExceptionHandler.java`, `services/card-service/src/main/java/com/carddemo/card/domain/CardUpdateService.java`, `services/fraud-detection-service/src/main/java/com/carddemo/fraud/api/FraudApiExceptionHandler.java`, `services/ledger-posting-service/src/main/java/com/carddemo/ledger/api/LedgerApiExceptionHandler.java` and `services/notification-service/src/main/java/com/carddemo/notification/api/NotificationApiExceptionHandler.java`. None passes the throwable to the logger, because an attached throwable is rendered with its message and a message quotes the value a constraint refused, the statement a timeout cancelled, or the data-source URL with its user.
- **Why it is still here:** A second sink is a deployment concern rather than application code: it needs its own destination, its own retention and its own access control, and section 0.2.2 of the plan excludes that class of hardening from this engagement. This platform ships one structured console appender, so there is nowhere for a trace to go that is not the ordinary log.
- **Check:** Fail one request against a paused database. The ordinary line names the failure type and no value; the diagnostic record carries the frames with every message redacted; and only the operators who are meant to read the second one can.
- **Behavior change:** None to any business rule. What changes is how much detail an operator can reach, and from where.

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

### Give the authorization relay a terminal diagnostic, or accept the silent unpublished row

- **Change:** Decide whether `authorization-service` should publish a governed `DeadLetterEnvelope` for an outbox row it gives up on, as the other four publishing relays do. The alternative is that the counter and the log line are sufficient for that service.
- **Where:** `services/authorization-service/src/main/java/com/carddemo/authorization/outbox/OutboxRelay.java` states at its class comment that a row whose event type has no bound topic "records an attempt and stays unpublished, reaching no topic at all". The class holds no dead-letter topic, template or envelope. The relays of the ledger, fraud, account and card services each send an envelope to `carddemo.dead-letter` instead. The listener side of the authorization service already routes a refused replica record to that same topic through `config/KafkaConsumerConfig`. The topic, the principal and the broker access-control entry therefore all exist, and only the relay path is missing.
- **Check:** Bind an outbox row to an event type with no topic property, then run the relay until its attempts are spent. Assert either that one envelope naming the row reaches `carddemo.dead-letter`, or that the documented behaviour is the counter alone.
- **Behavior change:** Publishing the envelope adds one diagnostic record per abandoned row and changes no business outcome. Leaving it changes nothing, and the gap stays recorded in [event flow](event-flow.md) and in the [authorization service guide](../services/authorization-service/README.md).

### Extend the durable dead-letter obligation to the remaining three relays

- **Change:** Give the card, ledger-posting and notification outbox relays the durable terminal obligation the authorization, account and fraud relays now carry, so an abandoned row of any service names itself on the dead-letter topic.
- **Where:** `services/card-service`, `services/ledger-posting-service` and `services/notification-service`, each needing the two columns of `V5__outbox_dead_letter_state.sql` in the fraud service, the `owesDeadLetter` and `markDeadLetterPublished` pair on its own `OutboxEventEntity`, a finder ordered by last attempt, and an owed-diagnostic pass at the head of its relay tick. Three of the six services answer this way today and three do not, which is why the two shipped migration headers say so rather than claiming the platform is uniform.
- **Check:** Drive one row of each service past `MAX_DELIVERY_ATTEMPTS` with the broker refusing, restore the broker, and confirm a diagnostic naming that row reaches `carddemo.dead-letter` on a later pass.
- **Behavior change:** None to any successful path. It adds two columns and one indexed read of no rows per pass to each of the three services, and it turns an abandoned row from a log line that dies with the container into a record an operator can find.

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

- **Change:** Adopt a mechanism that raises the three action pins deliberately — a configured dependency-update tool that proposes one change per release, or a recurring review the project owner performs — and record which of the two is wanted.
- **Where:** `.github/workflows/ci.yml` runs `actions/checkout` at `3d3c42e5aac5ba805825da76410c181273ba90b1` (v7.0.1), `actions/setup-java` at `b6effb05e454b25005698d916606bdc6ffcbf961` (v5.7.0) and `actions/upload-artifact` at `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` (v7.0.1). `ContinuousIntegrationWorkflowContractTest` holds the same three pins, so raising one is two edits made together. Nothing in this repository watches for a newer release. The [decision log](decision-log.md) records why a commit is named rather than a tag.
- **Check:** After adopting a mechanism, confirm that a new release of one action produces a proposal changing both the workflow key and the test constant, and that the contract test fails while only one of the two has moved.
- **Behavior change:** No. A pinned commit keeps running exactly what it runs today until someone raises it, which is the property the pin exists for.

### Raise the runner image before it reaches end of life

- **Change:** Move the six `runs-on` keys to the next generally available Ubuntu image once `ubuntu-24.04` approaches the end of its life on GitHub's schedule, after confirming the Docker and Compose versions the integration, equivalence and container stages depend on are present on the new image.
- **Where:** the six `runs-on: ubuntu-24.04` keys in `.github/workflows/ci.yml`, and `RUNNER_IMAGE` in `ContinuousIntegrationWorkflowContractTest`, which asserts all six agree and refuses `ubuntu-latest`.
- **Check:** Run the whole workflow on the new image and read the integration and container stages, which are the two that start Compose. A missing or older Compose is what breaks first.
- **Behavior change:** No, provided the new image carries the same container tooling. It is listed because a retired image stops the pipeline outright rather than degrading it.

## Informational register items

Some register entries explain the source without suggesting a change. Items 18 through 22 document specification conflicts, abandoned menu intent, unreachable role logic, stale identity moves, and over-allocated presentation arrays. Five appended items read the same way. Item 32 records that five batch programs credited with loading data write nothing. Items 35 and 36 record a copied comment block and an out-of-order default in the card expiry edits. Item 37 records seven message constants that are declared and never reached. Item 40 records that the report screen submits the report procedure rather than the posting job.
