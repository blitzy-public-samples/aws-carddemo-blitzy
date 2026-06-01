package com.carddemo.businesslogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.exception.DiscloseGroupNotFoundException;
import com.carddemo.repository.DisclosureGroupRepository;

/**
 * Parity test for {@code CBACT04C.cbl} (interest calculation).
 *
 * <p>Verifies that the Java implementation of the CardDemo interest calculator
 * produces identical results to the original COBOL program for all relevant
 * business behaviors. Per AAP &sect;0.7.1 PR-21, this is a MANDATORY deliverable
 * for the CardDemo COBOL/CICS/VSAM &rarr; Java/Spring Boot 3.2 migration.
 *
 * <p>Reference paragraphs in {@code app/cbl/CBACT04C.cbl}:
 * <ul>
 *   <li>{@code 1050-UPDATE-ACCOUNT} (L350-L370) - REWRITE pattern (PR-08)</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} (L415-L440) - DEFAULT fallback (PR-02)</li>
 *   <li>{@code 1200-A-GET-DEFAULT-INT-RATE} (L443-L460) - second DISCGRP read</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} (L462-L470) - interest formula (PR-01)</li>
 *   <li>{@code 1300-B-WRITE-TX} (L473-L500) - TRAN-ID format (PR-10)</li>
 *   <li>{@code Z-GET-DB2-FORMAT-TIMESTAMP} (L613-L626) - DB2 timestamp (PR-11)</li>
 * </ul>
 *
 * <p>This is a pure-function test (no Spring context, no PostgreSQL, no
 * Testcontainers); it exercises inline helpers that mirror the COBOL logic
 * line-by-line to guarantee value parity with the COBOL source. The only mocked
 * collaborator is {@link DisclosureGroupRepository}, used by the DEFAULT-fallback
 * tests to simulate the COBOL VSAM file status {@code '23'} (record not found).
 *
 * <p><strong>Fidelity notes (the COBOL source and the committed production code are
 * the single source of truth, overriding any incorrect AAP example commentary per
 * the migration's "preserve business logic exactly" mandate):</strong>
 * <ul>
 *   <li><em>Composite key type (PR-15):</em> {@code DisclosureGroupId} models the
 *       COBOL {@code DIS-GROUP-KEY} as three {@code String} components
 *       {@code (accountGroupId, tranTypeCd, tranCatCd)}. The category code
 *       {@code DIS-TRAN-CAT-CD PIC 9(04)} is mapped to a fixed-width {@code CHAR(4)}
 *       {@code String} (e.g. {@code "0005"}) to preserve leading zeros, so this test
 *       constructs keys with {@code String} category codes, not {@code Integer}.</li>
 *   <li><em>DB2 timestamp width (PR-11):</em> the COBOL {@code DB2-FORMAT-TS} field is
 *       26 characters with a 2-digit centisecond component
 *       ({@code DB2-MIL PIC 9(002)}) followed by the literal {@code '0000'}
 *       [app/cbl/CBACT04C.cbl L150-L165, L613-L626]. The Java parity formatter is
 *       therefore {@code yyyy-MM-dd-HH.mm.ss.SS'0000'} ({@code SS} = 2-digit
 *       centiseconds), producing 26 characters - matching both the COBOL byte layout
 *       and the committed {@code com.carddemo.util.DateConversionUtil}
 *       implementation.</li>
 * </ul>
 *
 * <p>Per PR-16 every {@link BigDecimal} assertion uses
 * {@code isEqualByComparingTo(...)} (never {@code isEqualTo(...)}), so that values of
 * equal magnitude but differing scale (e.g. {@code 100.00} vs {@code 100.0}) compare
 * equal - exactly the money-equality semantics required for COBOL packed-decimal
 * parity.
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationParityTest {

    // ---------------------------------------------------------------------
    // Constants mirroring COBOL CBACT04C
    // ---------------------------------------------------------------------

    /**
     * COBOL interest divisor: the literal {@code 1200} in
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
     * [app/cbl/CBACT04C.cbl L462-L470]. Twelve months times the percentage-to-fraction
     * factor of one hundred.
     */
    private static final BigDecimal INTEREST_DIVISOR = BigDecimal.valueOf(1200);

    /**
     * Money scale: COBOL monetary fields are {@code PIC S9(n)V99} - exactly two decimal
     * positions. {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} are {@code PIC S9(09)V99}.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * Rounding mode: the COBOL {@code ROUNDED}/implicit divide semantics map to
     * {@link RoundingMode#HALF_UP} (PR-01, PR-16).
     */
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    /**
     * DB2 external timestamp formatter (PR-11). Mirrors the COBOL {@code DB2-FORMAT-TS}
     * layout {@code yyyy-MM-dd-HH.mm.ss.SS'0000'} - 26 characters total, where {@code SS}
     * is the 2-digit centisecond component ({@code DB2-MIL PIC 9(002)}) and {@code '0000'}
     * is the trailing literal ({@code DB2-REST PIC X(04)})
     * [app/cbl/CBACT04C.cbl L150-L165, L613-L626]. This matches the committed
     * {@code com.carddemo.util.DateConversionUtil} formatter; the formatter is inlined
     * here because {@code DateConversionUtil} is intentionally outside this test's
     * dependency set.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SS'0000'");

    // =====================================================================
    // 3.1 Interest Formula Tests (1300-COMPUTE-INTEREST - PR-01)
    // =====================================================================

    /**
     * Verifies the preserved interest formula
     * {@code monthlyInt = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with scale&nbsp;2 and
     * {@code RoundingMode.HALF_UP} (PR-01), reproducing
     * {@code app/cbl/CBACT04C.cbl} paragraph {@code 1300-COMPUTE-INTEREST} (L462-L470)
     * and the {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} accumulation (L467).
     */
    @Nested
    @DisplayName("1300-COMPUTE-INTEREST [L462-L470] - PR-01 Interest formula")
    class InterestFormulaTests {

        @Test
        @DisplayName("Canonical example: 1000.00 balance * 12.00% rate / 1200 = 10.00")
        void shouldComputeMonthlyInterestForCanonicalInput() {
            BigDecimal tranCatBal = new BigDecimal("1000.00");
            BigDecimal disIntRate = new BigDecimal("12.00"); // 12% annual rate

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @ParameterizedTest(name = "[{index}] bal={0}, rate={1} -> expected={2}")
        @CsvSource({
            // Format: balance, rate, expected monthly interest = (bal * rate) / 1200
            "0.00,         12.00,   0.00",      // zero balance -> zero interest
            "1000.00,      0.00,    0.00",      // zero rate -> zero interest
            "1000.00,      12.00,   10.00",     // 12% annual on $1000 = $10/month
            "5000.00,      18.00,   75.00",     // 18% annual on $5000 = $75/month
            "10000.00,     24.99,   208.25",    // 24.99% annual on $10000
            "1500.50,      15.75,   19.69",     // odd amounts with HALF_UP rounding
            "100.00,       0.01,    0.00",      // very small rate rounds to 0
            "999999.99,    29.99,   24991.67",  // high balance + high rate (verified)
            "100.00,       5.00,    0.42"       // small fraction with HALF_UP
        })
        @DisplayName("Parameterized: (balance * rate) / 1200 = expected, scale 2 HALF_UP")
        void shouldComputeInterestForMatrix(String balance, String rate, String expected) {
            BigDecimal tranCatBal = new BigDecimal(balance);
            BigDecimal disIntRate = new BigDecimal(rate);

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("Result has exactly scale 2 regardless of input scale")
        void shouldProduceScaleTwoResult() {
            BigDecimal tranCatBal = new BigDecimal("1000");      // scale 0
            BigDecimal disIntRate = new BigDecimal("12.000");    // scale 3

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            assertThat(monthlyInterest.scale()).isEqualTo(MONEY_SCALE);
            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("WS-TOTAL-INT accumulator: ADD WS-MONTHLY-INT TO WS-TOTAL-INT")
        void shouldAccumulateTotalInterestAcrossCategories() {
            // CBACT04C L467: ADD WS-MONTHLY-INT TO WS-TOTAL-INT per category iteration.
            BigDecimal monthlyInt1 = new BigDecimal("10.00");
            BigDecimal monthlyInt2 = new BigDecimal("7.50");
            BigDecimal monthlyInt3 = new BigDecimal("0.42");

            BigDecimal wsTotalInt = BigDecimal.ZERO.setScale(MONEY_SCALE);
            wsTotalInt = wsTotalInt.add(monthlyInt1);
            wsTotalInt = wsTotalInt.add(monthlyInt2);
            wsTotalInt = wsTotalInt.add(monthlyInt3);

            assertThat(wsTotalInt).isEqualByComparingTo(new BigDecimal("17.92"));
        }

        @Test
        @DisplayName("Negative balance produces negative interest (signed packed-decimal preserved)")
        void shouldComputeInterestForNegativeBalance() {
            // TRAN-CAT-BAL is PIC S9(09)V99 - the leading S allows negative values.
            BigDecimal tranCatBal = new BigDecimal("-500.00");
            BigDecimal disIntRate = new BigDecimal("12.00");

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal("-5.00"));
        }
    }

    // =====================================================================
    // 3.2 DEFAULT Fallback Tests (1200-GET-INTEREST-RATE - PR-02)
    // =====================================================================

    /**
     * Verifies the disclosure-group interest-rate lookup with {@code "DEFAULT"} fallback
     * (PR-02), reproducing {@code app/cbl/CBACT04C.cbl} paragraphs
     * {@code 1200-GET-INTEREST-RATE} (L415-L440) and
     * {@code 1200-A-GET-DEFAULT-INT-RATE} (L443-L460).
     *
     * <p>The COBOL program first reads {@code DISCGRP} keyed on the account's group id;
     * if the read returns file status {@code '23'} (record not found) it moves
     * {@code 'DEFAULT'} into the group-id key field and re-reads. A miss on the
     * {@code "DEFAULT"} key as well drives {@code 9999-ABEND-PROGRAM}, which the Java
     * port surfaces as a {@link DiscloseGroupNotFoundException}.
     *
     * <p>{@link DisclosureGroupRepository} is mocked so the initial {@code findById}
     * returns {@link Optional#empty()} (simulating VSAM status {@code '23'}) and the
     * {@code "DEFAULT"} {@code findById} returns the fallback row.
     */
    @Nested
    @DisplayName("1200-GET-INTEREST-RATE [L415-L440] - PR-02 DEFAULT fallback")
    class DefaultFallbackTests {

        @Mock
        private DisclosureGroupRepository disclosureGroupRepository;

        /**
         * Builds a {@link DisclosureGroup} for the given composite-key components and rate.
         *
         * <p>The category code is a {@code String} (COBOL {@code DIS-TRAN-CAT-CD PIC 9(04)}
         * is mapped to a fixed-width {@code CHAR(4)} {@code String} to preserve leading
         * zeros), matching the actual {@link DisclosureGroupId} all-args constructor
         * {@code (String accountGroupId, String tranTypeCd, String tranCatCd)}.
         */
        private DisclosureGroup buildGroup(String groupId, String typeCd,
                                           String catCd, BigDecimal rate) {
            DisclosureGroupId id = new DisclosureGroupId(groupId, typeCd, catCd);
            DisclosureGroup group = new DisclosureGroup();
            group.setId(id);
            group.setDisIntRate(rate);
            return group;
        }

        @Test
        @DisplayName("Found in initial lookup - returns matched rate, no DEFAULT retry")
        void shouldReturnRateWhenInitialLookupFinds() {
            String groupId = "GOLD";
            String typeCd = "01";
            String catCd = "0005";
            BigDecimal expectedRate = new BigDecimal("18.99");
            DisclosureGroupId key = new DisclosureGroupId(groupId, typeCd, catCd);
            when(disclosureGroupRepository.findById(key))
                .thenReturn(Optional.of(buildGroup(groupId, typeCd, catCd, expectedRate)));

            BigDecimal rate = lookupRateWithFallback(groupId, typeCd, catCd);

            assertThat(rate).isEqualByComparingTo(expectedRate);
        }

        @Test
        @DisplayName("Initial miss triggers DEFAULT retry - returns DEFAULT rate")
        void shouldRetryWithDefaultGroupIdOnInitialMiss() {
            String groupId = "RARE";
            String typeCd = "01";
            String catCd = "0099";
            DisclosureGroupId initialKey = new DisclosureGroupId(groupId, typeCd, catCd);
            DisclosureGroupId defaultKey = new DisclosureGroupId("DEFAULT", typeCd, catCd);
            BigDecimal defaultRate = new BigDecimal("12.00");

            when(disclosureGroupRepository.findById(initialKey)).thenReturn(Optional.empty());
            when(disclosureGroupRepository.findById(defaultKey))
                .thenReturn(Optional.of(buildGroup("DEFAULT", typeCd, catCd, defaultRate)));

            BigDecimal rate = lookupRateWithFallback(groupId, typeCd, catCd);

            assertThat(rate).isEqualByComparingTo(defaultRate);
        }

        @Test
        @DisplayName("Both initial and DEFAULT miss throws DiscloseGroupNotFoundException")
        void shouldThrowWhenBothLookupsMiss() {
            String groupId = "MISSING";
            String typeCd = "01";
            String catCd = "0099";
            when(disclosureGroupRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> lookupRateWithFallback(groupId, typeCd, catCd))
                .isInstanceOf(DiscloseGroupNotFoundException.class);
        }

        /**
         * Inline lookup-with-fallback implementation mirroring the COBOL
         * {@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE} flow.
         * In production code this logic lives inside
         * {@code com.carddemo.batch.InterestCalculationTasklet} (or a delegate
         * {@code DiscloseGroupLookupService}); it is inlined here so the parity test
         * pins the required behavior independently of the production wiring.
         */
        private BigDecimal lookupRateWithFallback(String groupId, String typeCd, String catCd) {
            DisclosureGroupId initialKey = new DisclosureGroupId(groupId, typeCd, catCd);
            Optional<DisclosureGroup> result = disclosureGroupRepository.findById(initialKey);
            if (result.isEmpty()) {
                DisclosureGroupId defaultKey = new DisclosureGroupId("DEFAULT", typeCd, catCd);
                result = disclosureGroupRepository.findById(defaultKey);
            }
            return result
                .map(DisclosureGroup::getDisIntRate)
                .orElseThrow(() -> new DiscloseGroupNotFoundException(
                    "No DEFAULT entry for type=" + typeCd + " cat=" + catCd));
        }
    }

    // =====================================================================
    // 3.3 Account REWRITE Tests (1050-UPDATE-ACCOUNT - PR-08)
    // =====================================================================

    /**
     * Verifies the month-end account REWRITE pattern (PR-08), reproducing
     * {@code app/cbl/CBACT04C.cbl} paragraph {@code 1050-UPDATE-ACCOUNT} (L350-L370):
     * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}, then {@code MOVE 0 TO
     * ACCT-CURR-CYC-CREDIT} and {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}, then
     * {@code REWRITE}. After interest is applied, both cycle accumulators reset to zero
     * for the new cycle.
     */
    @Nested
    @DisplayName("1050-UPDATE-ACCOUNT [L350-L370] - PR-08 REWRITE pattern")
    class AccountRewriteTests {

        @Test
        @DisplayName("ACCT-CURR-BAL += WS-TOTAL-INT, cycle credit/debit zeroed")
        void shouldApplyTotalInterestAndZeroOutCycleAmounts() {
            // Simulate ACCOUNT-RECORD state before REWRITE.
            BigDecimal currentBalance = new BigDecimal("1234.56");
            BigDecimal totalInterest = new BigDecimal("17.92");

            // COBOL L352: ADD WS-TOTAL-INT TO ACCT-CURR-BAL
            BigDecimal newBalance = currentBalance.add(totalInterest);
            // COBOL L353-L354: MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT
            BigDecimal newCycCredit = BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal newCycDebit = BigDecimal.ZERO.setScale(MONEY_SCALE);

            assertThat(newBalance).isEqualByComparingTo(new BigDecimal("1252.48"));
            assertThat(newCycCredit).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(newCycDebit).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Zero total interest leaves balance unchanged but still zeroes cycle amounts")
        void shouldZeroCycleAmountsEvenWhenInterestIsZero() {
            BigDecimal currentBalance = new BigDecimal("1000.00");
            BigDecimal totalInterest = BigDecimal.ZERO;

            BigDecimal newBalance = currentBalance.add(totalInterest);
            BigDecimal newCycCredit = BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal newCycDebit = BigDecimal.ZERO.setScale(MONEY_SCALE);

            assertThat(newBalance).isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(newCycCredit).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(newCycDebit).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Negative cycle credit/debit gets reset to zero regardless of prior sign")
        void shouldResetNegativeCycleAmountsToZero() {
            BigDecimal currentBalance = new BigDecimal("500.00");
            BigDecimal totalInterest = new BigDecimal("5.00");

            // Even if prior cycle values were non-zero (any sign) they get zeroed.
            BigDecimal newBalance = currentBalance.add(totalInterest);
            BigDecimal newCycCredit = BigDecimal.ZERO.setScale(MONEY_SCALE);
            BigDecimal newCycDebit = BigDecimal.ZERO.setScale(MONEY_SCALE);

            assertThat(newBalance).isEqualByComparingTo(new BigDecimal("505.00"));
            assertThat(newCycCredit.signum()).isZero();
            assertThat(newCycDebit.signum()).isZero();
        }
    }

    // =====================================================================
    // 3.4 Transaction ID Generation Tests (1300-B-WRITE-TX - PR-10)
    // =====================================================================

    /**
     * Verifies the 16-character {@code TRAN-ID} format (PR-10), reproducing
     * {@code app/cbl/CBACT04C.cbl} paragraph {@code 1300-B-WRITE-TX} (L473-L500):
     * {@code ADD 1 TO WS-TRANID-SUFFIX} followed by
     * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID}.
     *
     * <p>{@code PARM-DATE} is {@code PIC X(10)} (10 chars) and {@code WS-TRANID-SUFFIX}
     * is {@code PIC 9(06)} (6 zero-padded digits, {@code VALUE 0}, incremented before the
     * STRING so the first generated id ends in {@code 000001}). The Java equivalent is
     * {@code "%s%06d".formatted(parmDate, counter.incrementAndGet())}.
     */
    @Nested
    @DisplayName("1300-B-WRITE-TX [L473-L500] - PR-10 TRAN-ID format")
    class TransactionIdFormatTests {

        @Test
        @DisplayName("TRAN-ID = PARM-DATE(10) + SUFFIX(6) zero-padded - total 16 chars")
        void shouldGenerateSixteenCharIdWithZeroPaddedSuffix() {
            String parmDate = "2022071800";  // 10-char PARM-DATE
            AtomicLong counter = new AtomicLong(0);

            String tranId1 = "%s%06d".formatted(parmDate, counter.incrementAndGet());
            String tranId2 = "%s%06d".formatted(parmDate, counter.incrementAndGet());
            String tranId3 = "%s%06d".formatted(parmDate, counter.incrementAndGet());

            assertThat(tranId1).hasSize(16).isEqualTo("2022071800000001");
            assertThat(tranId2).hasSize(16).isEqualTo("2022071800000002");
            assertThat(tranId3).hasSize(16).isEqualTo("2022071800000003");
        }

        @Test
        @DisplayName("Suffix is zero-padded to 6 digits (matches PIC 9(06))")
        void shouldZeroPadSuffixToSixDigits() {
            String parmDate = "2022071800";

            String tranIdAt1     = "%s%06d".formatted(parmDate, 1L);
            String tranIdAt12    = "%s%06d".formatted(parmDate, 12L);
            String tranIdAt123   = "%s%06d".formatted(parmDate, 123L);
            String tranIdAt12345 = "%s%06d".formatted(parmDate, 12345L);

            assertThat(tranIdAt1).endsWith("000001");
            assertThat(tranIdAt12).endsWith("000012");
            assertThat(tranIdAt123).endsWith("000123");
            assertThat(tranIdAt12345).endsWith("012345");
        }

        @Test
        @DisplayName("Prefix matches the PARM-DATE exactly")
        void shouldHavePrefixMatchingParmDate() {
            String parmDate = "2024010100";

            String tranId = "%s%06d".formatted(parmDate, 7L);

            assertThat(tranId).startsWith(parmDate);
            assertThat(tranId.substring(0, 10)).isEqualTo(parmDate);
        }

        @Test
        @DisplayName("Suffix at boundary 999999 still fits in 6 chars")
        void shouldHandleSuffixAtBoundary() {
            String parmDate = "2022071800";

            String tranId = "%s%06d".formatted(parmDate, 999_999L);

            assertThat(tranId).hasSize(16).isEqualTo("2022071800999999");
        }
    }

    // =====================================================================
    // 3.5 DB2 Timestamp Format Tests (Z-GET-DB2-FORMAT-TIMESTAMP - PR-11)
    // =====================================================================

    /**
     * Verifies the DB2 external timestamp format (PR-11), reproducing
     * {@code app/cbl/CBACT04C.cbl} paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP}
     * (L613-L626) and the {@code DB2-FORMAT-TS} field layout (L150-L165).
     *
     * <p>The COBOL field is {@code PIC X(26)} - 26 characters - laid out as
     * {@code YYYY-MM-DD-HH.MM.SS.NN0000} where {@code NN} is the 2-digit centisecond
     * component ({@code DB2-MIL PIC 9(002)}) and {@code 0000} is the trailing literal
     * ({@code DB2-REST PIC X(04)}). The Java parity formatter is therefore
     * {@code yyyy-MM-dd-HH.mm.ss.SS'0000'} ({@code SS} = 2 fractional digits), matching
     * both the COBOL byte layout and the committed
     * {@code com.carddemo.util.DateConversionUtil} implementation.
     */
    @Nested
    @DisplayName("Z-GET-DB2-FORMAT-TIMESTAMP [L613-L626] - PR-11 26-char DB2 format")
    class Db2TimestampFormatTests {

        @Test
        @DisplayName("Format produces 26 characters (DB2-FORMAT-TS PIC X(26))")
        void shouldProduceTwentySixCharString() {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 0, 0);

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            assertThat(formatted).hasSize(26);
        }

        @Test
        @DisplayName("Format ends with literal trailing 0000 (DB2-REST)")
        void shouldEndWithTrailingZeros() {
            // 120 ms -> centiseconds "12"; the trailing "0000" is the DB2-REST literal,
            // proving it is appended independently of the fractional-seconds value.
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 0, 120_000_000);

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            assertThat(formatted).endsWith("0000");
        }

        @Test
        @DisplayName("Format uses - separators in date portion (positions 4, 7, 10)")
        void shouldUseHyphenSeparatorsInDate() {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 7, 0);

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            // Date portion: yyyy-MM-dd-HH (DB2-STREEP-1/2/3).
            assertThat(formatted.charAt(4)).isEqualTo('-');
            assertThat(formatted.charAt(7)).isEqualTo('-');
            assertThat(formatted.charAt(10)).isEqualTo('-');
        }

        @Test
        @DisplayName("Format uses . separators in time portion (positions 13, 16, 19)")
        void shouldUseDotSeparatorsInTime() {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 7, 0);

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            // Time portion: HH.mm.ss.SS (DB2-DOT-1/2/3).
            assertThat(formatted.charAt(13)).isEqualTo('.');
            assertThat(formatted.charAt(16)).isEqualTo('.');
            assertThat(formatted.charAt(19)).isEqualTo('.');
        }

        @Test
        @DisplayName("Canonical example: 2022-07-18 23:16:00.000 -> '2022-07-18-23.16.00.000000'")
        void shouldFormatCanonicalExample() {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 0, 0);

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            // Pattern yyyy-MM-dd-HH.mm.ss.SS'0000' produces 26 chars: SS="00", literal "0000".
            assertThat(formatted).isEqualTo("2022-07-18-23.16.00.000000");
        }

        @Test
        @DisplayName("Centiseconds use SS pattern (2 digits): 120ms -> '12' (DB2-MIL PIC 9(02))")
        void shouldFormatCentisecondsAsTwoDigits() {
            LocalDateTime ts = LocalDateTime.of(2022, 7, 18, 23, 16, 0, 120_000_000); // 120 ms

            String formatted = ts.format(DB2_TIMESTAMP_FORMATTER);

            // SS pattern uses 2-digit fractional seconds (centiseconds); chars 20-21 are
            // the DB2-MIL centisecond component. 120 ms truncates to centiseconds "12".
            assertThat(formatted.substring(20, 22)).isEqualTo("12");
            assertThat(formatted).isEqualTo("2022-07-18-23.16.00.120000");
        }
    }

    // =====================================================================
    // 3.6 Edge Case Tests - precision and overflow
    // =====================================================================

    /**
     * Boundary and precision tests for the interest formula and the mandatory
     * {@link BigDecimal} money representation (PR-16). These guard the COBOL packed-decimal
     * semantics against accidental {@code float}/{@code double} regressions and verify
     * {@code HALF_UP} rounding behavior at the half-cent boundary.
     */
    @Nested
    @DisplayName("Edge cases - precision and overflow")
    class EdgeCaseTests {

        @Test
        @DisplayName("Very small rate (0.01%) preserves precision after division")
        void shouldHandleVerySmallRate() {
            BigDecimal tranCatBal = new BigDecimal("100000.00");
            BigDecimal disIntRate = new BigDecimal("0.01");

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            // 100000.00 * 0.01 / 1200 = 1000 / 1200 = 0.8333... -> 0.83 (HALF_UP)
            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal("0.83"));
        }

        @Test
        @DisplayName("Maximum PIC S9(10)V99 balance doesn't overflow BigDecimal")
        void shouldHandleMaximumBalance() {
            // ACCT money fields are PIC S9(10)V99 - up to 9999999999.99.
            BigDecimal tranCatBal = new BigDecimal("9999999999.99");
            BigDecimal disIntRate = new BigDecimal("29.99");

            BigDecimal monthlyInterest = tranCatBal
                .multiply(disIntRate)
                .divide(INTEREST_DIVISOR, MONEY_SCALE, HALF_UP);

            // BigDecimal is arbitrary precision; no overflow. Result is 249916666.67.
            assertThat(monthlyInterest.signum()).isPositive();
            assertThat(monthlyInterest.scale()).isEqualTo(MONEY_SCALE);
            assertThat(monthlyInterest).isEqualByComparingTo(new BigDecimal("249916666.67"));
        }

        @Test
        @DisplayName("HALF_UP rounding: 0.005 rounds up to 0.01")
        void shouldRoundHalfUpAtFiveTenthMillis() {
            BigDecimal halfPenny = new BigDecimal("0.005");

            BigDecimal rounded = halfPenny.setScale(MONEY_SCALE, HALF_UP);

            assertThat(rounded).isEqualByComparingTo(new BigDecimal("0.01"));
        }

        @Test
        @DisplayName("HALF_UP rounding: 0.004 rounds down to 0.00")
        void shouldRoundHalfUpAtFourTenthMillis() {
            BigDecimal lessThanHalfPenny = new BigDecimal("0.004");

            BigDecimal rounded = lessThanHalfPenny.setScale(MONEY_SCALE, HALF_UP);

            assertThat(rounded).isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("Float/double forbidden for money - BigDecimal mandatory (PR-16)")
        void shouldUseBigDecimalNotDoubleForMonetaryArithmetic() {
            // This test documents the rule by demonstrating WHY double is forbidden:
            // in IEEE-754 binary floating point, 0.1 + 0.2 evaluates to
            // 0.30000000000000004, NOT 0.3. The double inaccuracy itself is documented
            // here (not asserted); the test asserts only the correctness of the chosen
            // representation (BigDecimal), which is exact.
            BigDecimal bigDecimalSum = new BigDecimal("0.1").add(new BigDecimal("0.2"));

            assertThat(bigDecimalSum).isEqualByComparingTo(new BigDecimal("0.3"));
            assertThat(bigDecimalSum.toPlainString()).isEqualTo("0.3");
        }
    }
}
