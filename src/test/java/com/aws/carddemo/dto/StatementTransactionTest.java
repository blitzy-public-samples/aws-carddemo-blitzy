package com.aws.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link StatementTransaction}.
 *
 * <p><strong>Origin / oracle:</strong> {@code legacy/cpy/COSTM01.CPY} (COBOL record
 * {@code TRNX-RECORD}, "Transaction altered Layout for use in reporting"). {@code TRNX-RECORD}
 * is a fixed-width 350-byte layout consumed by the statement-generation batch programs
 * {@code CBSTM03A}/{@code CBSTM03B}. Its trailing {@code FILLER PIC X(20)} (bytes 331-350) is
 * reserved pad and is intentionally <em>not</em> modeled as a property; the 13 modeled data
 * fields are asserted here.</p>
 *
 * <p><strong>Composite key note.</strong> The COBOL group {@code TRNX-KEY} places
 * {@code TRNX-CARD-NUM} (bytes 1-16) <em>before</em> {@code TRNX-ID} (bytes 17-32). This is the
 * reverse of {@code DALYTRAN-RECORD} (copybook {@code CVTRA06Y} &rarr;
 * {@link com.aws.carddemo.dto.DailyTransaction}), whose leading key is {@code ID}. These tests
 * therefore explicitly guard against a silent key swap during translation by asserting the
 * leading-key ordering ({@code cardNum} first, {@code id} second).</p>
 *
 * <p><strong>Distinct from {@code DailyTransaction}.</strong> Although the two records carry the
 * same field set, they are modeled as independent classes. Phase D below documents and enforces
 * that independence.</p>
 *
 * <p><strong>Decimal fidelity.</strong> The monetary field {@code amount} ({@code TRNX-AMT
 * PIC S9(09)V99}) must be a {@link java.math.BigDecimal} of scale 2. Floating-point types
 * ({@code float}/{@code double}) are never used; every amount below is built via the
 * {@code BigDecimal(String)} constructor to preserve exact fixed-scale decimal semantics.</p>
 *
 * <p>This is a plain JUnit 5 (Jupiter) + AssertJ unit test: no Spring context, no database, and
 * no Testcontainers. It exercises {@code new StatementTransaction()} directly.</p>
 */
class StatementTransactionTest {

    // ------------------------------------------------------------------------------------------
    // Phase A - full field round-trip (all 13 data fields, key-first ordering)
    // ------------------------------------------------------------------------------------------

    /**
     * Every one of the 13 modeled fields round-trips through its setter/getter unchanged, in the
     * key-first field order dictated by {@code TRNX-RECORD} ({@code cardNum} then {@code id}).
     */
    @Test
    void all_thirteen_data_fields_round_trip() {
        String cardNum = "4111111111111111";
        String id = "0000000000000001";
        String typeCd = "01";
        Integer catCd = 5;
        String source = "POS       ";
        String description = "GROCERY PURCHASE";
        BigDecimal amount = new BigDecimal("1234.56");
        Long merchantId = 123456789L;
        String merchantName = "ACME FOODS";
        String merchantCity = "SEATTLE";
        String merchantZip = "98101";
        String origTs = "2022-07-18-09.05.03.123456";
        String procTs = "2022-07-18-09.05.04.000000";

        StatementTransaction txn = new StatementTransaction();
        txn.setCardNum(cardNum);
        txn.setId(id);
        txn.setTypeCd(typeCd);
        txn.setCatCd(catCd);
        txn.setSource(source);
        txn.setDescription(description);
        txn.setAmount(amount);
        txn.setMerchantId(merchantId);
        txn.setMerchantName(merchantName);
        txn.setMerchantCity(merchantCity);
        txn.setMerchantZip(merchantZip);
        txn.setOrigTs(origTs);
        txn.setProcTs(procTs);

        assertThat(txn.getCardNum()).isEqualTo(cardNum);
        assertThat(txn.getId()).isEqualTo(id);
        assertThat(txn.getTypeCd()).isEqualTo(typeCd);
        assertThat(txn.getCatCd()).isEqualTo(catCd);
        assertThat(txn.getSource()).isEqualTo(source);
        assertThat(txn.getDescription()).isEqualTo(description);
        assertThat(txn.getAmount()).isEqualTo(amount);
        assertThat(txn.getMerchantId()).isEqualTo(merchantId);
        assertThat(txn.getMerchantName()).isEqualTo(merchantName);
        assertThat(txn.getMerchantCity()).isEqualTo(merchantCity);
        assertThat(txn.getMerchantZip()).isEqualTo(merchantZip);
        assertThat(txn.getOrigTs()).isEqualTo(origTs);
        assertThat(txn.getProcTs()).isEqualTo(procTs);
    }

