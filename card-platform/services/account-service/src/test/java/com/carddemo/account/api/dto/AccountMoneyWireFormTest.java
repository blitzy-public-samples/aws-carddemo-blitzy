package com.carddemo.account.api.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire-form tests for the monetary components of {@link AccountView} and {@link CycleCloseResponse}.
 *
 * <p>Two properties are the subject, and both are correctness rather than style.
 *
 * <p>Money travels as a decimal string, never as a JavaScript Object Notation (JSON) number. Most
 * parsers read a JSON number into a binary floating-point type, which reintroduces the one
 * representation a platform built on fixed-point arithmetic cannot use. {@code TransactionAuthorized}
 * in {@code card-platform/libs/event-contracts} and {@code BalanceQueryController.AccountBalance} in
 * the ledger service both carry money as text, so these two records read alike.
 *
 * <p>Scale is pinned at construction, not trusted from the caller. Each component stores at the scale
 * of its own {@code PIC} clause, and truncation is toward zero: the {@code ROUNDED} phrase appears
 * nowhere across the twenty-eight programs of {@code app/cbl/}, so half-up rounding here would report
 * a cent the ledger never held.
 *
 * <p>Every fixture value below is synthetic. No real account figure appears.
 */
@DisplayName("Monetary components of the account surface travel as fixed-scale decimal strings")
final class AccountMoneyWireFormTest {

    /** Reads and writes the two records the way the web layer does. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** An eleven-digit account identifier, the width {@code ACCT-ID PIC 9(11)} declares. */
    private static final String ACCOUNT_ID = "00000000001";

    /** Scale every monetary component of both records stores at. */
    private static final int MONEY_SCALE = PicClause.ACCT_CURR_BAL_SCALE;

    /** The monetary components {@link AccountView} declares, in declaration order. */
    private static final List<String> VIEW_MONEY_COMPONENTS = List.of("currentBalance",
            "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit");

    /** The monetary components {@link CycleCloseResponse} declares, in declaration order. */
    private static final List<String> CYCLE_MONEY_COMPONENTS =
            List.of("currentCycleCredit", "currentCycleDebit");

    /** Tests over the account projection returned by the read route. */
    @Nested
    @DisplayName("AccountView")
    class AccountViewWireForm {

        @Test
        @DisplayName("all five monetary properties serialize as strings and none as a number")
        void allFiveMonetaryPropertiesSerializeAsStrings() {
            JsonNode body = JSON.readTree(JSON.writeValueAsString(view("1250.75")));

            for (String component : VIEW_MONEY_COMPONENTS) {
                assertTrue(body.get(component).isString(),
                        component + " travels as a JSON number, which a parser reads into a binary"
                                + " floating-point type");
            }
        }

        @Test
        @DisplayName("a value at another scale is stored at two fractional digits")
        void aValueAtAnotherScaleIsStoredAtTwoFractionalDigits() {
            AccountView stored = new AccountView(ACCOUNT_ID, "Y", new BigDecimal("100"),
                    new BigDecimal("100.0"), new BigDecimal("100.000"), new BigDecimal("0"),
                    BigDecimal.ZERO, "2020-01-01", "2030-01-01", "2025-01-01", "GROUP01");

            assertAll("every supplied scale stores at " + MONEY_SCALE,
                    () -> assertEquals("100.00", stored.currentBalance().toPlainString()),
                    () -> assertEquals("100.00", stored.creditLimit().toPlainString()),
                    () -> assertEquals("100.00", stored.cashCreditLimit().toPlainString()),
                    () -> assertEquals("0.00", stored.currentCycleCredit().toPlainString()),
                    () -> assertEquals("0.00", stored.currentCycleDebit().toPlainString()),
                    () -> assertEquals(MONEY_SCALE, stored.currentBalance().scale()));
        }

        @Test
        @DisplayName("a whole number reaches the wire with its two fractional digits")
        void aWholeNumberReachesTheWireWithItsTwoFractionalDigits() {
            JsonNode body = JSON.readTree(JSON.writeValueAsString(view("100")));

            assertEquals("100.00", body.get("currentBalance").stringValue(),
                    "a response reporting a balance as 100 would state a different precision than"
                            + " ACCT-CURR-BAL PIC S9(10)V99 holds");
        }

