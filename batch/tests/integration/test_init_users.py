# Ported from the legacy user-security seed. The IEBGENER/IDCAMS job
# app/jcl/DUSRSECJ.jcl defines AWS.M2.CARDDEMO.USRSEC.PS from a 10-user in-stream
# seed; the record layout is app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, RECLN 80); the
# EBCDIC source is app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS. These integration
# tests reconcile the Python port batch.loaders.init_users.InitializeUsers against
# that seed and assert the mandatory security uplift (AAP 0.7.7 / Ochs "no
# hardcoding secrets" rule): the legacy plaintext SEC-USR-PWD PIC X(08) is
# bcrypt-hashed into password_hash and is never persisted as cleartext.
"""DB-backed integration tests for the CardDemo user-security seed loader.

These tests reconcile :func:`batch.loaders.init_users.InitializeUsers` -- the
Python replacement for the mainframe ``DUSRSECJ`` job -- against the legacy
EBCDIC user-security dataset ``AWS.M2.CARDDEMO.USRSEC.PS``. That dataset is 800
bytes: exactly ten 80-byte ``CSUSR01Y`` ``SEC-USER-DATA`` records (five
administrators and five regular users). The suite proves three things:

1.  **Reconciliation** -- the loader decodes exactly ten users and persists them,
    split 5 admin / 5 regular by ``SEC-USR-TYPE``, with the copybook fields
    (id, first name, last name, type) decoded correctly from EBCDIC ``cp037``.
2.  **Security uplift** -- the ``users`` table has no plaintext password column,
    no stored ``password_hash`` equals or contains the seed plaintext, and every
    hash is a bcrypt digest that round-trips through
    :func:`app.core.security.VerifyPassword` (AAP 0.7.7).
3.  **Idempotency** -- re-running the loader upserts in place (``ON CONFLICT``),
    leaving the row count unchanged and the hashes still verifiable.

Synchronous by construction: the batch layer is blocking, so these tests use the
plain synchronous ``db_session`` fixture from ``batch/tests/conftest.py`` and
never import the asynchronous request-layer stack. None of the async database
session module, the async PostgreSQL driver, the async pytest plugin, the async
HTTP client, the standard-library async event-loop module, nor SQLAlchemy's async
ORM extension belong in the batch tests -- that async path is the FastAPI request
layer's, never batch's.

Ochs Rule conventions applied here: module constants are ``ALL_UPPERCASE``; the
non-test helper is ``PascalCase`` (:func:`_CountUsers`); local variables are
``camelCase``. Test function names (and the ``db_session`` / ``usrsec_path``
fixture parameters they consume) are ``snake_case`` -- the documented pytest
framework-contract exception, because pytest discovers ``test_``-prefixed
functions and injects fixtures by matching argument names.
"""

from __future__ import annotations

import pytest  # noqa: F401  (conventional pytest test-module import per the file spec)
from sqlalchemy import func, select

from app.core.security import VerifyPassword
from app.models import User
from batch.loaders.init_users import InitializeUsers

# --------------------------------------------------------------------------- #
# Module constants (ALL_UPPERCASE per the Ochs Rule).
# --------------------------------------------------------------------------- #
# The USRSEC seed is 800 bytes = 10 records x the 80-byte CSUSR01Y layout, split
# evenly into five administrators ('A') and five regular users ('U').
EXPECTED_USER_COUNT = 10
EXPECTED_ADMIN_COUNT = 5
EXPECTED_REGULAR_COUNT = 5

# "PASSWORD" is the KNOWN seed-only plaintext shipped in
# app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS (see the README sample credentials).
# It is used here SOLELY to prove the bcrypt hash round-trips; it is NOT a real
# or production secret and authenticates nothing outside this demo seed.
SEED_PLAINTEXT_PASSWORD = "PASSWORD"

# The five admin ('A') and five regular ('U') user ids seeded by DUSRSECJ, keyed
# on SEC-USR-ID (VSAM USRSEC KEYS(8,0)).
EXPECTED_ADMIN_IDS = ("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005")
EXPECTED_REGULAR_IDS = ("USER0001", "USER0002", "USER0003", "USER0004", "USER0005")

# (user_id, user_type, first_name, last_name) spot-check rows, decoded from
# USRSEC.PS with codec cp037 per the CSUSR01Y 80-byte field layout
# (ID[0:8], FNAME[8:28], LNAME[28:48], TYPE[56:57]).
EXPECTED_SAMPLE_USERS = (
    ("ADMIN001", "A", "MARGARET", "GOLD"),
    ("USER0001", "U", "LAWRENCE", "THOMAS"),
)


