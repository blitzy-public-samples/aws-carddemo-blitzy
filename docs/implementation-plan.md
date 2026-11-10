# Card Servicing Implementation Plan

## Opening summary

This plan is the entry point to the review-ready backlog for the first, read-only Card Servicing iteration over the AWS CardDemo data model. The backlog holds 13 epics in three groupings — four UI interaction epics (CS-01 to CS-04), four service endpoint epics (CS-05 to CS-08) and five external interaction epics (CS-09 to CS-13) — and 58 stories, with no Feature layer. Every story traces to a Use Cases screen or a Data Layouts field, and every document, diagram and contract the stories rely on is linked from this page.

The 58 stories run in four waves:

- W0 Foundation delivers the service build and CI gate, shared configuration, error mapping, the upstream call policy, the read-through cache, stub hosting and the loaded sample data model.
- W1 Stubbed integrations delivers the five stubbed external systems (Card Data, Transaction Activity, Config, Reference Data and Auth) with their clients, integration suites and performance volume.
- W2 Service endpoints delivers the four UI-facing endpoints over REST and gRPC: balance summary, category balances, transaction list and transaction detail.
- W3 UI interaction verification delivers the Compose stack, the end-to-end and performance harnesses, and verification of the four UI interactions along the real request path, with the end-to-end HTTP client standing in for the deferred UI.

The waves are a dependency order, not a schedule. A wave opens when the exit condition of the wave before it holds, and a story starts when every story it depends on is done. The CI gate grows wave by wave to its fixed stage order: Unit, Integration, Performance, End-to-end, Validation.

**[INFERRED] Build order:** the data model and core balance logic precede their consumers, and stubs precede the stories that call them. This order is inferred and awaits Product Owner confirmation in [sign-off](sign-off.md).

At a glance:

- 13 epics, 58 stories, 175 points and 224 estimated files touched.
- 4 waves (W0 to W3), 102 story edges and 37 epic dependencies.
- Requirement sources: Use Cases and Data Layouts. Requirements Analysis does not exist yet, so all 58 stories trace `CARD_REQ_TBD`, and the 17 Customer Details labels of the account view are flagged field gaps.
- Five external systems, each mock-data-backed behind a gRPC client whose target address and credential source form the swap point; zero live external calls.

Contents:

