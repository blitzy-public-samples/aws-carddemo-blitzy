-- Make an approval visible to the next decision, and make the recorded actor name one identity.
--
-- Two unrelated defects are closed here because both are corrections to columns this service
-- already owns and neither can be closed in application code alone.
--
-- ===========================================================================================
-- PART ONE: reserved cycle exposure
-- ===========================================================================================
--
-- The gap this closes. app/cbl/CBTRN02C.cbl posts each record before it validates the next one:
-- paragraph 2000-POST-TRANSACTION at :L424-L444 runs inside the read loop, and the account rewrite
-- at :L545-L560 has already moved ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT by the time
-- 1500-B-LOOKUP-ACCT reads them at :L403-L405 for the following record. The overlimit test at
-- :L407 therefore sees every earlier approval of the same run. Two 60.00 transactions against a
-- 100.00 limit approve once and decline once, in that order, and the second decline is the whole
-- point of the rule.
--
-- This service read the two accumulators from account_credit_snapshot and moved neither. The only
-- thing that moves them is an account.state-changed event, which arrives after the authorization
-- has been published, the ledger has posted it, and the account service has applied the posting:
-- four asynchronous hops. Between the decision and that event the row still reports the exposure
-- the account carried before the approval, so both 60.00 calls approved and the limit was breached
-- by every call that arrived inside the window. The replica-freshness refusal in
-- domain/AuthorizationService cannot see this, because the row is not stale — it is current and
-- does not yet include a decision this service itself has just taken.
--
-- What the two columns hold. pending_cycle_credit and pending_cycle_debit carry the approvals this
-- service has committed and the account service has not yet reported back. They accumulate exactly
-- as the source accumulates, which is the only reason the arithmetic stays equivalent:
-- app/cbl/CBTRN02C.cbl:L549 adds an amount of zero or more to the credit accumulator and :L551
-- adds a negative amount to the debit accumulator, so pending_cycle_credit is never negative and
-- pending_cycle_debit is never positive. The two CHECK constraints below hold that convention in
-- the database rather than trusting every future writer to remember it.
--
-- domain/CycleExposureReservation adds each column to its authoritative twin before
-- domain/rules/CreditLimitRule computes the working balance, so the rule reads the figures the
-- account would carry had every approved transaction already posted. The refund sign convention at
-- app/cbl/CBTRN02C.cbl:L551 and the narrower WS-TEMP-BAL PIC S9(09)V99 working field at
-- app/cbl/CBTRN02C.cbl:L187 are untouched by this change and stay reproduced, defects included.
--
-- The two columns answer in constant time and are exact.
-- card-platform/docs/decision-log.md carries the alternatives weighed against them, a query over
-- authorization_decision among them.
--
-- The expiry. A reservation is released when the posting it anticipates is reported back, and
-- pending_expires_at bounds the case where no posting ever arrives: a reservation past its expiry
-- counts as zero, and the next reservation written for that account replaces the expired figures
-- rather than adding to them, so nothing has to sweep the table. Without that bound an approval whose
-- event is never posted would hold its exposure for ever and available credit would shrink until every
-- call declined, which is the failure card-platform/docs/onboarding.md warns about for the cycle
-- accumulators themselves, whose only source-side reset is app/cbl/CBACT04C.cbl:L353-L354.
--
-- A NULL expiry means no reservation was ever written for the account, which is every row
-- V2__seed.sql loaded. A row whose reservation has drained to zero keeps whatever expiry it last
-- carried, because zero reserved exposure reads as zero whether the expiry has passed or not, and
-- a second statement to clear it would buy nothing.

-- One clause per statement, as every other migration of this platform writes them. Existing rows
-- take the default of zero, which is the correct starting position: no decision has reserved
-- anything against a row V2__seed.sql loaded.
ALTER TABLE account_credit_snapshot
    ADD COLUMN pending_cycle_credit NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE account_credit_snapshot
    ADD COLUMN pending_cycle_debit NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE account_credit_snapshot
    ADD COLUMN pending_expires_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE account_credit_snapshot
    ADD CONSTRAINT ck_account_credit_snapshot_pending_credit_sign
        CHECK (pending_cycle_credit >= 0);

ALTER TABLE account_credit_snapshot
    ADD CONSTRAINT ck_account_credit_snapshot_pending_debit_sign
        CHECK (pending_cycle_debit <= 0);

COMMENT ON COLUMN account_credit_snapshot.pending_cycle_credit IS
    'Approved exposure of zero or more that this service has committed and the account service has
     not yet reported back, accumulated as app/cbl/CBTRN02C.cbl:L549 accumulates
     ACCT-CURR-CYC-CREDIT. domain/rules/CreditLimitRule adds it to current_cycle_credit, so an
     approval is visible to the next decision instead of four asynchronous hops later. Released by
     an account.state-changed event that reports the posting, or by its expiry.';

COMMENT ON COLUMN account_credit_snapshot.pending_cycle_debit IS
    'Approved exposure of zero or less, accumulated as app/cbl/CBTRN02C.cbl:L551 accumulates
     ACCT-CURR-CYC-DEBIT: a negative amount makes the accumulator more negative, and
     app/cbl/CBTRN02C.cbl:L404 then subtracts it. The sign convention is the source''s, refund
     defect included, and card-platform/docs/business-rule-flags.md carries it.';

COMMENT ON COLUMN account_credit_snapshot.pending_expires_at IS
    'When the reserved figures stop counting. NULL for a row that never held a reservation. A
     reservation past this moment reads as zero, which is what stops an approval whose posting never
     arrives from holding its exposure for ever and shrinking available credit monotonically.';

-- ===========================================================================================
-- PART TWO: an actor that names one identity
-- ===========================================================================================
--
-- The gap this closes. V5__authorization_decision.sql declared actor VARCHAR(8) and
-- api/AuthenticatedActor truncated any longer principal to eight characters. The audit column then
-- stopped distinguishing identities whose names agree in their first eight characters, which is not
-- hypothetical: the shipped monitoring identity is monitor01, nine characters, and was recorded as
-- monitor0. Two identities sharing one recorded actor make the row unusable for the one purpose it
-- has, which is saying who took the decision.
--
-- The width. SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy:L18 is the source field, and eight
-- characters is what a signon identity holds there. This column does not record that field. It
-- records the authenticated principal of an HTTP request, which carddemo.security.users configures
-- and which no source field bounds. Sixty-four characters holds every principal this platform
-- configures with room to spare, and config/SecurityConfig refuses a longer one at start-up rather
-- than letting the first decision discover it.
--
-- The CHECK keeps the character class it had: printable ASCII, one character to sixty-four, so a
-- control character or an empty actor is still refused by the database.

ALTER TABLE authorization_decision
    DROP CONSTRAINT ck_authorization_decision_actor;

ALTER TABLE authorization_decision
    ALTER COLUMN actor TYPE VARCHAR(64);

ALTER TABLE authorization_decision
    ADD CONSTRAINT ck_authorization_decision_actor
        CHECK (actor ~ '^[!-~]{1,64}$');

COMMENT ON COLUMN authorization_decision.actor IS
    'The authenticated principal that asked for this decision, recorded whole. One to sixty-four
     printable characters, which config/SecurityConfig holds at start-up so no configured identity
     is silently shortened into another identity''s audit rows. ANONYMOUS is the sentinel
     domain/AuthenticatedActor records where a request reached the domain with no principal at all.';
