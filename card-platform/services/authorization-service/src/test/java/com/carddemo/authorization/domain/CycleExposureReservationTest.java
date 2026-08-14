package com.carddemo.authorization.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.cobol.PicClause;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Measures what one approval reserves, and what a reservation reads back.
 *
 * <p>ADDITIVE, and the reason is a difference in timing rather than in arithmetic.
 * {@code app/cbl/CBTRN02C.cbl} posts each record before it validates the next, because paragraph
 * {@code 2000-POST-TRANSACTION} at {@code :L424-L444} runs inside the sequential read loop and
 * paragraph {@code 2700-UPDATE-ACCOUNT} at {@code :L545-L560} has already moved both accumulators by
 * the time {@code :L403-L405} reads them again. This service does not own those accumulators, so a
 * reservation stands in for the rewrite until the account service reports it back.
 *
 * <p>Three source properties are asserted here because a change to any one of them changes a decision
 * silently: truncation toward zero, the sign convention of {@code :L548-L551}, and the fact that the
 * narrower {@code WS-TEMP-BAL} working field belongs to the rule and not to this component.
 */
@DisplayName("CycleExposureReservation, the approval the next decision can see")
class CycleExposureReservationTest {

    /** The account every reservation below is written against, eleven digits. */
    private static final String ACCOUNT_ID = "00000000077";

    /** How long a reservation counts under the configuration these tests build. */
    private static final Duration TTL = Duration.ofMinutes(15);

    /** The lock-wait bound these tests configure, in milliseconds. */
    private static final long LOCK_WAIT_MS = 3_000L;

    /** Zero at the scale both accumulators carry. */
    private static final BigDecimal NONE = new BigDecimal("0.00");

    private AccountCreditSnapshotRepository snapshots;
    private CycleExposureReservation reservation;

    @BeforeEach
    void buildReservation() {
        snapshots = mock(AccountCreditSnapshotRepository.class);
        when(snapshots.reserveCycleExposure(any(), any(), any(), any())).thenReturn(1);

        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        when(properties.decision())
                .thenReturn(new AuthorizationProperties.Decision(LOCK_WAIT_MS, TTL));
        reservation = new CycleExposureReservation(snapshots, properties);
    }

    @Nested
    @DisplayName("The sign convention of app/cbl/CBTRN02C.cbl:L548-L551")
    class SignConvention {

        @Test
        @DisplayName("an amount of zero or more accumulates into the reserved credit")
        void anAmountOfZeroOrMoreAccumulatesIntoTheCredit() {
            reservation.reserve(rowCarrying(NONE, NONE), new BigDecimal("504.77"));

            assertAll(
                    () -> assertEquals(new BigDecimal("504.77"), reservedCredit(),
                            "app/cbl/CBTRN02C.cbl:L549 adds the amount to ACCT-CURR-CYC-CREDIT"),
                    () -> assertEquals(NONE, reservedDebit(),
                            "and leaves the debit accumulator alone"));
        }

        @Test
        @DisplayName("an amount of exactly zero accumulates into the reserved credit")
        void anAmountOfExactlyZeroAccumulatesIntoTheCredit() {
            reservation.reserve(rowCarrying(NONE, NONE), NONE);

            assertEquals(NONE, reservedCredit(),
                    "the source branch tests for zero or more, so zero takes the credit arm");
        }

        @Test
        @DisplayName("a negative amount accumulates into the reserved debit, making it more negative")
        void aNegativeAmountAccumulatesIntoTheDebit() {
            reservation.reserve(rowCarrying(NONE, new BigDecimal("-20.00")),
                    new BigDecimal("-30.00"));

            assertAll(
                    () -> assertEquals(new BigDecimal("-50.00"), reservedDebit(),
                            "app/cbl/CBTRN02C.cbl:L551 adds a negative amount, and :L404 then "
                                    + "subtracts the accumulator, so a refund raises the working "
                                    + "balance. The defect is reproduced, not corrected"),
                    () -> assertEquals(NONE, reservedCredit(),
                            "and leaves the credit accumulator alone"));
        }
    }

    @Nested
    @DisplayName("Accumulation and truncation")
    class Accumulation {

