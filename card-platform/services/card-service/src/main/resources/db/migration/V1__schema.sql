-- Card service schema, applied once by Flyway from classpath:db/migration. Flyway
-- creates the schema named by spring.flyway.schemas in
-- src/main/resources/application.yml, and every object below is unqualified.
-- V2__seed.sql loads the rows.

-- card: the 150-byte card record. Fields from app/cpy/CVACT02Y.cpy:L5-L10, and the
-- trailing FILLER PIC X(59) at L11 has no column here. Primary key from KEYS(16 0)
-- at app/jcl/CARDFILE.jcl:L54, sixteen bytes at offset zero, alongside
-- RECORDSIZE(150 150) at L55.
CREATE TABLE card (
    card_number             CHAR(16) NOT NULL,
    -- CARD-ACCT-ID PIC 9(11) is an eleven-character display field, and columns 17
    -- through 27 of every record of app/data/ASCII/carddata.txt carry its leading
    -- zeros: record one holds 00000000050. A numeric column stores 50 and returns 50,
    -- so the value stops matching the eleven-character alternate-index key at
    -- KEYS(11 16) in app/jcl/CARDFILE.jcl:L85 and stops matching card_xref.account_id.
    account_id              CHAR(11) NOT NULL,
    -- CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 holds the card verification value. The
    -- column keeps it, and no event, log line or response payload carries it: CardEntity exposes
    -- no accessor for the field, and CardholderDataExposureTest asserts the non-emission. The type
    -- is CHAR(3) rather than NUMERIC(3,0) because the Picture clause is a display field: a numeric
    -- column returns 7 for a stored 007, which is a different card verification value.
    card_verification_value CHAR(3)  NOT NULL,
    embossed_name           CHAR(50) NOT NULL,
    -- CARD-EXPIRAION-DATE PIC X(10), spelling corrected in the column name. The ten
    -- characters read YYYY-MM-DD, and app/cbl/COCRDUPC.cbl:L117-L121 splits them into
    -- a four-character year, a two-character month and a two-character day.
    expiration_date         DATE     NOT NULL,
    -- app/jcl/POSTTRAN.jcl allocates no card dataset, and posting reads no status.
    active_status           CHAR(1)  NOT NULL,
    -- Card token: the platform identity of one card, and an addition with no field in
    -- app/cpy/CVACT02Y.cpy behind it. AAP section 0.1.1 requires a tokenized or masked
    -- card number on the wire and section 0.6.4 fixes the rule that produces this
    -- column: a decision runs on the full Primary Account Number, and only a published
    -- value carries a substitute.
    --
    -- The masked form cannot serve as that substitute. Twelve asterisks and four digits
    -- name every card sharing four digits, so a masked value identifies no single row.
    -- This column identifies exactly one, which is why the paging cursor of the card
    -- list carries it and never a card number.
    --
    -- The value is the HmacSHA256 code, taken under the configured card-token key, over
    -- the label CardDemo/card-token/v<version>: followed by the sixteen characters of
    -- card_number, rendered as sixty-four lower-case hexadecimal characters.
    -- com.carddemo.cobol.PanMasker.cardToken is the one place that derivation lives, and
    -- CardEntity applies it to every row this service writes. The code is keyed rather
    -- than a plain digest because the card-number space is sixteen digits wide: a plain
    -- digest of it can be recomputed for every candidate card number by anyone holding
    -- one token, and a token here reaches a cursor, a route and a response.
    --
    -- V2__seed.sql carries the same value as a checked-in literal on each of its fifty
    -- rows, which keeps the derivation out of SQL entirely, and
    -- CardRepositoryIT.everySeededTokenMatchesTheJavaDerivation compares all fifty
    -- literals against the helper. A drifted literal therefore fails a test rather than
    -- splitting one card's identity in two.
    --
    -- Those literals belong to the BUILD-SCOPE key card-platform/pom.xml supplies, not to a
    -- deployment key: card-platform/.env.example and deploy/k8s/31-secret.example.yaml both
    -- carry a placeholder, and this service refuses to start until a deployment supplies a
    -- key of its own. domain.CardTokenReconciler then re-derives this column for every row
    -- of this table under that key, before the service accepts traffic, so no stored token
    -- belongs to a key this repository publishes. There is no setter for the column on
    -- CardEntity: a row this service builds derives it in the constructor, and a row loaded
    -- by a migration is corrected by that statement.
    card_token              CHAR(64) NOT NULL,
    CONSTRAINT pk_card PRIMARY KEY (card_number),
    CONSTRAINT ck_card_account_id_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_card_verification_value_digits
        CHECK (card_verification_value ~ '^[0-9]{3}$'),
    -- A code renders as lower-case hexadecimal, and sixty-four characters of it cannot
    -- hold the sixteen digits of a card number.
    CONSTRAINT ck_card_card_token_hex
        CHECK (card_token ~ '^[0-9a-f]{64}$'),
    -- One card reaches one token and no two cards share one, which is what lets a
    -- cursor naming a token reach exactly one browse position.
    CONSTRAINT uq_card_card_token UNIQUE (card_token)
);

