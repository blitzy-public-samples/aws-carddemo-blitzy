#!/usr/bin/env python3
# CardDemo REST/JSON API - deterministic boundary/fault fixture generator.
#
# Emits byte-exact ASCII VSAM record images that let a clean environment
# deterministically PREPARE every documented boundary and fault state for the
# read-only API layer, closing the gap where those states previously needed
# tribal knowledge (QA finding MAJ-08). The generated records mirror the exact
# fixed-width layouts of the shipped app/data/ASCII fixtures:
#
#   CARDDAT  (CVACT02Y CARD-RECORD)      150 bytes/record
#   CCXREF   (CVACT03Y CARD-XREF-RECORD)  36 bytes/record (significant prefix;
#            the 14-byte trailing FILLER is not written, matching cardxref.txt)
#   TRANSACT (CVTRA05Y TRAN-RECORD)      350 bytes/record
#
# Every record is a single fixed-width line terminated by one Unix line feed;
# the line feed is a transfer delimiter (as the onboarding load procedure
# documents) and is NOT part of the record. The generator is fully
# deterministic - no timestamps, no randomness - so the SHA-256 digests it
# writes to MANIFEST.txt are stable and can be re-verified with --verify.
#
# It uses only the Python standard library, performs no network access, and
# never installs packages.
#
# Boundary/fault scenarios produced (see also the onboarding "Region-only
# boundary matrix"):
#
#   card-boundary  account with exactly 50 cross-referenced cards
#                  -> COTRSVCC card table (WS-MAX-CARDS=50) exactly full;
#                     GET /accounts/{acctId}/transactions proceeds (no 500).
#   card-overflow  account with exactly 51 cross-referenced cards
#                  -> COTRSVCC 3120-APPEND-CARD overflow -> deterministic 500.
#   tran-boundary  account (single card) with exactly 50 matching transactions
#                  -> 50 entries, truncated=false, HTTP 200.
#   tran-truncate  account (single card) with N (default 51) matching
#                  transactions -> first 50 entries, truncated=true, HTTP 200.
#                  Use --count to stress truncation far past the cap (e.g. 500
#                  or 501); the outcome is identical (first 50, truncated=true)
#                  because the WS-MAX-SCAN guard (1,000,000) is never reached.
#
# Licensed under the Apache License, Version 2.0:
#   http://www.apache.org/licenses/LICENSE-2.0
"""Deterministically generate CardDemo API boundary/fault test fixtures.

Usage::

    # write the four canonical scenarios + MANIFEST.txt into ./fixtures
    python3 app/test/api/gen-boundary-fixtures.py --out-dir app/test/api/fixtures

    # regenerate in a temp dir and check every file against the committed
    # MANIFEST.txt (record counts + SHA-256); exit 1 on any mismatch
    python3 app/test/api/gen-boundary-fixtures.py --verify \\
        --manifest app/test/api/fixtures/MANIFEST.txt

    # generate a large truncation-stress transaction set (e.g. 501 matches)
    python3 app/test/api/gen-boundary-fixtures.py --scenario tran-truncate \\
        --count 501 --out-dir /tmp/stress

Exit codes: 0 success (or --verify all matched); 1 a --verify mismatch;
2 a usage / IO error.
"""

import argparse
import hashlib
import os
import sys
import tempfile


# --- Fixed-width field widths (bytes), straight from the copybooks ----------
CARDDAT_LEN = 150   # CVACT02Y CARD-RECORD
CCXREF_LEN = 36     # CVACT03Y significant prefix (16 + 9 + 11)
TRANSACT_LEN = 350  # CVTRA05Y TRAN-RECORD

# --- Deterministic identifier bases (chosen to NOT collide with the shipped
# app/data/ASCII fixtures, whose accounts are 1-50 plus 99 and 888) ----------
ACCT_CARD_BOUNDARY = "00000000101"   # 50-card account
ACCT_CARD_OVERFLOW = "00000000102"   # 51-card account
ACCT_TRAN = "00000000103"            # single-card account for the tx matrix
TRAN_CARD = "9900000000000901"       # the one card cross-referenced to ACCT_TRAN