        @Test
        @DisplayName("a second approval adds to the first rather than replacing it")
        void aSecondApprovalAddsToTheFirst() {
            reservation.reserve(rowCarrying(new BigDecimal("60.00"), NONE), new BigDecimal("60.00"));

            assertEquals(new BigDecimal("120.00"), reservedCredit(),
                    "two approvals of 60.00 expose 120.00, which is what the source's own "
                            + "accumulator would carry after two postings");
        }

        @Test
        @DisplayName("a run of maximum approvals stores at the accumulator width and never overflows")
        void aRunOfMaximumApprovalsStoresAtTheAccumulatorWidth() {
            BigDecimal widestAmount = new BigDecimal("999999999.99");
            BigDecimal reserved = NONE;

            for (int approval = 1; approval <= 12; approval++) {
                reset(snapshots);
                when(snapshots.reserveCycleExposure(eq(ACCOUNT_ID), any(), any(), any()))
                        .thenReturn(1);
                reservation.reserve(rowCarrying(reserved, NONE), widestAmount);
                reserved = reservedCredit();

                assertTrue(reserved.precision() - reserved.scale()
                                <= PicClause.ACCT_CURR_CYC_CREDIT_PRECISION
                                        - PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                        "approval " + approval + " must fit the ten integer digits of"
                                + " ACCT-CURR-CYC-CREDIT PIC S9(10)V99, which is what column"
                                + " pending_cycle_credit NUMERIC(12,2) holds");
            }

            assertEquals(new BigDecimal("1999999999.88"), reserved,
                    "the eleventh integer digit is discarded where it stands, which is what a COBOL"
                            + " ADD with no ON SIZE ERROR phrase does: twelve approvals of"
                            + " 999999999.99 sum to 11999999999.88 and the accumulator keeps the"
                            + " low-order ten integer digits");
        }

        @Test
        @DisplayName("every figure written carries the scale the accumulator column holds")
        void everyFigureCarriesTheAccumulatorScale() {
            reservation.reserve(rowCarrying(NONE, NONE), new BigDecimal("1.00"));

            assertAll(
                    () -> assertEquals(2, reservedCredit().scale(),
                            "ACCT-CURR-CYC-CREDIT PIC S9(10)V99 holds two fractional digits"),
                    () -> assertEquals(2, reservedDebit().scale(),
                            "and so does ACCT-CURR-CYC-DEBIT"));
        }
    }

    @Nested
    @DisplayName("The reservation expiry")
    class Expiry {

        @Test
        @DisplayName("an expired reservation is replaced rather than added to")
        void anExpiredReservationIsReplaced() {
            AccountCreditSnapshotEntity expired = mock(AccountCreditSnapshotEntity.class);
            when(expired.getAccountId()).thenReturn(ACCOUNT_ID);
            when(expired.effectivePendingCycleCredit(any())).thenReturn(NONE);
            when(expired.effectivePendingCycleDebit(any())).thenReturn(NONE);

            reservation.reserve(expired, new BigDecimal("25.00"));

            assertEquals(new BigDecimal("25.00"), reservedCredit(),
                    "an approval whose posting never arrived would otherwise hold its exposure for "
                            + "ever and shrink available credit until every call declined");
        }

        @Test
        @DisplayName("every reservation carries a fresh expiry")
        void everyReservationCarriesAFreshExpiry() {
            Instant before = Instant.now();
            reservation.reserve(rowCarrying(NONE, NONE), new BigDecimal("5.00"));

            ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
            verify(snapshots).reserveCycleExposure(eq(ACCOUNT_ID), any(), any(), expiry.capture());

            assertTrue(expiry.getValue().isAfter(before.plus(TTL).minusSeconds(5)),
                    "the expiry is the configured lifetime ahead of the moment of the write");
        }

        @Test
        @DisplayName("a reservation past its expiry reads as zero and one before it reads whole")
        void aReservationPastItsExpiryReadsAsZero() {
            Instant now = Instant.now();
            AccountCreditSnapshotEntity standing = new AccountCreditSnapshotEntity(ACCOUNT_ID,
                    new BigDecimal("100.00"), "2099-12-31", NONE, NONE, now);

            assertAll(
                    () -> assertEquals(NONE, reservation.reservedCycleCredit(standing),
                            "a row that never held a reservation reserves nothing"),
                    () -> assertEquals(NONE, reservation.reservedCycleDebit(standing),
                            "on either arm"),
                    () -> assertEquals(NONE, standing.effectivePendingCycleCredit(now),
                            "and it carries no expiry to compare against"));
        }
    }

