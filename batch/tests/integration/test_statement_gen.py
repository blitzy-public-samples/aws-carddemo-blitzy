# batch/tests/integration/test_statement_gen.py
# =============================================================================
# Integration tests: batch.jobs.statement_gen.GenerateStatements reconciled
# against the legacy statement programs app/cbl/CBSTM03A.CBL (statement driver,
# 1000-MAINLINE loop) + app/cbl/CBSTM03B.CBL (VSAM I/O subroutine) and the job
# app/jcl/CREASTMT.JCL (STEP040 EXEC PGM=CBSTM03A).
#
# Verifies the single authorized behavior-adjacent redesign (AAP 0.7.5): the
# legacy GDG text + HTML statement output becomes CSV + PDF -- one statement per
# card cross-reference -- with the card PAN masked and the CVV/SSN never emitted
# (AAP 0.7.8). The legacy app/ tree is REFERENCE-only and is never modified.
# =============================================================================
"""Golden-master integration tests for the CardDemo statement-generation job.

Each test drives :func:`batch.jobs.statement_gen.GenerateStatements` against a
minimal, foreign-key-valid ORM graph staged in the rolled-back synchronous test
session (the ``db_session`` fixture) and asserts one behavior of the ported
statement job. The suite is deliberately SYNCHRONOUS: it uses only the blocking
SQLAlchemy session exposed by the batch test fixtures and imports no
asynchronous machinery.

Monetary assertions use exact :class:`decimal.Decimal` values only -- never
floating point -- so cent-level parity with the legacy COBOL numeric fields is
preserved (AAP 0.7.1). PDF output is asserted on its actual RENDERED CONTENT
(QA finding M-15): a dependency-free extractor (:func:`ExtractPdfText`) decodes
the ReportLab content streams -- ASCII85 then Flate, then the ``(text) Tj`` /
``[...] TJ`` text-showing operators -- so the PDF's bank header, account id,
transaction rows, and totals are verified to be genuinely present, and the full
PAN, CVV, and full SSN are verified genuinely absent, from the PDF bytes
themselves rather than being inferred from the sibling CSV. The extractor uses
only the Python standard library (``re`` / ``base64`` / ``zlib``) because no
third-party PDF parser is installable in the offline build environment.

Naming follows the Ochs Rule with the documented pytest exception: test
functions are snake_case (pytest discovers them by name), the module-level
helper is PascalCase, local variables are camelCase, and module constants are
ALL_UPPERCASE. Every test carries a comment citing the COBOL program
(``CBSTM03A`` / ``CBSTM03B``) or ``CREASTMT.JCL`` it reconciles against.
"""

import base64
import csv
import os
import re
import zlib
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

from app.models import STATUS_PENDING, STATUS_POSTED
from batch.jobs.statement_gen import GenerateStatements, _BuildStatementFilename

# --------------------------------------------------------------------------- #
# Module constants (Ochs ALL_UPPERCASE). Distinct primary-key values per card
# keep every staged graph free of primary-key collisions: ``card_xref`` carries
# hard foreign keys to ``cards``/``customers``/``accounts``.
# --------------------------------------------------------------------------- #
ACCT_ONE = "00000000001"
ACCT_TWO = "00000000002"
CARD_ONE = "4859452612877065"
CARD_ONE_LAST4 = "7065"
CARD_TWO = "0500024453765740"
CUST_ONE = "000000001"
CUST_TWO = "000000002"
SAMPLE_CVV = "747"                # structurally never stored (C-03); asserted absent
SAMPLE_SSN = "123456789"          # must never appear in full in any statement output
BANK_NAME = "Bank of XYZ"
BANK_ADDRESS_LINE = "410 Terry Ave N"
BANK_CITY_LINE = "Seattle WA 99999"
TRAN_ID_ONE = "0000000000000001"
TRAN_ID_TWO = "0000000000000002"
TRAN_ID_THREE = "0000000000000003"


