# Ported from legacy COBOL batch program CBCUS01C.cbl (CardDemo). Function: read
# and print the customer master file.
#
# Legacy source (REFERENCE only, never modified):
#   - app/cbl/CBCUS01C.cbl   BATCH COBOL program. Opens the CUSTFILE VSAM KSDS in
#                            SEQUENTIAL access mode, loops reading each
#                            CUSTOMER-RECORD, DISPLAYs it, then closes the file.
#   - app/cpy/CVCUS01Y.cpy   01 CUSTOMER-RECORD copybook backing that KSDS.
#
# The legacy PROCEDURE DIVISION does, in order: DISPLAY the start banner; OPEN
# INPUT the CUSTFILE (ORGANIZATION INDEXED, ACCESS SEQUENTIAL, RECORD KEY
# FD-CUST-ID); PERFORM UNTIL end-of-file reading each record and DISPLAYing it;
# CLOSE the file; DISPLAY the end banner; GOBACK. A VSAM KSDS read in SEQUENTIAL
# access mode returns records in ascending primary-key order, so this port
# iterates the modern ``customers`` table ordered by ``cust_id`` to reproduce the
# exact record sequence. The legacy Z-DISPLAY-IO-STATUS / Z-ABEND-PROGRAM
# file-status handling has no relational analogue (SQLAlchemy raises typed
# exceptions that propagate to the caller) and is intentionally not reproduced,
# consistent with this print job's read-only, diagnostic purpose.
"""Batch job: read and print the customer master file (port of ``CBCUS01C``).

This module reimplements the legacy CardDemo batch COBOL program ``CBCUS01C``,
whose sole function is to sequentially read the customer master file and print
each record. In the modern stack the customer master formerly stored in the VSAM
``CUSTDATA`` KSDS is the PostgreSQL ``customers`` table mapped by
:class:`app.models.customer.Customer`, so this job streams that table in
primary-key order and logs each record's identifying fields.

Transaction ownership:
    :func:`PrintCustomers` receives an already-open, caller-owned
    :class:`~sqlalchemy.orm.Session`. It is strictly read-only: it never calls
    ``commit``, ``rollback`` or ``close``, and it never issues any DDL. The batch
    orchestrator or CLI that opened the session (see
    :func:`batch.db.GetSyncSession`) owns the transaction boundary, mirroring the
    per-step commit semantics of the legacy JCL chain.

Sensitive data (AAP 0.7.8):
    The customer Social Security Number is sensitive. It is always masked to its
    last four digits (``***-**-1234``) before being emitted; the full SSN is
    never logged. All other fields are printed as stored, for diagnostic parity
    with the legacy DISPLAY output.

Output:
    Records are emitted through the standard :mod:`logging` framework at INFO
    level (one labeled line per field, followed by a dashed separator), replacing
    the COBOL ``DISPLAY`` writes to SYSOUT. The hosting CLI/orchestrator owns
    logging handler and formatter configuration; this module only obtains a
    module-level logger and never configures logging at import time.
"""

from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.customer import Customer


# Module-level logger. The COBOL program wrote records to SYSOUT via DISPLAY; the
# modern port routes the same output through the logging framework so the hosting
# CLI/orchestrator controls formatting and destinations.
LOGGER = logging.getLogger(__name__)

# Number of ORM rows fetched per batch while streaming the result set. Using
# ``yield_per`` keeps memory flat over an arbitrarily large customer master,
# reproducing the constant-memory footprint of the legacy sequential VSAM read.
YIELD_BATCH_SIZE = 500

# Column width to which each COBOL field label is left-justified so the trailing
# colon aligns across every line. It equals the length of the longest label,
# ``"CUST-FICO-CREDIT-SCORE"`` (22 characters), plus two padding spaces.
LABEL_WIDTH = 24

# Dashed line printed after each customer record, delimiting one record's block
# of labeled lines from the next.
RECORD_SEPARATOR = "-" * 49

# SSN masking format (AAP 0.7.8): only the final four digits are ever revealed,
# rendered as ``"***-**-1234"``. These are display constants, not secrets.
SSN_MASK_PREFIX = "***-**-"
SSN_VISIBLE_DIGITS = 4


def _MaskSsn(ssn: str) -> str:
    """Mask a Social Security Number, revealing only its last four digits.

    Returns ``***-**-1234`` for a populated SSN, or an empty string when the
    customer has none on file. The full SSN is never returned (AAP 0.7.8).
    """
    if not ssn:
        return ""
    lastFour = ssn[-SSN_VISIBLE_DIGITS:]
    return f"{SSN_MASK_PREFIX}{lastFour}"


def _PrintCustomerRecord(customer: Customer) -> None:
    """Emit one customer record as aligned, labeled log lines (SSN masked).

    Reproduces the legacy ``DISPLAY CUSTOMER-RECORD`` output for a single record:
    one ``LABEL : value`` line per meaningful field, then a dashed separator. The
    SSN is masked via :func:`_MaskSsn`; every other field is printed as stored.
    """
    labeledFields = [
        ("CUST-ID", customer.cust_id),
        ("CUST-FIRST-NAME", customer.first_name),
        ("CUST-MIDDLE-NAME", customer.middle_name),
        ("CUST-LAST-NAME", customer.last_name),
        ("CUST-ADDR-LINE-1", customer.addr_line_1),
        ("CUST-ADDR-LINE-2", customer.addr_line_2),
        ("CUST-ADDR-STATE-CD", customer.addr_state_cd),
        ("CUST-ADDR-ZIP", customer.addr_zip),
        ("CUST-PHONE-NUM-1", customer.phone_num_1),
        ("CUST-SSN", _MaskSsn(customer.ssn)),
        ("CUST-FICO-CREDIT-SCORE", customer.fico_credit_score),
    ]
    for fieldLabel, fieldValue in labeledFields:
        LOGGER.info("%-*s: %s", LABEL_WIDTH, fieldLabel, fieldValue)
    LOGGER.info(RECORD_SEPARATOR)


def PrintCustomers(session: Session) -> int:
    """Read and print every customer master record in primary-key order.

    Modern port of the legacy ``CBCUS01C`` batch program. Streams the
    ``customers`` table ordered by ``cust_id`` (the ascending order a VSAM KSDS
    SEQUENTIAL read produced), logging each record's identifying fields with the
    SSN masked, and returns the number of records printed.

    The supplied session is used strictly read-only: this function never commits,
    rolls back, or closes it, and it issues no DDL. The caller owns the
    transaction boundary.

    Args:
        session: An already-open, caller-owned synchronous SQLAlchemy session.

    Returns:
        The number of customer records read and printed.
    """
    LOGGER.info("START OF EXECUTION OF PROGRAM CBCUS01C")
    customerQuery = select(Customer).order_by(Customer.cust_id)
    recordCount = 0
    for customer in session.scalars(customerQuery).yield_per(YIELD_BATCH_SIZE):
        _PrintCustomerRecord(customer)
        recordCount += 1
    LOGGER.info("END OF EXECUTION OF PROGRAM CBCUS01C")
    return recordCount