        @Test
        @DisplayName("a third fractional digit truncates toward zero, never away from it")
        void aThirdFractionalDigitTruncatesTowardZero() {
            assertEquals("10.99", view("10.999").currentBalance().toPlainString(),
                    "truncation toward zero keeps 10.99; half-up rounding would report 11.00");
            assertEquals("-10.99", view("-10.999").currentBalance().toPlainString(),
                    "a negative value truncates toward zero as well, so it does not become -11.00");

            BigDecimal supplied = new BigDecimal("10.999");
            assertNotEquals(supplied.setScale(MONEY_SCALE, RoundingMode.DOWN),
                    supplied.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    "the two modes differ here, which is why the mode is pinned rather than left"
                            + " to a default");
        }

        @Test
        @DisplayName("a negative balance keeps its sign and its two fractional digits on the wire")
        void aNegativeBalanceKeepsItsSignOnTheWire() {
            JsonNode body = JSON.readTree(JSON.writeValueAsString(view("-25.50")));

            assertEquals("-25.50", body.get("currentBalance").stringValue(),
                    "a credit balance carries a leading minus");
        }

        @Test
        @DisplayName("a value too wide for PIC S9(10)V99 is refused, and the refusal names no figure")
        void aValueTooWideForItsSourceFieldIsRefused() {
            BigDecimal elevenIntegerDigits = new BigDecimal("12345678901.00");

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> view(elevenIntegerDigits.toPlainString()));

            assertTrue(refused.getMessage().contains("currentBalance"),
                    "the refusal names the component");
            assertFalse(refused.getMessage().contains("12345678901"),
                    "the refusal reports the stored width and never the figure");
        }

        @Test
        @DisplayName("a missing monetary component is refused by name")
        void aMissingMonetaryComponentIsRefusedByName() {
            NullPointerException refused = assertThrows(NullPointerException.class,
                    () -> new AccountView(ACCOUNT_ID, "Y", null, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, "2020-01-01", "2030-01-01",
                            "2025-01-01", "GROUP01"));

            assertTrue(refused.getMessage().contains("currentBalance"), "named");
        }