def card_number(seq):
    """A deterministic, unique 16-digit card number for boundary use."""
    return "9900" + "%012d" % seq


def rec_card(card_num, acct_id, cust_id):
    """Build one 150-byte CARDDAT (CVACT02Y) record image.

    CARD-NUM X(16) | CARD-ACCT-ID 9(11) | CARD-CVV-CD 9(03) |
    CARD-EMBOSSED-NAME X(50) | CARD-EXPIRAION-DATE X(10) |
    CARD-ACTIVE-STATUS X(01) | FILLER X(59).
    """
    name = ("BOUNDARY TEST CARD %d" % cust_id).ljust(50)[:50]
    rec = (card_num
           + acct_id.rjust(11, "0")
           + "000"                      # CVV is test-only; never serialized
           + name
           + "2030-12-31"
           + "Y"
           + " " * 59)
    assert len(rec) == CARDDAT_LEN, len(rec)
    return rec


def rec_xref(card_num, cust_id, acct_id):
    """Build one 36-byte CCXREF (CVACT03Y) record image.

    XREF-CARD-NUM X(16) | XREF-CUST-ID 9(09) | XREF-ACCT-ID 9(11).
    """
    rec = card_num + ("%09d" % cust_id) + acct_id.rjust(11, "0")
    assert len(rec) == CCXREF_LEN, len(rec)
    return rec


def rec_tran(seq, card_num):
    """Build one 350-byte TRANSACT (CVTRA05Y) record image whose
    TRAN-CARD-NUM equals ``card_num`` so COTRSVCC counts it as a match.

    TRAN-ID X(16) | TRAN-TYPE-CD X(02) | TRAN-CAT-CD 9(04) |
    TRAN-SOURCE X(10) | TRAN-DESC X(100) | TRAN-AMT S9(09)V99 |
    TRAN-MERCHANT-ID 9(09) | TRAN-MERCHANT-NAME X(50) |
    TRAN-MERCHANT-CITY X(50) | TRAN-MERCHANT-ZIP X(10) |
    TRAN-CARD-NUM X(16) | TRAN-ORIG-TS X(26) | TRAN-PROC-TS X(26) |
    FILLER X(20).
    """
    tran_id = ("90000000%08d" % seq)          # 16 digits
    # TRAN-AMT S9(09)V99 = 11 zoned digits; the last digit carries the sign
    # overpunch. '{' == +0, so "0000001000{" is +100.00 (a valid positive).
    amt = "0000001000{"
    # A distinct processed timestamp per record keeps the browse order stable.
    proc_ts = "2026-01-01 00:00:%02d.000000" % (seq % 60)
    rec = (tran_id
           + "01"                       # TRAN-TYPE-CD
           + "0001"                     # TRAN-CAT-CD
           + "POS TERM  "               # TRAN-SOURCE (10)
           + "Boundary test purchase".ljust(100)[:100]
           + amt
           + "000000001"               # TRAN-MERCHANT-ID
           + "BOUNDARY MERCHANT".ljust(50)[:50]
           + "TESTCITY".ljust(50)[:50]
           + "00000".ljust(10)[:10]
           + card_num
           + "2026-01-01 00:00:00.000000"   # TRAN-ORIG-TS (26)
           + proc_ts
           + " " * 20)
    assert len(rec) == TRANSACT_LEN, len(rec)
    return rec


