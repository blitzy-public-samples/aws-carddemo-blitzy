-- Notification service, migration V10.
-- Holds one card's whole-history metadata as one row, so a page of the history route no longer
-- aggregates that card's retained rows to answer it.
--
-- What the route needed and what it cost. GET /notifications/{cardToken} answers a bounded page of
-- detail rows beside three whole-card figures: how many rows the card holds, what they sum to, and
-- the sum of their magnitudes, which domain/NotificationService uses to prove that the running total
-- of app/cbl/CBSTM03A.CBL:L429 could not have overflowed WS-TOTAL-AMT PIC S9(9)V99 at
-- app/cbl/CBSTM03A.CBL:L65. Those three came from one aggregate over card_token. A performance
-- review measured it on a card holding 21,299 rows: a sequential scan of 1,228 buffers, 7.775 ms,
-- on every request whatever the page size, against 4 buffers and 0.048 ms for a card holding one.
-- The page was bounded and the figures beside it were not, and the aggregate grows with the
-- carddemo.history.statement-retention-days window.
--
-- What this table holds. One row per card the read model has ever held, carrying the same four
-- values the aggregate answered. It is maintained by the two statements that change the read model,
-- both in repository/StatementTransactionRepository: the upsert one posted event performs adds its
-- delta, and the retention delete subtracts what it removed. Each is one statement, so a row of
-- statement_transaction and this card's totals commit or fail together, and the marker
-- TransactionPostedConsumer claims commits with them.
--
-- A delta rather than a recount. A recount would put the cost this migration removes on the
-- write path instead of the read path. The delta is derived from the row as it stood before the
-- statement, so a redelivery that rewrites one transaction adds no count and only the difference
-- between the two amounts, and the totals stay equal to the aggregate they replace.
--
-- Widths. transaction_count is a count rather than a COBOL field. The two sums are NUMERIC(31,2)
-- rather than the NUMERIC(11,2) of statement_transaction.amount, because a sum of many rows exceeds
-- the field one row declares, and the aggregate that answered before was unbounded in the same way.
-- The overflow proof reads these values through com.carddemo.cobol.CobolDecimal, which truncates
-- them to the source field, so the wider column narrows where the source narrowed and nowhere else.
--
-- A card with no row. An aggregate always answered, with a count of zero; a table read answers
-- nothing at all. api/NotificationHistoryController therefore treats an absent row and a row
-- carrying zero as the same answer, which is the 404 the route already gave.
--
-- Design decisions: card-platform/docs/decision-log.md.
--
-- Nothing else changes. No column, constraint, default or row of any existing table is touched, and
-- no index is dropped. The two comments at the end restate what two objects of the read model are
-- for; a comment carries no behaviour.

CREATE TABLE statement_card_total (
    -- The card token, on the same footing as statement_transaction.card_token: the first part of
    -- that table's primary key, and the value this table is keyed by.
    card_token          CHAR(64)      NOT NULL,
    -- How many rows of statement_transaction the card holds. Zero is a real value: retention can
    -- remove every row of a card and leave the card known.
    transaction_count   BIGINT        NOT NULL,
    -- The exact sum of those rows' amounts, and the sum of their magnitudes. The second is what
    -- bounds every running total the source's accumulation passes through, so a value that fits
    -- WS-TOTAL-AMT proves no addition of app/cbl/CBSTM03A.CBL:L429 could have overflowed.
    total_amount        NUMERIC(31,2) NOT NULL,
    absolute_total      NUMERIC(31,2) NOT NULL,
    -- The masked card number the card's rows carry, display data alone. Every row of one card
    -- carries the same masked form, so which row it was taken from cannot matter. It is nullable
    -- because a card can be known and hold no row.
    masked_card_number  CHAR(16),
    CONSTRAINT pk_statement_card_total PRIMARY KEY (card_token),
    -- The key is a card token, exactly as it is next door: sixteen digits and twelve asterisks with
    -- four digits both fail 64 lower-case hexadecimal characters.
    CONSTRAINT ck_statement_card_total_card_token
        CHECK (card_token ~ '^[0-9a-f]{64}$'),
    -- The display column carries the masked form and never a full Primary Account Number.
    CONSTRAINT ck_statement_card_total_masked_card_number
        CHECK (masked_card_number IS NULL OR masked_card_number ~ '^\*{12}[0-9]{4}$'),
    -- A count and a sum of magnitudes cannot be negative. These two are the drift detector: the
    -- totals are maintained by delta, so a delta that ever failed to match the rows it described
    -- would fail a statement here rather than answer a wrong figure to a cardholder.
    CONSTRAINT ck_statement_card_total_transaction_count
        CHECK (transaction_count >= 0),
    CONSTRAINT ck_statement_card_total_absolute_total
        CHECK (absolute_total >= 0)
);

-- Backfill. Every row this table would have held had it existed, computed by the aggregate it
-- replaces. A schema carrying no statement row backfills nothing.
INSERT INTO statement_card_total (card_token, transaction_count, total_amount, absolute_total,
            masked_card_number)
SELECT card_token,
       COUNT(*),
       COALESCE(SUM(amount), 0),
       COALESCE(SUM(ABS(amount)), 0),
       MIN(masked_card_number)
  FROM statement_transaction
 GROUP BY card_token;

COMMENT ON TABLE statement_card_total IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Every row is keyed by a card
     token and carries the masked number that card displays, so a subject request reaches this table,
     and nothing expires the row: the statement horizon of statement_transaction removes the rows a
     row here counts and leaves it holding zero.
     One card''s whole-history metadata, maintained by delta rather than recounted: the row count,
     the sum of the amounts, the sum of their magnitudes, and the masked number the card displays.
     It replaces an aggregate over statement_transaction that GET /notifications/{cardToken} ran on
     every request, which cost a sequential scan of the card''s retained history whatever page size
     the caller asked for. Both writers are single statements of
     repository/StatementTransactionRepository, so these totals commit with the rows they describe.
     absolute_total is what proves the running total of app/cbl/CBSTM03A.CBL:L429 could not have
     overflowed WS-TOTAL-AMT at app/cbl/CBSTM03A.CBL:L65. Retention is the statement horizon of
     statement_transaction: a row here is decremented as its rows are removed, and a count of zero
     is a card the route answers 404 for. No entity maps this table; nothing loads it as an object.';

COMMENT ON CONSTRAINT pk_statement_card_total ON statement_card_total IS
    'Primary key and the whole access path: the history route reads exactly one row of this table by
     this key, so the figures beside a page cost one index lookup rather than a scan of the card.';

COMMENT ON CONSTRAINT pk_statement_transaction ON statement_transaction IS
    'Primary key and the access path of every read of this table, as a range scan: card_token fixes
     the prefix and transaction_id orders the entries, in the order
     SORT FIELDS=(263,16,CH,A,1,16,CH,A) at app/jcl/CREASTMT.JCL:L53 produces.
     EVERY READ NAMES A LIMIT, and both the V1 line comment above this constraint and the V7
     comment that superseded it describe reads the delivered code no longer performs.
     GET /notifications/{cardToken} scans forward one bounded page at a time from a keyset cursor,
     and takes the whole-card count and totals from one row of statement_card_total, which V10
     added for that purpose. One rendered alert scans backward under
     NotificationRenderer.MAXIMUM_STATEMENT_ROWS, an additive ceiling of 200 that bounds the alert
     alone; reading backward is what keeps the transaction the alert reports inside the rows it
     renders and inside its total. Growth is one row per posted transaction per card, expiring on
     carddemo.history.statement-retention-days.';
