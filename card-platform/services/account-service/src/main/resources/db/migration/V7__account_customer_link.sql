-- Account service, migration V7.
-- Replaces the card_xref replica V4 created with a relationship table that holds no card number.
--
-- V4 copied app/cpy/CVACT03Y.cpy whole, so it stored all fifty full card numbers as its primary
-- key. The account service never reads that column: its one query is
-- findFirstByAccountIdOrderByCardNumberAsc, whose only purpose is to derive XREF-CUST-ID from
-- XREF-ACCT-ID so that the update path at app/cbl/COACTUPC.cbl cannot be handed an unrelated
-- customer row. The card number was the source key and nothing more, and a Primary Account Number
-- replicated into a schema that reads no card is a disclosure surface with no reader. A security
-- review recorded it, and section 0.3.1 of the plan never listed a cross-reference table among the
-- tables this service owns.
--
-- What the relationship needs is the pair, so the pair is what this table holds. account_id is the
-- key because that is the value the query supplies. The source alternate index is non-unique — one
-- account reaches every card it holds at KEYS(11 25) in app/jcl/XREFFILE.jcl:L72-L77 — and every
-- card of one account names one customer, so collapsing the rows onto the account loses no
-- relationship. It also removes the ordering the previous query needed: there is one row to find
-- rather than a lowest card number to choose between.
--
-- The fifty relationships are re-seeded here from the same fixture V4 read, so the table this
-- service queries opens with the pairing it opened with before. Nothing on this platform refreshes
-- the rows V4 seeded — no consumer, service or repository writes that table — so the seeded pairing
-- and the live pairing are the same fifty rows, and re-seeding them loses nothing that a copy from
-- the dropped table would have kept. Writing the pairs out also keeps the seed readable in the
-- migration that owns the table, which is where card-platform/equivalence-tests compares a seed
-- against app/data/ASCII/cardxref.txt.

CREATE TABLE account_customer_link (
    account_id         CHAR(11)                    NOT NULL,
    customer_id        CHAR(9)                     NOT NULL,
    source_event_id    UUID,
    source_occurred_at TIMESTAMP(6) WITH TIME ZONE,
    observed_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_account_customer_link PRIMARY KEY (account_id),
    CONSTRAINT ck_account_customer_link_account_id_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_account_customer_link_customer_id_digits
        CHECK (customer_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_account_customer_link_source_pairing
        CHECK ((source_event_id IS NULL) = (source_occurred_at IS NULL))
);

-- The range a purge or a staleness report scans, as ix_card_xref_observed_at covered before.
CREATE INDEX ix_account_customer_link_observed_at ON account_customer_link (observed_at);

-- One row per account, carrying the customer every card of that account names, read from
-- XREF-ACCT-ID and XREF-CUST-ID at app/cpy/CVACT03Y.cpy:L6-L7 of app/data/ASCII/cardxref.txt. The
-- fixture pairs each of the fifty accounts with exactly one customer, so collapsing its fifty
-- cross-reference records onto the account yields fifty rows and drops no relationship. These are
-- the pairs findFirstByAccountIdOrderByCardNumberAsc resolved, so the value this service derives
-- does not move with the migration.
INSERT INTO account_customer_link (account_id, customer_id) VALUES
    ('00000000001', '000000001'),
    ('00000000002', '000000002'),
    ('00000000003', '000000003'),
    ('00000000004', '000000004'),
    ('00000000005', '000000005'),
    ('00000000006', '000000006'),
    ('00000000007', '000000007'),
    ('00000000008', '000000008'),
    ('00000000009', '000000009'),
    ('00000000010', '000000010'),
    ('00000000011', '000000011'),
    ('00000000012', '000000012'),
    ('00000000013', '000000013'),
    ('00000000014', '000000014'),
    ('00000000015', '000000015'),
    ('00000000016', '000000016'),
    ('00000000017', '000000017'),
    ('00000000018', '000000018'),
    ('00000000019', '000000019'),
    ('00000000020', '000000020'),
    ('00000000021', '000000021'),
    ('00000000022', '000000022'),
    ('00000000023', '000000023'),
    ('00000000024', '000000024'),
    ('00000000025', '000000025'),
    ('00000000026', '000000026'),
    ('00000000027', '000000027'),
    ('00000000028', '000000028'),
    ('00000000029', '000000029'),
    ('00000000030', '000000030'),
    ('00000000031', '000000031'),
    ('00000000032', '000000032'),
    ('00000000033', '000000033'),
    ('00000000034', '000000034'),
    ('00000000035', '000000035'),
    ('00000000036', '000000036'),
    ('00000000037', '000000037'),
    ('00000000038', '000000038'),
    ('00000000039', '000000039'),
    ('00000000040', '000000040'),
    ('00000000041', '000000041'),
    ('00000000042', '000000042'),
    ('00000000043', '000000043'),
    ('00000000044', '000000044'),
    ('00000000045', '000000045'),
    ('00000000046', '000000046'),
    ('00000000047', '000000047'),
    ('00000000048', '000000048'),
    ('00000000049', '000000049'),
    ('00000000050', '000000050');

DROP TABLE card_xref;

COMMENT ON TABLE account_customer_link IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. The account and customer
     identifiers the update path pairs, from XREF-ACCT-ID and XREF-CUST-ID at
     app/cpy/CVACT03Y.cpy:L6-L7. This table holds NO card number: XREF-CARD-NUM was the source
     primary key and no query here reads a card, so replicating it into this schema stored a
     Primary Account Number with no reader. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform:
     erasing one customer would mean deleting the customer row and this link, and the card and
     authorization services hold their own cross-reference replicas an erasure would have to reach
     too. card-platform/docs/suggested-next-tasks.md carries the task.';

COMMENT ON COLUMN account_customer_link.account_id IS
    'XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7. The key, because it is the value the
     account update path supplies.';

COMMENT ON COLUMN account_customer_link.customer_id IS
    'XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy:L6. The value the update path derives, so a
     caller cannot pair an account with a customer row it does not own.';

COMMENT ON COLUMN account_customer_link.source_event_id IS
    'Event that last refreshed this row, or NULL for a row this migration carried across.';

COMMENT ON COLUMN account_customer_link.source_occurred_at IS
    'Producer time of the last refresh, or NULL for a row this migration carried across.';

COMMENT ON COLUMN account_customer_link.observed_at IS
    'When this service last observed the relationship.';

-- Three comments V1 wrote name the table this migration dropped, and one of them promised a
-- capability this platform does not implement. Both are corrected here rather than in V1, because a
-- migration that has been applied is not edited.
--
-- The erasure sentence is the substantive change. V1 read 'retention=relationship, erase on
-- request', which a reader takes as a statement that an erasure route exists. None does: no
-- controller, event, service or repository on this platform erases or exports a customer, and a
-- security review recorded the promise as a finding. The text below states the position instead —
-- what an erasure would have to reach, and that the work has not been done — and
-- card-platform/docs/suggested-next-tasks.md carries the task.

COMMENT ON TABLE account IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. The account identifier,
     balances and credit limits describe one customer and are linked to that person through
     account_customer_link. app/cpy/CVACT01Y.cpy:L6 carries an active status the source never
     tests, so a closed account still holds a row; closing is not erasing.';

COMMENT ON TABLE customer IS
    'retention=relationship; purge_key=none; personal_data=yes. NO ERASURE OR EXPORT WORKFLOW
     EXISTS: this platform implements neither, and this comment records the position rather than a
     promise. The only table on this platform that describes an identifiable person: name, postal
     address, two telephone numbers, Social Security Number, government-issued identifier, date of
     birth and electronic funds transfer account. Erasure would be a deliberate act on a named
     customer and never a timer. Account rows carry no customer identifier at all
     (app/cpy/CVACT01Y.cpy:L4-L17 declares none), so the link runs through account_customer_link
     here and through the card_xref replicas the authorization and card services hold, and an
     erasure would have to reach this row, that link, both replicas, and the cardholder_context read
     model the notification service keys by account. card-platform/docs/suggested-next-tasks.md
     carries the task and names every store it has to reach.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=published_at; personal_data=pseudonymous. One
     account-keyed event whose payload carries financial state and can be linked to a customer
     through account_customer_link. Purge rows where published is true and published_at is older
     than 7 days.';