def build_scenarios(tran_count):
    """Return an ordered mapping {filename: [record, ...]} for every scenario.

    ``tran_count`` sets the size of the tran-truncate transaction set (default
    51; pass a larger value to stress truncation past the 50-entry cap).
    """
    files = {}

    # --- card-boundary: 50 cards for one account ---------------------------
    cb_cards, cb_xref = [], []
    for i in range(1, 51):
        cn = card_number(1000 + i)
        cb_cards.append(rec_card(cn, ACCT_CARD_BOUNDARY, 100 + i))
        cb_xref.append(rec_xref(cn, 100 + i, ACCT_CARD_BOUNDARY))
    files["card-boundary-50.carddat.txt"] = cb_cards
    files["card-boundary-50.ccxref.txt"] = cb_xref

    # --- card-overflow: 51 cards for one account ---------------------------
    co_cards, co_xref = [], []
    for i in range(1, 52):
        cn = card_number(2000 + i)
        co_cards.append(rec_card(cn, ACCT_CARD_OVERFLOW, 200 + i))
        co_xref.append(rec_xref(cn, 200 + i, ACCT_CARD_OVERFLOW))
    files["card-overflow-51.carddat.txt"] = co_cards
    files["card-overflow-51.ccxref.txt"] = co_xref

    # --- tran-boundary: 1 card, exactly 50 matching transactions -----------
    tb_xref = [rec_xref(TRAN_CARD, 301, ACCT_TRAN)]
    tb_card = [rec_card(TRAN_CARD, ACCT_TRAN, 301)]
    tb_tran = [rec_tran(i, TRAN_CARD) for i in range(1, 51)]
    files["tran-boundary-50.ccxref.txt"] = tb_xref
    files["tran-boundary-50.carddat.txt"] = tb_card
    files["tran-boundary-50.transact.txt"] = tb_tran

    # --- tran-truncate: 1 card, tran_count matching transactions -----------
    tt_xref = [rec_xref(TRAN_CARD, 301, ACCT_TRAN)]
    tt_card = [rec_card(TRAN_CARD, ACCT_TRAN, 301)]
    tt_tran = [rec_tran(1000 + i, TRAN_CARD) for i in range(1, tran_count + 1)]
    files["tran-truncate-%d.ccxref.txt" % tran_count] = tt_xref
    files["tran-truncate-%d.carddat.txt" % tran_count] = tt_card
    files["tran-truncate-%d.transact.txt" % tran_count] = tt_tran

    return files


# Which scenario each filename belongs to, and the documented API outcome, used
# to build the MANIFEST and to let --scenario emit a single scenario.
SCENARIO_OF = {
    "card-boundary": ("card-boundary-50.",
                      "GET /accounts/00000000101/transactions: card table "
                      "exactly full (50); proceeds, no 500."),
    "card-overflow": ("card-overflow-51.",
                      "GET /accounts/00000000102/transactions: 51 cards "
                      "overflow WS-MAX-CARDS -> deterministic HTTP 500."),
    "tran-boundary": ("tran-boundary-50.",
                      "GET /accounts/00000000103/transactions: exactly 50 "
                      "matches -> 50 entries, truncated=false, HTTP 200."),
    "tran-truncate": ("tran-truncate-",
                      "GET /accounts/00000000103/transactions: matches beyond "
                      "the 50 cap -> first 50 entries, truncated=true, "
                      "HTTP 200 (never 500)."),
}

RECLEN_OF = {
    "carddat": CARDDAT_LEN,
    "ccxref": CCXREF_LEN,
    "transact": TRANSACT_LEN,
}


def reclen_for(filename):
    for key, length in RECLEN_OF.items():
        if filename.endswith(key + ".txt"):
            return length
    return None


def render(records):
    """Join fixed-width records into the on-disk text (one LF per record)."""
    return "".join(rec + "\n" for rec in records)


def write_fixtures(out_dir, files, tran_count):
    """Write every fixture file plus a MANIFEST.txt and return the manifest
    text. Each record's width is asserted so a malformed layout fails loudly.
    """
    os.makedirs(out_dir, exist_ok=True)
    manifest_lines = [
        "# CardDemo API boundary/fault fixtures - deterministic manifest.",
        "# Regenerate + verify: python3 app/test/api/gen-boundary-fixtures.py "
        "--verify --manifest app/test/api/fixtures/MANIFEST.txt",
        "# Each fixture is a fixed-width ASCII VSAM record image (one LF per "
        "record; the LF is a transfer delimiter, not part of the record).",
        "# Columns: filename  records  reclen  bytes  sha256",
        "#",
    ]
    # Group scenario docs first for human readers.
    manifest_lines.append("# Scenario outcomes:")
    for _key, (_prefix, outcome) in SCENARIO_OF.items():
        manifest_lines.append("#   " + outcome)
    manifest_lines.append("#")

    for filename, records in files.items():
        reclen = reclen_for(filename)
        for rec in records:
            if reclen is not None and len(rec) != reclen:
                raise ValueError("record width %d != %d in %s"
                                 % (len(rec), reclen, filename))
        text = render(records)
        path = os.path.join(out_dir, filename)
        with open(path, "w", encoding="ascii", newline="") as handle:
            handle.write(text)
        digest = hashlib.sha256(text.encode("ascii")).hexdigest()
        manifest_lines.append("%-32s %6d %6s %8d %s"
                              % (filename, len(records),
                                 str(reclen) if reclen else "?",
                                 len(text.encode("ascii")), digest))

    manifest_text = "\n".join(manifest_lines) + "\n"
    with open(os.path.join(out_dir, "MANIFEST.txt"), "w",
              encoding="ascii", newline="") as handle:
        handle.write(manifest_text)
    return manifest_text