# --------------------------------------------------------------------------- #
# Small helper (PascalCase per the Ochs Rule; underscore-prefixed so pytest does
# not treat it as a test).
# --------------------------------------------------------------------------- #
def _CountUsers(session, userType=None) -> int:
    """Count users, optionally filtered by user_type, in the current transaction.

    Args:
        session: The active synchronous SQLAlchemy session under test.
        userType: Optional ``SEC-USR-TYPE`` code ('A' admin / 'U' regular) to
            filter by; when ``None`` every ``users`` row is counted.

    Returns:
        The number of matching ``users`` rows visible in the current transaction.
    """
    statement = select(func.count()).select_from(User)
    if userType is not None:
        statement = statement.where(User.user_type == userType)
    return session.execute(statement).scalar_one()


def test_initialize_users_count(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: the 800-byte EBCDIC seed is exactly ten
    # 80-byte SEC-USER-DATA records, so the loader must report and persist 10 users.
    rowsProcessed = InitializeUsers(db_session, usrsec_path)
    assert rowsProcessed == EXPECTED_USER_COUNT
    assert _CountUsers(db_session) == EXPECTED_USER_COUNT


def test_admin_and_regular_split(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: SEC-USR-TYPE ('A'/'U') splits the seed into
    # five administrators (ADMIN001-005) and five regular users (USER0001-0005).
    InitializeUsers(db_session, usrsec_path)
    assert _CountUsers(db_session, "A") == EXPECTED_ADMIN_COUNT
    assert _CountUsers(db_session, "U") == EXPECTED_REGULAR_COUNT
    for adminId in EXPECTED_ADMIN_IDS:
        adminUser = db_session.get(User, adminId)
        assert adminUser is not None
        assert adminUser.user_type == "A"
    for regularId in EXPECTED_REGULAR_IDS:
        regularUser = db_session.get(User, regularId)
        assert regularUser is not None
        assert regularUser.user_type == "U"


def test_user_fields_decoded(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: prove the cp037 EBCDIC decode and the 80-byte
    # field slicing (ID[0:8], FNAME[8:28], LNAME[28:48], TYPE[56:57]) are exact.
    InitializeUsers(db_session, usrsec_path)
    for userId, userType, firstName, lastName in EXPECTED_SAMPLE_USERS:
        user = db_session.get(User, userId)
        assert user is not None
        assert user.user_type == userType
        assert user.first_name == firstName
        assert user.last_name == lastName


def test_no_plaintext_password_persisted(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: the legacy plaintext SEC-USR-PWD PIC X(08)
    # must become a bcrypt password_hash -- the users table exposes no plaintext
    # password column and no stored hash may equal or contain the seed plaintext
    # (security uplift, AAP 0.7.7).
    columnNames = {column.name for column in User.__table__.columns}
    assert "password_hash" in columnNames
    assert "password" not in columnNames
    InitializeUsers(db_session, usrsec_path)
    allUsers = db_session.execute(select(User)).scalars().all()
    assert len(allUsers) == EXPECTED_USER_COUNT
    for user in allUsers:
        assert user.password_hash != SEED_PLAINTEXT_PASSWORD
        assert SEED_PLAINTEXT_PASSWORD not in user.password_hash
        assert user.password_hash.startswith("$2")
        assert len(user.password_hash) >= 20


def test_verify_password_round_trip(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: the stored bcrypt hash for ADMIN001 must
    # verify against the known seed plaintext and reject a wrong password, exercising
    # the real app.core.security.VerifyPassword (modern COSGN00C sign-on compare).
    InitializeUsers(db_session, usrsec_path)
    admin = db_session.get(User, "ADMIN001")
    assert admin is not None
    assert VerifyPassword(SEED_PLAINTEXT_PASSWORD, admin.password_hash) is True
    assert VerifyPassword("WRONG-PASSWORD", admin.password_hash) is False


def test_initialize_users_idempotent(db_session, usrsec_path):
    # DUSRSECJ / CSUSR01Y / USRSEC.PS: the loader upserts (INSERT ... ON CONFLICT),
    # so re-running the seed is idempotent -- the count stays 10 and the hash for
    # ADMIN001 still verifies after the second run (its bcrypt salt may differ).
    firstCount = InitializeUsers(db_session, usrsec_path)
    assert firstCount == EXPECTED_USER_COUNT
    secondCount = InitializeUsers(db_session, usrsec_path)
    assert secondCount == EXPECTED_USER_COUNT
    assert _CountUsers(db_session) == EXPECTED_USER_COUNT
    admin = db_session.get(User, "ADMIN001")
    assert admin is not None
    assert VerifyPassword(SEED_PLAINTEXT_PASSWORD, admin.password_hash) is True
