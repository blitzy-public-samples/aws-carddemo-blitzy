package com.aws.carddemo.dto;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit tests for {@link DailyTransaction}, the non-persistent daily-transaction
 * batch-feed DTO of the AWS CardDemo COBOL&rarr;Java/Spring Boot migration.
 *
 * <p><strong>Oracle:</strong> {@code legacy/cpy/CVTRA06Y.cpy} (COBOL record
 * {@code DALYTRAN-RECORD}, fixed record length {@code RECLN = 350}). The copybook
 * declares 13 data fields followed by a trailing {@code FILLER PIC X(20)} pad. The
 * production DTO models the 13 data fields only and deliberately does <em>not</em>
 * expose the reserved 20-byte {@code FILLER} (field widths sum to the record length:
 * 16 + 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 16 + 26 + 26 + 20 = 350).</p>
 *
 * <p><strong>What is verified here (and what is not):</strong> these tests exercise the
 * DTO's own typed field model &mdash; the 13 getters/setters, both constructors, the boxed
 * numeric types ({@code Integer} category code, {@code Long} merchant id), and the
 * parity-critical {@code BigDecimal} money field (Agent Action Plan &sect;0.6.1 /
 * &sect;0.6.4). The byte-offset fixed-width round-trip of the 350-byte {@code DALYTRAN}
 * record is the responsibility of {@code com.aws.carddemo.util.FixedWidthRecordMapper}
 * and is validated in the {@code util} test package; it is intentionally out of scope
 * here to avoid cross-package coupling and duplicate coverage. Accordingly this is a
 * plain JUnit 5 unit test: no Spring context, no database, no Testcontainers, and no
 * reference to {@code FixedWidthRecordMapper}.</p>
 *
 * <p><strong>Decimal fidelity:</strong> every monetary value in these tests is built with
 * the {@link BigDecimal#BigDecimal(String) String constructor}; the
 * {@code double}/{@code float} constructor is never used, which preserves exact scale and
 * proves no floating-point path is exercised.</p>
 */
class DailyTransactionTest {

    // ------------------------------------------------------------------
    // Representative field values shared by the round-trip tests. Each value
    // corresponds one-for-one to a DALYTRAN-RECORD data field (see class Javadoc);
    // widths mirror the copybook (e.g. SOURCE is the 10-wide "POS" source code).
    // ------------------------------------------------------------------

    private static final String ID = "0000000000000001";
    private static final String TYPE_CD = "01";
    private static final Integer CAT_CD = 5;
    private static final String SOURCE = "POS       ";
    private static final String DESCRIPTION = "GROCERY PURCHASE";
    private static final BigDecimal AMOUNT = new BigDecimal("1234.56");
    private static final Long MERCHANT_ID = 123456789L;
    private static final String MERCHANT_NAME = "ACME FOODS";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";
    private static final String CARD_NUM = "4111111111111111";
    private static final String ORIG_TS = "2022-07-18-09.05.03.123456";
    private static final String PROC_TS = "2022-07-18-09.05.04.000000";

    // ==================================================================
    // Phase A - full field round-trip (all 13 data fields)
    // ==================================================================

    @Test
    @DisplayName("all 13 data fields round-trip through the no-arg constructor and setters")
    void all_thirteen_data_fields_round_trip_via_setters() {
        DailyTransaction dt = new DailyTransaction();

        dt.setId(ID);
        dt.setTypeCd(TYPE_CD);
        dt.setCatCd(CAT_CD);
        dt.setSource(SOURCE);
        dt.setDescription(DESCRIPTION);
        dt.setAmount(AMOUNT);
        dt.setMerchantId(MERCHANT_ID);
        dt.setMerchantName(MERCHANT_NAME);
        dt.setMerchantCity(MERCHANT_CITY);
        dt.setMerchantZip(MERCHANT_ZIP);
        dt.setCardNum(CARD_NUM);
        dt.setOrigTs(ORIG_TS);
        dt.setProcTs(PROC_TS);

        assertThat(dt.getId()).isEqualTo(ID);
        assertThat(dt.getTypeCd()).isEqualTo(TYPE_CD);
        assertThat(dt.getCatCd()).isEqualTo(CAT_CD);
        assertThat(dt.getSource()).isEqualTo(SOURCE);
        assertThat(dt.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(dt.getAmount()).isEqualTo(AMOUNT);
        assertThat(dt.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(dt.getMerchantName()).isEqualTo(MERCHANT_NAME);
        assertThat(dt.getMerchantCity()).isEqualTo(MERCHANT_CITY);
        assertThat(dt.getMerchantZip()).isEqualTo(MERCHANT_ZIP);
        assertThat(dt.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(dt.getOrigTs()).isEqualTo(ORIG_TS);
        assertThat(dt.getProcTs()).isEqualTo(PROC_TS);
    }

    @Test
    @DisplayName("all 13 data fields round-trip through the all-args constructor")
    void all_thirteen_data_fields_round_trip_via_all_args_constructor() {
        // Field order follows the byte layout of DALYTRAN-RECORD.
        DailyTransaction dt = new DailyTransaction(
                ID, TYPE_CD, CAT_CD, SOURCE, DESCRIPTION, AMOUNT, MERCHANT_ID,
                MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUM, ORIG_TS, PROC_TS);

        assertThat(dt.getId()).isEqualTo(ID);
        assertThat(dt.getTypeCd()).isEqualTo(TYPE_CD);
        assertThat(dt.getCatCd()).isEqualTo(CAT_CD);
        assertThat(dt.getSource()).isEqualTo(SOURCE);
        assertThat(dt.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(dt.getAmount()).isEqualTo(AMOUNT);
        assertThat(dt.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(dt.getMerchantName()).isEqualTo(MERCHANT_NAME);
        assertThat(dt.getMerchantCity()).isEqualTo(MERCHANT_CITY);
        assertThat(dt.getMerchantZip()).isEqualTo(MERCHANT_ZIP);
        assertThat(dt.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(dt.getOrigTs()).isEqualTo(ORIG_TS);
        assertThat(dt.getProcTs()).isEqualTo(PROC_TS);
    }

    @Test
    @DisplayName("catCd is a boxed Integer and merchantId is a boxed Long")
    void catCd_is_Integer_and_merchantId_is_Long() {
        DailyTransaction dt = new DailyTransaction();
        dt.setCatCd(CAT_CD);
        dt.setMerchantId(MERCHANT_ID);

        // DALYTRAN-CAT-CD PIC 9(04) -> Integer; DALYTRAN-MERCHANT-ID PIC 9(09) -> Long.
        assertThat(dt.getCatCd()).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(dt.getMerchantId()).isInstanceOf(Long.class).isEqualTo(123456789L);
    }

    @Test
    @DisplayName("no FILLER field is modeled - only the 13 DALYTRAN data fields exist")
    void no_filler_field_is_modeled() {
        // The trailing COBOL "FILLER PIC X(20)" (bytes 331-350) is reserved pad that the
        // FixedWidthRecordMapper accounts for; it must never surface as a DTO property.
        // Synthetic members (e.g. the JaCoCo "$jacocoData" field injected during an
        // instrumented test run) are excluded so only real data fields are counted.
        long dataFieldCount = java.util.Arrays.stream(DailyTransaction.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .count();
        assertThat(dataFieldCount).isEqualTo(13L);

        for (var field : DailyTransaction.class.getDeclaredFields()) {
            assertThat(field.getName().toLowerCase())
                    .as("DailyTransaction must not model a FILLER field, but found '%s'", field.getName())
                    .doesNotContain("filler");
        }
    }

    // ==================================================================
    // Phase B - BigDecimal decimal fidelity (parity-critical, AAP 0.6.1)
    // ==================================================================

    @Test
    @DisplayName("amount is a BigDecimal that round-trips a positive value at scale 2")
    void amount_positive_round_trips_with_scale_two() {
        DailyTransaction dt = new DailyTransaction();
        dt.setAmount(new BigDecimal("1234.56"));

        // isEqualTo uses BigDecimal.equals (scale-sensitive), so this simultaneously
        // asserts the numeric value AND that the stored scale is exactly 2.
        assertThat(dt.getAmount())
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("1234.56"));
        assertThat(dt.getAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("amount round-trips a negative signed value exactly - S9(09)V99 is signed")
    void amount_negative_signed_round_trips_exactly() {
        DailyTransaction dt = new DailyTransaction();
        dt.setAmount(new BigDecimal("-9999999.99"));

        assertThat(dt.getAmount()).isEqualTo(new BigDecimal("-9999999.99"));
        assertThat(dt.getAmount().scale()).isEqualTo(2);
        assertThat(dt.getAmount().signum()).isEqualTo(-1);
    }

    @Test
    @DisplayName("amount round-trips the maximum S9(09)V99 magnitude without loss")
    void amount_max_magnitude_boundary_round_trips_without_loss() {
        DailyTransaction dt = new DailyTransaction();
        dt.setAmount(new BigDecimal("999999999.99"));

        // 9 integer digits + 2 fraction digits = precision 11, scale 2 (the S9(09)V99 ceiling).
        assertThat(dt.getAmount()).isEqualTo(new BigDecimal("999999999.99"));
        assertThat(dt.getAmount().scale()).isEqualTo(2);
        assertThat(dt.getAmount().precision()).isEqualTo(11);
    }

    @Test
    @DisplayName("amount getter/setter signatures use BigDecimal, never float or double")
    void amount_getter_and_setter_use_BigDecimal_never_floating_point() throws NoSuchMethodException {
        // Prove the money contract at the type level: the getter returns BigDecimal and the
        // setter accepts BigDecimal. If either used double/float, these reflective lookups
        // would fail (NoSuchMethodException) and fail the test - exactly the guardrail wanted.
        assertThat(DailyTransaction.class.getDeclaredMethod("getAmount").getReturnType())
                .isEqualTo(BigDecimal.class);
        assertThat(DailyTransaction.class.getDeclaredMethod("setAmount", BigDecimal.class))
                .isNotNull();
    }

    // ==================================================================
    // Phase C - construction defaults
    // ==================================================================

    @Test
    @DisplayName("a freshly constructed record has null fields and does not throw")
    void freshly_constructed_record_has_null_fields() {
        DailyTransaction dt = new DailyTransaction();

        assertThat(dt.getId()).isNull();
        assertThat(dt.getTypeCd()).isNull();
        assertThat(dt.getCatCd()).isNull();
        assertThat(dt.getSource()).isNull();
        assertThat(dt.getDescription()).isNull();
        assertThat(dt.getAmount()).isNull();
        assertThat(dt.getMerchantId()).isNull();
        assertThat(dt.getMerchantName()).isNull();
        assertThat(dt.getMerchantCity()).isNull();
        assertThat(dt.getMerchantZip()).isNull();
        assertThat(dt.getCardNum()).isNull();
        assertThat(dt.getOrigTs()).isNull();
        assertThat(dt.getProcTs()).isNull();
    }

    // ==================================================================
    // Phase D - object methods (present on the DTO; light assertions only)
    // ==================================================================

    @Test
    @DisplayName("toString is non-null and uses the safe identity form")
    void toString_is_non_null_identity_form() {
        DailyTransaction dt = new DailyTransaction();
        dt.setId(ID);

        // The DTO uses the safe identity form (class name + identity hash); it surfaces no field content.
        assertThat(dt.toString())
                .isNotNull()
                .startsWith("DailyTransaction@");
    }

    @Test
    @DisplayName("equals and hashCode are based on the id + cardNum business key")
    void equals_and_hashCode_are_based_on_id_and_cardNum() {
        DailyTransaction a = new DailyTransaction();
        a.setId(ID);
        a.setCardNum(CARD_NUM);
        a.setAmount(new BigDecimal("1234.56"));

        // Same id + cardNum but a different non-key field -> still equal (key-based equality).
        DailyTransaction b = new DailyTransaction();
        b.setId(ID);
        b.setCardNum(CARD_NUM);
        b.setAmount(new BigDecimal("0.00"));

        // Different id -> not equal.
        DailyTransaction c = new DailyTransaction();
        c.setId("0000000000000002");
        c.setCardNum(CARD_NUM);

        // Call equals(Object) directly so every branch is exercised (AssertJ's isEqualTo
        // short-circuits on reference identity and would skip the this==o branch).
        assertThat(a.equals(a)).isTrue();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.equals(c)).isFalse();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        // Exercise the null and different-type branches of equals(Object).
        Object notADailyTransaction = "not a daily transaction";
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals(notADailyTransaction)).isFalse();
    }
}
