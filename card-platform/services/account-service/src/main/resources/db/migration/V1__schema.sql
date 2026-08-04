-- Account service schema, first of three migrations.

-- app/cpy/CVACT01Y.cpy, the 300-byte account record. Key width and record size come from
-- app/jcl/ACCTFILE.jcl:L40 KEYS(11 0) and app/jcl/ACCTFILE.jcl:L41 RECORDSIZE(300 300).
-- FILLER PIC X(178) at app/cpy/CVACT01Y.cpy:L17 maps to no column.
CREATE TABLE account (
    -- ACCT-ID PIC 9(11) is an eleven-character display field, and every record of
    -- app/data/ASCII/acctdata.txt carries its leading zeros: record one holds
    -- 00000000001. A numeric column stores 1 and returns 1, so the value stops matching
    -- the eleven-character key at KEYS(11 0) and stops matching the aggregate_id this
    -- service publishes. Text keeps every character, and ck_account_account_id_digits
    -- holds the width and the digit class the Picture clause declares.
    account_id              CHAR(11)      NOT NULL PRIMARY KEY,
    active_status           CHAR(1)       NOT NULL,
    current_balance         NUMERIC(12,2) NOT NULL,
    credit_limit            NUMERIC(12,2) NOT NULL,
    cash_credit_limit       NUMERIC(12,2) NOT NULL,
    -- open_date, expiration_date and reissue_date hold ten characters as YYYY-MM-DD, with
    -- separators at positions 5 and 8. app/cbl/COACTUPC.cbl:L4127-L4137 slices each of the three
    -- as (1:4), (6:2) and (9:2).
    open_date               VARCHAR(10)   NOT NULL,
    -- Renamed from ACCT-EXPIRAION-DATE at app/cpy/CVACT01Y.cpy:L11.
    expiration_date         VARCHAR(10)   NOT NULL,
    reissue_date            VARCHAR(10)   NOT NULL,
    -- Billing-cycle accumulators. app/cbl/CBTRN02C.cbl:L551 adds a negative transaction amount
    -- to the debit accumulator, and app/cbl/CBACT04C.cbl:L353-L354 zeroes both at cycle close.
    current_cycle_credit    NUMERIC(12,2) NOT NULL,
    current_cycle_debit     NUMERIC(12,2) NOT NULL,
    address_zip             VARCHAR(10)   NOT NULL,
    -- group_id holds ten spaces in all 50 records of app/data/ASCII/acctdata.txt.
    group_id                VARCHAR(10)   NOT NULL,
    CONSTRAINT ck_account_account_id_digits CHECK (account_id ~ '^[0-9]{11}$')
);

