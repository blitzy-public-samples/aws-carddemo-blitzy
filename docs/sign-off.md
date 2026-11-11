# Card Servicing Sign-off

This record covers the review-ready backlog for the first, read-only Card Servicing iteration and its design artifacts: the [implementation plan](implementation-plan.md), which is the entry point, the [Technical Requirements and Architecture](technical-requirements.md), the [data model](data-model.md) and the [epics index](backlog/epics/README.md). Every approval field below is left blank for its approver. The run proceeds on the stated defaults and does not wait for an answer.

## Sign-off table

| Role | Name | Decision | Date | Comments |
|---|---|---|---|---|
| Product Owner | [Name] | | | |
| Workstream SME | [Name] | | | |
| Delivery Lead | [Name] | | | |

Decision records Approved or Changes requested. The Product Owner's approval in this table closes completion-bar item 5.

## Sign-off SLA

Approvers sign within 48 hours of submission. If no sign-off is recorded after 48 hours, "the prompt is submitted as drafted".

The SLA lives in this record, not in the implementation plan, because the plan carries no dates, durations or staffing: its waves are a dependency order, not a schedule.

## Build order confirmation

The build sequence is [INFERRED]: W0 Foundation → W1 Stubbed integrations → W2 Service endpoints → W3 UI interaction verification. Under it, the data model and core balance logic come before their consumers, and stubs come before the stories that call them. The run proceeds on this order. The waves, their exit conditions and the [Dependency Map](implementation-plan.md#dependency-map) are in the [implementation plan](implementation-plan.md).

- Product Owner confirmation: [ ] Confirmed / [ ] Changes requested
- Name:
- Notes:

## Delivery Lead review checklist

The Delivery Lead reviews decisions D1–D5 in the [Decisions](technical-requirements.md#decisions) section of the Technical Requirements and Architecture document.

Pass rule: every decision shows 1–3 ranked alternatives, with reasoning and a documented pressure-test against failure modes, data growth and the 1-second SLA. This review is manual and closes completion-bar item 3.

| Decision | Alternatives present (1–3) | Ranking stated | Reasoning | Pressure-test | Reviewer verdict |
|---|---|---|---|---|---|
| [D1 Data store](technical-requirements.md#d1-data-store): Azure Database for PostgreSQL Flexible Server, kept | [ ] | [ ] | [ ] | [ ] | |
| [D2 Epic grouping](technical-requirements.md#d2-epic-grouping): the three request groupings, each shared foundation owned by its first consumer's epic | [ ] | [ ] | [ ] | [ ] | |
| [D3 Stub hosting and swap point](technical-requirements.md#d3-stub-hosting-and-swap-point): one gRPC client per interaction against stub gRPC servers, swapped by target address and credential source | [ ] | [ ] | [ ] | [ ] | |
| [D4 Transaction-to-account access](technical-requirements.md#d4-transaction-to-account-access): a denormalized account column on transactions with an account-leading keyset index | [ ] | [ ] | [ ] | [ ] | |
| [D5 One Compose stack for both suites](technical-requirements.md#d5-one-compose-stack-for-both-suites): one stack loading sample and synthetic data in disjoint ID ranges | [ ] | [ ] | [ ] | [ ] | |

Reviewer verdict records Pass or Fail against the pass rule.

## Delivery-repository handoff

The deliverables belong in `your-org/carddemo-card-servicing` on branch `main`, with CardDemo as the read-only reference source. They are staged in the CardDemo checkout because the run's environment could not create or push to the delivery repository. Delivery completes when a person with access:

1. Creates `your-org/carddemo-card-servicing`.
2. Copies the run-created files, 111 in the plan's estimate, unedited to its root on branch `main`, keeping their relative paths. They are every tracked file under `docs/`, `card-servicing-contracts/`, `card-servicing-service/` and `tests/`, plus `.spectral.yaml`; `tests/validation/node_modules/` is not copied.
3. Reruns `tests/validation/run-all.sh` there from the repository root, and it exits with zero failures.

Until then, completion-bar items 1 (every artifact exists at its path) and 2 (every automated check reports zero failures) hold only for the staged copy in the CardDemo checkout.

- Repository: `your-org/carddemo-card-servicing`
- Branch: `main`
- Commit:
- Rerun result (`tests/validation/run-all.sh`):
- Performed by:
