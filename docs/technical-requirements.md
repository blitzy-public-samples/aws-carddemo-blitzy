# Card Servicing Technical Requirements and Architecture

This is the Technical Requirements and Architecture Document for the first, read-only Card Servicing iteration over the AWS CardDemo data model. It fixes the components, interfaces, decisions, resilience and security rules, request path and test harness that the backlog's stories implement. Nothing here is built in this iteration's documentation run: the service, its five stubs, the fixture loader and the test suites are specified for the stories listed in the [implementation plan](implementation-plan.md). Sources are cited by document and anchor, for example `Use Cases · COACTVWC`, `Data Layouts · CVACT01Y · ACCT-CURR-BAL` and `Reference Tables · TRANTYPE file`.

## Scope and planning-baseline assumptions

Scope. Four read-only UI interactions, each served by one UI-facing endpoint:

| Interaction | Anchor | UI-facing endpoint |
|---|---|---|
| Account balance summary | `Use Cases · COACTVWC` | `GET /v1/accounts/{accountId}/balance-summary` |
| Category balance view | `Data Layouts · CVTRA01Y · TRAN-CAT-BAL` (no CardDemo screen shows category balances) | `GET /v1/accounts/{accountId}/category-balances` |
| Transaction list drill-down | `Use Cases · COTRN00C` | `GET /v1/accounts/{accountId}/transactions` |
| Transaction detail drill-down | `Use Cases · COTRN01C` | `GET /v1/accounts/{accountId}/transactions/{transactionId}` |

Five external systems are stubbed: Card Data, Transaction Activity, Config, Reference Data and Auth. There are no business write operations; the only writes are the fixture load into PostgreSQL and cache entries in Redis. UI screens and mock-ups are deferred, and the end-to-end test's HTTP client stands in for the UI (see [Card Servicing UI](#card-servicing-ui)). Real Card Data, Transaction Activity, Config, Reference Data and Auth implementations are out of scope.

Constraints:

- Java 25 (Eclipse Temurin) through the Gradle toolchain; Gradle 9.1.0, the first release that runs on and targets Java 25.
- Spring Boot 3.5.7; grpc-java 1.71.0 with protobuf 3.25.5; OpenAPI 3.1.
- PostgreSQL 16 on Azure Database for PostgreSQL Flexible Server ([D1](#d1-data-store)).
- Azure Managed Redis (engine 7.4) through Spring Data Redis with Lettuce.
- AKS 1.28 or later and Docker 20.10 or later.
- Latency target: UI-to-service latency under 1,000 ms at p50, p95 and p99.

Assumptions, recorded as assumptions:

- Assumption — planning baseline: 10 concurrent users, 100,000 category balances and 1,000,000 transactions. The decisions that stop holding above it (data-store sizing and partitioning, keyset pagination indexes, cache sizing) are listed in [Recommendations](implementation-plan.md#recommendations).
- Assumption — requirements ownership: the Product Owner owns Requirements Analysis, which does not exist yet, so every story traces `CARD_REQ_TBD` and appears in the [gap list](implementation-plan.md#card_req_tbd-gap-list). No `CARD_REQ_NNN` identifier is invented.
- Assumption — build order: the [INFERRED] layered build order (data model and core balance logic before their consumers, stubs before the stories that call them) awaits Product Owner confirmation in the [sign-off record](sign-off.md#build-order-confirmation).

## Component catalogue

This catalogue is the one name list for every diagram under `docs/architecture/`. A node or participant ID in a diagram is a Name below, spelled exactly; its colour class follows the Category; the Diagrams column lists every diagram file stem in which the ID appears.

| Name | Category | Responsibility | Diagrams |
|---|---|---|---|
| `CardServicingUi` | Compute | Deferred UI for the four interactions, for which the end-to-end HTTP client stands in this iteration. | `logical-architecture`, `physical-deployment`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `ApiGateway` | Compute | Ingress that terminates TLS and routes REST and gRPC requests to the service. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `CardServicingService` | Compute | Validates requests, authorizes them, reads upstream through five gRPC clients and assembles responses. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `CategoryBalanceAggregator` | Compute | Sums an account's category balances into one balance per transaction-type group. | `sequence-balance-summary` |
| `FixtureLoader` | Compute | One-shot job that loads the CardDemo sample and the synthetic volume and writes a load report. | `logical-architecture`, `physical-deployment`, `security-architecture` |
| `AzurePostgres` | Data stores | Azure Database for PostgreSQL Flexible Server (PostgreSQL 16) holding the six entity tables the stubs read. | `logical-architecture`, `physical-deployment`, `security-architecture` |
| `AzureManagedRedis` | Data stores | Read-through cache used by the service only. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `AccountAccessGuard` | Security | Validates the bearer token and authorizes per-account access before any data or cache read. | `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `AzureKeyVault` | Security | Source of secrets in deployed environments. | `logical-architecture`, `physical-deployment`, `security-architecture` |
| `CardDataService` | External systems | Serves account records, card cross-references and category balances. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-detail` |
| `TransactionActivityService` | External systems | Serves an account's transaction list and single transactions. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `ConfigService` | External systems | Supplies page sizes and the upstream timeout and retry settings. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `ReferenceDataService` | External systems | Serves transaction types and categories with their descriptions. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances` |
| `AuthService` | External systems | Validates bearer tokens and decides per-account access. | `logical-architecture`, `physical-deployment`, `security-architecture`, `sequence-balance-summary`, `sequence-category-balances`, `sequence-transaction-list`, `sequence-transaction-detail` |
| `PepPostgres` | Networking | Private endpoint through which PostgreSQL is reached. | `physical-deployment`, `security-architecture` |
| `PepRedis` | Networking | Private endpoint through which Azure Managed Redis is reached. | `physical-deployment`, `security-architecture` |
| `PepKeyVault` | Networking | Private endpoint through which Key Vault is reached. | `physical-deployment`, `security-architecture` |
| `AzureSubscription` | Boundary | Subscription holding every deployed resource; the UI sits outside it. | `physical-deployment`, `security-architecture` |
| `AzureVNet` | Boundary | Virtual network holding the AKS subnet and the private-endpoint subnet; titled S1 Internet edge in the security view. | `physical-deployment`, `security-architecture` |
| `AksSubnet` | Boundary | Subnet holding the API gateway and the AKS cluster. | `physical-deployment`, `security-architecture` |
| `PepSubnet` | Boundary | Subnet holding the three private endpoints; titled S3 Private data zone in the security view. | `physical-deployment`, `security-architecture` |
| `AzureAKS` | Boundary | AKS cluster running the service, the five stubs and the fixture loader job; titled S2 Cluster trust zone in the security view. | `physical-deployment`, `security-architecture` |

Boundary rows are subgraphs, never nodes. Subgraph IDs in any diagram are limited to the five boundary names and `Legend`.

Diagram conventions, followed by every `.mmd` file and enforced by `tests/validation/check-diagrams.mjs`:

- Frontmatter `title:` comes first. The 1–2 sentence description comes last: a `Description` node in flowcharts, a closing `Note` in sequence diagrams.
- Every diagram carries all five legend entries: `LegendCompute` light blue `rgb(173,216,230)`, `LegendDataStores` light green `rgb(144,238,144)`, `LegendExternal` gray `rgb(211,211,211)`, `LegendSecurity` light red `rgb(244,204,204)` and `LegendNetworking` light yellow `rgb(255,255,224)`.
  - Flowcharts place them as nodes in a `Legend` subgraph, styled by category `classDef`. Flowchart `classDef` fills use the hex equivalents `#add8e6`, `#90ee90`, `#d3d3d3`, `#f4cccc` and `#ffffe0`, because a comma inside `rgb(...)` ends a `classDef` property.
  - Sequence diagrams declare five `box` groups in that colour order, each named for its category and opening with its legend participant labelled with the category name, so a category with no component in that interaction (Networking in every sequence) still shows. Each component participant sits in the box of its catalogue category.
- Node and participant IDs are catalogue names exactly. Labels run 1–5 words, with `P<n>` prefixes in the physical view and `S<n>` prefixes in the security view where the item is numbered.
- `Legend*`, `Acm*` (access-control matrix rows) and `Description` are annotations. They are exempt from the orphan and name checks but count toward the 25-node budget; every other node has at least one edge.
- Split rule: a diagram above 25 nodes is split by domain boundary, for example the security view into `security-architecture-network.mmd` and `security-architecture-access.mmd`, and each part keeps its title, legend and description.

Planned node counts: logical 12 components + 5 legend + 1 description = 18; physical 15 + 5 + 1 = 21; security 15 + 5 + 1 + 3 access-control rows = 24; each sequence at most 11 components + 5 legend participants + 1 description note.

## Logical architecture and data movement

[Logical architecture](architecture/logical-architecture.mmd) (C4 Level 2, `flowchart LR`) shows 12 components: `CardServicingUi`, `ApiGateway`, `CardServicingService`, `FixtureLoader`, `AzurePostgres`, `AzureManagedRedis`, `AzureKeyVault` and the five external systems `CardDataService`, `TransactionActivityService`, `ConfigService`, `ReferenceDataService` and `AuthService`. Edges run from initiator to target.

Data movement:

- `CardServicingUi` calls `ApiGateway` over HTTPS REST; the gateway forwards REST or gRPC to `CardServicingService`.
- `FixtureLoader` writes all six tables in `AzurePostgres`: `transaction_type`, `transaction_category`, `account`, `card_cross_reference`, `category_balance` and `transaction_record` ([data model](data-model.md)).
- The Card Data, Transaction Activity and Reference Data stubs read their tables through Spring Data JPA, or an in-memory fixture set in unit suites. The Config stub reads its key map from bound configuration properties. The Auth stub reads its fixture identity file from the classpath.
- `CardServicingService` never opens a database connection. It reads and writes Redis only through the read-through cache, and reads account, transaction, reference, configuration and authorization data only through its five gRPC clients. No stub reads Redis. A requirement that needs a store the service cannot reach is served by the stub that owns that store, through its contract.
- Every upstream call carries the caller's bearer token forwarded in `authorization` metadata plus the service credential in `x-api-key`. The stubs ignore the key and do not re-authorize.
- In deployed environments the service and the database readers take their secrets from `AzureKeyVault` ([Security](#security)).

Package map under `card-servicing-service/src/main/java/com/example/cardservicing/`:

| Package | Holds |
|---|---|
| `balance` | Balance summary assembly, its REST controller and its gRPC method. |
| `category` | `CategoryBalanceAggregator` and the category-balance endpoint. |
| `drill` | The transaction list and transaction detail endpoints. |
| `integration` | The five clients, the five stub servers with their mock-data providers, the fixture loader, the upstream call policy, the read-through cache, the account access guard and the page-token codec. |
| `config` | Typed configuration, API error mapping, request-parameter validation and gRPC server configuration. |

Java generated from the protos lands under the build directory in `com.example.cardservicing.contract.<service>.v1` (for example `com.example.cardservicing.contract.api.v1`) and is never checked in.

## Interface definitions

Contracts, one `.proto` and one OpenAPI 3.1 document per interface, plus the shared types:

| Interface | Proto package | `.proto` | OpenAPI document |
|---|---|---|---|
| Shared types | `cardservicing.common.v1` | [common.proto](../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto) | — (each OpenAPI document embeds the identical `ErrorObject` schema) |
| UI-facing API | `cardservicing.api.v1` | [card_servicing_service.proto](../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto) | [card-servicing-api.yaml](../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml) |
| Card Data | `cardservicing.carddata.v1` | [card_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto) | [card-data-service.yaml](../card-servicing-service/src/main/resources/openapi/card-data-service.yaml) |
| Transaction Activity | `cardservicing.transactionactivity.v1` | [transaction_activity_service.proto](../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto) | [transaction-activity-service.yaml](../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml) |
| Config | `cardservicing.config.v1` | [config_service.proto](../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto) | [config-service.yaml](../card-servicing-service/src/main/resources/openapi/config-service.yaml) |
| Reference Data | `cardservicing.referencedata.v1` | [reference_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto) | [reference-data-service.yaml](../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml) |
| Auth | `cardservicing.auth.v1` | [auth_service.proto](../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto) | [auth-service.yaml](../card-servicing-service/src/main/resources/openapi/auth-service.yaml) |

Lint configuration: [buf.yaml](../card-servicing-contracts/buf.yaml) and [.spectral.yaml](../.spectral.yaml).

The 14 baseline operations, each a gRPC RPC with its REST or HTTP/JSON twin:

| Service | RPC | REST operation |
|---|---|---|
| `CardServicingService` | `GetBalanceSummary` | `GET /v1/accounts/{accountId}/balance-summary` |
| `CardServicingService` | `ListCategoryBalances` | `GET /v1/accounts/{accountId}/category-balances` |
| `CardServicingService` | `ListTransactions` | `GET /v1/accounts/{accountId}/transactions` |
| `CardServicingService` | `GetTransaction` | `GET /v1/accounts/{accountId}/transactions/{transactionId}` |
| `CardDataService` | `GetAccount` | `GET /v1/card-data/accounts/{accountId}` |
| `CardDataService` | `GetCardCrossReference` | `GET /v1/card-data/accounts/{accountId}/card-cross-references` |
| `CardDataService` | `ListCategoryBalances` | `GET /v1/card-data/accounts/{accountId}/category-balances` |
| `TransactionActivityService` | `ListTransactions` | `GET /v1/transaction-activity/accounts/{accountId}/transactions` |
| `TransactionActivityService` | `GetTransaction` | `GET /v1/transaction-activity/transactions/{transactionId}` |
| `ConfigService` | `GetConfig` | `GET /v1/config?keys=` |
| `ReferenceDataService` | `ListTransactionTypes` | `GET /v1/reference-data/transaction-types` |
| `ReferenceDataService` | `ListTransactionCategories` | `GET /v1/reference-data/transaction-categories` |
| `AuthService` | `ValidateToken` | `POST /v1/auth/token-validations` (a `POST`, so a token never travels in a URL) |
| `AuthService` | `AuthorizeAccountAccess` | `GET /v1/auth/subjects/{subject}/accounts/{accountId}/access` |

UI-facing schemas: `GetBalanceSummaryResponse` with `TypeGroupBalance`, `ListCategoryBalancesResponse` with `CategoryBalance`, `ListTransactionsResponse` with `TransactionSummary`, and `GetTransactionResponse` with `Transaction`. The standard error object is `ErrorObject` (`code`, `message`, `traceId`, `details[]` of `{field, description}`), defined once in `common.proto` as `ErrorObject` and `FieldViolation`; each service proto names it in an `ErrorDetail` message.

Conventions:

- JSON names are the camelCase forms of the snake_case proto fields (`account_id` → `accountId`).
- Amounts, timestamps, `dataStatus` and error `code` are strings in REST and gRPC alike, so both carry identical text. Amounts serialize from scale-2 decimals, for example `"1234.56"`; timestamps use RFC 3339 with microseconds and `Z`, for example `"2024-03-05T14:07:09.123456Z"`.
- No well-known types and no googleapis imports. At runtime a gRPC error is the `ErrorObject` message packed into `google.rpc.Status` details; `proto-google-common-protos` reaches the runtime through `grpc-protobuf`.
- The four UI-facing 200 responses carry the `X-Data-Status` header, equal to the body's `dataStatus`.
- Arrays are always present, empty when nothing matches; absent optional scalars are omitted. The one exception is `typeDescription` and `categoryDescription`: in REST they are always present, as a string or as JSON `null` only when `dataStatus` is `PARTIAL` (OpenAPI `type: [string, "null"]`, required); in gRPC they are proto3 `optional string`, unset when `PARTIAL`. An empty string never stands for absence.
- `nextPageToken`: REST omits the property when no further item exists; gRPC `next_page_token` is a plain `string`, empty on the last page. The Transaction Activity stub's REST document uses the same omission.
- Every RPC comment carries `Example request:`, `Example response:` and `Errors:` blocks; every OpenAPI operation carries a description, a 200 example built from fixture account `00000000001`, its security schemes (`bearerAuth`, plus `apiKey` in header `x-api-key` on the stub documents) and an `ErrorObject` example for every error status it can return.

Fields added to the baseline, additive only:

| Where | Added fields | Source |
|---|---|---|
| Balance summary response | `activeStatus`, `openDate`, `expirationDate`, `reissueDate`, `cashCreditLimit`, `currentCycleCredit`, `currentCycleDebit`, `groupId` | `Use Cases · COACTVWC` shows them in the account section; `Data Layouts · CVACT01Y` defines them |
| Transaction list items | `description`, `originalTimestamp` | `Use Cases · COTRN00C` Description and Date columns (Date is taken from TRAN-ORIG-TS); `Data Layouts · CVTRA05Y · TRAN-DESC`, `TRAN-ORIG-TS` |
| `TransactionActivityService.ListTransactions` errors | `NOT_FOUND` when the account has no cross-reference record | The common mapping of an unknown record to 404 / `NOT_FOUND`, applied to the rule that an account's transactions are those of its cards |

Linting, both run from the repository root and both required to report zero errors:

- `buf lint --config card-servicing-contracts/buf.yaml`: buf v2 with the two root-relative modules `card-servicing-contracts/src/main/proto` and `card-servicing-service/src/main/proto`, and `lint.use: [STANDARD, COMMENTS]`, so every service, RPC, message, field and enum element carries a comment.
- `spectral lint <file> --ruleset .spectral.yaml --fail-severity=error` on each OpenAPI document, with the ruleset extending `spectral:oas`.

## External systems

Card Servicing is the consumer of all five external systems; each system is the producer of its data. In the stub phase every system is a stub gRPC server answered by a mock-data provider ([D3](#d3-stub-hosting-and-swap-point)): on a loopback port inside the test JVM for unit and integration suites, and as a container in the Compose stack. Each is reached through one gRPC client generated from its proto, and replacing a stub with the real service changes only that client's target address and credential source. Stub hosting, failure injection and the request log are shared (CS-11-002). Degradation outcomes below are those of the [degradation table](#resilience).

**Card Data — `CardDataService`.** Owning epic CS-09.

- Role: producer of account records (`Data Layouts · CVACT01Y`), card cross-references (`Data Layouts · CVACT03Y`) and category balances (`Data Layouts · CVTRA01Y`).
- Operations: `GetAccount`, `GetCardCrossReference`, `ListCategoryBalances`; contracts [card_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto) and [card-data-service.yaml](../card-servicing-service/src/main/resources/openapi/card-data-service.yaml).
- Data read: tables `account`, `card_cross_reference` and `category_balance`, through Spring Data JPA, or the in-memory fixture set in unit suites.
- Stub-phase hosting: gRPC stub with a mock-data provider over the fixture-loaded tables (CS-09-004), loopback in the test JVM, container in Compose. Tables and fixture load come from CS-09-002 and CS-09-003, the category-balance volume from CS-09-007.
- Degradation: `GetAccount` and `ListCategoryBalances` serve a cached entry within the 300 s max stale age as `STALE`, otherwise 503 or 504. `GetCardCrossReference` fails hard with 503 or 504. The client (CS-09-005) caches accounts and category balances; the integration suite is CS-09-006.
- Swap point: the client's target address and credential source only.

**Transaction Activity — `TransactionActivityService`.** Owning epic CS-10.

- Role: producer of posted transactions (`Data Layouts · CVTRA05Y`), listed per account or looked up by transaction ID. An account's transactions are those whose card number belongs to one of its cross-reference records.
- Operations: `ListTransactions` (newest processed first, then `transactionId` descending, optional `typeCode` and `categoryCode` filters, keyset page token), `GetTransaction`; contracts [transaction_activity_service.proto](../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto) and [transaction-activity-service.yaml](../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml).
- Data read: `transaction_record`, and `card_cross_reference`, which decides "account unknown": an account with no cross-reference record returns `NOT_FOUND` from `ListTransactions`.
- Stub-phase hosting: gRPC stub with keyset paging (CS-10-004) and single-transaction lookup (CS-10-005), loopback in the test JVM, container in Compose. The table with its account keyset index comes from CS-10-001, the fixture load from CS-10-002, the shared page-token codec from CS-10-003 and the transaction volume from CS-10-008.
- Degradation: `ListTransactions` fails hard with 503 or 504 and is never served stale. `GetTransaction` may be served from a cached entry, otherwise 503 or 504. The client (CS-10-006) caches transactions; the integration suite is CS-10-007.
- Swap point: the client's target address and credential source only.

**Config — `ConfigService`.** Owning epic CS-11.

- Role: producer of runtime settings (`default_page_size`, `max_page_size`, `upstream_timeout_ms`, `upstream_max_retries`); the page size traces to `Use Cases · COTRN00C`, which pages its list.
- Operations: `GetConfig`; contracts [config_service.proto](../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto) and [config-service.yaml](../card-servicing-service/src/main/resources/openapi/config-service.yaml).
- Data read: its configured key map, from bound configuration properties; baseline values 20, 100, 300 and 2.
- Stub-phase hosting: gRPC stub (CS-11-005), loopback in the test JVM, container in Compose.
- Degradation: a failed `GetConfig` never affects a request. The client (CS-11-006) keeps the last good values in the in-memory [configuration snapshot](#configuration-snapshot), otherwise the built-in defaults, and refreshes in the background. The integration suite is CS-11-007, which also adds the Integration stage to CI. The epic also owns the shared configuration properties (CS-11-001), stub hosting (CS-11-002), the upstream call policy (CS-11-003) and the read-through cache (CS-11-004).
- Swap point: the client's target address and credential source only.

**Reference Data — `ReferenceDataService`.** Owning epic CS-13.

- Role: producer of transaction types (`Data Layouts · CVTRA03Y`, `Reference Tables · TRANTYPE file`) and transaction categories (`Data Layouts · CVTRA04Y`, `Reference Tables · TRANCATG file`) with their descriptions.
- Operations: `ListTransactionTypes`, `ListTransactionCategories`; contracts [reference_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto) and [reference-data-service.yaml](../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml).
- Data read: `transaction_type` and `transaction_category`, seeded as data from two YAML files (CS-13-001, [reference data as data](data-model.md#reference-data-as-data)). A type or category change is a data load with no deploy.
- Stub-phase hosting: gRPC stub over the seeded rows (CS-13-002), loopback in the test JVM, container in Compose.
- Degradation: serve the cached entry within its max stale age, otherwise descriptions are `null` in REST and unset in gRPC and `dataStatus` is `PARTIAL`. The client (CS-13-003) caches under `cs:v1:reftypes` and `cs:v1:refcats` with a 3,600 s fresh TTL; the integration suite is CS-13-004.
- Swap point: the client's target address and credential source only.

**Auth — `AuthService`.** Owning epic CS-12.

- Role: producer of token validation (subject and roles) and per-account access decisions.
- Operations: `ValidateToken`, `AuthorizeAccountAccess`; contracts [auth_service.proto](../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto) and [auth-service.yaml](../card-servicing-service/src/main/resources/openapi/auth-service.yaml).
- Data read: the fixture identity file `card-servicing-service/src/main/resources/fixtures/auth-identities.yaml`, a classpath resource derived from the ten CardDemo users ([admin fixture policy](#admin-fixture-policy)).
- Stub-phase hosting: gRPC stub with the fixture identities (CS-12-001), loopback in the test JVM, container in Compose.
- Degradation: fail closed. 401 for an invalid, expired or malformed token, 403 for `allowed=false`, otherwise 503 or 504, with no account data read. The client (CS-12-002) sends the service API key; `AccountAccessGuard` (CS-12-003) calls it; the Key Vault secret source is CS-12-004 and the integration suite CS-12-005.
- Swap point: the client's target address and credential source only.

## Decisions

Each decision states its context, choice, ranked alternatives, reasoning and a pressure-test against failure modes, data growth and the 1-second SLA. The Delivery Lead reviews them with the [review checklist](sign-off.md#delivery-lead-review-checklist).

### D1 Data store

**Context.** The stubs need one store for the six baseline entities, which the Data Layouts define as foreign-key-linked records: Account, CardCrossReference, TransactionType, TransactionCategory, CategoryBalance and Transaction (`Data Layouts · CVACT01Y`, `CVACT03Y`, `CVTRA03Y`, `CVTRA04Y`, `CVTRA01Y`, `CVTRA05Y`). The access patterns are per-account key lookups and a newest-first keyset page over 1,000,000 transactions. The planning default is Azure Database for PostgreSQL Flexible Server.

**Choice.** Azure Database for PostgreSQL Flexible Server running PostgreSQL 16, the planning default, kept. The schema is in the [data model](data-model.md).

**Ranked alternatives.**

1. Azure Cosmos DB. Viable for per-account point reads with the account as partition key. It ranks below the choice because the foreign keys between the six entities, the reference joins for descriptions and the composite keyset order would all need application-side integrity and composite indexes, moving into code what the data model states as constraints.
2. Snowflake. Built for analytic scans over large tables, not for sub-second concurrent key lookups. It ranks last because each stub attempt must finish inside a 300 ms timeout, and the access pattern holds no analytic workload that would repay the mismatch.

**Reasoning.** The choice follows the source structure. The six-entity foreign-key structure maps one-to-one onto relational constraints. Per-account key lookups are primary-key or single-column index seeks. The keyset page is a composite B-tree with an equality leading key and the row-value predicate `(processed_timestamp, transaction_id) < ($1, $2)`, which PostgreSQL 16 serves in index order ([D4](#d4-transaction-to-account-access)). Read-only access needs no write scaling.

Dependency rows that follow the store stay as pinned: Spring Data JPA, the PostgreSQL JDBC driver, Testcontainers `postgresql`, the `postgres:16.6` image and the `DB_*` variables. A Cosmos DB selection would replace them with the Spring Data Azure Cosmos DB starter and the Cosmos DB emulator; a Snowflake selection with the Snowflake JDBC driver. Each would be pinned the same way.

**Pressure-test.**

- Failure modes: a zone-redundant failover takes tens of seconds, during which the stubs answer `UNAVAILABLE`. Account and category-balance reads serve `STALE` from cache within the 300 s max stale age; the transaction list returns 503; transaction detail fails with 503 or 504 when the cross-reference read fails, even with a cached transaction; reference data falls back to cache, otherwise `PARTIAL`.
- Data growth: unfiltered transaction pages and every key lookup stay one index seek as total volume grows. Filtered pages grow with one account's history and are pressure-tested under D4. Monthly partitioning of `transaction_record` is flagged in [Recommendations](implementation-plan.md#recommendations) with the 1,000,000-row baseline it assumes.
- 1-second SLA: the account and single-transaction lookups are primary-key seeks, and an unfiltered transaction page is one index seek plus `pageSize + 1` entries; the cross-reference and category-balance reads are one seek followed by a range over that one account's rows, and the reference-data reads return the 7 type and 18 category rows, so none of these reads grows with total volume. Index depth alone does not bound an attempt's latency, because the rows visited and their I/O still count. A filtered transaction page scans one account's history ([D4](#d4-transaction-to-account-access)), so its latency is measured by CS-03-003 against the 150 ms p99 trigger, with D4's three filter-leading indexes recorded as the remedy. The 300 ms per-attempt timeout is a budget each stub attempt is validated against, not a guarantee derived from the index; the performance scenarios prove p50, p95 and p99 below 1,000 ms at the planning baseline.

### D2 Epic grouping

**Context.** The request fixes exactly three epic groupings (UI interaction, service endpoint, external interaction), forbids a Feature layer and requires that no requirement sits in two epics. Shared foundations (the service scaffold and CI gate, the error object, configuration properties, stub hosting, the call policy, the read-through cache, the fixture loader core, the page-token codec, the access guard and the request-parameter validator) are used by epics in more than one grouping.

**Choice.** The request's three groupings: CS-01 to CS-04 one per UI interaction, CS-05 to CS-08 one per UI-facing endpoint, CS-09 to CS-13 one per external interaction. Each shared foundation is owned by the epic of its first consumer in build order, and every other consumer depends on the owning story instead of re-implementing it.

**Ranked alternatives.**

1. The same groupings, with each shared foundation owned by the epic it most resembles (the cache with Card Data, the error object with the first endpoint). It ranks below the choice because ownership becomes a judgment call for components every epic uses, such as the upstream call policy, and two reviewers can place the same component in different epics.

Counterfactuals, not counted as alternatives because each breaks a fixed input: vertical-slice or layer-based epics break "exactly these three groupings"; each consumer epic carrying its own story for every shared foundation puts one requirement in more than one epic and breaks the non-overlap rule.

**Reasoning.** First-consumer ownership is decidable from the build order alone, so every foundation has exactly one owner and every consumer has a dependency edge to it. Endpoint epics own server behaviour, UI-interaction epics own verification along the real request path, and external epics own everything behind a client interface.

**Pressure-test.**

- Failure modes (boundary drift): single ownership contains drift; a change to a foundation touches its one owning story, and the dependency map shows every consumer affected.
- Data growth (a new interaction): a new interaction adds one UI epic and one endpoint epic and reuses the existing integrations and foundations.
- 1-second SLA: SLA ownership sits in the UI epics, whose SLA scenarios measure each endpoint; deadline behaviour sits in the endpoint epics' acceptance criteria.

### D3 Stub hosting and swap point

**Context.** All five external interactions are stubbed with mock data and a documented swap point, with zero live external calls. The contracts must be exercised, and timeouts must act on a real channel.

**Choice.** One gRPC client per interaction, generated from its proto. Stub gRPC servers answered by mock-data providers, on a loopback port inside the JVM for unit and integration suites (`StubHarness` and `StubRegistration` under `card-servicing-service/src/test/java/com/example/cardservicing/support/`, CS-11-002) and as containers in the Compose stack (CS-01-001). The swap point is each client's target address and credential source.

**Ranked alternatives.**

1. Mock-provider beans behind a Java interface, with no gRPC. It ranks below the choice because the contracts stay unexercised and timeouts, retries and deadlines never act on a real channel.
2. The gRPC in-process transport. It ranks last because it needs `grpc-inprocess`, which is absent from the pinned dependency table.

**Reasoning.** A loopback gRPC server runs the generated stubs, the real serialization and the real deadline propagation, so a suite that passes against a stub proves the client against its contract. `grpc-netty-shaded`, already pinned, hosts both the API and the stubs.

**Pressure-test.**

- Failure modes: a crashed or killed stub yields `UNAVAILABLE` exactly as a real outage would, and failure injection (`STUB_LATENCY_MS`, `STUB_FAILURE_RATE`) produces latency and `UNAVAILABLE` on demand. The real swap changes only the target address and credential source, so no failure path is stub-specific.
- Data growth: the stubs read the same PostgreSQL tables at volume in the Compose stack, so performance runs exercise the real index paths.
- 1-second SLA: loopback adds sub-millisecond latency, so measured latency is service and store latency.

### D4 Transaction-to-account access

**Context.** The transaction list reads one account's transactions newest-processed first, one page at a time. The baseline Transaction entity links to the account only through its card number and the cross-reference.

**Choice.** A denormalized `account_id` column on `transaction_record` (foreign key to `account`), populated from the cross-reference at load, with the index `(account_id, processed_timestamp desc, transaction_id desc)`; the baseline card index is kept. See [denormalization of transaction account](data-model.md#denormalization-of-transaction-account).

**Ranked alternatives.**

1. The card-number index with `card_number = ANY(cards)` and a sort. It ranks below the choice because the card-leading index loses the global `(processed_timestamp, transaction_id)` order, so every page sorts and its cost grows with the account's history.
2. A join through `card_cross_reference` on each page. It ranks last because it has the same ordering problem and adds a join to every page.

**Reasoning.** There are no writes this iteration, so the column cannot drift: the fixture loader is its only writer and derives it. It costs about 12 bytes per row. An unfiltered page is one index seek plus `pageSize + 1` entries, whatever the total volume.

**Pressure-test.**

- Failure modes: a store failover returns 503 for the list (D1); a drift between the column and the cross-reference cannot arise without writes.
- Data growth, unfiltered: one seek plus `pageSize + 1` entries per page as total volume grows.
- Data growth, filtered: `type_code` and `category_code` are not in the index key. A filtered page scans the account's entries in index order, rejecting rows until `pageSize + 1` match, so its cost grows with the account's history divided by the filter's selectivity, not with total volume. In the worst case, a sparse filter on a large history, one page reads the whole history.
- 1-second SLA, validation: CS-03-003 measures four shapes on account `10000000001`, which holds 100,000 transactions of which 20 carry the type and category pair `05`/`0001`: unfiltered, `typeCode` only, `categoryCode` only, and both filters, the last returning the sparse 20. Each shape gets p50, p95 and p99, all below 1,000 ms. The Transaction Activity stub's request log records the duration of each `ListTransactions` call and must show no `DEADLINE_EXCEEDED` retry.
- Trigger: any filtered shape whose stub `ListTransactions` call exceeds 150 ms at p99 (half the 300 ms per-attempt timeout) fails CS-03-003.
- Remedy, recorded in [Recommendations](implementation-plan.md#recommendations) with the trigger and the 1,000,000-row baseline: three filter-leading indexes on `transaction_record`, each fixing every filtered column by equality ahead of the cursor order, so rows come back in cursor order with no sort: `(account_id, type_code, processed_timestamp desc, transaction_id desc)` for `typeCode` alone; `(account_id, type_code, category_code, processed_timestamp desc, transaction_id desc)` for both filters; `(account_id, category_code, processed_timestamp desc, transaction_id desc)` for `categoryCode` alone. Before the change is accepted, CS-03-003's four shapes are rerun with the remedy in place, and each must stay at or below 150 ms and below 1,000 ms at p99.

### D5 One Compose stack for both suites

**Context.** The request gives the performance and end-to-end suites the same command and file, `docker compose -f tests/docker-compose.yml up -d --wait`, yet describes the end-to-end stack as holding "the small fixture set" and the performance stack as generating volume at stack start. This decision resolves that input conflict.

**Choice.** One stack whose loader always loads the CardDemo sample and then generates the synthetic volume in a disjoint ID range: sample accounts `00000000001`–`00000000050`, synthetic accounts `10000000001`–`10000010000` ([synthetic volume](data-model.md#synthetic-volume)). The end-to-end suite reads only the sample range, so it observes the small fixture set; the performance suite reads the synthetic range.

**Ranked alternatives.**

1. One Compose file whose loader reads a data-set variable interpolated by Compose (for example `${FIXTURE_SET:-full}`), exported as `small` by the end-to-end CI step. It ranks below the choice because the command text stays identical but its result depends on the caller's shell environment, so a local run and a CI run of the same command can load different data.

Counterfactual, not counted as an alternative: separate Compose files or Compose profiles, which change the request's `docker compose -f tests/docker-compose.yml up -d --wait` command.

**Reasoning.** One store holding both data sets in disjoint ranges meets both descriptions with the one command, and the result never depends on the environment of whoever runs it.

**Pressure-test.**

- Failure modes: a loader failure fails `--wait` fast, because the stubs and the service start only after the loader completes successfully ([Compose topology](#test-and-performance-harness)).
- Data growth: the end-to-end start also pays the volume load. Its time is measured by CS-10-008 and bounded by the `--wait` health timeout.
- 1-second SLA: synthetic rows match the sample's shape, so measured latency is representative; the end-to-end suite reads only `00000000001`–`00000000050`, so volume never changes its expected values.


## Resilience

Timeouts, retries and the deadline (upstream call policy, CS-11-003):

- Per-attempt timeout: 300 ms (`UPSTREAM_TIMEOUT_MS`, replaced by Config `upstream_timeout_ms` once a snapshot is held), cut to the remaining budget: each attempt times out at the lesser of 300 ms and the time left before the cut-off.
- Retries: at most 2 (`UPSTREAM_MAX_RETRIES`, replaced by Config `upstream_max_retries` once held), with 50 ms then 100 ms backoff, on `UNAVAILABLE` and `DEADLINE_EXCEEDED` only. Any other status, `NOT_FOUND` and `INVALID_ARGUMENT` included, is never retried. A retry starts only if its backoff plus a full 300 ms attempt fits in the remaining budget.
- Request deadline: 900 ms (`REQUEST_DEADLINE_MS`) from receipt to the cut-off ([deadline boundary](#deadline-boundary)). `REQUEST_DEADLINE_MS` is never a Config key.

Failure injection (CS-11-002): every stub reads `STUB_LATENCY_MS` (delay before answering) and `STUB_FAILURE_RATE` (fraction of calls answered `UNAVAILABLE`), both overridable per stub and per test. Every stub records each call in its request log with the operation, the outcome and the call's duration. The integration suites, the endpoint deadline tests and the end-to-end and performance harnesses read these logs.

Degradation table. Every stage-3 story follows it; the outcome applies when the call fails after retries or is unfinished at the cut-off.

| Upstream call | Used by | Outcome when the call fails after retries or at the cut-off | Effect on dataStatus |
|---|---|---|---|
| `ValidateToken` | All four endpoints (stage 1) | Fail closed: 401 `UNAUTHENTICATED` for a missing, invalid, expired or malformed token, otherwise 503 `UNAVAILABLE` or 504 `DEADLINE_EXCEEDED`; no account data read from upstream or cache | None; no 200 is returned |
| `AuthorizeAccountAccess` | All four endpoints (stage 2) | Fail closed: `allowed=false` returns 403 `PERMISSION_DENIED`, a failed call 503 or 504; no account data read from upstream or cache | None; no 200 is returned |
| `GetConfig` | All four endpoints (background refresh only) | Request unaffected: the held snapshot values are used, otherwise the built-in defaults (20, 100, 300, 2) | Never affects `dataStatus` |
| `GetAccount` | Balance summary | Serve the cached entry within the 300 s max stale age, otherwise 503 (`UNAVAILABLE`) or 504 (`DEADLINE_EXCEEDED`) | `STALE` when a stale entry is served |
| `ListCategoryBalances` | Balance summary, category balances | Same as `GetAccount` | `STALE` when a stale entry is served |
| `ListTransactionTypes` | Balance summary | Serve the cached entry within its max stale age, otherwise `typeDescription` is `null` in REST and unset in gRPC | `PARTIAL` when descriptions are missing |
| `ListTransactionCategories` | Category balances | Serve the cached entry within its max stale age, otherwise `typeDescription` and `categoryDescription` are `null` in REST and unset in gRPC; the unknown-`typeCode` check is skipped and the `typeCode` filter still applies | `PARTIAL` when descriptions are missing |
| `ListTransactions` | Transaction list | Fail hard: 503 or 504; a page is never served stale | None; no 200 is returned |
| `GetTransaction` | Transaction detail | Serve the cached transaction entry within its max stale age, otherwise 503 or 504 | `STALE` when a stale entry is served |
| `GetCardCrossReference` | Transaction detail | Fail hard: 503 or 504, even when the transaction came from cache | None; no 200 is returned |

Cache rules (read-through cache, CS-11-004):

- Every entry carries `cachedAt`; every key has a fresh TTL and a max stale age, both measured from `cachedAt`. A fresh entry is served with no upstream call. On a miss or a stale entry the upstream is called; when that call fails after retries or at the cut-off, an entry within its max stale age is served where the degradation table allows it.
- A Redis failure is treated as a miss, so a cache outage never fails a request on its own.
- Keys carry no caller identity, and authorization always precedes a cache read. A cache hit removes stage-3 upstream calls but never the two Auth calls.
- Only the service reads or writes Redis.

| Entry | Key | Fresh TTL | Max stale age |
|---|---|---|---|
| Configuration snapshot mirror | `cs:v1:config` | 300 s | No stale limit: on a failed refresh the held values are kept |
| Transaction types | `cs:v1:reftypes` | 3,600 s | Set by the Reference Data client story (CS-13-003) |
| Transaction categories | `cs:v1:refcats` | 3,600 s | Set by the Reference Data client story (CS-13-003) |
| Account | Named under `cs:v1:` by the Card Data client story (CS-09-005) | Set by CS-09-005 | 300 s |
| Category balances of an account | Named under `cs:v1:` by CS-09-005 | Set by CS-09-005 | 300 s |
| Single transaction | Named under `cs:v1:` by the Transaction Activity client story (CS-10-006) | Set by CS-10-006 | Set by CS-10-006 |
| Transaction-list page | Named under `cs:v1:` by CS-10-006, bound to the account, filters, page size and page token | Set by CS-10-006 | None: a page is served only while fresh |
| Card cross-references | Not cached | — | — |

Where a value reads "Set by", the plan states no number, and the owning client story fixes it within these rules. Card cross-references are not cached: `GetCardCrossReference` is always an upstream call, so the ownership check of the transaction detail reads current data and fails hard when it cannot.

`dataStatus` and `asOf`. Precedence is `PARTIAL` > `STALE` > `FRESH`: a response is `PARTIAL` when any description is missing, otherwise `STALE` when any stale entry was served, otherwise `FRESH`. `asOf` is the upstream read time, or the oldest `cachedAt` among the cache entries used. Config outcomes never affect either. The resolver is shared (CS-05-005).

Ownership. Failure injection and the request log sit in CS-11-002; the call policy and request deadline in CS-11-003; the cache rules in CS-11-004. Per-call degrade or fail-hard behaviour sits in the client stories CS-09-005, CS-10-006, CS-11-006, CS-12-002 and CS-13-003. Endpoint deadline outcomes sit in CS-05-005, CS-06-002, CS-07-002 and CS-08-002. Failure scenarios are proven in the five integration suites CS-09-006, CS-10-007, CS-11-007, CS-12-005 and CS-13-004.

## Security

[Security architecture](architecture/security-architecture.mmd) extends the physical view: the `P<n>` numbering is removed and controls are numbered `S1`–`S8`, keyed in the [security control table](#security-control-table).

- Authentication, then authorization: `ValidateToken` returns the token's subject and roles; then `AuthorizeAccountAccess(subject, accountId)` decides access for the path `accountId`. Both run before any account-data read, cache reads included. `allowed=false` maps to 403 `PERMISSION_DENIED`.
- `AccountAccessGuard` (CS-12-003) runs as a REST filter and a gRPC interceptor. It logs one access-decision line per request with subject, `accountId`, outcome and `traceId`. On an allow it calls `UpstreamSettings.accessAllowed(token)`, the launch point of the background Config refresh ([configuration snapshot](#configuration-snapshot)).
- A caller may supply only the bearer token and the path and query parameters, all validated and authorized; never subject, roles or entitlements. An inbound `x-api-key` is ignored.
- Tokens and API keys are never logged. Cache keys carry no caller identity.
- The service forwards the caller's bearer token to every upstream in `authorization` metadata and sends its own credential in `x-api-key`, from `AUTH_SERVICE_API_KEY`.
- Redis uses TLS when `REDIS_SSL_ENABLED=true`.
- Deployed secrets come from Key Vault through the Key Vault secret source (CS-12-004), enabled only when `AZURE_KEYVAULT_URI` is set.
- PostgreSQL and Redis encrypt data at rest. Service-to-service traffic inside AKS uses mTLS.
- The sample data is synthetic and holds no real personal data, so standard per-account access control applies.

### Security control table

| Control | Kind | Protected components | Key |
|---|---|---|---|
| S1 | Threat boundary | `ApiGateway` | Internet edge: the API gateway is the only component that admits north–south traffic, and it terminates TLS at the cluster edge. |
| S2 | Threat boundary | `CardServicingService`, `AccountAccessGuard`, `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ConfigService`, `ReferenceDataService`, `AuthService` | Cluster trust zone: workloads in the AKS cluster accept traffic only from the gateway or from each other. |
| S3 | Threat boundary | `PepPostgres`, `PepRedis`, `PepKeyVault`, `AzurePostgres`, `AzureManagedRedis`, `AzureKeyVault` | Private data zone: PostgreSQL, Redis and Key Vault are reachable only through their private endpoints. |
| S4 | mTLS | `ApiGateway`, `CardServicingService`, `CardDataService`, `TransactionActivityService`, `ConfigService`, `ReferenceDataService` | Every hop between two Compute or External-systems components uses mutual TLS. The guard's calls to the Auth service are drawn with their S5 and S6 labels. |
| S5 | AuthN/authZ | `AccountAccessGuard`, `AuthService` | Token validation: the guard validates the bearer token through the Auth service before any data read, and an invalid token returns 401. |
| S6 | AuthN/authZ | `AccountAccessGuard`, `AuthService` | Per-account authorization: the guard asks the Auth service whether the subject may access the path account, following the access-control matrix, and a denial returns 403 before any data or cache read. |
| S7 | Secret | `CardServicingService`, `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ReferenceDataService` | Deployed secrets come from Key Vault, never from committed files. The consumers are exactly the components the environment-variable table lists as reading `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` or `DB_PASSWORD`. |
| S8 | Encryption at rest | `AzurePostgres`, `AzureManagedRedis` | Both managed data stores encrypt stored data at rest. |

Diagram form of each kind in `security-architecture.mmd`:

- Threat boundary: a subgraph whose title starts with its `S<n>` and contains its protected components. S1 is the `AzureVNet` subgraph titled "S1 Internet edge", holding `AksSubnet` with `ApiGateway`. S2 is the `AzureAKS` subgraph titled "S2 Cluster trust zone". S3 is the `PepSubnet` subgraph titled "S3 Private data zone", which in this view also holds the three managed services behind their private endpoints. `AzureSubscription` wraps them all.
- mTLS: the edge label `S4 mTLS` on every edge between two Compute or External-systems components: `ApiGateway → CardServicingService` and `CardServicingService` to each of `CardDataService`, `TransactionActivityService`, `ConfigService` and `ReferenceDataService`.
- Secret: an edge `AzureKeyVault → <consumer>` labelled `S7` for each S7 consumer.
- Encryption at rest: `S8` in the labels of `AzurePostgres` and `AzureManagedRedis`.
- AuthN/authZ: the edges `AccountAccessGuard → AuthService` labelled `S5` (token validation) and `S6` (account authorization), and every `Acm*` node linked to `AccountAccessGuard`.
- Data-path edges to the private endpoints (`TLS`, `Private link`) carry no S number.
- Node budget: the physical component set minus `CardServicingUi`, plus `AccountAccessGuard`: 15 components, 5 legend nodes, 1 description and 3 `Acm*` nodes, 24 in all. `CardServicingUi` is omitted because a browser edge is not mTLS.

Every `S<n>` in the diagram has a row above, and every row has its diagram form on the components it names.

### Access-control matrix

| Diagram node | Caller | Fixture identities | Outcome on an account path |
|---|---|---|---|
| `AcmRegularUser` | Regular user | `USER0001`–`USER0005` | Entitled accounts only: 200 for an entitled account, otherwise 403 `PERMISSION_DENIED` |
| `AcmAdminUser` | Admin user | `ADMIN001`–`ADMIN005` | Valid token with no accounts: 403 `PERMISSION_DENIED` on every account path ([admin fixture policy](#admin-fixture-policy)) |
| `AcmInvalidToken` | Missing, expired or malformed token | One expired and one malformed fixture token | 401 `UNAUTHENTICATED`; no authorization call and no data read |

### Mock-to-real swap and credential management

- Per external system, replacing the stub with the real service changes only that client's target address and credential source. Contracts, clients, call policy, cache and degradation rules stay as they are.
- `AUTH_SERVICE_API_KEY` is an empty placeholder in the stub phase and comes from Key Vault when deployed. The same applies to `REDIS_PASSWORD` and `DB_PASSWORD`, whose stub-phase values are throwaway local-container values.
- The stubs ignore the API key and do not re-authorize.
- No real secret is committed: the only credential-like values in the repository are empty placeholders or throwaway local-container values.
- Ownership of the data store once real services replace the stubs is open and recorded in [Recommendations](implementation-plan.md#recommendations).

### Admin fixture policy

- The two Use Cases sources disagree. The CardDemo README states that "the Admin users can only perform Admin functions". The main menu program (`Use Cases · COMEN01C`) blocks only regular users from admin-only options, and the account view program (`Use Cases · COACTVWC`) carries no admin prohibition.
- CardDemo has no user-to-account entitlement at all, so either reading has to be expressed as fixture data.
- The plan follows the README statement, the application-level rule and the least-privilege reading. `ADMIN001`–`ADMIN005` hold valid tokens with no accounts. `USER0001`–`USER0005` are each entitled to ten sample accounts in ID order (`USER0001` → `00000000001`–`00000000010`, `USER0002` → `00000000011`–`00000000020`, through `USER0005` → `00000000041`–`00000000050`) plus a 2,000-account slice of the synthetic range. The fixtures also hold one expired and one malformed token.
- This is a fixture policy, not an inherited rule. It is set in `card-servicing-service/src/main/resources/fixtures/auth-identities.yaml` (CS-12-001), and changing it is an edit to that file with no code change. The missing entitlement source is listed in [Recommendations](implementation-plan.md#recommendations).

## Card Servicing UI

UI screens and mock-ups are deferred. This section gives the deferred UI run what it needs; until then the end-to-end client (CS-01-002) stands in for the UI along the real request path.

Field map, Use Cases label to contract field:

| Interaction | Use Cases label or record field | Contract field |
|---|---|---|
| Account balance summary (`Use Cases · COACTVWC`, account section) | Account Number | `accountId` |
| Account balance summary | Active Y/N | `activeStatus` |
| Account balance summary | Opened | `openDate` |
| Account balance summary | Credit Limit | `creditLimit` |
| Account balance summary | Expiry | `expirationDate` |
| Account balance summary | Cash credit Limit | `cashCreditLimit` |
| Account balance summary | Reissue | `reissueDate` |
| Account balance summary | Current Balance | `currentBalance` |
| Account balance summary | Current Cycle Credit | `currentCycleCredit` |
| Account balance summary | Account Group | `groupId` |
| Account balance summary | Current Cycle Debit | `currentCycleDebit` |
| Account balance summary | Type-group balances (no screen label; `Data Layouts · CVTRA01Y · TRAN-CAT-BAL`) | `balancesByTypeGroup` (`typeCode`, `typeDescription`, `balance`) |
| Category balance view (`Data Layouts · CVTRA01Y`) | TRANCAT-TYPE-CD | `typeCode` |
| Category balance view | TRANCAT-CD | `categoryCode` |
| Category balance view | TRAN-CAT-BAL | `balance` |
| Category balance view | TRAN-TYPE-DESC (`Data Layouts · CVTRA03Y`) | `typeDescription` |
| Category balance view | TRAN-CAT-TYPE-DESC (`Data Layouts · CVTRA04Y`) | `categoryDescription` |
| Transaction list (`Use Cases · COTRN00C`) | Transaction ID | `transactionId` |
| Transaction list | Date | `originalTimestamp` |
| Transaction list | Description | `description` |
| Transaction list | Amount | `amount` |

Transaction detail (`Use Cases · COTRN01C`) maps all 13 labels, as listed in the [screen-field coverage matrix](data-model.md#screen-field-coverage-matrix): Transaction ID → `transactionId`, Card Number → `cardNumber`, Type CD → `typeCode`, Category CD → `categoryCode`, Source → `source`, Description → `description`, Amount → `amount`, Orig Date → `originalTimestamp`, Proc Date → `processedTimestamp`, Merchant ID → `merchantId`, Merchant Name → `merchantName`, Merchant City → `merchantCity`, Merchant Zip → `merchantZip`.

The 17 Customer Details labels of the account view are field gaps with no balance-summary field: 16 come from the customer record `Data Layouts · CVCUS01Y`, which no baseline entity or operation holds, and Customer id is a baseline cross-reference field not exposed on the balance summary. They are listed in the [screen-field coverage matrix](data-model.md#screen-field-coverage-matrix) and the [gap list](implementation-plan.md#card_req_tbd-gap-list).

Drill-down navigation: summary row → `typeCode` → category row → `typeCode` + `categoryCode` → transaction row → `transactionId`, with `accountId` carried throughout.

Paging: forward with `nextPageToken`; an absent `nextPageToken` ends the list. Backward paging replays the tokens the UI kept from earlier pages, which mirrors the PF7/PF8 keys of `Use Cases · COTRN00C` with no server change. The UI may request `pageSize=10` to match the COTRN00 page.

Presentation obligations:

- A visible indicator when `dataStatus` is `STALE`, and another when it is `PARTIAL` (descriptions `null`).
- One message per error status: 400, 401, 403, 404, 503 and 504.

## Request path

All four endpoints follow the same five stages. Endpoint stories, sequence diagrams and tests follow this order exactly.

### Stages

| Stage | What happens | Budget and residual-deadline rule |
|---|---|---|
| 0 Receipt | Start the 900 ms deadline (`REQUEST_DEADLINE_MS`) and generate the `traceId`. Read the in-memory [configuration snapshot](#configuration-snapshot), launching no refresh here. Syntactic validation: the `accountId` pattern `^[0-9]{11}$` and the all-zero rule; the `transactionId`, `typeCode` and `categoryCode` patterns; `pageSize` an integer 1–100; `pageToken` decodable, with `v` = 1 and a fingerprint matching `accountId` and the filters. Snapshot-dependent validation: `pageSize` no larger than the snapshot's `max_page_size`. | No network call. Any failure returns 400 before any upstream call. |
| 1 Authentication | `ValidateToken` on the bearer token. | Each attempt times out at the lesser of 300 ms and the remaining budget; a retry starts only if its backoff plus a full 300 ms attempt fits. Outcomes 401, 503 or 504, failing closed. |
| 2 Authorization | `AuthorizeAccountAccess(subject, accountId)`. Once access is allowed, launch the background Config refresh if the snapshot is stale or absent. | Same rule on the residual budget. `allowed=false` returns 403. A deadline exhausted here returns 504 with no account data read from upstream or cache. The refresh is never awaited. |
| 3 Data reads and reference-dependent validation | The endpoint's concurrent reads (table below), each through the read-through cache. Then the reference-dependent check: an unknown `typeCode` on category balances returns 400 and the data read is discarded. | Same per-attempt rule. At the cut-off, each unfinished call degrades per the [degradation table](#resilience) or the request returns 504. |
| 4 Respond | Assemble the response, resolve `dataStatus` and `asOf`, serialize. | Runs after the cut-off, inside the 100 ms the request leaves for serialization and network. |

| Endpoint | Stage 3 concurrent reads | Sequence diagram |
|---|---|---|
| Balance summary | `GetAccount` ‖ `ListCategoryBalances` ‖ `ListTransactionTypes`; `CategoryBalanceAggregator` then sums the category balances by type | [sequence-balance-summary.mmd](architecture/sequence-balance-summary.mmd) |
| Category balances | `ListCategoryBalances` ‖ `ListTransactionCategories` (which also decides "unknown `typeCode`") | [sequence-category-balances.mmd](architecture/sequence-category-balances.mmd) |
| Transaction list | `ListTransactions`, with the page size from the snapshot and the page token passed through | [sequence-transaction-list.mmd](architecture/sequence-transaction-list.mmd) |
| Transaction detail | `GetTransaction` ‖ `GetCardCrossReference`, then the ownership check of the transaction's card number against the account's cross-references | [sequence-transaction-detail.mmd](architecture/sequence-transaction-detail.mmd) |

Each sequence diagram runs end to end from `CardServicingUi` through these stages: validation, the guard, the cache and the stage-3 upstream calls. `ConfigService` appears only inside an `opt` block for a stale snapshot, placed right after `AccountAccessGuard` allows access, as an asynchronous refresh the request does not wait for. Validation and denial paths show no Config call. A cut-off note precedes the stage-4 response.

Open → settled by CS-06-002 with CS-13-003: the category-balance contract carries `typeDescription` (`Data Layouts · CVTRA03Y · TRAN-TYPE-DESC`) beside `categoryDescription`, while the stage-3 reads fixed for that endpoint name `ListTransactionCategories` and not `ListTransactionTypes`. Whatever source those stories settle on adds no serialized call to the request, and a type label that cannot be resolved follows the same rule as a category label: `null` in REST, unset in gRPC, `dataStatus` `PARTIAL`.

### Deadline boundary

- The 900 ms deadline runs from receipt to the cut-off: the moment the service stops waiting for upstream calls and fixes the outcome, whether a full result, a degraded result or an error.
- Stage 4 runs after the cut-off and uses the following 100 ms for serialization and network. The plan never asserts that the response leaves the service within 900 ms.
- "Responds within the deadline", for every failure-injection scenario, means two things: the cut-off falls at or before 900 ms after receipt, and the client receives the response in under 1,000 ms.
- `RequestDeadline` (CS-11-003) writes `cutoffMs`, the time from receipt to the cut-off, into each request's completion log line with the `traceId` and the outcome. Tests assert on that value and on client-measured latency.

### Configuration snapshot

- One snapshot supplies `default_page_size`, `max_page_size`, `upstream_timeout_ms` and `upstream_max_retries` to every request and every upstream call. The Config client (CS-11-006, `ConfigSnapshot`) holds the last good `GetConfig` values in memory and mirrors them in `cs:v1:config` with a 300 s fresh TTL.
- Each request reads the in-memory snapshot at receipt, so Config never adds a serialized network call. A snapshot past its 300 s fresh TTL, or absent, is only noted at receipt, and the request uses what is held: the last cached values, otherwise the built-in defaults 20, 100, 300 (`UPSTREAM_TIMEOUT_MS`) and 2 (`UPSTREAM_MAX_RETRIES`).
- Until the first successful refresh every call, both Auth calls and `GetConfig` itself included, uses the built-in defaults.
- The single-flight background `GetConfig` is launched only after the request passes Stage 0 validation and Stage 2 allows access, through the `UpstreamSettings.accessAllowed(token)` hook, which `AccountAccessGuard` calls on an allow and `ConfigSnapshot` implements. The refresh carries that request's bearer token. A request rejected with a Stage 0 400, a 401 or a 403 therefore starts no network call beyond the Auth calls it needed.
- No request waits for the refresh. A success rewrites the snapshot and the Redis entry; a failure keeps the held values, with no stale limit.
- `REQUEST_DEADLINE_MS` is never a Config key.
- The call policy reads its values through the `UpstreamSettings` provider (CS-11-003), which starts on the built-in defaults with a no-op `accessAllowed` hook and is backed by the snapshot once CS-11-006 lands, so no dependency changes.

Launch-point tests: CS-11-006 proves that `accessAllowed` launches one refresh per stale period, single-flight under concurrent requests, without blocking the caller. CS-12-003 proves that the guard calls `accessAllowed` only after `allowed=true`. CS-05-003, CS-06-001, CS-07-001 and CS-08-001 prove that a Stage 0 400 leaves every stub request log empty, Config included. CS-12-005 proves that, with a stale snapshot, Stage 0 400, 401 and 403 requests leave the Config stub log empty while an allowed request yields exactly one `GetConfig`, and that an injected Config latency of 2,000 ms does not delay that request's response.

### Stage budget on a cold path

- With no fresh cache entries, stages 1 and 2 are sequential. Two first Auth attempts can consume up to 600 ms, leaving about 300 ms for stage 3: one full data attempt and no data retry.
- A retry in any stage happens only while its backoff plus 300 ms still fits.
- Slow but successful Auth calls, for example 250 ms each, leave about 400 ms for stage 3.
- Auth calls that exhaust the deadline end the request with 504 before any data read.
- These budgets end at the cut-off; stage 4 uses the 100 ms after it.

Owning tests: CS-11-003 covers the attempt timeout cut to the residual budget and the retry-fit rule. CS-12-003 and CS-12-005 cover slow successful Auth calls completing the request, and deadline exhaustion in stages 1–2 returning 504 with no call in the data stubs' request logs. Each stage-3 story, CS-05-005, CS-06-002, CS-07-002 and CS-08-002, covers residual-budget degradation or 504.

### Edge cases

- `balancesByTypeGroup` holds one element per `typeCode` with at least one category balance. Its `balance` is the exact decimal sum, and the order is `typeCode` ascending. Arrays are always present, empty when nothing matches; absent optional scalars are omitted.
- An all-zero `accountId` returns 400 with the message "Account number must be a non zero 11 digit number", following `Use Cases · COACTVWC`. The schema pattern stays `^[0-9]{11}$` and its description states the zero rule.
- Category balances: an unknown `typeCode` returns 400 when reference data is usable, fresh or within its max stale age. With no usable reference data the check is skipped, the filter applies and `dataStatus` is `PARTIAL`.
- Transaction list: a well-formed but unknown `typeCode` or `categoryCode` returns 200 with empty items, because the degradation table gives this endpoint no reference-data call. `TransactionActivityService.ListTransactions` returns `NOT_FOUND` when the account has no cross-reference record, so "404 account unknown" is reachable; an existing account with no match returns empty items.
- Transaction detail: `NOT_FOUND` from `GetTransaction`, or a card number absent from the account's cross-reference records, returns 404. A cross-reference failure returns 503 or 504 even when the transaction came from cache.
- `dataStatus` precedence is `PARTIAL` > `STALE` > `FRESH`; `asOf` is the upstream read time or the oldest `cachedAt` used. Config outcomes never affect `dataStatus`.
- Every error carries the `ErrorObject` with the `traceId` generated at receipt.

Error mapping, the same for REST and gRPC:

| Condition | REST status | `code` |
|---|---|---|
| Malformed or all-zero parameter, `pageSize` out of bounds, or unknown `typeCode` on category balances with usable reference data | 400 | `INVALID_ARGUMENT`, with a `details` entry naming the field |
| `INVALID_ARGUMENT` on paging, from the service's own token check or the upstream | 400 | `INVALID_ARGUMENT`, with a `details` entry for `pageToken` |
| `UNAUTHENTICATED` | 401 | `UNAUTHENTICATED` |
| `allowed=false` from `AuthorizeAccountAccess` | 403 | `PERMISSION_DENIED` |
| Upstream `NOT_FOUND`, or a transaction whose card is not on the account | 404 | `NOT_FOUND` |
| `UNAVAILABLE` after retries | 503 | `UNAVAILABLE` |
| `DEADLINE_EXCEEDED`, or the request deadline reached at the cut-off | 504 | `DEADLINE_EXCEEDED` |

## Test and performance harness

Five suites, each with its command and pass condition. Commands run from the repository root; the JUnit tags `integration` and `e2e` are excluded from the `test` task.

| Suite | Command | Runs against | Pass condition |
|---|---|---|---|
| Unit | `./gradlew :card-servicing-service:test jacocoTestCoverageVerification` | In-JVM loopback stubs with per-test failure injection | All tests pass; line coverage at least 80% in every module |
| Integration | `./gradlew :card-servicing-service:integrationTest` | Testcontainers `postgres:16.6` and `redis:7.4.2` through `IntegrationTestSupport` (CS-11-007), with in-JVM stubs | All pass; one suite per external epic covering a stub exception, latency above `UPSTREAM_TIMEOUT_MS`, and the `UPSTREAM_MAX_RETRIES` cap |
| Performance | `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:performanceTest`, then `docker compose -f tests/docker-compose.yml down` | The Compose stack with failure injection off | For each UI-facing endpoint, 10 concurrent users × 100 sequential calls, p50, p95 and p99 below 1,000 ms over successful requests |
| End-to-end | `docker compose -f tests/docker-compose.yml up -d --wait`, then `./gradlew :card-servicing-service:e2eTest`, then `docker compose -f tests/docker-compose.yml down` | The Compose stack | Four steps in sequence return the expected fixture values for account `00000000001`, and each stub's request log shows at least one call |
| Validation | `tests/validation/run-all.sh` | Node.js 22 with the pinned `buf` 1.50.0, Spectral 6.15.0 and `mmdc` 11.4.2 | Every check reports zero failures, ending `Validation: all 7 checks passed` |

Suite ownership: the unit task and coverage rule come from CS-05-001, with tests in every code story. The integration stage comes from CS-11-007, with the suites CS-09-006, CS-10-007, CS-11-007, CS-12-005 and CS-13-004. The end-to-end harness comes from CS-01-002, with the steps CS-01-004 (balance summary), CS-02-001 (category balances), CS-03-001 (transaction list), CS-03-002 (paging navigation) and CS-04-001 (transaction detail, including the stub request-log assertion). Its HTTP client, `E2eClient`, uses a fixture token and reads the stub request logs. The performance harness comes from CS-01-003, with the scenarios CS-01-005, CS-02-002, CS-03-003 and CS-04-002.

Performance runner (`tests/performance/`, CS-01-003):

- It drives the ten users with the JDK `java.net.http` client only. It adds no load-testing tool or library and computes p50, p95 and p99 itself.
- Each UI-facing endpoint gets 10 concurrent users × 100 sequential calls at the planning baseline (100,000 category balances, 1,000,000 transactions). The percentiles cover successful requests and must all be below 1,000 ms.
- The run keeps the Compose defaults `STUB_LATENCY_MS=0` and `STUB_FAILURE_RATE=0`, and any non-200 response fails the scenario.
- Injected-failure scenarios are not part of the percentiles. They run where per-test overrides apply: the in-JVM stubs of the five integration suites and the endpoint deadline tests of CS-05-005, CS-06-002, CS-07-002 and CS-08-002. For each degraded 200, 503 or 504 they assert that the logged `cutoffMs` is at most 900 and that the client-measured response arrives in under 1,000 ms.
- The runner reads the stub request logs, which give the per-call durations the D4 filter scenarios need.

CI contract (`.github/workflows/ci.yml`):

- The workflow runs on every pull request with its stages in the fixed order Unit → Integration → Performance → End-to-end → Validation. Each stage needs the one before it, and a failure in any stage fails the gate.
- CS-05-001 creates the workflow with its tool setup (Temurin 25, Node.js 22, Docker Compose 2.29.7) and the Unit and Validation stages.
- CS-11-007 adds Integration, CS-01-003 adds Performance and CS-01-002 adds End-to-end, each in its slot. Each of those stories carries an acceptance criterion that its stage runs in that position on a pull request and that a failing suite fails the gate.

Compose topology (`tests/docker-compose.yml`, CS-01-001):

- Services: `postgres:16.6`, `redis:7.4.2`, the fixture loader, the five stub services and the Card Servicing service, each with a health check so that `--wait` succeeds only when the stack is ready.
- The fixture loader is a one-shot job that loads the CardDemo sample and then the synthetic volume; the stubs and the service start only after it completes successfully, so a loader failure fails `--wait`.
- Every variable in the [environment-variable table](#environment-variables) is set in this file.
- Data partition ([D5](#d5-one-compose-stack-for-both-suites)): the end-to-end suite reads only the sample accounts `00000000001`–`00000000050`; the performance suite reads the synthetic accounts `10000000001`–`10000010000`.

D4 filter scenarios: CS-03-003 runs the four transaction-list shapes on the large-history account `10000000001` (unfiltered, `typeCode` only, `categoryCode` only, both), with the percentile, request-log and 150 ms trigger rules of [D4](#d4-transaction-to-account-access).

## Physical deployment key

[Physical deployment](architecture/physical-deployment.mmd) (`flowchart TB`) shows the target topology: `AzureSubscription` → `AzureVNet` → `AksSubnet` (holding `ApiGateway` and the `AzureAKS` cluster) and `PepSubnet` (holding the three private endpoints). Ingress runs through `ApiGateway`; PostgreSQL, Redis and Key Vault sit in the subscription outside the VNet and are reached only through their private endpoints. Edges are labelled north–south (into the subscription) or east–west (inside it).

| Item | Component | Placement | Key |
|---|---|---|---|
| P1 | `CardServicingUi` | Outside `AzureSubscription` | The deferred UI; its only edge is north–south HTTPS to the gateway. |
| P2 | `ApiGateway` | `AksSubnet`, at the cluster edge outside `AzureAKS` | Ingress that terminates TLS and forwards east–west to the service. |
| P3 | `CardServicingService` | Pods in `AzureAKS` | The service; calls the five external systems, Redis and Key Vault east–west. |
| P4 | `FixtureLoader` | Job in `AzureAKS` | One-shot load into PostgreSQL through its private endpoint. |
| P5 | `CardDataService` | Stub pods in `AzureAKS` | Card Data stub, replaced at the swap; reads PostgreSQL. |
| P6 | `TransactionActivityService` | Stub pods in `AzureAKS` | Transaction Activity stub, replaced at the swap; reads PostgreSQL. |
| P7 | `ConfigService` | Stub pods in `AzureAKS` | Config stub, replaced at the swap; reads bound properties. |
| P8 | `ReferenceDataService` | Stub pods in `AzureAKS` | Reference Data stub, replaced at the swap; reads PostgreSQL. |
| P9 | `AuthService` | Stub pods in `AzureAKS` | Auth stub, replaced at the swap; reads its fixture identity file. |
| P10 | `PepPostgres` | `PepSubnet` | Private endpoint for PostgreSQL. |
| P11 | `PepRedis` | `PepSubnet` | Private endpoint for Azure Managed Redis. |
| P12 | `PepKeyVault` | `PepSubnet` | Private endpoint for Key Vault. |
| P13 | `AzurePostgres` | Inside `AzureSubscription`, outside `AzureVNet` | Managed PostgreSQL Flexible Server, reached only through `PepPostgres`. |
| P14 | `AzureManagedRedis` | Inside `AzureSubscription`, outside `AzureVNet` | Managed Redis, reached only through `PepRedis`. |
| P15 | `AzureKeyVault` | Inside `AzureSubscription`, outside `AzureVNet` | Managed Key Vault, reached only through `PepKeyVault`. |

The access guard runs inside the service process, so the physical view draws Auth calls from `CardServicingService` to `AuthService` and does not show `AccountAccessGuard`. Node budget: 15 components, 5 legend nodes and 1 description, 21 in all.

No AKS deployment manifest is produced; the diagram documents the target topology only.

## Security architecture key

[Security architecture](architecture/security-architecture.mmd) (`flowchart TB`) extends the physical view with threat boundaries, mTLS, Key Vault secrets, authN/authZ, encryption at rest and a three-row access-control matrix. The physical `P<n>` numbering is removed and the controls are numbered `S1`–`S8`. The `S<n>` key is the [security control table](#security-control-table), one row per control with its kind, protected components and key, followed there by the diagram form of each kind; the `Acm*` nodes are keyed in the [access-control matrix](#access-control-matrix).

## Environment variables

All 14 variables are declared with defaults in `card-servicing-service/src/main/resources/application.yml` (CS-11-001) and set in `tests/docker-compose.yml` (CS-01-001). No real secret is committed: the only credential-like values are empty placeholders or throwaway local-container values.

| Variable | Purpose | Stub-phase value | Read by |
|---|---|---|---|
| `AUTH_SERVICE_API_KEY` | Service-to-service credential sent as `x-api-key` on every upstream call | Empty placeholder; the stubs ignore it | `CardServicingService` |
| `REDIS_HOST` | Cache host | Local Redis container host | `CardServicingService` |
| `REDIS_PORT` | Cache port | `6379` for the local container; Azure Managed Redis uses `10000` | `CardServicingService` |
| `REDIS_PASSWORD` | Cache credential | Empty for the local container; Key Vault when deployed | `CardServicingService` |
| `REDIS_SSL_ENABLED` | Turns on TLS to the cache | `false` for the local container; Azure Managed Redis uses `true` | `CardServicingService` |
| `DB_URL` | Data-store connection for the stub mock-data providers and the fixture loader | Testcontainers-provided or Compose local value | `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ReferenceDataService` |
| `DB_USERNAME` | Data-store user | Testcontainers-provided or Compose local value | `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ReferenceDataService` |
| `DB_PASSWORD` | Data-store credential | Testcontainers-provided or Compose local throwaway value; Key Vault when deployed | `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ReferenceDataService` |
| `AZURE_KEYVAULT_URI` | Secret source for deployed environments | Unset, which disables the Key Vault property source | `CardServicingService`, `FixtureLoader`, `CardDataService`, `TransactionActivityService`, `ReferenceDataService` |
| `UPSTREAM_TIMEOUT_MS` | Per-attempt upstream timeout in milliseconds, until a Config snapshot is held | `300` | `CardServicingService` |
| `UPSTREAM_MAX_RETRIES` | Upstream retry cap, until a Config snapshot is held | `2` | `CardServicingService` |
| `REQUEST_DEADLINE_MS` | Per-request deadline in milliseconds, receipt to cut-off; never a Config key | `900` | `CardServicingService` |
| `STUB_LATENCY_MS` | Stub failure injection: delay before answering, overridable per stub and per test | `0` | `CardDataService`, `TransactionActivityService`, `ConfigService`, `ReferenceDataService`, `AuthService` |
| `STUB_FAILURE_RATE` | Stub failure injection: fraction of calls answered `UNAVAILABLE`, overridable per stub and per test | `0` | `CardDataService`, `TransactionActivityService`, `ConfigService`, `ReferenceDataService`, `AuthService` |

The components that read `AUTH_SERVICE_API_KEY`, `REDIS_PASSWORD` or `DB_PASSWORD` are exactly the S7 consumers of the [security control table](#security-control-table).