-- app/cpy/CVCUS01Y.cpy, the 500-byte customer record. Key width and record size come from
-- app/jcl/CUSTFILE.jcl:L50 KEYS(9 0) and app/jcl/CUSTFILE.jcl:L51 RECORDSIZE(500 500).
-- FILLER PIC X(168) at app/cpy/CVCUS01Y.cpy:L23 maps to no column.
CREATE TABLE customer (
    -- CUST-ID PIC 9(09), nine characters, held as text for the same reason
    -- account.account_id is: record one of app/data/ASCII/custdata.txt holds 000000001.
    customer_id                     CHAR(9)      NOT NULL PRIMARY KEY,
    first_name                      VARCHAR(25)  NOT NULL,
    middle_name                     VARCHAR(25)  NOT NULL,
    last_name                       VARCHAR(25)  NOT NULL,
    address_line_1                  VARCHAR(50)  NOT NULL,
    address_line_2                  VARCHAR(50)  NOT NULL,
    -- CUST-ADDR-LINE-3 holds the city. app/cbl/COACTUPC.cbl:L1615-L1618 labels the field 'City'
    -- and edits it as 50 required alphabetic characters.
    address_city                    VARCHAR(50)  NOT NULL,
    address_state_code              CHAR(2)      NOT NULL,
    address_country_code            CHAR(3)      NOT NULL,
    address_zip                     VARCHAR(10)  NOT NULL,
    phone_number_1                  VARCHAR(15)  NOT NULL,
    phone_number_2                  VARCHAR(15)  NOT NULL,
    -- Neither social_security_number nor government_issued_id leaves this service in an event,
    -- a log line or a response payload.
    -- CUST-SSN PIC 9(09) at app/cpy/CVCUS01Y.cpy:L17 is a nine-character display field, and
    -- offset (280,9) of record one of app/data/ASCII/custdata.txt holds 020973888. A numeric
    -- column stores 20973888 and returns eight digits, which is a different Social Security
    -- Number. Text keeps all nine characters, and the CHECK constraint holds the width.
    social_security_number          CHAR(9)      NOT NULL,
    government_issued_id            VARCHAR(20)  NOT NULL,
    date_of_birth                   VARCHAR(10)  NOT NULL,
    eft_account_id                  VARCHAR(10)  NOT NULL,
    primary_card_holder_indicator   CHAR(1)      NOT NULL,
    -- app/data/ASCII/custdata.txt carries credit scores from 1 to 793, and 21 of its 50 records
    -- sit outside the 300 through 850 range at app/cbl/COACTUPC.cbl:L848-L849.
    -- This column stays numeric: app/cbl/COACTUPC.cbl:L848-L849 compares the field against
    -- 300 and 850 as magnitudes, so it is a number and not an identifier.
    fico_credit_score               NUMERIC(3,0) NOT NULL,
    CONSTRAINT ck_customer_customer_id_digits CHECK (customer_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_customer_ssn_digits CHECK (social_security_number ~ '^[0-9]{9}$')
);

-- app/cpy/CVTRA02Y.cpy, the 50-byte disclosure group record. Key width and record size come from
-- app/jcl/DISCGRP.jcl:L40 KEYS(16 0) and app/jcl/DISCGRP.jcl:L41 RECORDSIZE(50 50).
-- FILLER PIC X(28) at app/cpy/CVTRA02Y.cpy:L10 maps to no column.
CREATE TABLE disclosure_group (
    account_group_id            VARCHAR(10)  NOT NULL,
    transaction_type_code       CHAR(2)      NOT NULL,
    -- DIS-TRAN-CAT-CD PIC 9(04), four characters. The ledger service holds the same source
    -- code as text in transaction_category.category_code, where the 18 rows of
    -- app/data/ASCII/trancatg.txt read 0001 through 0016. A numeric column here would hold 1
    -- for the same code and the two services would no longer compare equal.
    transaction_category_code   CHAR(4)      NOT NULL,
    interest_rate               NUMERIC(6,2) NOT NULL,
    -- The three key columns follow DIS-GROUP-KEY at app/cpy/CVTRA02Y.cpy:L5-L8 in order.
    CONSTRAINT pk_disclosure_group PRIMARY KEY (
        account_group_id, transaction_type_code, transaction_category_code),
    CONSTRAINT ck_disclosure_group_category_digits
        CHECK (transaction_category_code ~ '^[0-9]{4}$')
);

-- app/cpy/CSLKPCDY.cpy declares two disjoint bands over WS-US-PHONE-AREA-CODE-TO-EDIT at L24:
-- VALID-GENERAL-PURP-CODE at L521 with 410 codes, VALID-EASY-RECOG-AREA-CODE at L931 with 80.
-- V3__reference_data.sql seeds their 490-code union, one band label per row.
CREATE TABLE us_phone_area_code (
    area_code   CHAR(3)     NOT NULL PRIMARY KEY,
    band        VARCHAR(24) NOT NULL
);

-- VALID-US-STATE-CODE at app/cpy/CSLKPCDY.cpy:L1013, 56 two-character codes.
CREATE TABLE us_state_code (
    state_code  CHAR(2) NOT NULL PRIMARY KEY
);

-- VALID-US-STATE-ZIP-CD2-COMBO at app/cpy/CSLKPCDY.cpy:L1073, 240 four-character combinations.
-- LAST-3-OF-ZIP at app/cpy/CSLKPCDY.cpy:L1314 carries no condition name and no column here.
CREATE TABLE us_state_zip_prefix (
    state_zip_prefix    CHAR(4) NOT NULL PRIMARY KEY
);

-- Additive table with no source-record ancestor. aggregate_id carries the account identifier,
-- which is also the message key. payload carries one serialized event document.
-- payload is bounded at 8192 octets, the ceiling
-- libs/event-contracts/.../serde/EventWireBounds.java applies on the wire, so the stored
-- bound and the published bound are one bound. Before this it was an unbounded TEXT column,
-- which meant a document the wire gate would have refused could still be stored.
--
-- The seven relay-state columns are ADDITIVE with no COBOL ancestor: without a claim, two
-- relay instances read the same unpublished row and publish it twice, and without an attempt
-- count and a next-attempt time one undeliverable row is retried forever and blocks the rows
-- behind it.
CREATE TABLE outbox_event (
    -- Idempotency key. The same value travels in the EventEnvelope.eventId field of
    -- the payload, and every consumer records it in its own processed_event marker.
    event_id     UUID                        NOT NULL,
    -- AccountStateChanged is the one value written.
    event_type   VARCHAR(50)                 NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key. Fixed-width
    -- character storage keeps a leading zero, which XREF-ACCT-ID PIC 9(11) at
    -- app/cpy/CVACT03Y.cpy:L7 carries in 50 of the 50 rows of
    -- app/data/ASCII/cardxref.txt. A numeric column would partition 00000000050 and 50
    -- as two different keys.
    aggregate_id CHAR(11)                    NOT NULL,
    -- One event payload as JavaScript Object Notation (JSON) text, envelope fields
    -- and payload fields at the same level. ck_outbox_event_payload_bytes below holds
    -- it to the same 8192-octet ceiling the wire applies, so the stored bound and the
    -- published bound are one bound.
    payload      TEXT                        NOT NULL,
    published    BOOLEAN                     NOT NULL DEFAULT FALSE,
    -- Arrival order, and the leading column the relay orders its batch on.
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The one column of the seven that accepts NULL, and null until the publish succeeds.
    published_at TIMESTAMP(6) WITH TIME ZONE,
    -- PENDING, CLAIMED, PUBLISHED or ABANDONED. Terminal states are the last two.
    relay_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- How many publish attempts this row has taken. The relay abandons a row at its own
    -- ceiling rather than at a constraint, so exceeding the ceiling is a decision and not
    -- a failed statement.
    attempt_count   INTEGER     NOT NULL DEFAULT 0,
    -- When the relay may next attempt this row. A new row is due as soon as it is written.
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    -- A short, redacted reason. Never the payload and never a customer value: the column is
    -- bounded so that a stack trace cannot be stored here by accident.
    last_error      VARCHAR(500),
    -- Which relay instance holds the claim, and since when. Both or neither.
    claimed_by      VARCHAR(64),
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT ck_outbox_event_aggregate_id_digits CHECK (aggregate_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_outbox_event_payload_bytes CHECK (octet_length(payload) <= 8192),
    -- The flag and the timestamp move together. A row is either unpublished with no
    -- timestamp or published with one, and no third state reaches the table.
    CONSTRAINT ck_outbox_event_publication CHECK (
        (published = FALSE AND published_at IS NULL)
        OR (published = TRUE AND published_at IS NOT NULL)),
    CONSTRAINT ck_outbox_event_relay_state
        CHECK (relay_state IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'ABANDONED')),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    -- A claim has both halves or neither, so a half-written claim cannot strand a row.
    CONSTRAINT ck_outbox_event_claim_pairing
        CHECK ((claimed_by IS NULL) = (claimed_at IS NULL)),
    -- The boolean and the state cannot drift apart, whichever one a reader trusts.
    CONSTRAINT ck_outbox_event_published_agrees
        CHECK (published = (relay_state = 'PUBLISHED')),
    CONSTRAINT ck_outbox_event_published_at
        CHECK ((published_at IS NOT NULL) = (relay_state = 'PUBLISHED'))
);

-- Partial index over pending rows alone. The relay claims only unpublished rows, so a
-- published row carries no index entry and the index stays the size of the backlog
-- rather than the size of the table. The two key columns are the relay's ordering:
-- created_at first, then event_id to break a tie, so two relay instances agree on
-- which row comes next.
CREATE INDEX ix_outbox_event_pending
    ON outbox_event (created_at, event_id) WHERE published = FALSE;

-- The claim query filters on relay_state and orders by next_attempt_at, so both columns are
-- covered. This index is also the purge path for rows in a terminal state.
CREATE INDEX ix_outbox_event_claimable
    ON outbox_event (relay_state, next_attempt_at);


-- Retention. A published row has done its work and stays only for diagnosis. This index
-- serves the purge that deletes published rows past the retention horizon.
CREATE INDEX ix_outbox_event_published_at
    ON outbox_event (published_at) WHERE published = TRUE;
-- Additive table with no source-record ancestor.
-- The primary key is the guard as well as the key: an insert that collides is how a consumer
-- learns the event was already handled, so the guard cannot be checked and then raced past.
-- The consumer inserts this row in the same local transaction as its side effects and
-- acknowledges the message only after that transaction commits, which is why a crash between
-- the two leaves no half-processed event.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. NULL for a marker written before this column
    -- existed; every marker written since carries one.
    consumed_topic VARCHAR(128),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);


