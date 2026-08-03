-- Account service schema, first of three migrations.
-- card-platform/docs/data-model.md maps these eight tables.
-- card-platform/docs/decision-log.md records the column type and constraint choices.

-- app/cpy/CVACT01Y.cpy, the 300-byte account record. Key width and record size come from
-- app/jcl/ACCTFILE.jcl:L40 KEYS(11 0) and app/jcl/ACCTFILE.jcl:L41 RECORDSIZE(300 300).
-- FILLER PIC X(178) at app/cpy/CVACT01Y.cpy:L17 maps to no column.
CREATE TABLE account (
    account_id              NUMERIC(11,0) NOT NULL PRIMARY KEY,
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
    group_id                VARCHAR(10)   NOT NULL
);

-- app/cpy/CVCUS01Y.cpy, the 500-byte customer record. Key width and record size come from
-- app/jcl/CUSTFILE.jcl:L50 KEYS(9 0) and app/jcl/CUSTFILE.jcl:L51 RECORDSIZE(500 500).
-- FILLER PIC X(168) at app/cpy/CVCUS01Y.cpy:L23 maps to no column.
CREATE TABLE customer (
    customer_id                     NUMERIC(9,0) NOT NULL PRIMARY KEY,
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
    social_security_number          NUMERIC(9,0) NOT NULL,
    government_issued_id            VARCHAR(20)  NOT NULL,
    date_of_birth                   VARCHAR(10)  NOT NULL,
    eft_account_id                  VARCHAR(10)  NOT NULL,
    primary_card_holder_indicator   CHAR(1)      NOT NULL,
    -- app/data/ASCII/custdata.txt carries credit scores from 1 to 793, and 21 of its 50 records
    -- sit outside the 300 through 850 range at app/cbl/COACTUPC.cbl:L848-L849.
    fico_credit_score               NUMERIC(3,0) NOT NULL
);

-- app/cpy/CVTRA02Y.cpy, the 50-byte disclosure group record. Key width and record size come from
-- app/jcl/DISCGRP.jcl:L40 KEYS(16 0) and app/jcl/DISCGRP.jcl:L41 RECORDSIZE(50 50).
-- FILLER PIC X(28) at app/cpy/CVTRA02Y.cpy:L10 maps to no column.
CREATE TABLE disclosure_group (
    account_group_id            VARCHAR(10)  NOT NULL,
    transaction_type_code       CHAR(2)      NOT NULL,
    transaction_category_code   NUMERIC(4,0) NOT NULL,
    interest_rate               NUMERIC(6,2) NOT NULL,
    -- The three key columns follow DIS-GROUP-KEY at app/cpy/CVTRA02Y.cpy:L5-L8 in order.
    CONSTRAINT pk_disclosure_group PRIMARY KEY (
        account_group_id, transaction_type_code, transaction_category_code)
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
CREATE TABLE outbox_event (
    event_id        VARCHAR(36)   NOT NULL PRIMARY KEY,
    event_type      VARCHAR(64)   NOT NULL,
    payload         TEXT          NOT NULL,
    aggregate_id    NUMERIC(11,0) NOT NULL,
    published       BOOLEAN       NOT NULL,
    occurred_at     TIMESTAMP     NOT NULL,
    published_at    TIMESTAMP
);

-- Serves the outbox relay query: unpublished rows ordered by occurred_at then event_id.
CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (published, occurred_at, event_id);

-- Additive table with no source-record ancestor.
CREATE TABLE processed_event (
    event_id        VARCHAR(36) NOT NULL PRIMARY KEY,
    processed_at    TIMESTAMP   NOT NULL
);
