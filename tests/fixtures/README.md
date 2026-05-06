<!--
Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License"); you may
not use this file except in compliance with the License.  You may
obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
-->

# CardDemo Test Fixtures — Design Notes

This directory holds the byte-extraction tooling and the generated
COBOL snippet fixtures used by the cobol-check testsuites under
`tests/cobol-check/`.

| Path                                    | Purpose                                                                |
| --------------------------------------- | ---------------------------------------------------------------------- |
| `load_fixture.py`                       | Pure byte-extraction helper. No domain logic.                          |
| `cobol-snippets/<NAME>.cpy`             | Generated COBOL `MOVE X'..' TO ...` snippets (10 fixtures).            |

The `.cpy` files are committed so reviewers can audit fixture content
without running `make fixtures`, but they are reproducible at any time
from `app/data/ASCII/*.txt` plus the `SNIPPETS` table in
`load_fixture.py`.

## Regeneration

```sh
make fixtures           # regenerates all 10 .cpy files
```

`make fixtures` is idempotent: the SHA-256 banner inside each `.cpy`
file is the cryptographic attestation that the byte payload matches the
original record range from `app/data/ASCII/`.  Three back-to-back
invocations produce md5-identical files (verified by QA at CP6).

## Adding a fixture

1. Edit `SNIPPETS` in `load_fixture.py` to add a row of the form
   `(snippet_name, source_file, start_record, count, record_length,
    cobol_field_name, separator_size)`.
2. Run `make fixtures`.
3. Reference the new snippet from a `.cut` testsuite via
   `COPY <SNIPPET-NAME>` inside a `MOCK FILE … ON READ` block.

## Trailing period anchoring

`write_snippet()` in `load_fixture.py` deliberately appends a single
period (`.`) to the **final non-comment line** inside each generated
`.cpy` file.  This subsection explains why.

When a host paragraph contains:

```cobol
MOVE '00' TO WS-FIELD
COPY ACCT-FIXTURE-001
.
```

the standalone period on the line *following* the `COPY` directive is
consumed by the COBOL preprocessor as the `COPY` statement's syntactic
terminator, **not** as the host paragraph's sentence terminator.  After
expansion the merged source effectively becomes:

```cobol
MOVE '00' TO WS-FIELD
<expanded MOVE pairs from .cpy>     <-- no trailing period
```

leaving the host paragraph's last sentence unterminated.  The next
paragraph header (e.g. `UT-1-3-1-MOCK.`) is then folded into that
unterminated sentence as a phantom identifier reference, and GnuCOBOL
emits `'UT-1-3-1-MOCK' is not defined`.

Anchoring the period **inside** the `.cpy` file on the final
`TO <field>(start:len)` line prevents this: after `COPY` expansion the
sentence ends inside the snippet itself, so the standalone period that
follows the `COPY` directive in the host paragraph becomes a no-op (an
empty sentence) and the following paragraph header is recognised as a
new paragraph rather than as a phantom identifier inside the previous
one.

The implementation walks `move_lines` from the end and attaches `"."`
to the first non-comment line it finds.  This guarantees that fixtures
with comment lines interleaved between record blocks (e.g.
`DISCGRP-FIXTURE-A` with 17 records) still produce a single,
correctly-placed terminator.

## Stability guarantees

* The `SNIPPETS` table is the single source of truth for which records
  end up in which fixture.  Changing it is a breaking change to every
  testsuite that references the affected snippet.
* The 16-character SHA-256 prefix stamped into each `.cpy` banner is
  computed over the concatenated record bytes.  Any drift between the
  source `.txt` files and the regenerated `.cpy` files is detectable by
  re-running `make fixtures` and diffing the banner.
* The script uses only Python 3 stdlib modules (`argparse`, `hashlib`,
  `pathlib`).  No third-party dependencies.

## Rationale for the size cap

Per AAP Section 0.5.2 the `load_fixture.py` helper is mandated to be
≤80 lines and to contain **zero** domain logic.  Operational
documentation that previously lived inside the script — most notably
the trailing-period rationale above — has been moved into this README
so the script remains the canonical pure-byte-extraction utility while
the design notes remain co-located with the directory they document.