        @Test
        @DisplayName("the body round trips, so a string on the wire reads back as a decimal")
        void theBodyRoundTrips() {
            AccountView written = view("1250.75");

            AccountView returned =
                    JSON.readValue(JSON.writeValueAsString(written), AccountView.class);

            assertEquals(written, returned, "the body round trips component for component");
            assertEquals(MONEY_SCALE, returned.currentBalance().scale(),
                    "the scale survives the round trip");
        }
    }

    /** Tests over the response the cycle-close operation returns. */
    @Nested
    @DisplayName("CycleCloseResponse")
    class CycleCloseWireForm {

        @Test
        @DisplayName("both accumulators serialize as strings and neither as a number")
        void bothAccumulatorsSerializeAsStrings() {
            JsonNode body = JSON.readTree(JSON.writeValueAsString(
                    new CycleCloseResponse(ACCOUNT_ID, BigDecimal.ZERO, BigDecimal.ZERO)));

            for (String component : CYCLE_MONEY_COMPONENTS) {
                assertTrue(body.get(component).isString(),
                        component + " travels as a JSON number");
            }
        }

        @Test
        @DisplayName("a closed cycle reports both accumulators as 0.00, the value CBACT04C leaves")
        void aClosedCycleReportsBothAccumulatorsAtTwoFractionalDigits() {
            JsonNode body = JSON.readTree(JSON.writeValueAsString(
                    new CycleCloseResponse(ACCOUNT_ID, BigDecimal.ZERO, new BigDecimal("0"))));

            assertAll("app/cbl/CBACT04C.cbl:L353-L354 zeroes both accumulators",
                    () -> assertEquals("0.00", body.get("currentCycleCredit").stringValue()),
                    () -> assertEquals("0.00", body.get("currentCycleDebit").stringValue()));
        }

        @Test
        @DisplayName("a third fractional digit truncates toward zero here too")
        void aThirdFractionalDigitTruncatesTowardZeroHereToo() {
            CycleCloseResponse stored = new CycleCloseResponse(ACCOUNT_ID,
                    new BigDecimal("10.999"), new BigDecimal("-10.999"));

            assertAll("truncation toward zero on both accumulators",
                    () -> assertEquals("10.99", stored.currentCycleCredit().toPlainString()),
                    () -> assertEquals("-10.99", stored.currentCycleDebit().toPlainString()));
        }

        @Test
        @DisplayName("an accumulator too wide for PIC S9(10)V99 is refused without naming a figure")
        void anAccumulatorTooWideIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new CycleCloseResponse(ACCOUNT_ID, new BigDecimal("12345678901.00"),
                            BigDecimal.ZERO));

            assertTrue(refused.getMessage().contains("currentCycleCredit"), "named");
            assertFalse(refused.getMessage().contains("12345678901"), "no figure in the message");
        }

        @Test
        @DisplayName("a missing accumulator is refused by name")
        void aMissingAccumulatorIsRefusedByName() {
            NullPointerException refused = assertThrows(NullPointerException.class,
                    () -> new CycleCloseResponse(ACCOUNT_ID, null, BigDecimal.ZERO));

            assertTrue(refused.getMessage().contains("currentCycleCredit"), "named");
        }
    }

    /** Tests that hold the two records to the same rule and guard against a regression. */
    @Nested
    @DisplayName("Both records")
    class SharedRule {

        @Test
        @DisplayName("every decimal component of both records is a monetary one this test covers")
        void everyDecimalComponentIsCovered() {
            assertEquals(VIEW_MONEY_COMPONENTS, decimalComponentsOf(AccountView.class),
                    "a decimal component added to AccountView needs its wire form stated here");
            assertEquals(CYCLE_MONEY_COMPONENTS, decimalComponentsOf(CycleCloseResponse.class),
                    "a decimal component added to CycleCloseResponse needs the same");
        }

        @Test
        @DisplayName("no serialized body of either record carries a bare JSON number")
        void noSerializedBodyCarriesABareJsonNumber() {
            for (Object record : List.of(view("1250.75"),
                    new CycleCloseResponse(ACCOUNT_ID, BigDecimal.ZERO, BigDecimal.ZERO))) {
                JsonNode body = JSON.readTree(JSON.writeValueAsString(record));
                for (Iterator<String> names = body.propertyNames().iterator();
                        names.hasNext();) {
                    String property = names.next();
                    assertFalse(body.get(property).isNumber(),
                            record.getClass().getSimpleName() + " carries " + property
                                    + " as a JSON number, and no component of either record is a"
                                    + " magnitude a parser may read as a double");
                }
            }
        }

        @Test
        @DisplayName("both records declare the same money shape, so a figure reads alike in either")
        void bothRecordsDeclareTheSameMoneyShape() {
            assertEquals(AccountView.MONEY_PATTERN, CycleCloseResponse.MONEY_PATTERN,
                    "PIC S9(10)V99 governs the balance, both limits and both accumulators, so one"
                            + " shape covers every monetary component of this service");
        }

        /**
         * Names the decimal components one record declares, in declaration order.
         *
         * @param record the record class to read
         * @return the component names whose type is {@link BigDecimal}
         */
        private List<String> decimalComponentsOf(Class<?> record) {
            List<String> decimals = new ArrayList<>();
            for (RecordComponent component : record.getRecordComponents()) {
                if (BigDecimal.class.equals(component.getType())) {
                    decimals.add(component.getName());
                }
            }
            return decimals;
        }
    }

    /**
     * Builds an account view whose five monetary components all carry one figure.
     *
     * @param amount the figure every monetary component carries, in decimal text
     * @return the view
     */
    private static AccountView view(String amount) {
        BigDecimal figure = new BigDecimal(amount);
        return new AccountView(ACCOUNT_ID, "Y", figure, figure, figure, figure, figure,
                "2020-01-01", "2030-01-01", "2025-01-01", "GROUP01");
    }
}
