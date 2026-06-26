# Golden — Sorted/Combined Transaction File (COMBTRAN SORT + IDCAMS REPRO)

This folder holds the **expected** sorted/combined transaction output used to assert that the
modernized `TransactionCombineStep` `Comparator<>` reproduces the legacy `SORT FIELDS=(TRAN-ID,A)`
ordering from `app/jcl/COMBTRAN.jcl`, prior to (or equivalent to) the IDCAMS `REPRO` load defined
by `app/ctl/REPROCT.ctl`. The Java combine step is the port of the legacy `COMBTRAN` SORT step, and
this golden is the byte-exact expectation its ordering must match.

This asset is **OPTIONAL / supplementary** — a nice-to-have ordering-parity assertion, **not a hard
quality gate**. It is generated **locally** from the documented COBOL/JCL logic; **no COBOL or
mainframe runtime is required** to author or run the paired test (AAP §0.6.3, §0.6.7). Legacy
specifications are read-only at `legacy/app/...` (the source `app/...` tree maps 1:1 to
`legacy/app/...`).

## The golden file

| Property | Value |
|----------|-------|
| **Filename** | `dailytran.combined-sorted.dat` |
| **Classpath access** | `classpath:golden/sorted/dailytran.combined-sorted.dat` |
| **Record layout** | `CVTRA05Y` / `TRAN-RECORD`, fixed **`LRECL = 350`** bytes |
| **Records** | **300** |
| **Total size** | **105300 bytes** ( = 350 × 300 + 300 LF ) |
| **Encoding** | plain 7-bit **ASCII** |
| **Line endings** | **Unix LF only** — trailing newline present, **no CR**, **no BOM** |
| **MD5** | `473363eedabb74e0a47e543f18cdcc32` |
| **SHA-256** | `1605206de7009cba771a921bf13f4dfcd1673fc13f1b844150355e9a95fa8da3` |

The classpath name follows the parent convention `classpath:golden/sorted/<case>.dat`, where
`<case>` echoes the input fixture `dailytran`. The inbound `dailytran` fixture uses the
byte-identical `CVTRA06Y` / `DALYTRAN-RECORD` layout (the same 350-byte field map as `CVTRA05Y` /
`TRAN-RECORD`), so the two records are interchangeable at the byte level.

## Sort contract

Verified from `app/jcl/COMBTRAN.jcl`:

```text
TRAN-ID,1,16,CH            SYMNAMES: sort key = bytes 1–16, CHARACTER collation
SORT FIELDS=(TRAN-ID,A)    ascending
```

- **Sort key** — the first **16 bytes** (`TRAN-ID`) under **CHARACTER collation** (ASCII byte
  order). In Java, compare the 16-character `TRAN-ID` substring with natural `String` / byte
  ordering (i.e. `LC_ALL=C` semantics — `Comparator.comparing` over a `US_ASCII` substring) in
  **ascending** (`A`) order.
- **Inputs combined** — the legacy step concatenates `AWS.M2.CARDDEMO.TRANSACT.BKUP(0)` +
  `AWS.M2.CARDDEMO.SYSTRAN(0)`, sorts them, then a second step runs IDCAMS
  `REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)` to load the sorted result into the transaction master
  KSDS. This golden represents the **sorted result prior to / equivalent to** the `REPRO` load —
  `REPRO` is a straight copy and does **not** reorder records. There is no separate BKUP/SYSTRAN
  ASCII fixture in this repo, so the representative transaction dataset is `dailytran`.
- **Tie-order (IMPORTANT)** — the legacy job specifies **no `EQUALS`** option, so the relative
  order of records sharing an equal `TRAN-ID` is **unspecified** by the mainframe sort. The Java
  side MUST therefore use a **stable** `Comparator<>` on `TRAN-ID` ascending and treat equal-key
  order as **input order**. In this specific dataset **all 300 `TRAN-ID`s are UNIQUE (zero
  duplicates)**, so the ascending order is **total and unambiguous** and the tie-break rule does
  not affect this golden — but the stable-comparator requirement still holds for general
  correctness.

