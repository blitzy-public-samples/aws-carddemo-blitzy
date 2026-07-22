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
preserved (AAP 0.7.1). PDF output is asserted only by existence and by its
``.pdf`` extension; all textual content (bank header, account id, card masking)
is asserted against the human-readable CSV, because the PDF binary is
intentionally not parsed.

Naming follows the Ochs Rule with the documented pytest exception: test
functions are snake_case (pytest discovers them by name), the module-level
helper is PascalCase, local variables are camelCase, and module constants are
ALL_UPPERCASE. Every test carries a comment citing the COBOL program
(``CBSTM03A`` / ``CBSTM03B``) or ``CREASTMT.JCL`` it reconciles against.
"""

from decimal import Decimal
from pathlib import Path

from batch.jobs.statement_gen import GenerateStatements

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
SAMPLE_CVV = "747"                # must never appear in any statement output
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
            :class:`~decimal.Decimal` account balance), ``cvv_cd`` (staged only to
            prove the card verification value is never emitted), ``ssn`` (staged
            only to prove the SSN is never emitted in full), and ``tranSpecs`` (a
            list of ``{"tran_id": str, "tran_amt": Decimal}`` mappings).

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
    cardOverrides = {}
    if "cvv_cd" in spec:
        cardOverrides["cvv_cd"] = spec["cvv_cd"]
    recordBuilder.BuildCard(spec["card_num"], spec["acct_id"], overrides=cardOverrides or None)
    xref = recordBuilder.BuildXref(spec["card_num"], spec["cust_id"], spec["acct_id"])
    builtTransactions = []
    for tranSpec in spec.get("tranSpecs", []):
        builtTransaction = recordBuilder.BuildPendingTransaction(
            tranSpec["tran_id"],
            spec["card_num"],
            tranSpec["tran_amt"],
        )
        builtTransactions.append(builtTransaction)
    return xref, builtTransactions


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
            "cvv_cd": SAMPLE_CVV,
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
    # Reconciles CREASTMT.JCL statement dataset naming: card-scoped, acct + last 4.
    _BuildStatementCard(
        record_builder,
        {
            "acct_id": ACCT_ONE,
            "card_num": CARD_ONE,
            "cust_id": CUST_ONE,
        },
    )
    result = GenerateStatements(db_session, tmp_path)
    assert Path(result.csvPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}.csv"
    assert Path(result.pdfPaths[0]).name == f"statement_{ACCT_ONE}_{CARD_ONE_LAST4}.pdf"


def test_no_card_xref_yields_no_statements(db_session, tmp_path):
    # Reconciles CBSTM03A: an empty CARDXREF master yields an empty statement run.
    result = GenerateStatements(db_session, tmp_path)
    assert result.statementsGenerated == 0
    assert result.csvPaths == []
    assert result.pdfPaths == []
    assert result.totalAmount == Decimal("0")