# --------------------------------------------------------------------------- #
# Statement-graph builder (PascalCase helper; <=4 params -- all inputs bundled
# in a single ``spec`` mapping to honor the Ochs four-parameter maximum).
# --------------------------------------------------------------------------- #
def _BuildStatementCard(recordBuilder, spec):
    """Stage one full statement graph (customer, account, card, xref) + transactions.

    The rows are inserted through the local ``record_builder`` fixture in
    foreign-key-safe order (customer, then account, then card, then the
    cross-reference, then any transactions), because ``card_xref`` carries hard
    foreign keys to ``cards``/``customers``/``accounts`` and each transaction
    carries one to ``cards``. The primary-key values come from ``spec`` so the
    caller controls uniqueness across cards.

    Args:
        recordBuilder: The ``RecordBuilder`` bound to the rolled-back test
            session (the ``record_builder`` fixture).
        spec: A mapping describing the card graph. Required keys: ``acct_id``,
            ``card_num``, ``cust_id``. Optional keys: ``curr_bal`` (the exact
            :class:`~decimal.Decimal` account balance), ``cvv_cd`` (ignored: CVV
            is never persisted (C-03) and is asserted absent from output),
            ``ssn`` (staged only to prove the SSN is never emitted in full), and
            ``tranSpecs`` (a
            list of ``{"tran_id": str, "tran_amt": Decimal}`` mappings, each with
            an optional ``status`` key). Because the statement job includes only
            POSTED transactions (AAP 0.7.5), ``tranSpecs`` default to
            ``STATUS_POSTED`` when no ``status`` is given, so staged transactions
            appear on the statement unless a test deliberately stages a non-posted
            status to assert its exclusion.

    Returns:
        A ``(xref, builtTransactions)`` tuple: the inserted cross-reference row
        and the list of inserted transaction rows (empty when ``spec`` carries no
        ``tranSpecs``).
    """
    customerOverrides = {"cust_id": spec["cust_id"]}
    if "ssn" in spec:
        customerOverrides["ssn"] = spec["ssn"]
    recordBuilder.BuildCustomer(overrides=customerOverrides)
    accountOverrides = {"acct_id": spec["acct_id"]}
    if "curr_bal" in spec:
        accountOverrides["curr_bal"] = spec["curr_bal"]
    recordBuilder.BuildAccount(overrides=accountOverrides)
    # CVV is never persisted (C-03, AAP 0.7.8): cards carry no cvv column, so no
    # per-card CVV override is possible. Any ``spec["cvv_cd"]`` is intentionally
    # ignored here and asserted absent from statement output below.
    recordBuilder.BuildCard(spec["card_num"], spec["acct_id"])
    xref = recordBuilder.BuildXref(spec["card_num"], spec["cust_id"], spec["acct_id"])
    builtTransactions = []
    for tranSpec in spec.get("tranSpecs", []):
        # Default to POSTED so staged transactions are statemented (AAP 0.7.5);
        # a test asserting exclusion passes an explicit non-posted status. Any
        # additional tranSpec keys (for example ``tran_desc``) are forwarded as
        # column overrides so a test can stage attacker-controlled free text.
        tranOverrides = {
            key: value
            for key, value in tranSpec.items()
            if key not in ("tran_id", "tran_amt")
        }
        tranOverrides.setdefault("status", STATUS_POSTED)
        builtTransaction = recordBuilder.BuildPendingTransaction(
            tranSpec["tran_id"],
            spec["card_num"],
            tranSpec["tran_amt"],
            overrides=tranOverrides,
        )
        builtTransactions.append(builtTransaction)
    return xref, builtTransactions


# --------------------------------------------------------------------------- #
# Dependency-free PDF text extractor (QA finding M-15). PascalCase helper.
# --------------------------------------------------------------------------- #
# ReportLab writes each statement line via canvas.drawString(...), which emits a
# ``(text) Tj`` text-showing operator inside a content stream compressed with the
# default filter chain ``[ /ASCII85Decode /FlateDecode ]``. The stream body ends
# with the ASCII85 EOD marker ``~>`` immediately followed by ``endstream`` (no
# separating newline). To assert the PDF's real rendered content WITHOUT a
# third-party PDF library (none is installable offline), the extractor below
# decodes each stream (ASCII85 -> Flate) and pulls the literal strings out of the
# ``Tj`` / ``TJ`` operators. Only the standard library is used.
_PDF_STREAM_PATTERN = re.compile(rb"stream\r?\n(.*?)endstream", re.DOTALL)
_PDF_TJ_PATTERN = re.compile(r"\(((?:[^()\\]|\\.)*)\)\s*Tj")
_PDF_TJ_ARRAY_PATTERN = re.compile(r"\[(.*?)\]\s*TJ", re.DOTALL)
_PDF_ARRAY_LITERAL_PATTERN = re.compile(r"\(((?:[^()\\]|\\.)*)\)")
_ASCII85_EOD_MARKER = b"~>"


