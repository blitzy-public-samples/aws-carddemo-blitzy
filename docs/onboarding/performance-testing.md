# Performance Testing — Scaled Datasets and Query-Plan Verification

This guide takes you from the shipped 1x seed to a **reproducible, referentially
intact multi-x dataset** and shows how to measure query plans and batch timings
against it. It is the missing companion to
[`getting-started.md`](./getting-started.md): that guide gets a *functional*
CardDemo running on the 1x seed; this one gets a *performance-representative*
CardDemo so you can confirm that the data tier scales the way the design intends.

It exists because performance work on this codebase needs two things that were
previously undocumented: (1) how the seed data is scaled up so plans reflect a
larger table, and (2) how to read the resulting plans so a regression such as a
missing index is caught before it ships. The worked example below is the exact
scenario behind Flyway migration **`V4`** and decision-log **D10** ("Statement
per-card covering index").

---

## Contents

1. [When to use this](#1-when-to-use-this)
2. [Prerequisites](#2-prerequisites)
3. [Step 1 — establish the 1x baseline](#3-step-1--establish-the-1x-baseline)
4. [Step 2 — generate a scaled (Nx) dataset](#4-step-2--generate-a-scaled-nx-dataset)
5. [Step 3 — measure query plans (EXPLAIN)](#5-step-3--measure-query-plans-explain)
6. [Step 4 — measure the statement batch job](#6-step-4--measure-the-statement-batch-job)
7. [Interpreting results — the red flags](#7-interpreting-results--the-red-flags)
8. [Cleanup and isolation](#8-cleanup-and-isolation)
9. [Where to go next](#9-where-to-go-next)

---

## 1. When to use this

Reach for this guide when you are:

- adding or changing an index, a query, or a `@Query`/derived repository method
  and want to prove the plan before and after;
- investigating a batch job whose cost appears to grow faster than linearly with
  the input (the statement, posting, interest, and report jobs all iterate over
  per-card or per-account sets);
- reproducing a performance finding that quotes buffer counts, timings, or a
  `Sort Method: external merge Disk` line.

You do **not** need it for functional testing — the unit and Testcontainers
integration suites (`./mvnw -B clean verify`) run against the 1x seed and are the
correct gate for behavioral parity.

---

## 2. Prerequisites

1. **A running PostgreSQL 16 you can freely fill and drop.** Use the project's
   local stack from [`getting-started.md` §3](./getting-started.md#3-start-the-local-dependencies-docker-compose).
   For performance runs prefer a **dedicated, isolated** database so your numbers
   are not disturbed by other work. The local stack already parameterizes the
   host port and container name by `CLONE_INDEX` (`PG_PORT = 5432 + CLONE_INDEX`,
   container `carddemo-postgres-<CLONE_INDEX>`); set `CLONE_INDEX` so parallel
   workspaces never share a database:

   ```bash
   export CLONE_INDEX=0            # pick a value unique to your workspace
   export PG_PORT=$((5432 + CLONE_INDEX))
   # DB_URL then points at jdbc:postgresql://localhost:${PG_PORT}/carddemo
   ```

2. **`psql` on your PATH** (from the `postgresql-client` package or the Postgres
   container: `docker exec -it carddemo-postgres-<CLONE_INDEX> psql ...`).

3. **Credentials via environment only** — never hard-code them. The local-dev
   values live in your shell as `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`
   (see [`getting-started.md` §4](./getting-started.md#4-configure-environment-variables)).
   The examples below assume:

   ```bash
   export PGHOST=localhost PGPORT=${PG_PORT:-5432}
   export PGUSER="$DB_USERNAME" PGPASSWORD="$DB_PASSWORD" PGDATABASE=carddemo
   ```

4. **Know your `work_mem`.** A sort that exceeds `work_mem` spills to a temp file
   (`Sort Method: external merge Disk: …kB`), which is the single loudest signal
   of a missing supporting index. Check and, if you want to reproduce a spill on
   modest data, lower it for the session:

   ```sql
   SHOW work_mem;              -- default is often 4MB
   SET work_mem = '4MB';       -- session-only; make spills easy to observe
   ```

5. **(Optional) `pg_stat_statements`** for aggregate call/row/buffer totals across
   a whole job run (the shape the performance report quotes as "N calls, M rows,
   T ms, B buffers"). If the extension is available:

   ```sql
   CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
   SELECT pg_stat_statements_reset();
   -- ... run the workload ...
   SELECT calls, rows, total_exec_time, shared_blks_hit + shared_blks_read AS buffers, query
   FROM pg_stat_statements
   WHERE query ILIKE '%from transaction%where%card_num%'
   ORDER BY total_exec_time DESC LIMIT 5;
   ```

---

## 3. Step 1 — establish the 1x baseline

The 1x corpus is the CSV seed under `src/main/resources/db/seed/`, loaded by the
`local` profile's `LocalSeedDataLoader` after Flyway has applied the schema. Bring
up an app instance (or a plain JDBC connection) with the schema migrated and the
seed loaded, then confirm the baseline counts:

```bash
psql -c "
SELECT 'customer' AS t, count(*) FROM customer
UNION ALL SELECT 'account', count(*) FROM account
UNION ALL SELECT 'card', count(*) FROM card
UNION ALL SELECT 'card_xref', count(*) FROM card_xref
UNION ALL SELECT 'transaction', count(*) FROM transaction
UNION ALL SELECT 'tran_cat_balance', count(*) FROM tran_cat_balance
UNION ALL SELECT 'daily_transaction', count(*) FROM daily_transaction
UNION ALL SELECT 'user_security', count(*) FROM user_security
ORDER BY t;"
```

Expected 1x counts:

| Table | 1x rows |
|-------|--------:|
| `customer` | 50 |
| `account` | 50 |
| `card` | 50 |
| `card_xref` | 50 |
| `transaction` | 300 |
| `tran_cat_balance` | 50 |
| `daily_transaction` | 300 |
| `user_security` | 10 |

The three **reference** tables are fixed by the domain and are the same at every
scale: `transaction_type` = 7, `transaction_category` = 18, `disclosure_group` = 51.

At 1x the per-card statement query touches so few pages that a missing index is
invisible — which is exactly why scaling up is necessary to see the regression.

---

## 4. Step 2 — generate a scaled (Nx) dataset

Use the shipped, dependency-free generator
[`perf/scale-dataset.sql`](./perf/scale-dataset.sql). It adds `(N-1)` fully
linked replicas of the eight transactional tables on top of the 1x rows while
leaving the reference tables untouched, so the result is `N` total copies with
**100% referential integrity**. Its header documents the exact key-disjointness
strategy (BIGINT block offsets for `cust_id`/`acct_id`, leading-band offsets for
`tran_id`/`dalytran_id`, and synthetic remaps for the full-width `card_num` /
`xref_card_num` / `sec_usr_id` keys).

Start from a **fresh 1x database** (the script stacks replicas on whatever is
present, so applying it twice yields 2·N−1 copies, not N). The clean pattern is to
clone your seeded baseline into a throwaway database first:

```bash
# clone the seeded baseline into an isolated perf database
psql -d postgres -c "CREATE DATABASE carddemo_perf TEMPLATE carddemo;"

# scale it to 10x (the QA performance corpus)
psql -d carddemo_perf -v factor=10 -f docs/onboarding/perf/scale-dataset.sql
```

The script prints the resulting counts. At `factor=10` you get the QA **10x**
corpus, verified by this guide's author to match exactly:

| Table | 1x | 10x (`factor=10`) |
|-------|---:|------------------:|
| `customer` | 50 | 500 |
| `account` | 50 | 500 |
| `card` | 50 | 500 |
| `card_xref` | 50 | 500 |
| `transaction` | 300 | 3000 |
| `tran_cat_balance` | 50 | 500 |
| `daily_transaction` | 300 | 3000 |
| `user_security` | 10 | 100 |
| reference tables | 7 / 18 / 51 | 7 / 18 / 51 (unchanged) |

To confirm integrity yourself, every foreign key should have zero orphans, e.g.:

```sql
SELECT count(*) AS txn_orphans
FROM transaction t LEFT JOIN card c ON c.card_num = t.card_num
WHERE c.card_num IS NULL;                        -- expect 0
```

> **Validated range.** The key-disjointness math is proven for `factor <= 10`
> (the script emits a warning above that). Larger factors need a wider synthetic
> key band for `card_num`/`tran_id`; adjust the offsets in the script header
> before trusting the result. Reference-only tables are never scaled because
> their cardinality is a domain constant, not a volume.

---

## 5. Step 3 — measure query plans (EXPLAIN)

Always update statistics before measuring, then use `EXPLAIN (ANALYZE, BUFFERS)`
so you see the *chosen* plan, real row counts, and page accesses:

```sql
ANALYZE;   -- or: ANALYZE transaction;
```

The canonical hot path is the statement job's per-card browse
(`TransactionRepository.findByCardNumOrderByProcTsAscTranIdAsc`, invoked once per
card from `StatementFileService.readTransactionsForCard`):

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM transaction
WHERE card_num = '<some card_num from your dataset>'
ORDER BY proc_ts, tran_id;
```

**Before the covering index** (schema V1–V3), on the 10x corpus this plan is a
full sequential scan feeding a sort — the whole 3000-row table is read to return
one card's handful of rows:

```
Sort  (cost=114.58..114.59 rows=6 ...)
  Sort Key: proc_ts, tran_id
  Sort Method: quicksort  Memory: 26kB
  Buffers: shared hit=80
  ->  Seq Scan on transaction  (cost=0.00..114.50 rows=6 ...)
        Filter: ((card_num)::text = '...'::text)
        Rows Removed by Filter: 2994
        Buffers: shared hit=77
```

Because the scan cost is independent of how many rows match, doing this once per
card makes the whole job **O(cards × table size)** — quadratic in the dataset.

**After the covering index** `idx_transaction_card_num (card_num, proc_ts,
tran_id)` (Flyway `V4`), the same query stops scanning the table and reads only
the matching index/heap pages — buffer reads collapse from **80 to ~6**:

```
Index Scan using idx_transaction_card_num on transaction  (cost=0.28..17.37 rows=6 ...)
  Index Cond: ((card_num)::text = '...'::text)
  Buffers: shared hit=4 read=2
```

Two honest notes on plan shape:

- For a **small** match the planner may instead pick a *Bitmap Index Scan* on the
  same index followed by a tiny in-memory sort (e.g. `quicksort Memory: 26kB`).
  That is fine: the full sequential scan is still gone and buffers still collapse
  to ~6. The lead `card_num` column drives the index; the trailing `proc_ts,
  tran_id` columns let the planner skip the sort entirely when it chooses the
  ordered Index Scan.
- For a **large** single-card history the ordered Index Scan is decisively chosen
  and the sort disappears completely — which is what removes the disk spill (see
  the next section).

---

## 6. Step 4 — measure the statement batch job

To exercise the real job rather than a single query, run the statement generation
job against your scaled database and watch the aggregate the report quotes:

```bash
# web server off so the process exit code == the Spring Batch return code
java -jar target/carddemo-*.jar \
  --spring.batch.job.name=statementGenerationJob \
  --spring.main.web-application-type=none
  # statementGenerationJob takes NO required (and no date) parameters - it reads posted transaction
  # history; override output paths with the carddemo.batch.statement.* properties. To re-run a
  # completed no-parameter job, add a unique job parameter, e.g. stamp=$(date +%s).
  # Full launch + config reference: getting-started.md section 8 "Run the batch jobs".
```

With `pg_stat_statements` reset immediately before the run (see §2), the per-card
query row should show the difference the index makes. On the 10x corpus the
performance report measured the pre-index job at roughly **500 calls / 3000 rows
/ ~149 ms / ~38,500 buffers** (about 77 buffers per call — one full table scan per
card); with the covering index each call drops to the same ~6 buffers seen in §5,
removing the quadratic buffer growth.

**Reproducing the disk spill.** The most severe symptom appears when a single card
has a long history and the resulting sort exceeds `work_mem`. Concentrate many
rows on one card, set `work_mem = '4MB'`, and EXPLAIN the per-card query: without
the index you will see

```
Sort Method: external merge  Disk: ~9-12 MB
Buffers: shared hit=..., temp read=... written=...
```

i.e. the sort spills to temp files (temp `read`/`written` are the tell). With
`idx_transaction_card_num` in place the same query becomes an ordered Index Scan
with **no `Sort` node and zero `temp` I/O**, eliminating the spill entirely. This
is the concrete win recorded in decision **D10**.

---

## 7. Interpreting results — the red flags

Scan `EXPLAIN (ANALYZE, BUFFERS)` output for these, in priority order:

| Signal in the plan | What it means | Usual fix |
|--------------------|---------------|-----------|
| `Seq Scan` on a large table with a selective `Filter` and high `Rows Removed by Filter` | No usable index for the predicate; cost scales with table size | Add an index on the filter column(s) |
| `Sort Method: external merge  Disk: …kB` / non-zero `temp read/written` | The sort exceeded `work_mem` and spilled to disk | Add a covering index whose trailing columns match the `ORDER BY`, or raise `work_mem` |
| `Buffers` per call that grows with the dataset | Per-iteration work is a function of table size, not match size — the hallmark of an O(N²) loop | Index the per-iteration predicate |
| Actual `rows` far from estimated `rows` | Stale statistics | `ANALYZE` the table |

The covering index pattern used for `V4` — **equality column(s) first, then the
`ORDER BY` columns** — is the general remedy for a "filter then sort" hot path:
it satisfies the `WHERE` with the leading column and the `ORDER BY` with the
trailing columns, so the plan needs neither a sequential scan nor a sort.

---

## 8. Cleanup and isolation

The scaled database is disposable. Drop it when you are done so it never pollutes
a later measurement:

```bash
psql -d postgres -c "DROP DATABASE IF EXISTS carddemo_perf;"
```

Keep performance databases **separate** from the functional `carddemo` database
the app and tests use, and keep them **isolated per workspace** via `CLONE_INDEX`
(see §2) so parallel runs never contend for the same rows, connections, or buffer
cache.

---

## 9. Where to go next

- [`../decision-log.md`](../decision-log.md) — decision **D10** (the covering
  index measured above) and **D55** (the combine job's in-memory sort ceiling),
  the two performance-relevant design records.
- [`./perf/scale-dataset.sql`](./perf/scale-dataset.sql) — the generator, with its
  full key-disjointness rationale in the header.
- [`./getting-started.md`](./getting-started.md) — the functional setup this guide
  builds on, including [§8 "Run the batch jobs"](./getting-started.md#8-run-the-batch-jobs)
  (statement-job launch, no-parameter re-run note, and the `carddemo.batch.statement.*` output keys).
- [`./extending.md`](./extending.md) — the idiomatic way to add a query or index
  (write the migration, then verify the plan with this guide).
- [`../architecture.md`](../architecture.md) — where the data-tier indexes and the
  batch data-flow are described.