def parse_manifest(path):
    """Parse a MANIFEST.txt into {filename: (records, bytes, sha256)}."""
    entries = {}
    with open(path, "r", encoding="ascii") as handle:
        for line in handle:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.split()
            if len(parts) != 5:
                continue
            name, records, _reclen, nbytes, digest = parts
            entries[name] = (int(records), int(nbytes), digest)
    return entries


def verify(manifest_path, tran_count):
    """Regenerate every scenario in memory and compare record counts, byte
    sizes, and SHA-256 digests against ``manifest_path``. Returns 0 on a full
    match, 1 otherwise.
    """
    expected = parse_manifest(manifest_path)
    if not expected:
        print("FAIL: manifest %s has no fixture rows" % manifest_path)
        return 1
    files = build_scenarios(tran_count)
    ok = True
    for filename, records in files.items():
        text = render(records)
        digest = hashlib.sha256(text.encode("ascii")).hexdigest()
        nbytes = len(text.encode("ascii"))
        exp = expected.get(filename)
        if exp is None:
            print("FAIL: %s missing from manifest" % filename)
            ok = False
            continue
        exp_records, exp_bytes, exp_digest = exp
        if (exp_records, exp_bytes, exp_digest) == (len(records), nbytes,
                                                    digest):
            print("PASS: %s (%d records, %d bytes, sha256 match)"
                  % (filename, len(records), nbytes))
        else:
            print("FAIL: %s records=%d/%d bytes=%d/%d sha256=%s/%s"
                  % (filename, len(records), exp_records, nbytes, exp_bytes,
                     digest, exp_digest))
            ok = False
    # Every manifest row must correspond to a generated file.
    for name in expected:
        if name not in files:
            print("FAIL: manifest lists %s but the generator did not produce "
                  "it" % name)
            ok = False
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(
        description="Deterministically generate CardDemo API boundary/fault "
                    "fixtures.")
    ap.add_argument("--out-dir", default="app/test/api/fixtures",
                    help="directory to write fixtures + MANIFEST.txt into")
    ap.add_argument("--scenario",
                    choices=["all"] + list(SCENARIO_OF),
                    default="all",
                    help="emit a single scenario instead of all four")
    ap.add_argument("--count", type=int, default=51,
                    help="matching-transaction count for tran-truncate "
                         "(default 51; use 501 to stress past the cap)")
    ap.add_argument("--verify", action="store_true",
                    help="regenerate and check against --manifest; write "
                         "nothing")
    ap.add_argument("--manifest", default="app/test/api/fixtures/MANIFEST.txt",
                    help="manifest to check against with --verify")
    args = ap.parse_args()

    if args.count < 1:
        print("ERROR: --count must be >= 1")
        return 2

    if args.verify:
        return verify(args.manifest, args.count)

    files = build_scenarios(args.count)
    if args.scenario != "all":
        prefix = SCENARIO_OF[args.scenario][0]
        files = {n: r for n, r in files.items() if n.startswith(prefix)}
        if not files:
            print("ERROR: no files for scenario %s" % args.scenario)
            return 2

    try:
        write_fixtures(args.out_dir, files, args.count)
    except (OSError, ValueError) as exc:
        print("ERROR: %s" % exc)
        return 2

    print("wrote %d fixture file(s) + MANIFEST.txt into %s"
          % (len(files), args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