def _UnescapePdfLiteral(literal):
    """Undo the PDF string-literal escapes the extractor cares about.

    Args:
        literal: The raw bytes-string body captured between ``(`` and ``)``.

    Returns:
        The literal with escaped parentheses and backslashes restored.
    """
    return literal.replace(r"\(", "(").replace(r"\)", ")").replace(r"\\", "\\")


def ExtractPdfText(pdfBytes):
    """Extract the visible text of a ReportLab-generated PDF using only stdlib.

    Each content stream is located, its ASCII85 + Flate encoding reversed, and
    the string literals shown by the ``Tj`` and ``TJ`` operators concatenated in
    render order. This lets tests assert on the PDF's ACTUAL rendered content
    (QA finding M-15) without any third-party PDF parser.

    Args:
        pdfBytes: The raw bytes of a PDF file produced by the statement job.

    Returns:
        A single newline-joined string of every text run rendered in the PDF.
    """
    renderedRuns = []
    for streamMatch in _PDF_STREAM_PATTERN.finditer(pdfBytes):
        payload = streamMatch.group(1).strip()
        if payload.endswith(_ASCII85_EOD_MARKER):
            payload = payload[: -len(_ASCII85_EOD_MARKER)]
        try:
            ascii85Decoded = base64.a85decode(payload, adobe=False)
        except ValueError:
            # Not an ASCII85 stream (for example a raw font program); skip it.
            continue
        try:
            streamBody = zlib.decompress(ascii85Decoded)
        except zlib.error:
            streamBody = ascii85Decoded
        content = streamBody.decode("latin-1")
        for tjMatch in _PDF_TJ_PATTERN.finditer(content):
            renderedRuns.append(_UnescapePdfLiteral(tjMatch.group(1)))
        for arrayMatch in _PDF_TJ_ARRAY_PATTERN.finditer(content):
            for literalMatch in _PDF_ARRAY_LITERAL_PATTERN.finditer(arrayMatch.group(1)):
                renderedRuns.append(_UnescapePdfLiteral(literalMatch.group(1)))
    return "\n".join(renderedRuns)