-- ============================================================================
-- Retention and erasure
-- ============================================================================
-- No source dataset carries a retention rule. app/csd/CARDDEMO.CSD defines eight files
-- with RECOVERY(NONE) and JOURNAL(NO) and no expiry, the Job Control Language members
-- under app/jcl/ define datasets without an EXPDT or RETPD parameter, and no program
-- under app/cbl/ deletes a record: the only DELETE in the repository is IDCAMS deleting
-- a whole dataset before it is redefined. A table that only ever grows is a table whose
-- oldest row is as exposed as its newest, so this platform states a rule for every table
-- it owns.
--
-- Each COMMENT below reads as four fields followed by a sentence, so an operator can
-- read the policy out of the catalogue rather than out of a document:
--   retention=<window>      how long a row may stay, or the word relationship for a business
--                           record whose life is the customer relationship
--   purge_key=<column>      the column a purge job ranges over, or 'none'
--   personal_data=<yes|no>  whether the row describes an identifiable person
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The windows below are the demo baseline this platform ships with. No requirement in
-- scope fixes a legal retention period, and card-platform/docs/suggested-next-tasks.md (planned)
-- carries the task of replacing them with the periods a deployment's jurisdiction
-- requires. The purge job itself is out of scope for the same reason: nothing in the
-- Agent Action Plan schedules one, and a job that deletes financial records is not
-- something to add without an owner. The columns and indexes it needs are here.