## Input pairing (logical only)

This golden pairs with the sibling input fixture `src/test/resources/fixtures/ascii/dailytran.txt`
(which mirrors `legacy/app/data/ASCII/dailytran.txt`), loadable as
`classpath:/fixtures/ascii/dailytran.txt`.

The pairing is **logical only — there is NO build dependency** between `golden/sorted/` and
`fixtures/`; both are static test data, and the wiring (which fixture feeds the step to produce this
golden) is asserted entirely by the test code in `src/test/java`, not by the build.

Because the `dailytran` dataset is **already in ascending `TRAN-ID` order**, this golden is
**byte-identical** to the `dailytran` fixture — both share MD5 `473363eedabb74e0a47e543f18cdcc32`.

## Meaningful-test recipe (the key section)

**Pitfall.** Since the input fixture is already sorted, feeding it **unchanged** into the sort and
asserting against this golden would pass **even with a broken or no-op comparator** — the test would
prove nothing.

**Fix (deterministic, self-contained, no RNG).** The paired test MUST first transform the input into
a **guaranteed-unsorted** order, then apply the Java ascending `Comparator<>`, then assert the output
equals this golden byte-for-byte. The recommended transform is to **reverse** the 300 records
(strictly descending, since the keys are unique), because reversal is fully deterministic and needs
no extra fixture.

```java
// Load 350-byte records from the input fixture
List<byte[]> input = readFixedRecords("/fixtures/ascii/dailytran.txt", 350);

// Make the input GUARANTEED unsorted so the assertion is meaningful
Collections.reverse(input);                 // strictly descending (keys are unique)

// Apply the SAME comparator the batch step uses: ascending on TRAN-ID (bytes 0..15), ASCII order
Comparator<byte[]> byTranIdAsc =
    Comparator.comparing(r -> new String(r, 0, 16, StandardCharsets.US_ASCII));
input.sort(byTranIdAsc);                    // STABLE sort (Java's List.sort is stable)

// Re-emit 350-byte records, LF-separated, trailing newline, then compare bytes to the golden
byte[] actual = joinRecordsWithLf(input);
byte[] golden = readClasspathBytes("/golden/sorted/dailytran.combined-sorted.dat");
assertArrayEquals(golden, actual);
```

A no-op comparator on the reversed input would remain descending and **FAIL** the assertion, so the
test genuinely exercises ordering. Java's `List.sort` / `Collections.sort` are **stable**, which
satisfies the no-`EQUALS` requirement (equal-key records keep their input-relative order).

## Regeneration / fidelity note

This golden was produced as a **verbatim, checksum-verified copy** of the already-sorted `dailytran`
dataset:

```text
cp src/test/resources/fixtures/ascii/dailytran.txt dailytran.combined-sorted.dat
# verify: md5sum -> 473363eedabb74e0a47e543f18cdcc32
```

Byte-fidelity rules that any regeneration must preserve:

- Fixed **350-byte** records; `X(n)` fields are right-padded with spaces and `9(n)` fields are
  left-padded with zeros, so byte offsets are stable across every record.
- **Unix LF** line endings with a single trailing newline; **no CR**, **no BOM**.
- COBOL **sign-overpunch** bytes in the `TRAN-AMT` (`S9(09)V99`) field are preserved **verbatim**:
  positive last digit `0..9` -> `{ A B C D E F G H I`; negative last digit `0..9` ->
  `} J K L M N O P Q R`. The golden is compared as **raw bytes**; if an amount is ever decoded, use
  `java.math.BigDecimal` at scale 2 with `RoundingMode.DOWN` — **never** `float` / `double`.

## License

These test assets are part of AWS CardDemo (modernized) and are licensed under the Apache License,
Version 2.0.