# --------------------------------------------------------------------------- #
# Tests (snake_case per the pytest exception to the Ochs Rule).
# --------------------------------------------------------------------------- #
def test_generates_one_statement_per_card_xref(db_session, record_builder, tmp_path):
    # Reconciles CBSTM03A 1000-MAINLINE / CREASTMT.JCL: one statement per card xref.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_TWO,
            "card_num": CARD_TWO,
            "cust_id": CUST_TWO,
            "tranSpecs": [{"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("25.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    assert result.statementsGenerated == 2
    assert len(result.csvPaths) == 2
    assert len(result.pdfPaths) == 2
    for generatedPath in result.csvPaths + result.pdfPaths:
        assert Path(generatedPath).exists()


def test_statement_produces_csv_and_pdf_per_card(db_session, record_builder, tmp_path):
    # Reconciles AAP 0.7.5 redesign of CBSTM03A output: legacy text+HTML -> CSV + PDF.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    assert result.statementsGenerated == 1
    assert len(result.csvPaths) == 1
    assert len(result.pdfPaths) == 1
    assert result.csvPaths[0].endswith(".csv")
    assert result.pdfPaths[0].endswith(".pdf")
    assert Path(result.csvPaths[0]).exists()
    assert Path(result.pdfPaths[0]).exists()


def test_statement_total_is_decimal_sum(db_session, record_builder, tmp_path):
    # Reconciles CBSTM03A WS-TOTAL-AMT: statement total = exact sum of tran amounts.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "tranSpecs": [
                {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")},
                {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("50.50")},
                {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("-20.00")},
            ],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    assert result.totalAmount == Decimal("130.50")
    assert isinstance(result.totalAmount, Decimal)


def test_statement_csv_contains_bank_header_and_account(db_session, record_builder, tmp_path):
    # Reconciles CBSTM03A/CBSTM03B: bank header block + Basic Details account id.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "curr_bal": Decimal("194.00"),
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    csvText = Path(result.csvPaths[0]).read_text()
    assert BANK_NAME in csvText
    assert BANK_ADDRESS_LINE in csvText
    assert BANK_CITY_LINE in csvText
    assert ACCT_ONE in csvText
    assert TRAN_ID_ONE in csvText


def test_statement_masks_card_and_hides_cvv_ssn(db_session, record_builder, tmp_path):
    # Reconciles AAP 0.7.8 + CBSTM03A: mask PAN to last 4; never emit cvv or full ssn.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "ssn": SAMPLE_SSN,
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    csvText = Path(result.csvPaths[0]).read_text()
    assert CARD_ONE not in csvText
    assert CARD_ONE_LAST4 in csvText
    assert SAMPLE_CVV not in csvText
    assert SAMPLE_SSN not in csvText
    csvName = Path(result.csvPaths[0]).name
    assert CARD_ONE not in csvName
    assert CARD_ONE_LAST4 in csvName


def test_statement_filename_uses_acct_and_last4(db_session, record_builder, tmp_path):
    # Reconciles CREASTMT.JCL statement dataset naming: card-scoped, acct + last 4,
    # plus the run generation suffix (QA finding M19 -- GDG (+1), no overwrite).
    # The first run of an empty output directory is generation "0001".
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    assert Path(result.csvPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0001.csv"
    assert Path(result.pdfPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0001.pdf"


def test_statement_generation_increments_on_rerun(db_session, record_builder, tmp_path):
    # QA finding M19: statement runs use GDG-like generation suffixes and must
    # NEVER overwrite a prior run's output. Re-running for the same account/card
    # produces a new, higher generation and keeps BOTH prior files on disk, so a
    # historical statement can never be silently clobbered.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
        },
    )
    firstResult = GenerateStatements(db_session, tmp_path)
    secondResult = GenerateStatements(db_session, tmp_path)

    # Distinct, ascending generations across the two runs.
    assert Path(firstResult.csvPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0001.csv"
    assert Path(secondResult.csvPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0002.csv"
    assert Path(firstResult.pdfPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0001.pdf"
    assert Path(secondResult.pdfPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_0002.pdf"

    # Both generations coexist (no overwrite): 2 CSV + 2 PDF files remain.
    assert len(list(tmp_path.glob(f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_*.csv"))) == 2
    assert len(list(tmp_path.glob(f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}_*.pdf"))) == 2


def test_statement_filename_is_basename_safe_against_path_traversal():
    # QA finding F-5 (defense-in-depth): even if a hostile account id carrying
    # path separators reached statement generation through some other code path
    # (the loader itself now rejects such an id -- see test_loaders.py), the
    # composed statement file name must remain a SINGLE path component so the
    # write can never escape the caller's output directory. _BuildStatementFilename
    # runs the composed name through os.path.basename, so a "../../../" prefix is
    # explicitly discarded rather than the traversal merely failing closed by
    # accident on the incidental "statement_" prefix.
    hostileAcctId = "../../../etc/passwd"
    # `generation` is part of the file name (QA finding M19, GDG (+1)); supply a
    # representative zero-padded run generation so the crafted context matches
    # the StatementContext shape _BuildStatementFilename consumes.
    craftedGeneration = "0001"
    craftedContext = SimpleNamespace(
        account=SimpleNamespace(acct_id=hostileAcctId),
        xref=SimpleNamespace(acct_id=hostileAcctId, xref_card_num=CARD_ONE),
        generation=craftedGeneration,
    )
    for suffix in (".csv", ".pdf"):
        fileName = _BuildStatementFilename(craftedContext, suffix)
        # No directory component survives: the name is its own basename.
        assert "/" not in fileName
        assert os.sep not in fileName
        assert ".." not in Path(fileName).parts
        assert fileName == os.path.basename(fileName)
        # The masked last-4, the run generation, and the suffix are preserved;
        # the full PAN never is.
        assert fileName.endswith(f"_{CARD_ONE_LAST4}_{craftedGeneration}{suffix}")


def test_no_card_xref_yields_no_statements(db_session, tmp_path):
    # Reconciles CBSTM03A: an empty CARDXREF master yields an empty statement run.
    result = GenerateStatements(db_session, tmp_path)
    assert result.statementsGenerated == 0
    assert result.csvPaths == []
    assert result.pdfPaths == []
    assert result.totalAmount == Decimal("0")


def test_statement_excludes_non_posted_transactions(db_session, record_builder, tmp_path):
    # Reconciles CBSTM03A posted-master read + AAP 0.7.5: statements include ONLY
    # POSTED transactions. A PENDING daily row and a validation-REJECTED row must
    # never appear on a customer statement nor inflate Total EXP (QA finding F-1:
    # 1 POSTED +100.00 and 1 PENDING +50.00 must total 100.00, never 150.00).
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "tranSpecs": [
                {"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00"), "status": STATUS_POSTED},
                {"tran_id": TRAN_ID_TWO, "tran_amt": Decimal("50.00"), "status": STATUS_PENDING},
                # A non-posted, non-pending status (the terminal REJECTED status)
                # is likewise excluded: the filter is strictly ``== POSTED``.
                {"tran_id": TRAN_ID_THREE, "tran_amt": Decimal("30.00"), "status": "REJECTED"},
            ],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    # Total EXP reflects the POSTED amount only (100.00), not 100+50+30 = 180.00.
    assert result.totalAmount == Decimal("100.00")
    assert isinstance(result.totalAmount, Decimal)
    csvText = Path(result.csvPaths[0]).read_text()
    assert TRAN_ID_ONE in csvText          # POSTED -> statemented
    assert TRAN_ID_TWO not in csvText      # PENDING -> excluded
    assert TRAN_ID_THREE not in csvText    # REJECTED -> excluded


def test_statement_csv_neutralizes_formula_injection_and_preserves_amount(
    db_session, record_builder, tmp_path
):
    # QA finding F-3 (CWE-1236): a transaction description that begins with a
    # spreadsheet formula trigger (here "=1+2") must be written as inert text
    # (apostrophe-prefixed), while the negative monetary amount "-919.00" (a real
    # seed credit) must be preserved unchanged as an exact Decimal (AAP 0.7.1).
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "tranSpecs": [
                {
                    "tran_id": TRAN_ID_ONE,
                    "tran_amt": Decimal("-919.00"),
                    "tran_desc": "=1+2",
                },
            ],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    csvText = Path(result.csvPaths[0]).read_text()
    allCells = [cell for row in csv.reader(csvText.splitlines()) for cell in row]
    # The formula-leading description is neutralized, not left live.
    assert "'=1+2" in allCells
    assert "=1+2" not in allCells
    # The negative amount is a numeric literal and is preserved byte-for-byte
    # (never neutralized to "'-919.00"), and the statement total stays exact.
    assert "-919.00" in allCells
    assert "'-919.00" not in allCells
    assert result.totalAmount == Decimal("-919.00")


def test_statement_pdf_renders_header_account_and_transactions(
    db_session, record_builder, tmp_path
):
    # QA finding M-15: the PDF is evidenced by its ACTUAL rendered content, not by
    # existence + extension alone. Decode the ReportLab content streams and assert
    # the bank header block (CBSTM03B), the Basic Details account id, the posted
    # transaction id, its exact Decimal amount, and the statement total are all
    # genuinely present in the PDF bytes -- the same facts the CSV test asserts,
    # now proven in the PDF itself.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "curr_bal": Decimal("194.00"),
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    pdfText = ExtractPdfText(Path(result.pdfPaths[0]).read_bytes())
    # The extractor recovered real text (guards against a silently empty PDF).
    assert pdfText.strip() != ""
    assert BANK_NAME in pdfText
    assert BANK_ADDRESS_LINE in pdfText
    assert BANK_CITY_LINE in pdfText
    assert ACCT_ONE in pdfText
    assert TRAN_ID_ONE in pdfText
    assert "100.00" in pdfText          # exact Decimal amount rendered in the PDF
    # The masked PAN (last four only) is what the PDF shows.
    assert CARD_ONE_LAST4 in pdfText


def test_statement_pdf_excludes_full_pan_cvv_and_ssn(
    db_session, record_builder, tmp_path
):
    # QA finding M-15 + AAP 0.7.8: prove the sensitive-data guarantees hold in the
    # PDF binary itself, not merely in the CSV. Stage a customer SSN, decode the
    # rendered PDF text, and assert the full PAN, a representative CVV literal, and
    # the full SSN are all absent -- while the masked last-four remains, confirming
    # a real (non-empty) statement was rendered.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
            "ssn": SAMPLE_SSN,
            "tranSpecs": [{"tran_id": TRAN_ID_ONE, "tran_amt": Decimal("100.00")}],
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    pdfText = ExtractPdfText(Path(result.pdfPaths[0]).read_bytes())
    assert pdfText.strip() != ""       # a real statement was rendered (non-vacuous)
    assert CARD_ONE not in pdfText     # full PAN never rendered
    assert SAMPLE_CVV not in pdfText   # CVV never rendered (structurally absent, C-03)
    assert SAMPLE_SSN not in pdfText   # full SSN never rendered
    assert CARD_ONE_LAST4 in pdfText   # masked last-four IS rendered
