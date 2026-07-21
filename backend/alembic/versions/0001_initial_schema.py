"""0001 initial schema — CardDemo VSAM→PostgreSQL.

Creates the 10 relational tables ported 1:1 from the COBOL record copybooks
(app/cpy/*.cpy) and VSAM catalog (app/catlg/LISTCAT.txt):
  users<-CSUSR01Y/USRSEC, accounts<-CVACT01Y/ACCTDATA, customers<-CVCUS01Y/CUSTDATA,
  cards<-CVACT02Y/CARDDATA, card_xref<-CVACT03Y/CARDXREF,
  transactions<-CVTRA05Y+CVTRA06Y/TRANSACT, tran_category_balance<-CVTRA01Y/TCATBALF,
  disclosure_group<-CVTRA02Y/DISCGRP, transaction_type<-CVTRA03Y/TRANTYPE,
  transaction_category<-CVTRA04Y/TRANCATG.

Type-mapping rules (AAP 0.7.1 Finding #1, verified against the copybooks):
  * Numeric identifier PIC 9(n) -> VARCHAR(n) (sa.String) to preserve leading
    zeros -- never an integer type. Exception: fico_credit_score is a true small
    integer (sa.SmallInteger).
  * Signed money PIC S9(n)V99 is signed zoned-decimal DISPLAY (no COMP-3 clause
    anywhere) -> NUMERIC(n+2, 2) (sa.Numeric) mapped to Python Decimal. Never
    Float/Double, because binary floating point would break regulatory parity.
  * PIC X(10) date text -> DATE; PIC X(26) timestamp text -> TIMESTAMPTZ
    (sa.DateTime(timezone=True)).
  * Fixed-width codes -> CHAR(n); variable text -> VARCHAR(n). COBOL FILLER
    fields carry no data and are dropped (never mapped to a column).

Constraint and index names are hand-written to EXACTLY match the shared
MetaData naming convention declared in backend/app/db/base.py
(pk_%(table_name)s / fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s
/ ix_%(column_0_label)s), so this migration reproduces what ORM autogenerate
would emit against Base.metadata.

Revision ID: 0001
Revises: (base)
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

# --- Alembic revision identifiers (documented framework names; kept lowercase/
# snake exactly as Alembic requires -- the Ochs PascalCase/camelCase rules do
# NOT apply to these). This is the BASE revision, so down_revision is None. The
# on-disk filename slug (0001_initial_schema) agrees with revision "0001" per
# alembic.ini file_template = %(rev)s_%(slug)s, giving deterministic ordering.
revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create all 10 CardDemo tables, FK-safe (parents before children).

    Tables are created in dependency order so every foreign-key target already
    exists: the three FK-bearing tables (cards, card_xref, transactions) are
    created after their referenced parents (accounts, customers, cards). The
    five secondary indexes are emitted immediately after their owning table.
    """
    # --- Table 1: transaction_type (<- CVTRA03Y / TRANTYPE KSDS, KEYLEN 2 @ RKP 0)
    # Standalone lookup: two-character type code -> description. No FK, no index.
    op.create_table(
        "transaction_type",
        sa.Column("tran_type", sa.CHAR(2), nullable=False),
        sa.Column("tran_type_desc", sa.String(50), nullable=False),
        sa.PrimaryKeyConstraint("tran_type", name="pk_transaction_type"),
    )

    # --- Table 2: transaction_category (<- CVTRA04Y / TRANCATG KSDS, KEYLEN 6 = 2+4)
    # Composite (tran_type_cd, tran_cat_cd) key -> description. tran_cat_cd is
    # PIC 9(04) but stored as VARCHAR(4) to keep significant leading zeros.
    op.create_table(
        "transaction_category",
        sa.Column("tran_type_cd", sa.CHAR(2), nullable=False),
        sa.Column("tran_cat_cd", sa.String(4), nullable=False),
        sa.Column("tran_cat_type_desc", sa.String(50), nullable=False),
        sa.PrimaryKeyConstraint(
            "tran_type_cd", "tran_cat_cd", name="pk_transaction_category"
        ),
    )

    # --- Table 3: disclosure_group (<- CVTRA02Y / DISCGRP KSDS, KEYLEN 16 = 10+2+4)
    # interest_rate is DIS-INT-RATE PIC S9(04)V99 -> NUMERIC(6, 2) per AAP 0.7.2
    # Finding #2 (precision 6,2 NOT 5,2: four integer + two fractional digits).
    # The ORM acct_group_id is a synonym only -> NO column here.
    op.create_table(
        "disclosure_group",
        sa.Column("group_id", sa.String(10), nullable=False),
        sa.Column("tran_type_cd", sa.CHAR(2), nullable=False),
        sa.Column("tran_cat_cd", sa.String(4), nullable=False),
        sa.Column("interest_rate", sa.Numeric(6, 2), nullable=False),
        sa.PrimaryKeyConstraint(
            "group_id", "tran_type_cd", "tran_cat_cd", name="pk_disclosure_group"
        ),
    )

    # --- Table 4: customers (<- CVCUS01Y / CUSTDATA KSDS, KEYLEN 9 @ RKP 0)
    # cust_id/ssn are PIC 9(n) identifiers -> VARCHAR (leading zeros preserved).
    # fico_credit_score is the sole numeric field kept as a true SMALLINT.
    # ssn is SENSITIVE (masked by the schema/response layer, stored in full here).
    op.create_table(
        "customers",
        sa.Column("cust_id", sa.String(9), nullable=False),
        sa.Column("first_name", sa.String(25), nullable=False),
        sa.Column("middle_name", sa.String(25), nullable=True),
        sa.Column("last_name", sa.String(25), nullable=False),
        sa.Column("addr_line_1", sa.String(50), nullable=False),
        sa.Column("addr_line_2", sa.String(50), nullable=True),
        sa.Column("addr_line_3", sa.String(50), nullable=True),
        sa.Column("addr_state_cd", sa.CHAR(2), nullable=True),
        sa.Column("addr_country_cd", sa.CHAR(3), nullable=True),
        sa.Column("addr_zip", sa.String(10), nullable=True),
        sa.Column("phone_num_1", sa.String(15), nullable=True),
        sa.Column("phone_num_2", sa.String(15), nullable=True),
        sa.Column("ssn", sa.String(9), nullable=True),
        sa.Column("govt_issued_id", sa.String(20), nullable=True),
        sa.Column("date_of_birth", sa.Date(), nullable=True),
        sa.Column("eft_account_id", sa.String(10), nullable=True),
        sa.Column("pri_card_holder_ind", sa.CHAR(1), nullable=True),
        sa.Column("fico_credit_score", sa.SmallInteger(), nullable=True),
        sa.PrimaryKeyConstraint("cust_id", name="pk_customers"),
    )

    # --- Table 5: accounts (<- CVACT01Y / ACCTDATA KSDS, KEYLEN 11 @ RKP 0)
    # Five monetary fields PIC S9(10)V99 -> NUMERIC(12, 2) (Decimal, never float).
    # group_id is an indexed logical reference to disclosure_group.group_id with
    # NO hard FK (disclosure_group's PK is composite, so no single-column target).
    op.create_table(
        "accounts",
        sa.Column("acct_id", sa.String(11), nullable=False),
        sa.Column("active_status", sa.CHAR(1), nullable=False),
        sa.Column("curr_bal", sa.Numeric(12, 2), nullable=False),
        sa.Column("credit_limit", sa.Numeric(12, 2), nullable=False),
        sa.Column("cash_credit_limit", sa.Numeric(12, 2), nullable=False),
        sa.Column("open_date", sa.Date(), nullable=True),
        sa.Column("expiration_date", sa.Date(), nullable=True),
        sa.Column("reissue_date", sa.Date(), nullable=True),
        sa.Column("curr_cyc_credit", sa.Numeric(12, 2), nullable=False),
        sa.Column("curr_cyc_debit", sa.Numeric(12, 2), nullable=False),
        sa.Column("addr_zip", sa.String(10), nullable=True),
        sa.Column("group_id", sa.String(10), nullable=True),
        sa.PrimaryKeyConstraint("acct_id", name="pk_accounts"),
    )
    op.create_index("ix_accounts_group_id", "accounts", ["group_id"], unique=False)

    # --- Table 6: tran_category_balance (<- CVTRA01Y / TCATBALF KSDS, KEYLEN 17 = 11+2+4)
    # Per-account, per-category running balance. balance is PIC S9(09)V99 ->
    # NUMERIC(11, 2). No FKs by design (scope discipline; matches the ORM).
    op.create_table(
        "tran_category_balance",
        sa.Column("acct_id", sa.String(11), nullable=False),
        sa.Column("tran_type_cd", sa.CHAR(2), nullable=False),
        sa.Column("tran_cat_cd", sa.String(4), nullable=False),
        sa.Column("balance", sa.Numeric(11, 2), nullable=False),
        sa.PrimaryKeyConstraint(
            "acct_id", "tran_type_cd", "tran_cat_cd", name="pk_tran_category_balance"
        ),
    )

    # --- Table 7: users (<- CSUSR01Y / USRSEC KSDS, KEYLEN 8 @ RKP 0)
    # password_hash replaces the legacy plaintext SEC-USR-PWD PIC X(08): it is a
    # bcrypt/argon2 digest widened to VARCHAR(255) and is NEVER stored/returned
    # in plaintext (AAP 0.1.1, 0.7.7; Ochs no-hardcoding rule). Standalone table.
    op.create_table(
        "users",
        sa.Column("user_id", sa.String(8), nullable=False),
        sa.Column("first_name", sa.String(20), nullable=False),
        sa.Column("last_name", sa.String(20), nullable=False),
        sa.Column("password_hash", sa.String(255), nullable=False),
        sa.Column("user_type", sa.CHAR(1), nullable=False),
        sa.PrimaryKeyConstraint("user_id", name="pk_users"),
    )

    # --- Table 8: cards (<- CVACT02Y / CARDDATA KSDS, KEYLEN 16 @ RKP 0; AIX AXRKP 16)
    # First FK-bearing table: acct_id -> accounts.acct_id. cvv_cd is SENSITIVE
    # (stored only; never serialized in any response). The secondary index on
    # acct_id is the relational form of the CARDDATA alternate index and powers
    # the card-list-by-account screen COCRDLIC (CCLI).
    op.create_table(
        "cards",
        sa.Column("card_num", sa.String(16), nullable=False),
        sa.Column("acct_id", sa.String(11), nullable=False),
        sa.Column("cvv_cd", sa.String(3), nullable=False),
        sa.Column("embossed_name", sa.String(50), nullable=False),
        sa.Column("expiration_date", sa.Date(), nullable=True),
        sa.Column("active_status", sa.CHAR(1), nullable=False),
        sa.PrimaryKeyConstraint("card_num", name="pk_cards"),
        sa.ForeignKeyConstraint(
            ["acct_id"], ["accounts.acct_id"], name="fk_cards_acct_id_accounts"
        ),
    )
    op.create_index("ix_cards_acct_id", "cards", ["acct_id"], unique=False)

    # --- Table 9: card_xref (<- CVACT03Y / CARDXREF KSDS, KEYLEN 16 @ RKP 0; AIX AXRKP 25)
    # Physical cross-reference table. The real column is xref_card_num (the ORM
    # card_num is a synonym only). All three FKs make the VSAM CARDXREF
    # referential integrity explicit. Note the FK name fk_card_xref_xref_card_num_cards
    # legitimately contains a DOUBLE "xref": table=card_xref, column=xref_card_num,
    # referred=cards (fk_<table>_<column_0_name>_<referred_table>).
    op.create_table(
        "card_xref",
        sa.Column("xref_card_num", sa.String(16), nullable=False),
        sa.Column("cust_id", sa.String(9), nullable=False),
        sa.Column("acct_id", sa.String(11), nullable=False),
        sa.PrimaryKeyConstraint("xref_card_num", name="pk_card_xref"),
        sa.ForeignKeyConstraint(
            ["xref_card_num"],
            ["cards.card_num"],
            name="fk_card_xref_xref_card_num_cards",
        ),
        sa.ForeignKeyConstraint(
            ["cust_id"], ["customers.cust_id"], name="fk_card_xref_cust_id_customers"
        ),
        sa.ForeignKeyConstraint(
            ["acct_id"], ["accounts.acct_id"], name="fk_card_xref_acct_id_accounts"
        ),
    )
    op.create_index("ix_card_xref_cust_id", "card_xref", ["cust_id"], unique=False)
    op.create_index("ix_card_xref_acct_id", "card_xref", ["acct_id"], unique=False)

    # --- Table 10: transactions (<- CVTRA05Y posted + CVTRA06Y daily / TRANSACT KSDS, KEYLEN 16 @ RKP 0)
    # tran_amt is PIC S9(09)V99 -> NUMERIC(11, 2). orig_ts/proc_ts are PIC X(26)
    # timestamp text -> TIMESTAMPTZ. card_num -> FK cards.card_num plus the
    # required secondary index for the list-by-card browse COTRN00C (CT00, AAP
    # 0.7.4). status stages daily rows PENDING -> POSTED (AAP 0.7.5), defaulting
    # to 'POSTED' since the table is fundamentally the CVTRA05Y posted ledger.
    # NOTE: the legacy VSAM alternate index was on proc_ts (LISTCAT AXRKP 304),
    # but the authoritative secondary index for this migration is on card_num;
    # deliberately NO index is created on proc_ts.
    op.create_table(
        "transactions",
        sa.Column("tran_id", sa.String(16), nullable=False),
        sa.Column("tran_type_cd", sa.CHAR(2), nullable=False),
        sa.Column("tran_cat_cd", sa.String(4), nullable=False),
        sa.Column("tran_source", sa.String(10), nullable=True),
        sa.Column("tran_desc", sa.String(100), nullable=True),
        sa.Column("tran_amt", sa.Numeric(11, 2), nullable=False),
        sa.Column("merchant_id", sa.String(9), nullable=True),
        sa.Column("merchant_name", sa.String(50), nullable=True),
        sa.Column("merchant_city", sa.String(50), nullable=True),
        sa.Column("merchant_zip", sa.String(10), nullable=True),
        sa.Column("card_num", sa.String(16), nullable=False),
        sa.Column("orig_ts", sa.DateTime(timezone=True), nullable=True),
        sa.Column("proc_ts", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "status",
            sa.String(10),
            nullable=False,
            server_default=sa.text("'POSTED'"),
        ),
        sa.PrimaryKeyConstraint("tran_id", name="pk_transactions"),
        sa.ForeignKeyConstraint(
            ["card_num"], ["cards.card_num"], name="fk_transactions_card_num_cards"
        ),
    )
    op.create_index(
        "ix_transactions_card_num", "transactions", ["card_num"], unique=False
    )


def downgrade() -> None:
    """Drop every object created by :func:`upgrade` in strict reverse order.

    Children are dropped before parents so no foreign-key dependency blocks a
    DROP. The five named secondary indexes are dropped explicitly before their
    owning tables to keep upgrade/downgrade perfectly symmetric (dropping a
    table also drops its own PK/FK constraints).
    """
    op.drop_index("ix_transactions_card_num", table_name="transactions")
    op.drop_table("transactions")
    op.drop_index("ix_card_xref_acct_id", table_name="card_xref")
    op.drop_index("ix_card_xref_cust_id", table_name="card_xref")
    op.drop_table("card_xref")
    op.drop_index("ix_cards_acct_id", table_name="cards")
    op.drop_table("cards")
    op.drop_table("users")
    op.drop_table("tran_category_balance")
    op.drop_index("ix_accounts_group_id", table_name="accounts")
    op.drop_table("accounts")
    op.drop_table("customers")
    op.drop_table("disclosure_group")
    op.drop_table("transaction_category")
    op.drop_table("transaction_type")

