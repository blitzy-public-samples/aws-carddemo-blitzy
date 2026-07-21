# Ported from COBOL copybook CSUSR01Y (SEC-USER-DATA); VSAM USRSEC (KEYLEN=8, RKP=0).
# Auth in COSGN00C; admin CRUD in COUSR00C-03C.
#
# CardDemo modernization (AAP 0.5.1): the 80-byte VSAM user-security record becomes the
# "users" table. The single intentional, mandatory deviation from a byte-for-byte port is
# the password field -- the legacy 8-char PLAINTEXT SEC-USR-PWD is replaced by a
# bcrypt/argon2 password_hash (AAP 0.1.1, 0.7.7; Ochs "no hardcoding secrets" rule).
"""SQLAlchemy 2.0 ORM model for the CardDemo ``users`` security table.

This module ports the COBOL copybook ``CSUSR01Y`` (``SEC-USER-DATA``) -- the
80-byte VSAM ``USRSEC`` record read at sign-on (``COSGN00C``) and maintained by
the admin user-management programs (``COUSR00C``-``COUSR03C``) -- into the
:class:`User` ORM model.

Field mapping (legacy COBOL -> modern column), preserving copybook order::

    SEC-USR-ID     PIC X(08)  -> user_id       VARCHAR(8)   PRIMARY KEY
    SEC-USR-FNAME  PIC X(20)  -> first_name    VARCHAR(20)
    SEC-USR-LNAME  PIC X(20)  -> last_name     VARCHAR(20)
    SEC-USR-PWD    PIC X(08)  -> password_hash VARCHAR(255) (hash, never plaintext)
    SEC-USR-TYPE   PIC X(01)  -> user_type     CHAR(1)      ('A' admin / 'U' regular)
    SEC-USR-FILLER PIC X(23)  -> dropped (record padding; no target column)

The record keys off ``user_id`` exactly as VSAM ``USRSEC`` keyed off
``SEC-USR-ID`` (KEYLEN=8, RKP=0), so ``user_id`` is the sole primary key. The
table is standalone: it carries no foreign keys or relationships, mirroring the
self-contained security file.

Security note:
    ``password_hash`` deliberately widens the legacy 8-character field to 255
    characters to hold a bcrypt/argon2 digest. Hashing and verification live in
    ``app.core.security``; this model only persists the already-hashed value and
    never stores, accepts, or returns a plaintext password.
"""

from sqlalchemy import CHAR, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class User(Base):
    """User-security record (``users`` table), ported from ``CSUSR01Y``.

    Each attribute maps 1:1 to a field of the legacy ``SEC-USER-DATA`` copybook,
    in copybook order. ``user_type`` is the single source of the admin/regular
    role that, in the mainframe design, was propagated through the CICS COMMAREA
    (``COCOM01Y``); it now drives the ``require_admin`` dependency server-side.
    """

    __tablename__ = "users"

    # SEC-USR-ID PIC X(08): 8-char user id; VSAM USRSEC key (KEYLEN=8, RKP=0) -> sole PK.
    user_id: Mapped[str] = mapped_column(String(8), primary_key=True)

    # SEC-USR-FNAME PIC X(20): user first name.
    first_name: Mapped[str] = mapped_column(String(20))

    # SEC-USR-LNAME PIC X(20): user last name.
    last_name: Mapped[str] = mapped_column(String(20))

    # SECURITY: bcrypt/argon2 hash of the password; legacy SEC-USR-PWD X(08) plaintext is NEVER stored or returned.
    password_hash: Mapped[str] = mapped_column(String(255))

    # SEC-USR-TYPE PIC X(01): role flag -- 'A' = administrator, 'U' = regular user.
    user_type: Mapped[str] = mapped_column(CHAR(1))

    # SEC-USR-FILLER PIC X(23): dropped -- legacy record padding to 80 bytes, no target column.


__all__ = ["User"]
