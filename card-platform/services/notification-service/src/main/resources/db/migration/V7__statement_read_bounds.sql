-- Notification service, migration V7.
-- Records the read bounds of statement_transaction in the catalogue, superseding the claim the
-- V1 line comment above CONSTRAINT pk_statement_transaction makes.
--
-- V1:L74-L76 states that every read of this table names a limit, that
-- NotificationRenderer.MAXIMUM_STATEMENT_ROWS fixes it, and that no query returns a whole card
-- history. Two of the three are untrue of the delivered code. There are two reads:
--
--   api/NotificationHistoryController      GET /notifications/{cardNumber}
--                                          findByIdCardTokenOrderByIdTransactionIdAsc
--                                          Limit.unlimited() -- every row of the card, ascending
--   domain/NotificationService             renderableRows, for one alert
--                                          findByIdCardTokenOrderByIdTransactionIdDesc
--                                          Limit.of(MAXIMUM_STATEMENT_ROWS) = 200, newest first
--
-- The unlimited read is the source-faithful one. app/cbl/CBSTM03A.CBL:L429 totals every row of one
-- card between two key breaks, and app/cbl/CBSTM03A.CBL:L819-L825 reads each card's whole run from
-- the sorted input. openapi.yaml declares the history array with no maxItems for that reason.
-- MAXIMUM_STATEMENT_ROWS is additive and bounds one rendered alert alone.
--
-- Both reads are range scans over this index: card_token fixes the prefix and transaction_id
-- orders the entries, forward for the history route and backward for an alert. That order is the
-- order SORT FIELDS=(263,16,CH,A,1,16,CH,A) at app/jcl/CREASTMT.JCL:L53 produces.
--
-- Response growth: one row per posted transaction per card, expiring on
-- carddemo.history.statement-retention-days (400 by default), so the history response grows with
-- one card's retained transactions.
--
-- Rationale for the unlimited history read and for the additive alert ceiling:
-- card-platform/docs/decision-log.md.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 is left as it ran.

COMMENT ON CONSTRAINT pk_statement_transaction ON statement_transaction IS
    'Primary key and the access path of both reads of this table, as a range scan: card_token
     fixes the prefix and transaction_id orders the entries, in the order
     SORT FIELDS=(263,16,CH,A,1,16,CH,A) at app/jcl/CREASTMT.JCL:L53 produces. NOT EVERY READ
     NAMES A LIMIT, and the V1 line comment above this constraint says otherwise.
     GET /notifications/{cardNumber} scans forward with Limit.unlimited() and returns every row of
     the card, which is what app/cbl/CBSTM03A.CBL:L429 did between two key breaks and why the
     published array carries no maxItems. One rendered alert scans backward under
     NotificationRenderer.MAXIMUM_STATEMENT_ROWS, an additive ceiling of 200 that bounds the alert
     alone; reading backward is what keeps the transaction the alert reports inside the rows it
     renders and inside its total. Growth is one row per posted transaction per card, expiring on
     carddemo.history.statement-retention-days.';