-- The range a purge job scans.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE account IS
    'retention=relationship; purge_key=none; personal_data=no. The account record.
     app/cpy/CVACT01Y.cpy:L6 carries an active status the source never tests, so a closed
     account still holds a row; closing is not erasing.';

COMMENT ON TABLE customer IS
    'retention=relationship, erase on request; purge_key=none; personal_data=yes. The only
     table on this platform that describes an identifiable person: name, postal address, two
     telephone numbers, Social Security Number, government-issued identifier, date of birth
     and electronic funds transfer account. Erasure is a deliberate act on a named customer
     and never a timer. Erasing one customer means deleting this row; account rows carry no
     customer identifier at all (app/cpy/CVACT01Y.cpy:L4-L17 declares none), so the link runs
     only through card_xref in the authorization and card services, and an erasure has to
     reach those two services as well.';

COMMENT ON TABLE disclosure_group IS
    'retention=reference; purge_key=none; personal_data=no. Seeded interest-rate lookup from
     app/data/ASCII/discgrp.txt, 51 rows.';

COMMENT ON TABLE us_phone_area_code IS
    'retention=reference; purge_key=none; personal_data=no. Seeded validation data from
     app/cpy/CSLKPCDY.cpy.';

COMMENT ON TABLE us_state_code IS
    'retention=reference; purge_key=none; personal_data=no. Seeded validation data from
     app/cpy/CSLKPCDY.cpy.';

COMMENT ON TABLE us_state_zip_prefix IS
    'retention=reference; purge_key=none; personal_data=no. Seeded validation data from
     app/cpy/CSLKPCDY.cpy.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=occurred_at; personal_data=no. One account
     or cycle-close event awaiting publication. Purge rows where published is true and
     occurred_at is older than 7 days.';

COMMENT ON TABLE processed_event IS
    'retention=30 days; purge_key=processed_at; personal_data=no. Duplicate-delivery marker,
     kept longer than broker topic retention.';

COMMENT ON COLUMN customer.social_security_number IS
    'personal_data=yes. Held because app/cpy/CVCUS01Y.cpy:L17 declares it and the edit at
     app/cbl/COACTUPC.cbl:L2431-L2491 reads it. It reaches no event, no log line and no
     response body, and an erasure request clears it with the rest of the row.';

COMMENT ON COLUMN customer.date_of_birth IS
    'personal_data=yes. Same handling as social_security_number.';

COMMENT ON COLUMN customer.government_issued_id IS
    'personal_data=yes. Same handling as social_security_number.';

COMMENT ON COLUMN customer.eft_account_id IS
    'personal_data=yes. Same handling as social_security_number.';
