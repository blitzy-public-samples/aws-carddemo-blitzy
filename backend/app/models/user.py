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

    ``session_version`` is a second security-infrastructure column with no
    CSUSR01Y source field. AAP 0.1.1 authorizes reconstructing session/identity
    semantics statelessly, and it is the mechanism (QA finding M-02) by which a
    session can be revoked server-side: each minted token embeds the current
    generation, and incrementing the column invalidates every token that carried
    the old one. It is never returned on a response DTO.
"""

from sqlalchemy import CHAR, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base

# Initial session generation assigned to every user (M-02). Stored as an
# ALL_UPPERCASE constant (Ochs Rule) and used as the column's default and
# server_default so seeded/loaded rows (which never supply it) start at
# generation 1. Incremented server-side on logout and on a role/password change
# to revoke any token that embedded an older generation.
INITIAL_SESSION_VERSION = 1


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

    # SECURITY INFRASTRUCTURE (M-02): server-side session generation counter. It
    # has no CSUSR01Y source field -- it is the second mandatory security-uplift
    # column (alongside password_hash) that AAP 0.1.1 authorizes to reconstruct
    # session/identity semantics statelessly. Every minted session/JWT embeds the
    # user's current session_version in its ``sver`` claim; get_current_user
    # rejects a token whose claim no longer matches this value. Incrementing it
    # (logout, or a role/password change) therefore REVOKES every outstanding
    # token for the user. NOT NULL with a server_default of 1 so rows created by
    # the seed loader/migration -- which never supply it -- start at generation 1.
    session_version: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=INITIAL_SESSION_VERSION,
        server_default=str(INITIAL_SESSION_VERSION),
    )

    # SEC-USR-FILLER PIC X(23): dropped -- legacy record padding to 80 bytes, no target column.


__all__ = ["User", "INITIAL_SESSION_VERSION"]