-- The alternate index at app/jcl/CARDFILE.jcl:L85-L87 carries KEYS(11 16) and
-- NONUNIQUEKEY: eleven bytes at offset 16, where the account identifier starts, and a
-- repeated value is allowed.
--
-- card_number follows account_id so that one index serves both an account-scoped read and
-- the keyset walk over that account's cards. The list path orders by card_number and bounds
-- it by a cursor, which this index supplies in order and without a sort. A leading-column
-- lookup on account_id alone reads the same index.
CREATE INDEX idx_card_account_id ON card (account_id, card_number);

-- card_xref: card-to-account cross-reference, a replica private to this service.
-- Fields from app/cpy/CVACT03Y.cpy:L5-L7, and the trailing FILLER PIC X(14) at L8 has
-- no column here. Primary key from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, alongside
-- RECORDSIZE(50 50) at L44.
-- XREF-CUST-ID PIC 9(09) at :L6 and XREF-ACCT-ID PIC 9(11) at :L7 are display fields,
-- and every record of app/data/ASCII/cardxref.txt carries their leading zeros: record
-- one holds 000000050 and 00000000050. Both are held as text so a value written here
-- compares equal to the same identifier in the authorization service and in an event
-- key, which a numeric column would reduce to 50.
-- The three freshness columns have no COBOL ancestor, because the source has no
-- replica to keep current: app/cbl/COCRDSLC.cbl reads the cross-reference dataset itself.
-- This table is a copy, and a copy that cannot say how old it is cannot be refused when it is
-- too old. source_event_id and source_occurred_at name the state-change event that last wrote
-- the row, so an out-of-order delivery changes nothing; observed_at is what a freshness check
-- reads.
CREATE TABLE card_xref (
    card_number CHAR(16) NOT NULL,
    customer_id CHAR(9)  NOT NULL,
    account_id  CHAR(11) NOT NULL,
    -- The event that last wrote this row. NULL for a row loaded by V2__seed.sql, which is the
    -- initial load rather than an event.
    source_event_id    UUID,
    source_occurred_at TIMESTAMP(6) WITH TIME ZONE,
    -- Never NULL, so a freshness check always has a value to compare. The default exists for
    -- V2__seed.sql alone: the seed is the initial observation, so the moment the migration ran
    -- is the truthful answer, and every later writer sets the column explicitly.
    observed_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_card_xref PRIMARY KEY (card_number),
    CONSTRAINT ck_card_xref_customer_id_digits
        CHECK (customer_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_card_xref_account_id_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    -- An event-written row carries both halves of its provenance or neither.
    CONSTRAINT ck_card_xref_source_pairing
        CHECK ((source_event_id IS NULL) = (source_occurred_at IS NULL))
);

-- A staleness sweep and the freshness check both read observed_at.
CREATE INDEX ix_card_xref_observed_at ON card_xref (observed_at);

-- The alternate index at app/jcl/XREFFILE.jcl:L74-L75 carries KEYS(11,25) and
-- NONUNIQUEKEY: eleven bytes at offset 25, where the account identifier starts.
CREATE INDEX idx_card_xref_account_id ON card_xref (account_id);

-- outbox_event: one row per event, written in the same local transaction as the card
-- update. app/csd/CARDDEMO.CSD:L7 and :L9 set JOURNAL(NO) and RECOVERY(NONE), and all
-- eight of its file definitions carry both. app/cbl/CORPT00C.cbl:L517 writes the one
-- asynchronous handoff in the source, a transient data queue record. The relay
-- publishes a row, then sets published.
-- payload is bounded at 8192 octets, the ceiling
-- libs/event-contracts/.../serde/EventWireBounds.java applies on the wire, so the stored
-- bound and the published bound are one bound. The seven relay-state columns have no COBOL
-- ancestor: without a claim, two relay instances read the same unpublished
-- row and publish it twice, and without an attempt count and a next-attempt time one
-- undeliverable row is retried forever and blocks the rows behind it.
CREATE TABLE outbox_event (
    event_id     UUID          NOT NULL,
    event_type   VARCHAR(50)   NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key. Fixed-width
    -- character storage keeps a leading zero, which XREF-ACCT-ID PIC 9(11) at
    -- app/cpy/CVACT03Y.cpy:L7 carries in 50 of the 50 rows of
    -- app/data/ASCII/cardxref.txt.
    aggregate_id CHAR(11)                    NOT NULL,
    -- One event payload as JavaScript Object Notation (JSON) text, envelope fields
    -- and payload fields at the same level. ck_outbox_event_payload_bytes below holds
    -- it to the same 8192-octet ceiling the wire applies, so the stored bound and the
    -- published bound are one bound.
    payload      TEXT                        NOT NULL,
    published    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- PENDING, CLAIMED, PUBLISHED or ABANDONED. Terminal states are the last two.
    relay_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- How many publish attempts this row has taken. The relay abandons a row at its own
    -- ceiling rather than at a constraint, so exceeding the ceiling is a decision and not
    -- a failed statement.
    attempt_count   INTEGER     NOT NULL DEFAULT 0,
    -- When the relay may next attempt this row. A new row is due as soon as it is written.
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    -- A short, redacted reason. Never the payload and never an event value: the column is
    -- bounded so that a stack trace cannot be stored here by accident.
    last_error      VARCHAR(500),
    -- Which relay instance holds the claim, and since when. Both or neither.
    claimed_by      VARCHAR(64),
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    published_at    TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
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

-- The relay reads unpublished rows in arrival order.
-- Partial index over pending rows alone. The relay claims only unpublished rows, so a
-- published row carries no index entry and the index stays the size of the backlog
-- rather than the size of the table. The two key columns are the relay's ordering:
-- created_at first, then event_id to break a tie, so two relay instances agree on
-- which row comes next.
CREATE INDEX ix_outbox_event_pending
    ON outbox_event (created_at, event_id) WHERE published = FALSE;
-- The claim query filters on relay_state and orders by next_attempt_at, so both columns are
-- covered. This index is also the purge path for rows in a terminal state.
CREATE INDEX ix_outbox_event_claimable ON outbox_event (relay_state, next_attempt_at);

-- Retention. A published row has done its work and stays only for diagnosis. This index
-- serves the purge that deletes published rows past the retention horizon.
CREATE INDEX ix_outbox_event_published_at
    ON outbox_event (published_at) WHERE published = TRUE;
-- processed_event: one row per event identifier a consumer has already handled. No listener in
-- this service writes it today, so the table stays empty.
-- The primary key is the guard as well as the key: an insert that collides is how a consumer
-- learns the event was already handled, so the guard cannot be checked and then raced past. A
-- retention index over processed_at follows the table.
-- The consumer commits this row in the same local transaction as its side effects, marker after
-- effects, and acknowledges the message only after that transaction commits, which is why a crash
-- between the two leaves no half-processed event.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. NULL for a marker written before this column
    -- existed; every marker written since carries one.
    consumed_topic VARCHAR(128),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

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
--   retention=<window>      how long a row may stay, or the word relationship for a
--                           business record whose life is the customer relationship
--   purge_key=<column>      the column a purge job ranges over, or 'none'
--   personal_data=<value>   one of three values:
--                             yes           direct identifiers or authentication data
--                             pseudonymous  linkable card or event data without a direct name
--                             no            operational metadata not linked to a person
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The windows below are the demo baseline this platform ships with. No requirement in
-- scope fixes a legal retention period, so a deployment replaces them with the periods
-- its own jurisdiction requires. The purge job itself is out of scope for the same
-- reason: nothing in the Agent Action Plan schedules one, and a job that deletes
-- financial records is not something to add without an owner. The columns and indexes
-- it needs are here.

COMMENT ON TABLE card IS
    'retention=relationship; purge_key=none; personal_data=yes. The card record contains the
     embossed cardholder name, full card number and card verification value.
     card_verification_value lives here and nowhere else on this platform: AAP section 0.4.1
     requires that this service store it and never emit it, CardEntity declares no accessor
     for it, and card-platform/docs/business-rule-flags.md carries the open question of
     dropping the column outright. A deployment that answers that question yes erases the
     column in a later migration.';

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference replica. Its card, account and customer identifiers link the row to one person,
     and an erasure request has to reach it.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=published_at; personal_data=pseudonymous. One
     account-keyed card mutation event carrying a masked card number, expiry and status. Purge
     rows where published is true and published_at is older than 7 days.';

COMMENT ON TABLE processed_event IS
    'retention=carddemo.processed-event.marker-retention-hours; purge_key=processed_at;
     personal_data=no. Duplicate-delivery marker.';
