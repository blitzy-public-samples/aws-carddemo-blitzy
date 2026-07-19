-- ============================================================================
-- scale-dataset.sql — reproducible performance-test dataset generator
-- ----------------------------------------------------------------------------
-- Multiplies the eight *transactional* CardDemo tables to a caller-supplied
-- factor while preserving 100% referential integrity, so that EXPLAIN plans and
-- batch timings reflect a realistically larger data tier. The three *reference*
-- tables (transaction_type, transaction_category, disclosure_group) are code
-- tables and are intentionally left untouched — their cardinality is fixed by
-- the business domain, not by data volume.
--
-- WHAT "factor" MEANS
--   factor = 1  -> no change (the 1x seed baseline loaded from db/seed).
--   factor = N  -> the original 1x rows PLUS (N-1) fully linked replicas, i.e.
--                  N total copies. factor=10 reproduces the QA "10x" corpus:
--                    customer 500, account 500, card 500, card_xref 500,
--                    transaction 3000, tran_cat_balance 500,
--                    daily_transaction 3000, user_security 100.
--
-- PRECONDITION
--   Run against a database that already holds the 1x seed (schema V1..V4 applied
--   by Flyway, rows loaded by the `local` seed profile — see
--   ../performance-testing.md §2). Re-running this script would stack further
--   replicas on top; start from a fresh 1x database for a deterministic factor.
--
-- USAGE
--   psql "$DB_URL" -v factor=10 -f docs/onboarding/perf/scale-dataset.sql
--
-- KEY-DISJOINTNESS STRATEGY (why the replicas never collide and every FK holds)
--   * cust_id / acct_id (BIGINT, base range 1..50): offset by k*1000. Each
--     replica k occupies its own thousand-block, disjoint from the base and from
--     every other replica.
--   * tran_id / dalytran_id (16-digit numeric strings, base value < 1e9): offset
--     by k*1e15. Because the base values use only the low ~9 digits, adding
--     k*1e15 lands each replica in its own leading-digit band and still fits in
--     16 digits (max 9e15 + 1e9 < 1e16). Validated for factor <= 10.
--   * card_num / xref_card_num (full 16-digit numeric strings, base >= 5e14):
--     an arithmetic offset would overflow 16 digits, so these are REMAPPED to
--     synthetic numbers k*1e11 + ord (ord = a stable per-table ordinal). The
--     synthetic values are <= 9e11, far below the smallest real card number
--     (~5e14), so they never collide with the base rows, and the same remap is
--     applied to transaction.card_num / daily_transaction.card_num so the
--     card foreign key stays satisfied. Validated for factor <= 10.
--   * sec_usr_id (VARCHAR(8) alphanumeric): synthesised as 'P' + 7 digits
--     ('P' + lpad(k*1000+ord)), which cannot collide with the seed's ADMIN0nn /
--     USER000n identifiers.
--   * daily_transaction.id is GENERATED ALWAYS AS IDENTITY and is therefore
--     omitted from the insert column list so PostgreSQL assigns fresh values.
--
-- The whole operation runs in a single transaction: either the target factor is
-- reached in full or nothing is committed.
-- ============================================================================

\set ON_ERROR_STOP on

-- Expose the caller's :factor to the PL/pgSQL block via a namespaced GUC
-- (psql does not interpolate :variables inside a dollar-quoted body).
SELECT set_config('perf.factor', :'factor', false);

BEGIN;

-- Stable ordinal maps and pristine base snapshots, captured BEFORE any replica
-- is inserted so every replica derives from the 1x rows (never from a prior
-- replica). ON COMMIT DROP cleans them up when the transaction commits.
CREATE TEMP TABLE _card_ord ON COMMIT DROP AS
    SELECT card_num AS orig, row_number() OVER (ORDER BY card_num) AS ord FROM card;
CREATE TEMP TABLE _xref_ord ON COMMIT DROP AS
    SELECT xref_card_num AS orig, row_number() OVER (ORDER BY xref_card_num) AS ord FROM card_xref;
CREATE TEMP TABLE _user_ord ON COMMIT DROP AS
    SELECT sec_usr_id AS orig, row_number() OVER (ORDER BY sec_usr_id) AS ord FROM user_security;

CREATE TEMP TABLE _base_customer ON COMMIT DROP AS SELECT * FROM customer;
CREATE TEMP TABLE _base_account  ON COMMIT DROP AS SELECT * FROM account;
CREATE TEMP TABLE _base_card     ON COMMIT DROP AS SELECT * FROM card;
CREATE TEMP TABLE _base_xref     ON COMMIT DROP AS SELECT * FROM card_xref;
CREATE TEMP TABLE _base_tcb      ON COMMIT DROP AS SELECT * FROM tran_cat_balance;
CREATE TEMP TABLE _base_txn      ON COMMIT DROP AS SELECT * FROM transaction;
CREATE TEMP TABLE _base_daly     ON COMMIT DROP AS SELECT * FROM daily_transaction;
CREATE TEMP TABLE _base_user     ON COMMIT DROP AS SELECT * FROM user_security;

DO $$
DECLARE
    k int;
    f int := current_setting('perf.factor')::int;
BEGIN
    IF f < 1 THEN
        RAISE EXCEPTION 'factor must be >= 1 (got %)', f;
    END IF;
    IF f > 10 THEN
        RAISE WARNING 'factor % exceeds the validated range (<= 10); verify key disjointness for card_num/tran_id before trusting the result', f;
    END IF;

    FOR k IN 1 .. (f - 1) LOOP
        -- customer: cust_id offset into replica block k
        INSERT INTO customer (cust_id, cust_first_name, cust_middle_name, cust_last_name,
            cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd,
            cust_addr_country_cd, cust_addr_zip, cust_phone_num_1, cust_phone_num_2,
            cust_ssn, cust_govt_issued_id, cust_dob, cust_eft_account_id,
            cust_pri_card_holder_ind, cust_fico_credit_score, version)
        SELECT cust_id + k*1000, cust_first_name, cust_middle_name, cust_last_name,
            cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd,
            cust_addr_country_cd, cust_addr_zip, cust_phone_num_1, cust_phone_num_2,
            cust_ssn, cust_govt_issued_id, cust_dob, cust_eft_account_id,
            cust_pri_card_holder_ind, cust_fico_credit_score, version
        FROM _base_customer;

        -- account: acct_id offset; group_id points at an unchanged reference row
        INSERT INTO account (acct_id, acct_active_status, curr_bal, credit_limit,
            cash_credit_limit, acct_open_date, acct_expiration_date, acct_reissue_date,
            curr_cyc_credit, curr_cyc_debit, acct_addr_zip, group_id, version)
        SELECT acct_id + k*1000, acct_active_status, curr_bal, credit_limit,
            cash_credit_limit, acct_open_date, acct_expiration_date, acct_reissue_date,
            curr_cyc_credit, curr_cyc_debit, acct_addr_zip, group_id, version
        FROM _base_account;

        -- card: card_num remapped; acct_id follows the replicated account
        INSERT INTO card (card_num, acct_id, cvv, card_embossed_name,
            card_expiration_date, card_active_status, version)
        SELECT lpad((k::bigint*100000000000 + o.ord)::text, 16, '0'),
            b.acct_id + k*1000, b.cvv, b.card_embossed_name,
            b.card_expiration_date, b.card_active_status, b.version
        FROM _base_card b JOIN _card_ord o ON o.orig = b.card_num;

        -- card_xref: xref_card_num remapped; cust_id/acct_id follow their parents
        INSERT INTO card_xref (xref_card_num, cust_id, acct_id)
        SELECT lpad((k::bigint*100000000000 + o.ord)::text, 16, '0'),
            b.cust_id + k*1000, b.acct_id + k*1000
        FROM _base_xref b JOIN _xref_ord o ON o.orig = b.xref_card_num;

        -- tran_cat_balance: acct_id follows the account; (type_cd,cat_cd) unchanged
        INSERT INTO tran_cat_balance (acct_id, type_cd, cat_cd, bal, version)
        SELECT acct_id + k*1000, type_cd, cat_cd, bal, version
        FROM _base_tcb;

        -- transaction: tran_id offset; card_num remapped to match the card FK
        INSERT INTO transaction (tran_id, type_cd, cat_cd, tran_source, tran_desc,
            tran_amt, tran_merchant_id, tran_merchant_name, tran_merchant_city,
            tran_merchant_zip, card_num, orig_ts, proc_ts)
        SELECT lpad(((b.tran_id)::numeric + k::numeric*1000000000000000)::text, 16, '0'),
            b.type_cd, b.cat_cd, b.tran_source, b.tran_desc, b.tran_amt,
            b.tran_merchant_id, b.tran_merchant_name, b.tran_merchant_city,
            b.tran_merchant_zip, lpad((k::bigint*100000000000 + o.ord)::text, 16, '0'),
            b.orig_ts, b.proc_ts
        FROM _base_txn b JOIN _card_ord o ON o.orig = b.card_num;

        -- daily_transaction: identity id omitted; dalytran_id offset; card_num remapped
        INSERT INTO daily_transaction (dalytran_id, type_cd, cat_cd, tran_source,
            tran_desc, tran_amt, merchant_id, merchant_name, merchant_city,
            merchant_zip, card_num, orig_ts, proc_ts)
        SELECT lpad(((b.dalytran_id)::numeric + k::numeric*1000000000000000)::text, 16, '0'),
            b.type_cd, b.cat_cd, b.tran_source, b.tran_desc, b.tran_amt,
            b.merchant_id, b.merchant_name, b.merchant_city, b.merchant_zip,
            coalesce(lpad((k::bigint*100000000000 + o.ord)::text, 16, '0'), b.card_num),
            b.orig_ts, b.proc_ts
        FROM _base_daly b LEFT JOIN _card_ord o ON o.orig = b.card_num;

        -- user_security: synthetic 8-char id that cannot collide with ADMIN0nn/USER000n
        INSERT INTO user_security (sec_usr_id, sec_usr_fname, sec_usr_lname,
            sec_usr_pwd, sec_usr_type, version)
        SELECT 'P' || lpad((k*1000 + o.ord)::text, 7, '0'), b.sec_usr_fname,
            b.sec_usr_lname, b.sec_usr_pwd, b.sec_usr_type, b.version
        FROM _base_user b JOIN _user_ord o ON o.orig = b.sec_usr_id;
    END LOOP;
END $$;

COMMIT;

-- Post-condition report: row counts after scaling.
SELECT 'customer' AS table, count(*) FROM customer
UNION ALL SELECT 'account', count(*) FROM account
UNION ALL SELECT 'card', count(*) FROM card
UNION ALL SELECT 'card_xref', count(*) FROM card_xref
UNION ALL SELECT 'transaction', count(*) FROM transaction
UNION ALL SELECT 'tran_cat_balance', count(*) FROM tran_cat_balance
UNION ALL SELECT 'daily_transaction', count(*) FROM daily_transaction
UNION ALL SELECT 'user_security', count(*) FROM user_security
ORDER BY 1;
