# Getting Started

This guide takes a new developer from a clean machine to a checked-out, building,
and verifiable CardDemo workspace. It is written to be followed top to bottom
without prior knowledge of the project.

> **Where the project is right now (read this first).**
> CardDemo is being re-platformed from the legacy mainframe stack (COBOL / CICS /
> VSAM / JCL) to Java 25 + Spring Boot 3.5.x + PostgreSQL 16. Delivery is
> incremental so each unit is independently reviewable. At the current checkpoint
> the repository contains:
>
> - the **complete legacy source**, retained read-only under [`legacy/`](../../legacy) for reference;
> - the **Maven build manifest** ([`pom.xml`](../../pom.xml)) — dependencies, Java 25 toolchain, coverage gate, and the OWASP security gate;
> - the **migration documentation** ([architecture](../architecture.md), [decision log](../decision-log.md), [traceability matrix](../traceability-matrix.md)); and
> - the **executive deck** ([`blitzy-deck/executive-summary.html`](../../blitzy-deck/executive-summary.html)).
>
> The Java application modules (`src/main/java/**`, `src/main/resources/**`,
> `src/test/**`), the `docker-compose.yml` observability stack, the `mvnw`
> wrapper, and the CI workflow are **delivered in the subsequent implementation
> checkpoints** described by the Agent Action Plan. Commands in this guide are
> labelled **[available now]** or **[target — lands with the application modules]**
> so you always know what you can run today.

---

## 1. Prerequisites

Install the following. Versions are pinned to what the build targets; newer
patch releases are fine.

| Tool | Version | Why | Verify |
|------|---------|-----|--------|
| **JDK** | Java **25** (LTS; e.g. Eclipse Temurin) | Language/runtime target; the compiler is configured for `--release 25` | `java -version` |
| **Apache Maven** | **3.9+** | Build, test, coverage, and security gates | `mvn -version` |
| **Docker** + Compose plugin | current | Runs PostgreSQL 16 locally (and, later, the observability stack) | `docker --version` |
| **Git** (+ Git LFS) | current | Source control | `git --version` |

> The build also works with the system `mvn` today. Once the `mvnw` wrapper lands
> with the application modules, prefer `./mvnw` so the exact Maven version is
> reproducible on any machine.

---

## 2. Clone and explore

```bash
git clone <repository-url> carddemo
cd carddemo
```

Top-level layout you will see today:

```
carddemo/
├── pom.xml                  # Maven build manifest (Java 25, Spring Boot 3.5.16, gates)
├── .gitattributes           # byte-exact protection for legacy/data/** authorities
├── README.md                # project overview + build/run entry point
├── docs/                    # architecture, decision log, traceability, onboarding, observability
├── blitzy-deck/             # self-contained executive presentation (reveal.js)
└── legacy/                  # READ-ONLY relocated mainframe source (formerly app/**)
    ├── cbl/  cpy/  cpy-bms/  bms/     # COBOL programs, copybooks, BMS maps
    ├── jcl/  proc/  ctl/  csd/        # batch orchestration + CICS resource defs
    ├── data/ASCII/  data/EBCDIC/      # seed data (fixed-width) + EBCDIC datasets
    └── catlg/LISTCAT.txt              # VSAM catalog listing (attribute reference)
```

Start with the [architecture overview](../architecture.md) for the target layered
design, then [domain context](./domain-context.md) for the business background.

---

## 3. Start PostgreSQL 16 (local database) — [available now]

The application resolves every secret from the environment; **nothing is
hardcoded**. Start a local PostgreSQL 16 with credentials supplied at run time:

```bash
docker run -d --name carddemo-postgres \
  -e POSTGRES_DB="${DB_NAME:-carddemo}" \
  -e POSTGRES_USER="${DB_USERNAME:-carddemo}" \
  -e POSTGRES_PASSWORD="${DB_PASSWORD:?set a password}" \
  -p 5432:5432 postgres:16
```

Verify it is reachable:

```bash
docker exec -it carddemo-postgres pg_isready -U "${DB_USERNAME:-carddemo}"
```

---

## 4. Build and verify — [available now]

From the repository root:

```bash
# 1) Validate the build manifest (fast; no sources required yet).
mvn -B validate

# 2) Inspect the resolved dependency graph, including the security overrides.
mvn -B dependency:tree
```

You should see the CVE-remediated coordinates resolved:
`tomcat-embed-core:10.1.57`, `commons-compress:1.27.1`, and
`commons-lang3:3.20.0` (see the `<dependencyManagement>` block in `pom.xml` and
[decision log D5](../decision-log.md)).

### Run the security gate (OWASP dependency-check)

```bash
# Populates the local NVD database, then fails the build on any CVSS >= 7.
mvn -B dependency-check:check
```

> **NVD API key.** The first run downloads the full National Vulnerability
> Database. Without an API key this is heavily rate-limited and can take a long
> time. For reproducible/CI runs, obtain a free key from the NVD and pass it via
> the plugin's `nvdApiKey` (or the `NVD_API_KEY` environment variable). After the
> first successful update the local database is cached under
> `~/.m2/repository/org/owasp/dependency-check-data/`.

### Full build — [target — lands with the application modules]

Once `src/**` is present, the canonical, reproducible, non-interactive build is:

```bash
./mvnw -B clean verify
```

which compiles with **zero warnings** under Java 25, runs unit tests, runs
Testcontainers integration tests against a real PostgreSQL 16, enforces the
**JaCoCo ≥ 80%** line-coverage gate, and runs the **OWASP** security gate.

---

## 5. Configuration via environment variables — [available now for config, target for run]

No credentials or connection strings are committed. Provide them through the
environment (activated through the `local` Spring profile once the app modules
land):

```bash
export DB_URL="jdbc:postgresql://localhost:5432/${DB_NAME:-carddemo}"
export DB_USERNAME="${DB_USERNAME:-carddemo}"
export DB_PASSWORD="<supplied at runtime; never committed>"
export SPRING_PROFILES_ACTIVE=local
```

---

## 6. Run the application — [target — lands with the application modules]

When the application modules are present:

```bash
# Option A: bring up local infrastructure (PostgreSQL + observability stack)
docker-compose up -d

# Option B: run from source
./mvnw spring-boot:run

# Option C: run the packaged jar
./mvnw -B clean verify
java -jar target/carddemo-1.0.0.jar
```

The **observability** stack (structured logging with correlation IDs, Micrometer
+ OpenTelemetry tracing over OTLP, a Prometheus metrics endpoint, Actuator
health/readiness, and a Grafana dashboard template) is **designed and configured
in `pom.xml` and the planned `docker-compose.yml`**; it will be exercisable
locally once those modules land. See [architecture — Cross-Cutting Concerns](../architecture.md)
and the [Grafana dashboard template](../observability/grafana-dashboard.json).

---

## 7. Where to go next

- [Domain context](./domain-context.md) — the credit-card business model and how the legacy authorities are organized.
- [Extending the application](./extending.md) — how to add a screen or batch job the idiomatic way, plus **suggested next tasks**.
- [Pitfalls](./pitfalls.md) — the traps that will bite you (decimal fidelity, fixed-width layouts, the alternate-index timestamp, the disclosure-group key, EBCDIC bytes).
- [Traceability matrix](../traceability-matrix.md) — every COBOL construct mapped to its Java target.
