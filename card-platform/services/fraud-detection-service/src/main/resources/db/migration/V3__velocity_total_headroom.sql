-- Fraud detection service, migration V3.
-- Gives velocity_window.total_amount the width of an accumulator instead of the width of one
-- transaction amount.
--
-- Version 2 is the seed slot the other five services use for their fixture loads. This service
-- seeds nothing, so V2 is absent by design and this is the first follow-up migration.
--
-- V1__schema.sql declared total_amount as NUMERIC(11,2), a precision and a scale both taken from
-- TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10. The scale is right and stays. The precision
-- was not: the column does not hold one amount, it holds every amount magnitude an account
-- accumulates inside one bucket, and VelocityWindowRepository.addAuthorization raises the stored
-- value by the incoming magnitude in the database. Two authorizations at the maximum magnitude the
-- event contract admits therefore reached 1999999999.98 and PostgreSQL refused the statement with
-- "numeric field overflow", which rolled back the whole consumer transaction: no assessment row,
-- no event, and the authorization dead-lettered after its attempts.
--
-- The failure was fail-safe rather than fail-wrong, and it was also unassessable traffic. An
-- account cannot be scored while its bucket is at the ceiling, and a burst of high-value
-- authorizations on one account is the traffic a velocity rule exists to notice, so the ceiling
-- must not sit inside the anomaly the rule detects. Fifteen digits hold ten thousand maximum-
-- magnitude authorizations in one bucket, and authorization_count is an INTEGER, so the count
-- reaches its own ceiling first for any plausible bucket. Why fifteen and not thirteen:
-- card-platform/docs/decision-log.md.
--
-- No copybook field is behind this column's width, so widening it changes no COBOL equivalence
-- claim. Nothing in app/cbl/ counts authorization velocity. The scale stays two, so every stored
-- value and every sum keeps the two fractional digits the amount contract carries, and no stored
-- row changes: PostgreSQL widens a numeric column in place and rewrites no value.
--
-- ck_velocity_window_total_nonnegative is unaffected and still refuses a negative total, which is
-- what keeps the magnitude convention enforced at the table rather than only in the rule.
ALTER TABLE velocity_window ALTER COLUMN total_amount TYPE NUMERIC(15,2);

COMMENT ON COLUMN velocity_window.total_amount IS
    'Amount magnitudes accumulated in this bucket, at the two-digit scale of TRAN-AMT
     PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 and at accumulator width. Fifteen digits hold ten
     thousand maximum-magnitude authorizations in one bucket, so the ceiling sits outside the
     burst the velocity rule exists to notice. A refund raises this total by its magnitude rather
     than lowering it; ck_velocity_window_total_nonnegative enforces that.';