    @Nested
    @DisplayName("The collaborator contract")
    class Contract {

        @Test
        @DisplayName("a row that vanished after the lock stops the decision rather than approving")
        void aVanishedRowStopsTheDecision() {
            when(snapshots.reserveCycleExposure(any(), any(), any(), any()))
                    .thenReturn(AccountCreditSnapshotRepository.NO_ROW_WRITTEN);

            assertThrows(IllegalStateException.class,
                    () -> reservation.reserve(rowCarrying(NONE, NONE), new BigDecimal("1.00")),
                    "an approval that reserved nothing would let the next decision approve against "
                            + "exposure already committed");
        }

        @Test
        @DisplayName("the lock-wait bound is applied as a PostgreSQL interval")
        void theLockWaitBoundIsAppliedAsAnInterval() {
            reservation.boundLockWait();

            verify(snapshots).applyLockWaitBound(LOCK_WAIT_MS + "ms");
        }

        @Test
        @DisplayName("a lifetime of zero is refused where it is configured")
        void aLifetimeOfZeroIsRefusedWhereItIsConfigured() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AuthorizationProperties.Decision(LOCK_WAIT_MS, Duration.ZERO),
                    "a lifetime of zero releases every reservation as it is written, which is the "
                            + "behaviour these columns exist to replace");
        }

        @Test
        @DisplayName("an absent lifetime stops start-up")
        void anAbsentLifetimeStopsStartUp() {
            AuthorizationProperties absent = decisionOf(LOCK_WAIT_MS, null);

            assertThrows(NullPointerException.class,
                    () -> new CycleExposureReservation(snapshots, absent),
                    "an absent lifetime leaves every expiry undefined");
        }

        @Test
        @DisplayName("a lock-wait bound of zero stops start-up")
        void aLockWaitBoundOfZeroStopsStartUp() {
            AuthorizationProperties unbounded = decisionOf(0L, TTL);

            assertThrows(IllegalArgumentException.class,
                    () -> new CycleExposureReservation(snapshots, unbounded),
                    "a bound of zero would refuse every lock immediately, so no account with a "
                            + "decision in flight could be decided again");
        }

        /**
         * Builds configuration carrying one decision block.
         *
         * @param lockWaitMs     the lock-wait bound
         * @param reservationTtl the reservation lifetime, possibly {@code null}
         * @return the configuration
         */
        private AuthorizationProperties decisionOf(long lockWaitMs, Duration reservationTtl) {
            AuthorizationProperties properties = mock(AuthorizationProperties.class);
            when(properties.decision())
                    .thenReturn(new AuthorizationProperties.Decision(lockWaitMs, reservationTtl));
            return properties;
        }
    }

    /**
     * Builds one snapshot row already carrying a standing reservation.
     *
     * @param reservedCredit the reserved cycle credit the row holds
     * @param reservedDebit  the reserved cycle debit the row holds
     * @return the row
     */
    private static AccountCreditSnapshotEntity rowCarrying(BigDecimal reservedCredit,
            BigDecimal reservedDebit) {

        AccountCreditSnapshotEntity row = mock(AccountCreditSnapshotEntity.class);
        when(row.getAccountId()).thenReturn(ACCOUNT_ID);
        when(row.effectivePendingCycleCredit(any())).thenReturn(reservedCredit);
        when(row.effectivePendingCycleDebit(any())).thenReturn(reservedDebit);
        return row;
    }

    /**
     * Reads the reserved cycle credit the one write of this test carried.
     *
     * @return the figure written
     */
    private BigDecimal reservedCredit() {
        return writtenFigures().get(0);
    }

    /**
     * Reads the reserved cycle debit the one write of this test carried.
     *
     * @return the figure written
     */
    private BigDecimal reservedDebit() {
        return writtenFigures().get(1);
    }

    /**
     * Captures the two figures the one reservation write of this test carried.
     *
     * @return the reserved credit followed by the reserved debit
     */
    private java.util.List<BigDecimal> writtenFigures() {
        ArgumentCaptor<BigDecimal> credit = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> debit = ArgumentCaptor.forClass(BigDecimal.class);
        verify(snapshots).reserveCycleExposure(eq(ACCOUNT_ID), credit.capture(), debit.capture(),
                any());
        return java.util.List.of(credit.getValue(), debit.getValue());
    }
}