- [Opening summary](#opening-summary)
- [Epics in scope](#epics-in-scope)
- [Sizing basis](#sizing-basis)
- [W0 Foundation](#w0-foundation)
- [W1 Stubbed integrations](#w1-stubbed-integrations)
- [W2 Service endpoints](#w2-service-endpoints)
- [W3 UI interaction verification](#w3-ui-interaction-verification)
- [Dependency Map](#dependency-map): [Story edges](#story-edges), [Epic dependencies](#epic-dependencies)
- [Feature Checklist coverage](#feature-checklist-coverage)
- [Recommendations](#recommendations)
- [CARD_REQ_TBD gap list](#card_req_tbd-gap-list): [Story gaps](#story-gaps), [Unmapped-field table](#unmapped-field-table)

Terms:

- **Wave**: a set of stories that share one exit condition; no story of a later wave starts before that condition holds.
- **Story edge**: one dependency of a story on a prerequisite story, as its metadata Depends on value lists it.
- **Epic dependency**: an ordered pair of epics joined by at least one story edge; a checked summary of those edges, not a scheduling constraint between whole epics.
- **Estimated files touched**: the files a story creates, edits or deletes, counted together as one integer; every epic and wave value is the sum of its stories.
- **`CARD_REQ_TBD`**: the requirement identifier every story carries until Requirements Analysis assigns one; the anchor after it names the Use Cases screen or Data Layouts field the story serves.

Artifacts:

- Backlog: [epics index](backlog/epics/README.md), which lists every epic folder; each epic README links its stories.
- Documents: [Technical Requirements and Architecture](technical-requirements.md), [Data Model](data-model.md), [Sign-off record](sign-off.md).
- Architecture diagrams:
  - [logical-architecture.mmd](architecture/logical-architecture.mmd)
  - [physical-deployment.mmd](architecture/physical-deployment.mmd)
  - [security-architecture.mmd](architecture/security-architecture.mmd)
  - [sequence-balance-summary.mmd](architecture/sequence-balance-summary.mmd)
  - [sequence-category-balances.mmd](architecture/sequence-category-balances.mmd)
  - [sequence-transaction-list.mmd](architecture/sequence-transaction-list.mmd)
  - [sequence-transaction-detail.mmd](architecture/sequence-transaction-detail.mmd)
- Shared contract types: [common.proto](../card-servicing-contracts/src/main/proto/cardservicing/common/v1/common.proto).
- gRPC contracts:
  - [card_servicing_service.proto](../card-servicing-service/src/main/proto/cardservicing/api/v1/card_servicing_service.proto) (UI-facing API)
  - [card_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/carddata/v1/card_data_service.proto)
  - [transaction_activity_service.proto](../card-servicing-service/src/main/proto/cardservicing/transactionactivity/v1/transaction_activity_service.proto)
  - [config_service.proto](../card-servicing-service/src/main/proto/cardservicing/config/v1/config_service.proto)
  - [reference_data_service.proto](../card-servicing-service/src/main/proto/cardservicing/referencedata/v1/reference_data_service.proto)
  - [auth_service.proto](../card-servicing-service/src/main/proto/cardservicing/auth/v1/auth_service.proto)
- OpenAPI 3.1 contracts:
  - [card-servicing-api.yaml](../card-servicing-service/src/main/resources/openapi/card-servicing-api.yaml) (UI-facing API)
  - [card-data-service.yaml](../card-servicing-service/src/main/resources/openapi/card-data-service.yaml)
  - [transaction-activity-service.yaml](../card-servicing-service/src/main/resources/openapi/transaction-activity-service.yaml)
  - [config-service.yaml](../card-servicing-service/src/main/resources/openapi/config-service.yaml)
  - [reference-data-service.yaml](../card-servicing-service/src/main/resources/openapi/reference-data-service.yaml)
  - [auth-service.yaml](../card-servicing-service/src/main/resources/openapi/auth-service.yaml)
- Lint configuration: [buf.yaml](../card-servicing-contracts/buf.yaml) (buf v2, STANDARD and COMMENTS), [.spectral.yaml](../.spectral.yaml) (Spectral `spectral:oas`).
- Validation entry point: `tests/validation/run-all.sh`, run from the repository root, checks every artifact above.

Wave summary:

| Wave | Name | Stories | Points | Estimated files touched | Unlocks | CI gate stage added |
|---|---|---|---|---|---|---|
| W0 | Foundation | 12 | 40 | 83 | Stubs and clients | Unit; Validation |
| W1 | Stubbed integrations | 21 | 63 | 75 | Service endpoints | Integration |
| W2 | Service endpoints | 13 | 42 | 44 | Interaction verification | None (extends Unit and Integration) |
| W3 | UI interaction verification | 12 | 30 | 22 | The deferred UI run and the real-service swap | End-to-end; Performance |
| **Total** | | 58 | 175 | 224 | | |

## Epics in scope

Each epic maps to exactly one grouping, and no requirement sits in two epics. Endpoint epics own server behaviour, UI interaction epics own verification of their interaction along the real request path, and external interaction epics own everything behind a client interface. Each shared foundation is owned by the epic of its first consumer in build order, and every other consumer depends on the owning story ([D2 Epic grouping](technical-requirements.md#d2-epic-grouping)).

| Epic | Name | Grouping | Stories | Points | Estimated files touched | Waves |
|---|---|---|---|---|---|---|
| [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | Account Balance Summary View | UI interaction | 5 | 15 | 15 | W3 |
| [CS-02](backlog/epics/CS-02-category-balance-view/README.md) | Category Balance View | UI interaction | 2 | 4 | 2 | W3 |
| [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md) | Transaction List Drill-Down | UI interaction | 3 | 7 | 3 | W3 |
| [CS-04](backlog/epics/CS-04-transaction-detail-drill-down/README.md) | Transaction Detail Drill-Down | UI interaction | 2 | 4 | 2 | W3 |
| [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | Balance Summary Endpoint | Service endpoint | 6 | 20 | 35 | W0, W2 |
| [CS-06](backlog/epics/CS-06-category-balances-endpoint/README.md) | Category Balances Endpoint | Service endpoint | 3 | 11 | 10 | W2 |
| [CS-07](backlog/epics/CS-07-transaction-list-endpoint/README.md) | Transaction List Endpoint | Service endpoint | 3 | 9 | 10 | W2 |
| [CS-08](backlog/epics/CS-08-transaction-detail-endpoint/README.md) | Transaction Detail Endpoint | Service endpoint | 3 | 10 | 10 | W2 |
| [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | Card Data Integration | External interaction | 7 | 19 | 37 | W0, W1 |
| [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | Transaction Activity Integration | External interaction | 8 | 23 | 27 | W0, W1 |
| [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | Config Service Integration | External interaction | 7 | 27 | 39 | W0, W1 |
| [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | Auth Service Integration | External interaction | 5 | 15 | 18 | W1 |
| [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) | Reference Data Integration | External interaction | 4 | 11 | 16 | W0, W1 |
| **Total** | 13 epics | | 58 | 175 | 224 | W0, W1, W2, W3 |

## Sizing basis

Every story's points come from this rubric:

- **1**: configuration or documentation only, with no runtime branch.
- **2**: one component, one behaviour path plus input checks.
- **3**: one component with error, boundary or failure paths, or one container-backed test suite or stack.
- **5**: two or more collaborating components, concurrency, time-budget or staleness logic, or a build or hosting foundation other stories run on.

Points rest on behaviour, not file count. Estimated files touched is a single integer derived from each story's own scope: the files it creates, edits or deletes, counted together. Distinct requirements never share a story, so validation, assembly and exposure are separate stories in every endpoint epic. CS-11-006 carries 5 points because its single-flight background refresh of the configuration snapshot is concurrency logic.

Trace formation: there is no Requirements Analysis, so every story carries `CARD_REQ_TBD · Use Cases · <program>` or `CARD_REQ_TBD · Data Layouts · <copybook> · <field>`, naming the screen or record its capability serves. Platform stories that originate in a request directive (scaffold, call policy, cache, harnesses, Key Vault and the like) add a `Directive:` line, and the gap list marks them in its Origin column.

## W0 Foundation

W0 delivers the service build and CI gate, shared configuration, error mapping, the upstream call policy, the read-through cache, stub hosting and the loaded sample data model.

Exit condition: the Unit and Validation stages are green with line coverage of at least 80% per module, and the sample fixture load reports zero skipped rows.

CI gate stage added: Unit; Validation (CS-05-001).

| Pos. | Story | Title | Epic | Points | Estimated files touched | Depends on |
|---|---|---|---|---|---|---|
| 1 | [CS-05-001](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-001-service-module-scaffold-and-ci-gate.md) | Service module scaffold and CI gate | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 5 | 14 | — |
| 2 | [CS-11-001](backlog/epics/CS-11-config-service-integration/stories/CS-11-001-service-configuration-properties-and-environment-variables.md) | Service configuration properties and environment variables | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 2 | 4 | CS-05-001 |
| 3 | [CS-05-002](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-002-standard-error-object-and-status-mapping.md) | Standard error object and status mapping | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 3 | 7 | CS-05-001 |
| 4 | [CS-11-002](backlog/epics/CS-11-config-service-integration/stories/CS-11-002-stub-hosting-failure-injection-and-request-log.md) | Stub hosting, failure injection and request log | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 5 | 8 | CS-11-001 |
| 5 | [CS-11-003](backlog/epics/CS-11-config-service-integration/stories/CS-11-003-upstream-call-policy-with-request-deadline.md) | Upstream call policy with request deadline | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 5 | 7 | CS-11-001, CS-05-002 |
| 6 | [CS-11-004](backlog/epics/CS-11-config-service-integration/stories/CS-11-004-read-through-cache-with-stale-fallback.md) | Read-through cache with stale fallback | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 5 | 7 | CS-11-001 |
| 7 | [CS-09-001](backlog/epics/CS-09-card-data-integration/stories/CS-09-001-fixture-loader-core-and-load-report.md) | Fixture loader core and load report | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 3 | 6 | CS-11-001 |
| 8 | [CS-13-001](backlog/epics/CS-13-reference-data-integration/stories/CS-13-001-reference-data-tables-and-seed-files.md) | Reference data tables and seed files | [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) | 2 | 5 | CS-09-001 |
| 9 | [CS-09-002](backlog/epics/CS-09-card-data-integration/stories/CS-09-002-account-cross-reference-and-category-balance-tables.md) | Account, cross-reference and category balance tables | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 2 | 7 | CS-13-001 |
| 10 | [CS-09-003](backlog/epics/CS-09-card-data-integration/stories/CS-09-003-card-data-fixture-load.md) | Card Data fixture load | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 3 | 10 | CS-09-002 |
| 11 | [CS-10-001](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-001-transaction-table-with-account-keyset-index.md) | Transaction table with account keyset index | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 2 | 4 | CS-09-002 |
| 12 | [CS-10-002](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-002-transaction-fixture-load.md) | Transaction fixture load | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 3 | 4 | CS-10-001, CS-09-003 |

W0 totals: 12 stories, 40 points, 83 files touched

## W1 Stubbed integrations

W1 delivers the five stubbed integrations with their clients, integration suites and performance volume.

Exit condition: every stub answers its contract operations, and one integration suite per external epic is green.

CI gate stage added: Integration (CS-11-007).

| Pos. | Story | Title | Epic | Points | Estimated files touched | Depends on |
|---|---|---|---|---|---|---|
| 1 | [CS-11-005](backlog/epics/CS-11-config-service-integration/stories/CS-11-005-config-stub-service.md) | Config stub service | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 2 | 3 | CS-11-002 |
| 2 | [CS-11-006](backlog/epics/CS-11-config-service-integration/stories/CS-11-006-config-client-with-cached-values-and-built-in-defaults.md) | Config client with cached values and built-in defaults | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 5 | 5 | CS-11-005, CS-11-003, CS-11-004 |
| 3 | [CS-11-007](backlog/epics/CS-11-config-service-integration/stories/CS-11-007-config-integration-suite.md) | Config integration suite | [CS-11](backlog/epics/CS-11-config-service-integration/README.md) | 3 | 5 | CS-11-006 |
| 4 | [CS-12-001](backlog/epics/CS-12-auth-service-integration/stories/CS-12-001-auth-stub-with-carddemo-derived-fixture-identities.md) | Auth stub with CardDemo-derived fixture identities | [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | 3 | 4 | CS-11-002 |
| 5 | [CS-12-002](backlog/epics/CS-12-auth-service-integration/stories/CS-12-002-auth-client-with-service-api-key.md) | Auth client with service API key | [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | 2 | 3 | CS-12-001, CS-11-003 |
| 6 | [CS-12-003](backlog/epics/CS-12-auth-service-integration/stories/CS-12-003-account-access-guard-for-rest-and-grpc.md) | Account access guard for REST and gRPC | [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | 5 | 6 | CS-12-002, CS-05-002 |
| 7 | [CS-12-004](backlog/epics/CS-12-auth-service-integration/stories/CS-12-004-key-vault-secret-source-for-deployed-environments.md) | Key Vault secret source for deployed environments | [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | 2 | 3 | CS-11-001 |
| 8 | [CS-12-005](backlog/epics/CS-12-auth-service-integration/stories/CS-12-005-auth-integration-suite.md) | Auth integration suite | [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) | 3 | 2 | CS-12-003, CS-11-007 |
| 9 | [CS-13-002](backlog/epics/CS-13-reference-data-integration/stories/CS-13-002-reference-data-stub-service.md) | Reference Data stub service | [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) | 3 | 4 | CS-13-001, CS-11-002 |
| 10 | [CS-13-003](backlog/epics/CS-13-reference-data-integration/stories/CS-13-003-reference-data-client-with-cache-and-description-fallback.md) | Reference Data client with cache and description fallback | [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) | 3 | 5 | CS-13-002, CS-11-003, CS-11-004 |
| 11 | [CS-13-004](backlog/epics/CS-13-reference-data-integration/stories/CS-13-004-reference-data-integration-suite.md) | Reference Data integration suite | [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) | 3 | 2 | CS-13-003, CS-11-007 |
| 12 | [CS-09-004](backlog/epics/CS-09-card-data-integration/stories/CS-09-004-card-data-stub-service.md) | Card Data stub service | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 3 | 4 | CS-09-003, CS-11-002 |
| 13 | [CS-09-005](backlog/epics/CS-09-card-data-integration/stories/CS-09-005-card-data-client-with-account-and-category-balance-caching.md) | Card Data client with account and category balance caching | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 3 | 5 | CS-09-004, CS-11-003, CS-11-004 |
| 14 | [CS-09-006](backlog/epics/CS-09-card-data-integration/stories/CS-09-006-card-data-integration-suite.md) | Card Data integration suite | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 3 | 2 | CS-09-005, CS-11-007 |
| 15 | [CS-09-007](backlog/epics/CS-09-card-data-integration/stories/CS-09-007-category-balance-performance-volume.md) | Category balance performance volume | [CS-09](backlog/epics/CS-09-card-data-integration/README.md) | 2 | 3 | CS-09-003 |
| 16 | [CS-10-003](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-003-page-token-codec.md) | Page token codec | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 3 | 3 | CS-05-002 |
| 17 | [CS-10-004](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-004-transaction-activity-stub-transaction-listing-with-keyset-paging.md) | Transaction Activity stub transaction listing with keyset paging | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 5 | 4 | CS-10-002, CS-10-003, CS-11-002 |
| 18 | [CS-10-005](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-005-transaction-activity-stub-single-transaction-lookup.md) | Transaction Activity stub single transaction lookup | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 2 | 3 | CS-10-004 |
| 19 | [CS-10-006](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-006-transaction-activity-client-with-transaction-caching.md) | Transaction Activity client with transaction caching | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 3 | 4 | CS-10-005, CS-11-003, CS-11-004 |
| 20 | [CS-10-007](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-007-transaction-activity-integration-suite.md) | Transaction Activity integration suite | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 3 | 2 | CS-10-006, CS-11-007 |
| 21 | [CS-10-008](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-008-transaction-performance-volume.md) | Transaction performance volume | [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) | 2 | 3 | CS-10-002, CS-09-007 |

W1 totals: 21 stories, 63 points, 75 files touched

## W2 Service endpoints

W2 delivers the four UI-facing endpoints over REST and gRPC.

Exit condition: the four endpoints serve REST and gRPC per contract, and the Unit and Integration stages are green.

CI gate stage added: None (W2 extends Unit and Integration).

| Pos. | Story | Title | Epic | Points | Estimated files touched | Depends on |
|---|---|---|---|---|---|---|
| 1 | [CS-05-003](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-003-account-identifier-and-balance-summary-input-validation.md) | Account identifier and balance summary input validation | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 2 | 3 | CS-05-002 |
| 2 | [CS-05-004](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-004-type-group-balance-aggregation.md) | Type-group balance aggregation | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 2 | 2 | CS-05-001 |
| 3 | [CS-05-005](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-005-balance-summary-assembly-with-stale-and-partial-degradation.md) | Balance summary assembly with stale and partial degradation | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 5 | 5 | CS-05-004, CS-09-005, CS-13-003, CS-12-003, CS-11-006 |
| 4 | [CS-05-006](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-006-balance-summary-rest-and-grpc-endpoints.md) | Balance summary REST and gRPC endpoints | [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) | 3 | 4 | CS-05-003, CS-05-005 |
| 5 | [CS-06-001](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-001-category-balance-input-validation.md) | Category balance input validation | [CS-06](backlog/epics/CS-06-category-balances-endpoint/README.md) | 3 | 3 | CS-05-003, CS-13-003 |
| 6 | [CS-06-002](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-002-category-balance-retrieval-filter-and-degradation.md) | Category balance retrieval, filter and degradation | [CS-06](backlog/epics/CS-06-category-balances-endpoint/README.md) | 5 | 3 | CS-09-005, CS-13-003, CS-12-003, CS-05-005 |
| 7 | [CS-06-003](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-003-category-balance-rest-and-grpc-endpoints.md) | Category balance REST and gRPC endpoints | [CS-06](backlog/epics/CS-06-category-balances-endpoint/README.md) | 3 | 4 | CS-06-001, CS-06-002 |
| 8 | [CS-07-001](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-001-transaction-list-input-validation.md) | Transaction list input validation | [CS-07](backlog/epics/CS-07-transaction-list-endpoint/README.md) | 3 | 3 | CS-05-003, CS-10-003, CS-11-006 |
| 9 | [CS-07-002](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-002-transaction-list-retrieval-with-token-pass-through.md) | Transaction list retrieval with token pass-through | [CS-07](backlog/epics/CS-07-transaction-list-endpoint/README.md) | 3 | 3 | CS-10-006, CS-12-003, CS-11-006 |
| 10 | [CS-07-003](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-003-transaction-list-rest-and-grpc-endpoints.md) | Transaction list REST and gRPC endpoints | [CS-07](backlog/epics/CS-07-transaction-list-endpoint/README.md) | 3 | 4 | CS-07-001, CS-07-002 |
| 11 | [CS-08-001](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-001-transaction-detail-input-validation.md) | Transaction detail input validation | [CS-08](backlog/epics/CS-08-transaction-detail-endpoint/README.md) | 2 | 3 | CS-05-003 |
| 12 | [CS-08-002](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-002-transaction-ownership-check-and-detail-retrieval.md) | Transaction ownership check and detail retrieval | [CS-08](backlog/epics/CS-08-transaction-detail-endpoint/README.md) | 5 | 3 | CS-10-006, CS-09-005, CS-12-003, CS-05-005 |
| 13 | [CS-08-003](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-003-transaction-detail-rest-and-grpc-endpoints.md) | Transaction detail REST and gRPC endpoints | [CS-08](backlog/epics/CS-08-transaction-detail-endpoint/README.md) | 3 | 4 | CS-08-001, CS-08-002 |

W2 totals: 13 stories, 42 points, 44 files touched

## W3 UI interaction verification

W3 delivers the Compose stack, the end-to-end and performance harnesses and verification of the four UI interactions.

Exit condition: the end-to-end flow is green, and p50, p95 and p99 are under 1,000 ms for all four endpoints; this condition gates the deferred UI run and the real-service swap.

CI gate stage added: End-to-end (CS-01-002); Performance (CS-01-003).

| Pos. | Story | Title | Epic | Points | Estimated files touched | Depends on |
|---|---|---|---|---|---|---|
| 1 | [CS-01-001](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-001-compose-test-stack-with-stub-services.md) | Compose test stack with stub services | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | 3 | 3 | CS-05-006, CS-06-003, CS-07-003, CS-08-003, CS-09-007, CS-10-008 |
| 2 | [CS-01-002](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-002-end-to-end-test-harness.md) | End-to-end test harness | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | 3 | 4 | CS-01-001 |
| 3 | [CS-01-003](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-003-performance-test-harness.md) | Performance test harness | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | 5 | 6 | CS-01-001 |
| 4 | [CS-01-004](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-004-account-balance-summary-end-to-end-step.md) | Account balance summary end-to-end step | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | 2 | 1 | CS-01-002 |
| 5 | [CS-01-005](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-005-account-balance-summary-sla-scenario.md) | Account balance summary SLA scenario | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) | 2 | 1 | CS-01-003 |
| 6 | [CS-02-001](backlog/epics/CS-02-category-balance-view/stories/CS-02-001-category-balance-end-to-end-step.md) | Category balance end-to-end step | [CS-02](backlog/epics/CS-02-category-balance-view/README.md) | 2 | 1 | CS-01-004 |
| 7 | [CS-02-002](backlog/epics/CS-02-category-balance-view/stories/CS-02-002-category-balance-sla-scenario.md) | Category balance SLA scenario | [CS-02](backlog/epics/CS-02-category-balance-view/README.md) | 2 | 1 | CS-01-003 |
| 8 | [CS-03-001](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-001-transaction-list-end-to-end-step.md) | Transaction list end-to-end step | [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md) | 2 | 1 | CS-02-001 |
| 9 | [CS-03-002](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-002-transaction-list-paging-navigation-step.md) | Transaction list paging navigation step | [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md) | 3 | 1 | CS-03-001 |
| 10 | [CS-03-003](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-003-transaction-list-sla-scenario.md) | Transaction list SLA scenario | [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md) | 2 | 1 | CS-01-003 |
| 11 | [CS-04-001](backlog/epics/CS-04-transaction-detail-drill-down/stories/CS-04-001-transaction-detail-end-to-end-step.md) | Transaction detail end-to-end step | [CS-04](backlog/epics/CS-04-transaction-detail-drill-down/README.md) | 2 | 1 | CS-03-001 |
| 12 | [CS-04-002](backlog/epics/CS-04-transaction-detail-drill-down/stories/CS-04-002-transaction-detail-sla-scenario.md) | Transaction detail SLA scenario | [CS-04](backlog/epics/CS-04-transaction-detail-drill-down/README.md) | 2 | 1 | CS-01-003 |

W3 totals: 12 stories, 30 points, 22 files touched

## Dependency Map

Ordering is enforced only at story level. An epic dependency is a checked summary of story edges, not a scheduling constraint. Epics span waves: CS-05 sits in W0 and W2, and CS-09, CS-10, CS-11 and CS-13 sit in W0 and W1. Three pairs run in both directions: CS-05 with CS-11, CS-05 with CS-12, and CS-09 with CS-13. For example, CS-05-005 needs CS-11-006 while CS-11-001 needs CS-05-001.

Every Depends on value in story metadata appears as one story-edge row below, and every row appears in story metadata. No story is positioned before a prerequisite: each prerequisite sits in an earlier wave or earlier in the same wave's table. Each edge carries one reason type, set by what the prerequisite provides:

- data model: tables, seed rows, loaded sample data or synthetic volume.
- stubbed integration: a mocked external service.
- upstream service: a client or endpoint the dependent story calls.
- shared component: a foundation, validator, aggregator or retrieval step the dependent story builds on.
- test harness: a test stack, harness, suite base or end-to-end step the dependent story extends.

### Story edges

102 edges, ordered by the dependent story's wave and position, then by the order of its Depends on list. By reason type: shared component 48, upstream service 20, test harness 15, data model 13, stubbed integration 6.

| Story | Prerequisite | Reason type | Reason |
|---|---|---|---|
| CS-11-001 | CS-05-001 | shared component | provides the build, pinned versions and CI gate |
| CS-05-002 | CS-05-001 | shared component | provides the build, pinned versions and CI gate |
| CS-11-002 | CS-11-001 | shared component | provides the typed configuration and environment variables |
| CS-11-003 | CS-11-001 | shared component | provides the typed configuration and environment variables |
| CS-11-003 | CS-05-002 | shared component | provides the standard error object and status mapping |
| CS-11-004 | CS-11-001 | shared component | provides the typed configuration and environment variables |
| CS-09-001 | CS-11-001 | shared component | provides the typed configuration and environment variables |
| CS-13-001 | CS-09-001 | shared component | provides the fixture loader core and load report |
| CS-09-002 | CS-13-001 | data model | provides the reference data tables and seed rows |
| CS-09-003 | CS-09-002 | data model | provides the account, cross-reference and category balance tables |
| CS-10-001 | CS-09-002 | data model | provides the account, cross-reference and category balance tables |
| CS-10-002 | CS-10-001 | data model | provides the transaction table and account keyset index |
| CS-10-002 | CS-09-003 | data model | provides the loaded sample accounts, cross-references and category balances |
| CS-11-005 | CS-11-002 | shared component | provides stub hosting, failure injection and the request log |
| CS-11-006 | CS-11-005 | stubbed integration | provides the Config stub |
| CS-11-006 | CS-11-003 | shared component | provides the upstream call policy and request deadline |
| CS-11-006 | CS-11-004 | shared component | provides the read-through cache with stale fallback |
| CS-11-007 | CS-11-006 | upstream service | provides the Config client and configuration snapshot |
| CS-12-001 | CS-11-002 | shared component | provides stub hosting, failure injection and the request log |
| CS-12-002 | CS-12-001 | stubbed integration | provides the Auth stub and its fixture identities |
| CS-12-002 | CS-11-003 | shared component | provides the upstream call policy and request deadline |
| CS-12-003 | CS-12-002 | upstream service | provides the Auth client |
| CS-12-003 | CS-05-002 | shared component | provides the standard error object and status mapping |
| CS-12-004 | CS-11-001 | shared component | provides the typed configuration and environment variables |
| CS-12-005 | CS-12-003 | shared component | provides the account access guard |
| CS-12-005 | CS-11-007 | test harness | provides the container-backed integration base and Integration CI stage |
| CS-13-002 | CS-13-001 | data model | provides the reference data tables and seed rows |
| CS-13-002 | CS-11-002 | shared component | provides stub hosting, failure injection and the request log |
| CS-13-003 | CS-13-002 | stubbed integration | provides the Reference Data stub |
| CS-13-003 | CS-11-003 | shared component | provides the upstream call policy and request deadline |
| CS-13-003 | CS-11-004 | shared component | provides the read-through cache with stale fallback |
| CS-13-004 | CS-13-003 | upstream service | provides the Reference Data client with description fallback |
| CS-13-004 | CS-11-007 | test harness | provides the container-backed integration base and Integration CI stage |
| CS-09-004 | CS-09-003 | data model | provides the loaded sample accounts, cross-references and category balances |
| CS-09-004 | CS-11-002 | shared component | provides stub hosting, failure injection and the request log |
| CS-09-005 | CS-09-004 | stubbed integration | provides the Card Data stub |
| CS-09-005 | CS-11-003 | shared component | provides the upstream call policy and request deadline |
| CS-09-005 | CS-11-004 | shared component | provides the read-through cache with stale fallback |
| CS-09-006 | CS-09-005 | upstream service | provides the Card Data client with account and category balance caching |
| CS-09-006 | CS-11-007 | test harness | provides the container-backed integration base and Integration CI stage |
| CS-09-007 | CS-09-003 | data model | provides the loaded sample accounts, cross-references and category balances |
| CS-10-003 | CS-05-002 | shared component | provides the standard error object and status mapping |
| CS-10-004 | CS-10-002 | data model | provides the loaded sample transactions |
| CS-10-004 | CS-10-003 | shared component | provides the page token codec |
| CS-10-004 | CS-11-002 | shared component | provides stub hosting, failure injection and the request log |
| CS-10-005 | CS-10-004 | stubbed integration | provides the Transaction Activity stub listing |
| CS-10-006 | CS-10-005 | stubbed integration | provides the Transaction Activity stub single transaction lookup |
| CS-10-006 | CS-11-003 | shared component | provides the upstream call policy and request deadline |
| CS-10-006 | CS-11-004 | shared component | provides the read-through cache with stale fallback |
| CS-10-007 | CS-10-006 | upstream service | provides the Transaction Activity client with transaction caching |
| CS-10-007 | CS-11-007 | test harness | provides the container-backed integration base and Integration CI stage |
| CS-10-008 | CS-10-002 | data model | provides the loaded sample transactions |
| CS-10-008 | CS-09-007 | data model | provides the synthetic category balance volume |
| CS-05-003 | CS-05-002 | shared component | provides the standard error object and status mapping |
| CS-05-004 | CS-05-001 | shared component | provides the build, pinned versions and CI gate |
| CS-05-005 | CS-05-004 | shared component | provides the type-group balance aggregation |
| CS-05-005 | CS-09-005 | upstream service | provides the Card Data client with account and category balance caching |
| CS-05-005 | CS-13-003 | upstream service | provides the Reference Data client with description fallback |
| CS-05-005 | CS-12-003 | shared component | provides the account access guard |
| CS-05-005 | CS-11-006 | upstream service | provides the Config client and configuration snapshot |
| CS-05-006 | CS-05-003 | shared component | provides the shared request-parameter validator |
| CS-05-006 | CS-05-005 | shared component | provides the data status resolver and degradation assembly |
| CS-06-001 | CS-05-003 | shared component | provides the shared request-parameter validator |
| CS-06-001 | CS-13-003 | upstream service | provides the Reference Data client with description fallback |
| CS-06-002 | CS-09-005 | upstream service | provides the Card Data client with account and category balance caching |
| CS-06-002 | CS-13-003 | upstream service | provides the Reference Data client with description fallback |
| CS-06-002 | CS-12-003 | shared component | provides the account access guard |
| CS-06-002 | CS-05-005 | shared component | provides the data status resolver and degradation assembly |
| CS-06-003 | CS-06-001 | shared component | provides the category balance input validation |
| CS-06-003 | CS-06-002 | shared component | provides the category balance retrieval |
| CS-07-001 | CS-05-003 | shared component | provides the shared request-parameter validator |
| CS-07-001 | CS-10-003 | shared component | provides the page token codec |
| CS-07-001 | CS-11-006 | upstream service | provides the Config client and configuration snapshot |
| CS-07-002 | CS-10-006 | upstream service | provides the Transaction Activity client with transaction caching |
| CS-07-002 | CS-12-003 | shared component | provides the account access guard |
| CS-07-002 | CS-11-006 | upstream service | provides the Config client and configuration snapshot |
| CS-07-003 | CS-07-001 | shared component | provides the transaction list input validation |
| CS-07-003 | CS-07-002 | shared component | provides the transaction list retrieval |
| CS-08-001 | CS-05-003 | shared component | provides the shared request-parameter validator |
| CS-08-002 | CS-10-006 | upstream service | provides the Transaction Activity client with transaction caching |
| CS-08-002 | CS-09-005 | upstream service | provides the Card Data client with account and category balance caching |
| CS-08-002 | CS-12-003 | shared component | provides the account access guard |
| CS-08-002 | CS-05-005 | shared component | provides the data status resolver and degradation assembly |
| CS-08-003 | CS-08-001 | shared component | provides the transaction detail input validation |
| CS-08-003 | CS-08-002 | shared component | provides the ownership check and detail retrieval |
| CS-01-001 | CS-05-006 | upstream service | provides the balance summary endpoint |
| CS-01-001 | CS-06-003 | upstream service | provides the category balances endpoint |
| CS-01-001 | CS-07-003 | upstream service | provides the transaction list endpoint |
| CS-01-001 | CS-08-003 | upstream service | provides the transaction detail endpoint |
| CS-01-001 | CS-09-007 | data model | provides the synthetic category balance volume |
| CS-01-001 | CS-10-008 | data model | provides the synthetic transaction volume |
| CS-01-002 | CS-01-001 | test harness | provides the Compose test stack |
| CS-01-003 | CS-01-001 | test harness | provides the Compose test stack |
| CS-01-004 | CS-01-002 | test harness | provides the end-to-end test harness |
| CS-01-005 | CS-01-003 | test harness | provides the performance test harness |
| CS-02-001 | CS-01-004 | test harness | provides end-to-end step 1 and its type-code hand-off |
| CS-02-002 | CS-01-003 | test harness | provides the performance test harness |
| CS-03-001 | CS-02-001 | test harness | provides end-to-end step 2 and its category hand-off |
| CS-03-002 | CS-03-001 | test harness | provides end-to-end step 3 and its transaction hand-off |
| CS-03-003 | CS-01-003 | test harness | provides the performance test harness |
| CS-04-001 | CS-03-001 | test harness | provides end-to-end step 3 and its transaction hand-off |
| CS-04-002 | CS-01-003 | test harness | provides the performance test harness |

### Epic dependencies

37 rows, one per ordered pair of epics joined by at least one story edge. The Story edges column lists every producing edge as dependent story → prerequisite story; Reason types is the distinct set of those edges' reason types. Each epic README's Dependencies section lists exactly that epic's rows.

| Dependent epic | Prerequisite epic | Story edges | Reason types |
|---|---|---|---|
| CS-01 | CS-05 | CS-01-001 → CS-05-006 | upstream service |
| CS-01 | CS-06 | CS-01-001 → CS-06-003 | upstream service |
| CS-01 | CS-07 | CS-01-001 → CS-07-003 | upstream service |
| CS-01 | CS-08 | CS-01-001 → CS-08-003 | upstream service |
| CS-01 | CS-09 | CS-01-001 → CS-09-007 | data model |
| CS-01 | CS-10 | CS-01-001 → CS-10-008 | data model |
| CS-02 | CS-01 | CS-02-001 → CS-01-004, CS-02-002 → CS-01-003 | test harness |
| CS-03 | CS-01 | CS-03-003 → CS-01-003 | test harness |
| CS-03 | CS-02 | CS-03-001 → CS-02-001 | test harness |
| CS-04 | CS-01 | CS-04-002 → CS-01-003 | test harness |
| CS-04 | CS-03 | CS-04-001 → CS-03-001 | test harness |
| CS-05 | CS-09 | CS-05-005 → CS-09-005 | upstream service |
| CS-05 | CS-11 | CS-05-005 → CS-11-006 | upstream service |
| CS-05 | CS-12 | CS-05-005 → CS-12-003 | shared component |
| CS-05 | CS-13 | CS-05-005 → CS-13-003 | upstream service |
| CS-06 | CS-05 | CS-06-001 → CS-05-003, CS-06-002 → CS-05-005 | shared component |
| CS-06 | CS-09 | CS-06-002 → CS-09-005 | upstream service |
| CS-06 | CS-12 | CS-06-002 → CS-12-003 | shared component |
| CS-06 | CS-13 | CS-06-001 → CS-13-003, CS-06-002 → CS-13-003 | upstream service |
| CS-07 | CS-05 | CS-07-001 → CS-05-003 | shared component |
| CS-07 | CS-10 | CS-07-001 → CS-10-003, CS-07-002 → CS-10-006 | upstream service, shared component |
| CS-07 | CS-11 | CS-07-001 → CS-11-006, CS-07-002 → CS-11-006 | upstream service |
| CS-07 | CS-12 | CS-07-002 → CS-12-003 | shared component |
| CS-08 | CS-05 | CS-08-001 → CS-05-003, CS-08-002 → CS-05-005 | shared component |
| CS-08 | CS-09 | CS-08-002 → CS-09-005 | upstream service |
| CS-08 | CS-10 | CS-08-002 → CS-10-006 | upstream service |
| CS-08 | CS-12 | CS-08-002 → CS-12-003 | shared component |
| CS-09 | CS-11 | CS-09-001 → CS-11-001, CS-09-004 → CS-11-002, CS-09-005 → CS-11-003, CS-09-005 → CS-11-004, CS-09-006 → CS-11-007 | shared component, test harness |
| CS-09 | CS-13 | CS-09-002 → CS-13-001 | data model |
| CS-10 | CS-05 | CS-10-003 → CS-05-002 | shared component |
| CS-10 | CS-09 | CS-10-001 → CS-09-002, CS-10-002 → CS-09-003, CS-10-008 → CS-09-007 | data model |
| CS-10 | CS-11 | CS-10-004 → CS-11-002, CS-10-006 → CS-11-003, CS-10-006 → CS-11-004, CS-10-007 → CS-11-007 | shared component, test harness |
| CS-11 | CS-05 | CS-11-001 → CS-05-001, CS-11-003 → CS-05-002 | shared component |
| CS-12 | CS-05 | CS-12-003 → CS-05-002 | shared component |
| CS-12 | CS-11 | CS-12-001 → CS-11-002, CS-12-002 → CS-11-003, CS-12-004 → CS-11-001, CS-12-005 → CS-11-007 | shared component, test harness |
| CS-13 | CS-09 | CS-13-001 → CS-09-001 | shared component |
| CS-13 | CS-11 | CS-13-002 → CS-11-002, CS-13-003 → CS-11-003, CS-13-003 → CS-11-004, CS-13-004 → CS-11-007 | shared component, test harness |

## Feature Checklist coverage

The Feature Checklist (CardDemo README · Application Inventory) is a coverage table only; the backlog does not adopt its grouping. Every in-scope item traces to at least one epic, and every other item is listed as future iteration or out of scope.

| Feature Checklist item | Coverage |
|---|---|
| CC00 Signon (COSGN00C) | Screen out of scope; caller authentication is covered by [CS-12](backlog/epics/CS-12-auth-service-integration/README.md) |
| CM00 Main Menu (COMEN01C) | Screen out of scope; its "View Account" and "List Transactions" entries lead to [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) and [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md) |
| CAVW Account View (COACTVWC) | [CS-01](backlog/epics/CS-01-account-balance-summary-view/README.md) and [CS-05](backlog/epics/CS-05-balance-summary-endpoint/README.md) (account section); the Customer Details section is flagged as field gaps in CS-01's Requirements and in the [unmapped-field table](#unmapped-field-table) |
| CT00 Transaction List (COTRN00C) | [CS-03](backlog/epics/CS-03-transaction-list-drill-down/README.md), [CS-07](backlog/epics/CS-07-transaction-list-endpoint/README.md), [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) |
| CT01 Transaction View (COTRN01C) | [CS-04](backlog/epics/CS-04-transaction-detail-drill-down/README.md), [CS-08](backlog/epics/CS-08-transaction-detail-endpoint/README.md), [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) |
| CCLI Credit Card List and CCDL Credit Card View (COCRDLIC, COCRDSLC) | Future iteration; the card cross-reference is used for ownership in [CS-09](backlog/epics/CS-09-card-data-integration/README.md) |
| CT02 Transaction Add (COTRN02C) | Future iteration, because this iteration has no write operations |
| CAUP Account Update and CCUP Credit Card Update | Out of scope (account and card update) |
| CR00 Transaction Reports | Out of scope (reporting) |
| CB00 Bill Payment | Out of scope (bill payment) |
| CA00 Admin Menu and CU00 to CU03 user functions | Out of scope (user administration) |
| All batch jobs (POSTTRAN, INTCALC, CREASTMT, TRANBKP and the load jobs) | Out of scope (batch processing); the load layouts inform [CS-09](backlog/epics/CS-09-card-data-integration/README.md), [CS-10](backlog/epics/CS-10-transaction-activity-integration/README.md) and [CS-13](backlog/epics/CS-13-reference-data-integration/README.md) |
| Authorization-message processing | Out of scope; not present in this checkout of CardDemo |

## Recommendations

These are findings beyond the three requirement sources (Use Cases, Requirements Analysis and Data Layouts), or ones the request asks to record. Nothing here enters an epic or a story.

- No version substitution was needed: every pinned version resolves as a GA release. The request's Gradle 8.x becomes Gradle 9.1.0, the first release that runs on and targets Java 25.
- Known advisories in the locked validation toolchain, `tests/validation/package-lock.json`, are recorded rather than fixed: the request fixes the three tool versions the lock is generated from and every fix lies outside a range a fixed tool declares, so no regenerated lock closes one, and `npm audit` does not cover the downloaded browser or Buf's native binary. Until a transition is approved, the suite runs only over this repository's own files, locally, as an unprivileged user in a disposable environment, with no proxy or proxy auto-config and the default browser download origin. The proposed transition is Mermaid CLI 12.2.0 with Puppeteer 25.17.0, and Spectral CLI 6.16.0, keeping Buf 1.50.0: a lock generated for that set reports no `npm audit` finding and needs Node.js 22.13.0 or later, which the 22.23.4 pin meets. It changes versions the request fixes, so adopting it is a version-policy decision; adoption regenerates the lock and reruns `tests/validation/run-all.sh`, where the seven diagrams re-render under Mermaid 12.
  - `basic-ftp` 5.3.1 arrives through the browser download's proxy chain (`@puppeteer/browsers` 2.6.1, `proxy-agent`, `pac-proxy-agent`, then `get-uri` 6.0.5, which accepts only 5.x) and carries GHSA-c475-qrg2-pj4r, a quadratic regular-expression slowdown when parsing a Unix FTP directory listing, fixed in 6.2.1. It is reached only when a proxy environment variable names a proxy auto-config file served over FTP and that server returns a hostile listing, so the suite takes no FTP or proxy auto-config input. The transition removes the proxy chain and `basic-ftp`.
  - `extract-zip` 2.0.1 arrives through `@puppeteer/browsers` 2.6.1, which unpacks the browser archive at setup, and carries GHSA-jmr9-qjv8-65gv and GHSA-7pqw-9j4j-h8q3, symlink entries that write outside the extraction directory; there is no fixed release. It is reached only by a hostile archive, and the archive comes from Google's Chrome for Testing storage over HTTPS, so setup keeps that origin, with no mirror or download-host override by environment variable or Puppeteer configuration file, and extracts as an unprivileged user in an isolated environment. The transition removes `extract-zip`, which `@puppeteer/browsers` 3.2.7 does not depend on.
  - `katex` 0.16.47 arrives through `mermaid` 11.17.2 and carries GHSA-238p-pmpm-9mq7, a bypass of KaTeX's trust restrictions, fixed in 0.18.2; Mermaid CLI 11.4.2 accepts only Mermaid 11, whose newest release, 11.17.2, declares `katex` 0.16.x, so the fixed tool's dependency constraint blocks the fix. It is reached only after a separate prototype pollution or through an influenced options prototype, so the suite renders only this repository's diagrams and sets no KaTeX option from outside them. The transition resolves `katex` 0.18.10.
  - Root `lodash` 4.17.23 arrives through Spectral CLI 6.15.0, which declares `~4.17.21`, while the copies nested under Spectral's own packages are already 4.18.1. It carries GHSA-r5fr-rjxr-66jc, code injection through `_.template` imports key names, fixed in 4.18.0, and GHSA-f23m-r3pf-42rh, deletion of prototype properties through array-wrapped paths in `_.unset`, `_.omit` and `_.pullAt`, fixed in 4.18.1; the root copy's consumers call none of `_.template`, `_.unset` or `_.pullAt`, their one `_.omit` call passes a string path, and the ruleset and lint inputs are developer-controlled files in this repository. Spectral CLI 6.16.0 declares `^4.18.1` and resolves 4.18.1.
  - `zod` 3.23.8 arrives through `chromium-bidi` 0.11.0, which Puppeteer 23.11.1 pins and which pins `zod` exactly, and carries GHSA-mrhx-c7xc-m3w5, memory exhaustion while validating oversized collections, fixed only in 4.6.6 with no 3.x backport. It is reached only when a hostile browser or protocol peer sends oversized collections over WebDriver BiDi; the suite launches its own local browser over the Chrome DevTools Protocol, which does not load `chromium-bidi`, and never connects to a remote endpoint. The transition resolves `zod` 4.6.7.
  - `chrome-headless-shell` 131.0.6778.204 is the build Puppeteer 23.11.1 pins (the newest release Mermaid CLI 11.4.2's peer range `^23` allows, and one npm marks unsupported), and `tests/validation/run-all.sh` installs exactly that build. Its V8 predates the fixes for CVE-2025-5419 (fixed in 137.0.7151.68) and CVE-2025-13223 (fixed in 142.0.7444.175); the download sits outside the lock's integrity hashes and `npm audit`, and headless mode is not a patched V8. No other browser is substituted: the suite renders only this repository's diagrams in an isolated, low-privilege environment, and the transition moves to build 157.0.8103.50, which Puppeteer 25.17.0 pins.
  - Buf 1.50.0 ships a native Go binary that `npm audit` does not inspect, so a clean audit is not a native safety assessment. Its build info records Go 1.22.11, `golang.org/x/net` 0.34.0, `golang.org/x/crypto` 0.32.0, `golang.org/x/text` 0.21.0, `google.golang.org/grpc` 1.69.4, `quic-go` 0.48.2 and `klauspost/compress` 1.17.11, inside published advisory ranges such as GO-2025-3503 (CVE-2025-22870, fixed in Go 1.23.7 and `golang.org/x/net` 0.36.0); reachability through local lint is not established, so the 1.50.0 pin stays and lint runs with least privilege over this repository's own proto files, with no remote modules or plugins. Each tool review inventories the binary's embedded Go modules against the Go vulnerability database and proposes a maintained Buf release once its build info clears those advisories; the newest reviewed, 1.75.0, still embeds `golang.org/x/net` 0.61.0, below the 0.62.0 fix for GO-2026-6860 and GO-2026-6866.
- The Spring Cloud Azure 5.23.0 and Spring Boot 3.5.7 pairing is not the exact pair upstream records: its 5.23.0 baseline is Boot 3.5.4, with a compatibility run on 3.5.7. CS-05-001's first build confirms the pairing.
- The Java and container image versions the stories pin carry known advisories, recorded here for adoption review. The pins and the quoted story bodies stay as written: these are future dependencies, not running components, and which advisories apply depends on the features the effective dependency graph includes, which the pinning story records at implementation.
  - Spring Boot 3.5.7, plugin and BOM (CS-05-001), manages Spring Framework 6.2.12, Spring Security 6.5.6, Tomcat 10.1.48, Netty 4.1.128, Reactor Netty 1.2.11 and Reactor Core 3.7.12, all inside later advisory ranges: Boot CVE-2026-22731, 22733, 40971 to 40975, 40977, 40992 and 41001 (fixed in 3.5.12, 3.5.14 and 3.5.15); an earlier Framework group, CVE-2026-41838 to 41846, 41848 and 41850 to 41855 (fixed in 6.2.19), and a later one, CVE-2026-47883 to 47893, 59280 to 59283, 59313 and 59314 (fixed only in commercial 6.2.20 or open-source 7.0.9); an earlier Security group, CVE-2026-40988, 41003, 41694, 41706 and 47838 (fixed in 6.5.11), and a later one, CVE-2026-41707, 47841, 47842, 59270 and 59276 (fixed only in commercial 6.5.12 or open-source 7.x); Tomcat fixes released through 10.1.61; Reactor Netty CVE-2026-41715 (fixed in 1.2.18) and CVE-2026-47843 to 47845, 47848 and 47874 (fixed in open-source 1.3.7 or commercial 1.2.19); and Reactor Core CVE-2026-47857 and 47863 (fixed in open-source 3.8.7 or commercial 3.7.20). Many Boot, Framework and Security entries need modules no story specifies (Actuator, DevTools, RabbitMQ, Cassandra, mail, Artemis, WebSocket, JMS, FreeMarker, SAML2 and Spring Security itself), while the planned graph holds Web MVC on Tomcat, Lettuce on Netty and Reactor Core, Spring Data JPA, and the Key Vault HTTP transport on Reactor Netty, so the Framework web, Tomcat, Netty and Reactor entries are the ones it can include. Proposed transition: move the Boot plugin and BOM together to 3.5.16, the newest 3.5.x patch release, with no per-component overrides; it manages Framework 6.2.19, Security 6.5.11, Tomcat 10.1.55, Netty 4.1.135, Reactor Netty 1.2.18 and Reactor Core 3.7.19, which closes the Boot groups, the earlier Framework and Security groups, CVE-2026-41715 and the Netty fixes through 4.1.135. The later Framework, Security, Reactor Netty and Reactor Core groups, the Tomcat fixes after 10.1.55 and the Netty fixes after 4.1.135 stay open on 3.5.16, because their only fixes are commercial patch releases, the Framework 7 and Security 7 line, or component releases 3.5.16 does not manage, so they need a version-policy decision; CS-05-001's first build records the effective graph.
  - `io.grpc:grpc-netty-shaded` 1.71.0 (CS-05-001) embeds relocated Netty 4.1.110, inside the ranges of CVE-2025-24970 and CVE-2025-25193 (fixed in Netty 4.1.118), CVE-2025-58056 and CVE-2025-58057 (4.1.125), CVE-2026-45673, 45674, 47691 and 47244 (4.1.135) and CVE-2026-93492 (4.1.138). Prerequisites differ: CVE-2025-24970 needs the native SSL engine, CVE-2025-25193 a Windows host, CVE-2025-58056 HTTP/1 chunked parsing and CVE-2025-58057 a compression decoder, CVE-2026-47244 and CVE-2026-93492 sit in the HTTP/2 codec every gRPC call uses, and the three DNS-resolver entries concern a Netty module the shaded jar does not contain. A standalone Netty override or a Boot BOM bump cannot fix them, because the shaded jar carries its own relocated copies of the Netty classes, so the fix is a grpc-java release whose bundled Netty carries the fixes: the newest published release, 1.85.1, bundles Netty 4.2.17 and closes every entry except CVE-2026-93492, which the 4.2 line fixes in 4.2.18. Proposed transition: the first grpc-java release that bundles Netty 4.2.18 or later, with `grpc-protobuf`, `grpc-stub` and `protoc-gen-grpc-java` moving with it, and `protobuf-java` and `protoc` moving from 3.25.5 to the 3.25.x version that release's catalog names (3.25.9 for 1.85.1).
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui` 2.8.13 (CS-05-001) keeps one generated OpenAPI document per request locale with no bound (GHSA-rhhx-6j8h-8cvw), so distinct `Accept-Language` values exhaust the heap wherever the documentation endpoint is reachable, and it bundles Swagger UI 5.28.1, whose DOMPurify 3.2.4 is in the CVE-2026-75838 range (fixed in DOMPurify 3.4.13). Springdoc 2.9.1 bounds the cache and bundles Swagger UI 5.32.14, which requires DOMPurify 3.4.13 or later, and it builds on Spring Boot 3.5.16, the Boot transition proposed above. Until it is adopted, set `springdoc.allowed-locales` to the locales the service serves, or keep the documentation endpoints off public access.
  - Testcontainers `testcontainers`, `postgresql` and `junit-jupiter` 1.21.3 (pinned by CS-05-001, used by CS-11-007) bring Commons Compress 1.24.0 (CVE-2024-25710 and CVE-2024-26308, fixed in 1.26.0) and the docker-java 3.4.2 transport with shaded HttpClient 5.0.3 and HttpCore 5.0.2 (CVE-2026-54399 and CVE-2026-54428, fixed in HttpCore 5.4.3). Commons Compress is an ordinary dependency, so a version constraint of 1.26.0 or later raises it; the HttpCore copy is shaded, so only a Testcontainers or docker-java release that embeds HttpCore 5.4.3 or later fixes it, and none is published: the newest, docker-java 3.7.1 in Testcontainers 2.0.5, embeds HttpCore 5.3.6. Applicability is test-only: the integration suites talk only to the local Docker daemon and its containers, and neither library enters the service image, so the archive entries need a crafted DUMP or Pack200 archive and the HttpCore entries a hostile Docker endpoint.
  - `postgres:16.6` (CS-11-007 for Testcontainers, CS-01-001 for Compose) predates 45 vendor fixes released in 16.7 to 16.15: 16.7 CVE-2025-1094; 16.9 CVE-2025-4207; 16.10 CVE-2025-8713, 8714 and 8715; 16.11 CVE-2025-12817 and 12818; 16.12 CVE-2026-2003 to 2006; 16.14 CVE-2026-6472 to 6475, 6477 to 6479, 6637 and 6638; 16.15 CVE-2026-6464, 6469 to 6471, 14662 to 14664, 14666, 14668 to 14673, 14677 to 14680, 15741, 15742, 16239, 16241, 18024, 18408 and 19385. Applicability differs by component: client entries such as CVE-2025-1094 (libpq quoting) and CVE-2025-8714 (pg_dump output replayed through psql) need psql, pg_dump, pg_basebackup, libpq or ECPG facing a hostile server or dump, contrib entries need their extension installed, and privilege-check entries need a second, less-privileged role, none of which the JDBC access the stories specify uses; core server entries reachable through SQL, such as the regexp, to_char and cursor code-execution fixes in 16.15, apply to any holder of a database login. Proposed transition: move both stories together to `postgres:16.15`, the newest 16.x tag on Docker Hub; the story pins stay at 16.6 until that change is adopted.
  - `redis:7.4.2` (CS-11-007, and CS-01-001, whose Compose service runs it with no password) carries the Lua garbage-collector use-after-free CVE-2025-49844 (GHSA-4789-qfc9-5f9q), a remote code execution fixed in 7.4.6, and further engine advisories: CVE-2025-21605 (fixed in 7.4.3), CVE-2025-27151 (7.4.4), CVE-2025-32023 and 48367 (7.4.5), CVE-2025-46817, 46818 and 46819 (7.4.6), and CVE-2026-23479, 23631 and the `RESTORE` advisory CVE-2026-25243 (GHSA-c8h9-259x-jff4), fixed in 7.4.9 although the advisory record still lists no patched version, with further `RESTORE` and memory-safety fixes in 7.4.10 to 7.4.12. The server entries need a client that reaches the Redis port; the stories specify no scripting or `RESTORE` use by the service, but a passwordless, unrestricted Redis lets any client that reaches the port meet that prerequisite. Proposed transition: move both stories together to `redis:7.4.12`, the newest 7.4.x tag on Docker Hub, and hold the deployed Azure Managed Redis engine 7.4 at a patch level that carries the same fixes. Until then, publish the port only on the test network or loopback, and give the default user an ACL rule that keeps the empty stub-phase password but denies scripting and `RESTORE`: `user default on nopass ~* &* +@all -@scripting -restore -restore-asking`.
  - The Key Vault starter (CS-12-004) takes its version from the Spring Cloud Azure 5.23.0 BOM that CS-05-001 pins, which imports Azure SDK BOM 1.2.37: Key Vault Secrets 4.10.1, Azure Identity 1.16.2 (past the CVE-2024-35255 fix in 1.12.2) and the Netty HTTP transport `azure-core-http-netty` 1.15.13, which declares Reactor Netty 1.0.48, or Boot-managed Reactor Netty 1.2.11 where the Boot BOM governs it. Both Reactor Netty versions are in the CVE-2026-41715 range, where a redirect from a secure to an insecure endpoint can leak credentials, but only on a client explicitly configured to follow redirects, which no story configures, and the Key Vault property source runs only when `AZURE_KEYVAULT_URI` is set, so the exposure is conditional and no current Key Vault secret leak is claimed. Keep redirect following disabled, record the effective transport version at CS-05-001 and CS-12-004 implementation, and let the corrected Boot patch line's Reactor Netty (1.2.18 or later, as Boot 3.5.16 manages) govern the transport, or commercial 1.0.52 where the 1.0 line stays; Spring Cloud Azure 5.24.0 to 5.25.0 import the same Azure SDK BOM 1.2.37, so a Spring Cloud Azure bump alone does not move the transport.
- Decisions that stop holding above the planning baseline of 10 concurrent users, 100,000 category balances and 1,000,000 transactions:
  - Data-store sizing and monthly partitioning of the transaction table, which [D1 Data store](technical-requirements.md#d1-data-store) does not yet need at the 1,000,000-row baseline.
  - Per-account keyset pagination ([D4 Transaction-to-account access](technical-requirements.md#d4-transaction-to-account-access)). The trigger is any filtered transaction-list shape whose stub `ListTransactions` call exceeds 150 ms at p99, half the 300 ms per-attempt timeout, in CS-03-003. The remedy is three filter-leading indexes on `transaction_record`, each fixing every filtered column by equality ahead of `processed_timestamp desc, transaction_id desc`, so rows come back in cursor order with no sort: `(account_id, type_code, processed_timestamp desc, transaction_id desc)` for `typeCode` alone; `(account_id, type_code, category_code, processed_timestamp desc, transaction_id desc)` for both filters; `(account_id, category_code, processed_timestamp desc, transaction_id desc)` for `categoryCode` alone. Before the change is accepted, CS-03-003's four shapes are rerun with the indexes in place, and each must stay at or below 150 ms for the stub call and below 1,000 ms end to end at p99.
  - Cache sizing for the account, category-balance, transaction and reference-data entries.
- TRAN-PROC-TS is blank in all 300 sample transactions and every TRAN-ORIG-TS holds the same value, `2022-06-10 19:27:53.000000`, so newest-first ordering of the sample falls to the transaction identifier.
- Every sample account carries `A000000000` in the ACCT-ADDR-ZIP position and a blank ACCT-GROUP-ID; the loader reads both strictly by copybook offset.
- Sample cross-reference lines are 36 bytes, not the 50-byte record of Data Layouts · CVACT03Y; the loader accepts both.
- Sample timestamps use `YYYY-MM-DD HH:MM:SS.ffffff`, not the assumed `YYYY-MM-DD-HH.MM.SS.nnnnnn`; the loader parses both as UTC.
- There is no TRANSACT ASCII sample file; the daily transaction file is the transaction source.
- Use Cases behaviours not carried, because the Stub Contract Baseline governs operations, keys and ordering: the Use Cases · COTRN00C Tran ID start-key search, its browse across all accounts and its 10-row page. The UI may request `pageSize=10`.
- CardDemo has no user-to-account entitlement source, so the Auth fixture identities and the empty admin entitlement are a derived fixture policy ([admin fixture policy](technical-requirements.md#admin-fixture-policy)); changing the policy is an edit to the fixture file, with no code change.
- Ownership of the data store once real services replace the stubs is open.
- No sample account comes near 1,000 category balances (the sample maximum is 1); the synthetic volume uses 10 per account.
- The 22 platform stories whose origin is a request directive rather than a source document are marked in the [Story gaps](#story-gaps) Origin column for the Requirements Analysis author.
- This plan carries no schedule dates, durations or staffing, because waves are a dependency order; approval timing lives in the [sign-off record](sign-off.md). Latency thresholds and the sample timestamp stay, because they are performance criteria and source data.

## CARD_REQ_TBD gap list

Requirements Analysis (`docs/requirements-analysis.md`) does not exist, so every story traces `CARD_REQ_TBD` followed by its Use Cases or Data Layouts anchor. Its author, the Product Owner, assigns the requirement identifiers; this backlog never invents `CARD_REQ_NNN` identifiers.

### Story gaps

All 58 stories, in wave and position order. Anchor is the story's trace after `CARD_REQ_TBD · `; Origin is the story's `Directive:` line, or Source document where it has none; Requirement is the story statement.

| Story | Anchor | Origin | Requirement | Estimated files touched |
|---|---|---|---|---|
| [CS-05-001](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-001-service-module-scaffold-and-ci-gate.md) | Use Cases · COACTVWC | Directive: module and file organization | As a delivery engineer, I want a buildable two-module service with exact version pins and a pull-request CI gate, so that every later story builds, tests and validates the same way. | 14 |
| [CS-11-001](backlog/epics/CS-11-config-service-integration/stories/CS-11-001-service-configuration-properties-and-environment-variables.md) | Use Cases · COTRN00C | Directive: environment variables | As an operations engineer, I want every runtime setting bound from one typed configuration with safe defaults, so that each environment differs only by variables. | 4 |
| [CS-05-002](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-002-standard-error-object-and-status-mapping.md) | Use Cases · COACTVWC | Directive: standard error object | As a UI developer, I want every service error in one standard error object carrying a trace identifier, so that each failure maps to one screen message. | 7 |
| [CS-11-002](backlog/epics/CS-11-config-service-integration/stories/CS-11-002-stub-hosting-failure-injection-and-request-log.md) | Use Cases · COTRN00C | Directive: stub all five external calls | As a test engineer, I want every mocked upstream hosted the same way with injectable latency and failures and a request log, so that each failure mode is reproducible on a real channel. | 8 |
| [CS-11-003](backlog/epics/CS-11-config-service-integration/stories/CS-11-003-upstream-call-policy-with-request-deadline.md) | Use Cases · COTRN00C | Directive: error handling, timeouts and retries | As a card servicing user, I want every upstream call bounded by one request deadline, so that I get an answer or an error inside one second. | 7 |
| [CS-11-004](backlog/epics/CS-11-config-service-integration/stories/CS-11-004-read-through-cache-with-stale-fallback.md) | Use Cases · COTRN00C | Directive: read-through cache | As a card servicing user, I want recent data served from cache and kept usable during an upstream outage, so that a short outage shows stale data instead of an error. | 7 |
| [CS-09-001](backlog/epics/CS-09-card-data-integration/stories/CS-09-001-fixture-loader-core-and-load-report.md) | Data Layouts · CVACT01Y · ACCT-ID | Directive: in-repo fixture loader | As a test engineer, I want one loader that parses the fixed-width sample records and reports what it loaded, so that every stub serves known, verifiable data. | 6 |
| [CS-13-001](backlog/epics/CS-13-reference-data-integration/stories/CS-13-001-reference-data-tables-and-seed-files.md) | Data Layouts · CVTRA03Y · TRAN-TYPE-DESC | Source document | As a data owner, I want transaction types and categories held as seed data in their own tables, so that I change a description by loading data instead of deploying code. | 5 |
| [CS-09-002](backlog/epics/CS-09-card-data-integration/stories/CS-09-002-account-cross-reference-and-category-balance-tables.md) | Data Layouts · CVACT01Y · ACCT-ID | Source document | As a data owner, I want the account, cross-reference and category-balance tables to match the data model exactly, so that fixture data and stubs share one schema. | 7 |
| [CS-09-003](backlog/epics/CS-09-card-data-integration/stories/CS-09-003-card-data-fixture-load.md) | Data Layouts · CVACT03Y · XREF-ACCT-ID | Source document | As a test engineer, I want the sample accounts, cross-references and category balances loaded with zero skipped rows, so that every test reads the same known records. | 10 |
| [CS-10-001](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-001-transaction-table-with-account-keyset-index.md) | Data Layouts · CVTRA05Y · TRAN-ID | Source document | As a card servicing user, I want my transactions stored keyed by account in newest-first order, so that each page costs one index seek whatever the total volume. | 4 |
| [CS-10-002](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-002-transaction-fixture-load.md) | Data Layouts · CVTRA05Y · TRAN-PROC-TS | Source document | As a test engineer, I want the 300 sample transactions loaded with derived processed timestamps and their account, so that listings order deterministically. | 4 |
| [CS-11-005](backlog/epics/CS-11-config-service-integration/stories/CS-11-005-config-stub-service.md) | Use Cases · COTRN00C | Source document | As a test engineer, I want a mocked Config service answering from a bound key map, so that page size and call settings are testable without the real service. | 3 |
| [CS-11-006](backlog/epics/CS-11-config-service-integration/stories/CS-11-006-config-client-with-cached-values-and-built-in-defaults.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want configuration refreshed in the background, so that no request ever waits on the Config service. | 5 |
| [CS-11-007](backlog/epics/CS-11-config-service-integration/stories/CS-11-007-config-integration-suite.md) | Use Cases · COTRN00C | Directive: testing requirements (integration suite) | As a delivery engineer, I want the Config path proven against real containers, so that failure behaviour is verified before endpoints rely on it. | 5 |
| [CS-12-001](backlog/epics/CS-12-auth-service-integration/stories/CS-12-001-auth-stub-with-carddemo-derived-fixture-identities.md) | Use Cases · COACTVWC | Source document | As a test engineer, I want a mocked Auth service whose identities and entitlements come from the CardDemo user list, so that authorization is testable with deterministic users. | 4 |
| [CS-12-002](backlog/epics/CS-12-auth-service-integration/stories/CS-12-002-auth-client-with-service-api-key.md) | Use Cases · COACTVWC | Source document | As a card servicing user, I want my token checked through one Auth client that fails closed, so that no request proceeds on an unverified identity. | 3 |
| [CS-12-003](backlog/epics/CS-12-auth-service-integration/stories/CS-12-003-account-access-guard-for-rest-and-grpc.md) | Use Cases · COACTVWC | Source document | As an account holder, I want every account-data request checked against my entitlements before any data is read, so that no one sees an account they are not entitled to. | 6 |
| [CS-12-004](backlog/epics/CS-12-auth-service-integration/stories/CS-12-004-key-vault-secret-source-for-deployed-environments.md) | Use Cases · COACTVWC | Directive: secret management | As an operations engineer, I want deployed secrets read from Key Vault, so that no credential is stored in the repository or image. | 3 |
| [CS-12-005](backlog/epics/CS-12-auth-service-integration/stories/CS-12-005-auth-integration-suite.md) | Use Cases · COACTVWC | Directive: testing requirements (integration suite) | As a delivery engineer, I want the authentication and authorization path proven against real containers, so that fail-closed behaviour and the Config launch point are verified before endpoints rely on them. | 2 |
| [CS-13-002](backlog/epics/CS-13-reference-data-integration/stories/CS-13-002-reference-data-stub-service.md) | Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC | Source document | As a test engineer, I want a mocked Reference Data service listing types and categories from the loaded tables, so that descriptions come from data with zero live external calls. | 4 |
| [CS-13-003](backlog/epics/CS-13-reference-data-integration/stories/CS-13-003-reference-data-client-with-cache-and-description-fallback.md) | Data Layouts · CVTRA03Y · TRAN-TYPE-DESC | Source document | As a card servicing user, I want balances shown even when descriptions cannot be fetched, so that missing labels never block my balances. | 5 |
| [CS-13-004](backlog/epics/CS-13-reference-data-integration/stories/CS-13-004-reference-data-integration-suite.md) | Data Layouts · CVTRA04Y · TRAN-CAT-TYPE-DESC | Directive: testing requirements (integration suite) | As a delivery engineer, I want the Reference Data path proven against real containers, so that description fallback is verified before endpoints rely on it. | 2 |
| [CS-09-004](backlog/epics/CS-09-card-data-integration/stories/CS-09-004-card-data-stub-service.md) | Data Layouts · CVACT01Y · ACCT-CURR-BAL | Source document | As a test engineer, I want a mocked Card Data service answering its three contract operations, so that the service runs with zero live external calls. | 4 |
| [CS-09-005](backlog/epics/CS-09-card-data-integration/stories/CS-09-005-card-data-client-with-account-and-category-balance-caching.md) | Data Layouts · CVACT01Y · ACCT-CURR-BAL | Source document | As a card servicing user, I want account and category-balance reads cached with a stale fallback, so that a short Card Data outage still shows my balances. | 5 |
| [CS-09-006](backlog/epics/CS-09-card-data-integration/stories/CS-09-006-card-data-integration-suite.md) | Data Layouts · CVACT01Y · ACCT-ID | Directive: testing requirements (integration suite) | As a delivery engineer, I want the Card Data path proven against real PostgreSQL and Redis containers, so that its failure behaviour is verified before endpoints rely on it. | 2 |
| [CS-09-007](backlog/epics/CS-09-card-data-integration/stories/CS-09-007-category-balance-performance-volume.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Directive: performance planning baseline | As a performance engineer, I want 100,000 synthetic category balances loaded after the sample, so that latency is measured at the planning baseline. | 3 |
| [CS-10-003](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-003-page-token-codec.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want an opaque page token that cannot be replayed against another account or filter, so that paging always continues the list I started. | 3 |
| [CS-10-004](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-004-transaction-activity-stub-transaction-listing-with-keyset-paging.md) | Use Cases · COTRN00C | Source document | As a test engineer, I want a mocked Transaction Activity service that lists one account's transactions newest first in keyset pages, so that the drill-down is testable at full volume. | 4 |
| [CS-10-005](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-005-transaction-activity-stub-single-transaction-lookup.md) | Use Cases · COTRN01C | Source document | As a test engineer, I want the mocked Transaction Activity service to return one transaction by identifier, so that the detail drill-down is testable. | 3 |
| [CS-10-006](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-006-transaction-activity-client-with-transaction-caching.md) | Data Layouts · CVTRA05Y · TRAN-ID | Source document | As a card servicing user, I want transaction reads cached and bounded by the call policy, so that drill-downs answer inside the deadline. | 4 |
| [CS-10-007](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-007-transaction-activity-integration-suite.md) | Data Layouts · CVTRA05Y · TRAN-ID | Directive: testing requirements (integration suite) | As a delivery engineer, I want the Transaction Activity path proven against real containers, so that paging and failure behaviour are verified before endpoints rely on them. | 2 |
| [CS-10-008](backlog/epics/CS-10-transaction-activity-integration/stories/CS-10-008-transaction-performance-volume.md) | Data Layouts · CVTRA05Y · TRAN-ID | Directive: performance planning baseline | As a performance engineer, I want 1,000,000 synthetic transactions with a large-history account and a sparse filter pair, so that paging latency is measured at the planning baseline. | 3 |
| [CS-05-003](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-003-account-identifier-and-balance-summary-input-validation.md) | Use Cases · COACTVWC | Source document | As a card servicing user, I want a malformed or all-zero account number rejected at once, so that I see the error before any data lookup. | 3 |
| [CS-05-004](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-004-type-group-balance-aggregation.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Source document | As a card servicing user, I want my category balances summed by transaction type, so that the summary shows one balance per type. | 2 |
| [CS-05-005](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-005-balance-summary-assembly-with-stale-and-partial-degradation.md) | Data Layouts · CVACT01Y · ACCT-CURR-BAL | Source document | As a card servicing user, I want my balance summary even when one source is slow or down, so that I see the freshest data available with its status. | 5 |
| [CS-05-006](backlog/epics/CS-05-balance-summary-endpoint/stories/CS-05-006-balance-summary-rest-and-grpc-endpoints.md) | Use Cases · COACTVWC | Source document | As a UI developer, I want the balance summary served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface. | 4 |
| [CS-06-001](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-001-category-balance-input-validation.md) | Data Layouts · CVTRA01Y · TRANCAT-TYPE-CD | Source document | As a card servicing user, I want an invalid or unknown transaction type rejected with a clear error, so that I never see an empty list that hides a typing mistake. | 3 |
| [CS-06-002](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-002-category-balance-retrieval-filter-and-degradation.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Source document | As a card servicing user, I want my category balances for one type even when descriptions or the source are unavailable, so that I still see balances with their freshness status. | 3 |
| [CS-06-003](backlog/epics/CS-06-category-balances-endpoint/stories/CS-06-003-category-balance-rest-and-grpc-endpoints.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Source document | As a UI developer, I want category balances served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface. | 4 |
| [CS-07-001](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-001-transaction-list-input-validation.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want invalid page sizes, tokens and filters rejected before any lookup, so that every page I see continues the list I asked for. | 3 |
| [CS-07-002](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-002-transaction-list-retrieval-with-token-pass-through.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want my transactions listed newest first a page at a time, so that I can drill from a category into its transactions. | 3 |
| [CS-07-003](backlog/epics/CS-07-transaction-list-endpoint/stories/CS-07-003-transaction-list-rest-and-grpc-endpoints.md) | Use Cases · COTRN00C | Source document | As a UI developer, I want the transaction list served over REST and gRPC exactly as contracted, so that the deferred UI pages with one fixed interface. | 4 |
| [CS-08-001](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-001-transaction-detail-input-validation.md) | Use Cases · COTRN01C | Source document | As a card servicing user, I want a malformed transaction identifier rejected at once, so that I see the error before any lookup. | 3 |
| [CS-08-002](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-002-transaction-ownership-check-and-detail-retrieval.md) | Data Layouts · CVTRA05Y · TRAN-CARD-NUM | Source document | As an account holder, I want a transaction shown only when its card belongs to my account, so that no one reads another account's transaction by guessing its identifier. | 3 |
| [CS-08-003](backlog/epics/CS-08-transaction-detail-endpoint/stories/CS-08-003-transaction-detail-rest-and-grpc-endpoints.md) | Use Cases · COTRN01C | Source document | As a UI developer, I want the transaction detail served over REST and gRPC exactly as contracted, so that the deferred UI binds to a fixed interface. | 4 |
| [CS-01-001](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-001-compose-test-stack-with-stub-services.md) | Use Cases · COACTVWC | Directive: testing requirements (Compose test stack) | As a test engineer, I want one Compose stack running the service, five stubs, PostgreSQL, Redis and the fixture loader, so that end-to-end and performance suites run against the same real request path. | 3 |
| [CS-01-002](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-002-end-to-end-test-harness.md) | Use Cases · COACTVWC | Directive: testing requirements (end-to-end suite) | As a test engineer, I want an HTTP client that stands in for the deferred UI and reads every stub's request log, so that each interaction is verified on the real path. | 4 |
| [CS-01-003](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-003-performance-test-harness.md) | Use Cases · COACTVWC | Directive: testing requirements (performance harness) | As a performance engineer, I want a load runner that measures p50, p95 and p99 per endpoint, so that the 1-second SLA is proven at the planning baseline. | 6 |
| [CS-01-004](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-004-account-balance-summary-end-to-end-step.md) | Use Cases · COACTVWC | Source document | As a card servicing user, I want my balance summary shown from the real path, so that the account view is proven before the UI is built. | 1 |
| [CS-01-005](backlog/epics/CS-01-account-balance-summary-view/stories/CS-01-005-account-balance-summary-sla-scenario.md) | Use Cases · COACTVWC | Directive: 1-second SLA | As a product owner, I want the balance summary measured at the planning baseline, so that the 1-second SLA is proven for the account view. | 1 |
| [CS-02-001](backlog/epics/CS-02-category-balance-view/stories/CS-02-001-category-balance-end-to-end-step.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Source document | As a card servicing user, I want to open the categories behind a balance-summary type row, so that I see which categories make up that balance. | 1 |
| [CS-02-002](backlog/epics/CS-02-category-balance-view/stories/CS-02-002-category-balance-sla-scenario.md) | Data Layouts · CVTRA01Y · TRAN-CAT-BAL | Directive: 1-second SLA | As a product owner, I want category balances measured at the planning baseline, so that the 1-second SLA is proven for this view. | 1 |
| [CS-03-001](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-001-transaction-list-end-to-end-step.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want to open the transactions behind a category row, so that I see the activity that makes up that balance. | 1 |
| [CS-03-002](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-002-transaction-list-paging-navigation-step.md) | Use Cases · COTRN00C | Source document | As a card servicing user, I want to page forward and back through my transactions, so that I can browse a long history as the mainframe screen allowed. | 1 |
| [CS-03-003](backlog/epics/CS-03-transaction-list-drill-down/stories/CS-03-003-transaction-list-sla-scenario.md) | Use Cases · COTRN00C | Directive: 1-second SLA | As a product owner, I want the transaction list measured at full volume including sparse filters, so that the 1-second SLA and the indexing decision are proven. | 1 |
| [CS-04-001](backlog/epics/CS-04-transaction-detail-drill-down/stories/CS-04-001-transaction-detail-end-to-end-step.md) | Use Cases · COTRN01C | Source document | As a card servicing user, I want to open one transaction from my list, so that I see its full detail. | 1 |
| [CS-04-002](backlog/epics/CS-04-transaction-detail-drill-down/stories/CS-04-002-transaction-detail-sla-scenario.md) | Use Cases · COTRN01C | Directive: 1-second SLA | As a product owner, I want transaction detail measured at full volume, so that the 1-second SLA is proven for the detail view. | 1 |
| **Total** | 58 stories | | | 224 |

### Unmapped-field table

The 17 Customer Details labels of the account view (Use Cases · COACTVWC, filled from the customer record of Data Layouts · CVCUS01Y), in screen order. Labels and statuses equal the [Customer Details field gaps](backlog/epics/CS-01-account-balance-summary-view/README.md#customer-details-field-gaps) of the CS-01 epic README and the [screen-field coverage matrix](data-model.md#screen-field-coverage-matrix).

| Label | Data Layouts · CVCUS01Y field | PICTURE | Anchor | Status |
|---|---|---|---|---|
| Customer id | CUST-ID | 9(09) | Use Cases · COACTVWC | Field gap — baseline field not exposed on balance summary; delivery awaits a Requirements Analysis ID |
| SSN | CUST-SSN | 9(09) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Date of birth | CUST-DOB-YYYY-MM-DD | X(10) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| FICO Score | CUST-FICO-CREDIT-SCORE | 9(03) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| First Name | CUST-FIRST-NAME | X(25) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Middle Name | CUST-MIDDLE-NAME | X(25) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Last Name | CUST-LAST-NAME | X(25) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Address | CUST-ADDR-LINE-1, CUST-ADDR-LINE-2 | X(50) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| State | CUST-ADDR-STATE-CD | X(02) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Zip | CUST-ADDR-ZIP | X(10) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| City | CUST-ADDR-LINE-3 | X(50) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Country | CUST-ADDR-COUNTRY-CD | X(03) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Phone 1 | CUST-PHONE-NUM-1 | X(15) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Government Issued Id Ref | CUST-GOVT-ISSUED-ID | X(20) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Phone 2 | CUST-PHONE-NUM-2 | X(15) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| EFT Account Id | CUST-EFT-ACCOUNT-ID | X(10) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |
| Primary Card Holder Y/N | CUST-PRI-CARD-HOLDER-IND | X(01) | Use Cases · COACTVWC | Field gap — no baseline entity or operation; delivery awaits a Requirements Analysis ID |

Resolution, pending a Requirements Analysis identifier for each label:

- The 16 customer-record labels need a customer entity beyond the six fixed baseline entities and a customer read on the Card Data service; neither is in the Stub Contract Baseline.
- Customer id needs no new entity or operation: only a customer identifier field on the balance-summary response, a cross-reference read on that endpoint and a degradation rule for that read.
- Each would become a later story of that shape if the Requirements Analysis author adopts it; no contract field, message or operation is added for these labels in this iteration.