    /**
     * The numeric fields use the correct boxed reference types: {@code catCd} is an
     * {@link Integer} ({@code TRNX-CAT-CD PIC 9(04)}) and {@code merchantId} is a {@link Long}
     * ({@code TRNX-MERCHANT-ID PIC 9(09)}).
     */
    @Test
    void cat_cd_is_integer_and_merchant_id_is_long() {
        StatementTransaction txn = new StatementTransaction();
        txn.setCatCd(4321);
        txn.setMerchantId(987654321L);

        assertThat(txn.getCatCd()).isInstanceOf(Integer.class);
        assertThat(txn.getMerchantId()).isInstanceOf(Long.class);
    }

    /**
     * The trailing {@code FILLER PIC X(20)} is reserved pad and must not be exposed as a field or
     * accessor. This guards the byte-authoritative layout against an accidental {@code filler}
     * property being introduced during translation.
     */
    @Test
    void filler_is_not_modeled_as_a_field_or_accessor() {
        assertThat(StatementTransaction.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("filler", "FILLER");

        assertThat(StatementTransaction.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("getFiller", "setFiller");
    }

    // ------------------------------------------------------------------------------------------
    // Phase B - composite-key ordering (cardNum first, then id)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code TRNX-KEY} orders {@code cardNum} (bytes 1-16) before {@code id} (bytes 17-32). Using
     * two clearly distinguishable values, this test proves the halves are not swapped and that the
     * optional {@link StatementTransaction#getKey()} concatenates them in {@code cardNum + id}
     * order (never {@code id + cardNum}).
     */
    @Test
    void composite_key_places_card_num_before_id() {
        String cardNum = "4111111111111111";
        String id = "0000000000000001";

        StatementTransaction txn = new StatementTransaction();
        txn.setCardNum(cardNum);
        txn.setId(id);

        // Halves are stored/returned without being swapped.
        assertThat(txn.getCardNum()).isEqualTo(cardNum);
        assertThat(txn.getId()).isEqualTo(id);

        // getKey() concatenates cardNum first, then id - matching TRNX-KEY ordering.
        assertThat(txn.getKey()).isEqualTo(cardNum + id);
        assertThat(txn.getKey()).isNotEqualTo(id + cardNum);
    }

    // ------------------------------------------------------------------------------------------
    // Phase C - BigDecimal decimal fidelity (parity-critical)
    // ------------------------------------------------------------------------------------------

    /**
     * A positive amount round-trips exactly and retains scale 2, matching {@code PIC S9(09)V99}.
     */
    @Test
    void amount_round_trips_and_preserves_scale_two() {
        StatementTransaction txn = new StatementTransaction();
        txn.setAmount(new BigDecimal("1234.56"));

        assertThat(txn.getAmount()).isEqualTo(new BigDecimal("1234.56"));
        assertThat(txn.getAmount().scale()).isEqualTo(2);
    }

    /**
     * A negative amount round-trips exactly, exercising the signed nature of {@code S9(09)V99}.
     */
    @Test
    void amount_round_trips_negative_value_exactly() {
        StatementTransaction txn = new StatementTransaction();
        txn.setAmount(new BigDecimal("-500000.01"));

        assertThat(txn.getAmount()).isEqualByComparingTo(new BigDecimal("-500000.01"));
        assertThat(txn.getAmount()).isEqualTo(new BigDecimal("-500000.01"));
        assertThat(txn.getAmount().scale()).isEqualTo(2);
    }

    /**
     * The maximum-magnitude value permitted by {@code S9(09)V99} ({@code 999999999.99})
     * round-trips without any loss of precision.
     */
    @Test
    void amount_round_trips_boundary_value_without_loss() {
        StatementTransaction txn = new StatementTransaction();
        txn.setAmount(new BigDecimal("999999999.99"));

        assertThat(txn.getAmount()).isEqualTo(new BigDecimal("999999999.99"));
        assertThat(txn.getAmount().scale()).isEqualTo(2);
        assertThat(txn.getAmount().unscaledValue().toString()).isEqualTo("99999999999");
    }

    /**
     * The {@code amount} property is a {@link java.math.BigDecimal}; the {@code getAmount()}
     * accessor is declared to return {@code BigDecimal}, never a {@code double}/{@code float}.
     * This locks in the money-as-{@code BigDecimal} decimal-fidelity rule.
     *
     * @throws NoSuchMethodException never; {@code getAmount()} is a declared accessor
     */
    @Test
    void amount_is_big_decimal_and_never_floating_point() throws NoSuchMethodException {
        StatementTransaction txn = new StatementTransaction();
        txn.setAmount(new BigDecimal("0.00"));

        assertThat(txn.getAmount()).isInstanceOf(BigDecimal.class);
        assertThat(StatementTransaction.class.getMethod("getAmount").getReturnType())
                .isEqualTo(BigDecimal.class)
                .isNotEqualTo(double.class)
                .isNotEqualTo(float.class);
    }

    // ------------------------------------------------------------------------------------------
    // Phase D - independence from DailyTransaction
    // ------------------------------------------------------------------------------------------

    /**
     * {@link StatementTransaction} and {@link com.aws.carddemo.dto.DailyTransaction} are distinct,
     * unrelated types. Despite sharing a field set, the two 350-byte layouts are modeled as
     * independent classes (their composite keys are ordered differently), so neither is an
     * instance of the other.
     */
    @Test
    void statement_transaction_is_independent_from_daily_transaction() {
        assertThat((Object) new StatementTransaction())
                .isNotInstanceOf(com.aws.carddemo.dto.DailyTransaction.class);
        assertThat((Object) new com.aws.carddemo.dto.DailyTransaction())
                .isNotInstanceOf(StatementTransaction.class);
    }

    // ------------------------------------------------------------------------------------------
    // Phase E - optional object methods
    // ------------------------------------------------------------------------------------------

    /**
     * {@code toString()} returns a non-null diagnostic string in the safe identity form
     * (class name + identity hash) that leaks no field content - in particular not the card number
     * (PAN) - into logs (CWE-532).
     */
    @Test
    void to_string_returns_non_null_representation() {
        StatementTransaction txn = new StatementTransaction();
        txn.setCardNum("4111111111111111");
        txn.setId("0000000000000001");

        assertThat(txn.toString())
                .isNotNull()
                .startsWith("StatementTransaction@")
                .doesNotContain("4111111111111111");
    }

    /**
     * {@code equals}/{@code hashCode} are based on the composite key ({@code cardNum} + {@code id}),
     * mirroring {@code TRNX-KEY}: two records with the same key are equal and share a hash code,
     * while differing on either key component makes them unequal.
     */
    @Test
    void equals_and_hash_code_are_based_on_composite_key() {
        StatementTransaction first = new StatementTransaction();
        first.setCardNum("4111111111111111");
        first.setId("0000000000000001");

        StatementTransaction sameKey = new StatementTransaction();
        sameKey.setCardNum("4111111111111111");
        sameKey.setId("0000000000000001");

        StatementTransaction differentId = new StatementTransaction();
        differentId.setCardNum("4111111111111111");
        differentId.setId("0000000000000002");

        assertThat(first).isEqualTo(sameKey);
        assertThat(first).hasSameHashCodeAs(sameKey);
        assertThat(first).isNotEqualTo(differentId);
    }
}
