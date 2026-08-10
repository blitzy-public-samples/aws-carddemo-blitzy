package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.CustomerEntity;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers every transformation {@code AccountRecordMapper} applies to a submitted value.
 *
 * <h2>What this class covers</h2>
 *
 * <p>The mapper brings sixteen text components to the width their Picture clause declares, pads one
 * identifier with leading zeros instead, reshapes four eight-character dates into ten, joins three
 * parts into one social security number, and truncates five monetary values and one credit score.
 * Before this class only one of those — the first name — was checked, and only through a full-stack
 * integration test. A width constant off by one, two components wired to each other's setters, or a
 * {@code LOW-VALUES} field padded into fifty spaces would all have reached a column silently.</p>
 *
 * <h2>How the expectations are derived</h2>
 *
 * <p>Every expected value here is written out rather than computed. A width comes from the Picture
 * clause the copybook declares, quoted per case below, and the padded result is built by this test
 * from that number — never by calling the helper under test. Each monetary expectation is the literal
 * a reader can check against {@code app/cpy/CVACT01Y.cpy}, so a defect shared with
 * {@code CobolDecimal} cannot hide behind an oracle that calls it.</p>
 *
 * <p>Each case also submits a value shorter than its field, which is what makes a swapped mapping
 * visible: the sixteen submitted values are distinct, so a component landing in the wrong column
 * fails on content as well as on width.</p>
 */
class AccountRecordMapperTest {

    /** The account identifier of row one of {@code app/data/ASCII/acctdata.txt}. */
    private static final String ACCOUNT_ID = "00000000001";

    /** The character a fixed-width text column pads with. */
    private static final char PAD = ' ';

    /** The character {@code LOW-VALUES} fills a field with, from {@code app/cbl/COACTUPC.cbl:L2184}. */
    private static final String LOW_VALUES = "\u0000\u0000\u0000";

    /**
     * One text component of the request, its declared width, and the accessor of its column.
     *
     * @param label     the component name, used in the failure message and the display name
     * @param width     the width the Picture clause declares
     * @param submitted the value this case submits, distinct from every other case
     * @param mapped    reads the column the component reaches
     */
    private record TextField(String label, int width, String submitted,
            Function<Mapped, String> mapped) {

        @Override
        public String toString() {
            return label + " at " + width;
        }
    }

    /**
     * The two entities one request maps to.
     *
     * @param account  the account values
     * @param customer the customer values
     */
    private record Mapped(AccountEntity account, CustomerEntity customer) {
    }

    /** Maps one account component, leaving every other component absent. */
    private static Mapped mapAccount(String component, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(component, value);
        return new Mapped(AccountRecordMapper.accountOf(ACCOUNT_ID, new AccountDataRequest(
                values.get("activeStatus"), values.get("currentBalance"),
                values.get("creditLimit"), values.get("cashCreditLimit"), values.get("openDate"),
                values.get("expirationDate"), values.get("reissueDate"),
                values.get("currentCycleCredit"), values.get("currentCycleDebit"),
                values.get("groupId"))), new CustomerEntity());
    }

    /** Maps one customer component, leaving every other component absent. */
    private static Mapped mapCustomer(String component, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(component, value);
        return new Mapped(new AccountEntity(),
                AccountRecordMapper.customerOf(customerRequest(values)));
    }

    /** Builds a customer request carrying only the components the map names. */
    private static CustomerDataRequest customerRequest(Map<String, String> values) {
        return new CustomerDataRequest(values.get("customerId"), values.get("firstName"),
                values.get("middleName"), values.get("lastName"), values.get("addressLine1"),
                values.get("addressLine2"), values.get("addressCity"),
                values.get("addressStateCode"), values.get("addressCountryCode"),
                values.get("addressZip"), values.get("phoneNumber1"), values.get("phoneNumber2"),
                values.get("socialSecurityPart1"), values.get("socialSecurityPart2"),
                values.get("socialSecurityPart3"), values.get("governmentIssuedId"),
                values.get("dateOfBirth"), values.get("eftAccountId"),
                values.get("primaryCardHolderIndicator"), values.get("ficoCreditScore"));
    }

    /** Pads one submitted value to a width, without calling the class under test. */
    private static String padded(String submitted, int width) {
        StringBuilder field = new StringBuilder(submitted);
        while (field.length() < width) {
            field.append(PAD);
        }
        return field.toString();
    }

    /**
     * The sixteen fixed-width text components, with the width each Picture clause declares.
     *
     * <p>Account: {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT01Y.cpy:L6} and
     * {@code ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L16}. Customer: the fourteen
     * text members of {@code app/cpy/CVCUS01Y.cpy:L5-L22}, excluding the social security number and
     * the date of birth, which carry their own cases below.</p>
     */
    private static Stream<Arguments> textFields() {
        return Stream.of(
                new TextField("activeStatus", 1, "N", at -> at.account().getActiveStatus()),
                new TextField("groupId", 10, "ZEROAPR", at -> at.account().getGroupId()),
                new TextField("firstName", 25, "Aniya", at -> at.customer().getFirstName()),
                new TextField("middleName", 25, "Madeline", at -> at.customer().getMiddleName()),
                new TextField("lastName", 25, "Rutherford", at -> at.customer().getLastName()),
                new TextField("addressLine1", 50, "618 Deshaun Route",
                        at -> at.customer().getAddressLine1()),
                new TextField("addressLine2", 50, "Suite 40",
                        at -> at.customer().getAddressLine2()),
                new TextField("addressCity", 50, "Port Kirstin",
                        at -> at.customer().getAddressCity()),
                new TextField("addressStateCode", 2, "NY",
                        at -> at.customer().getAddressStateCode()),
                new TextField("addressCountryCode", 3, "USA",
                        at -> at.customer().getAddressCountryCode()),
                new TextField("addressZip", 10, "10036", at -> at.customer().getAddressZip()),
                new TextField("phoneNumber1", 15, "(212)5550118",
                        at -> at.customer().getPhoneNumber1()),
                new TextField("phoneNumber2", 15, "(646)5550199",
                        at -> at.customer().getPhoneNumber2()),
                new TextField("governmentIssuedId", 20, "NY-DL-8841",
                        at -> at.customer().getGovernmentIssuedId()),
                new TextField("eftAccountId", 10, "EFT77120",
                        at -> at.customer().getEftAccountId()),
                new TextField("primaryCardHolderIndicator", 1, "Y",
                        at -> at.customer().getPrimaryCardHolderIndicator()))
                .map(Arguments::of);
    }

    /** The two components of the sixteen that belong to the account section. */
    private static final List<String> ACCOUNT_TEXT_COMPONENTS = List.of("activeStatus", "groupId");

    /** Maps one text component, choosing the request section it belongs to. */
    private static Mapped map(TextField field, String value) {
        return ACCOUNT_TEXT_COMPONENTS.contains(field.label())
                ? mapAccount(field.label(), value)
                : mapCustomer(field.label(), value);
    }

    /** Maps all sixteen text components in one request, so a swapped setter shows up. */
    private static Mapped mapEveryTextField() {
        Map<String, String> values = new LinkedHashMap<>();
        textFields().forEach(argument -> {
            TextField field = (TextField) argument.get()[0];
            values.put(field.label(), field.submitted());
        });
        return new Mapped(AccountRecordMapper.accountOf(ACCOUNT_ID, new AccountDataRequest(
                values.get("activeStatus"), null, null, null, null, null, null, null, null,
                values.get("groupId"))), AccountRecordMapper.customerOf(customerRequest(values)));
    }

    @Nested
    @DisplayName("A fixed-width text component")
    class FixedWidthText {

        @ParameterizedTest(name = "{0} reaches its column at its declared width")
        @MethodSource(
                "com.carddemo.account.api.AccountRecordMapperTest#textFields")
        @DisplayName("reaches its own column, padded with trailing spaces to its declared width")
        void reachesItsOwnColumnPaddedToItsDeclaredWidth(TextField field) {
            String stored = field.mapped().apply(map(field, field.submitted()));

            assertEquals(padded(field.submitted(), field.width()), stored,
                    field.label() + " reaches its column at " + field.width() + " characters, "
                            + "padded with trailing spaces, because the source moves the screen "
                            + "field into a fixed-width field and rewrites the whole record");
            assertEquals(field.width(), stored.length(),
                    field.label() + " occupies its whole field, so its width is " + field.width());
            assertTrue(stored.startsWith(field.submitted()),
                    field.label() + " keeps the value submitted, ahead of the padding");
        }

        @Test
        @DisplayName("lands in its own column when all sixteen are submitted together")
        void landsInItsOwnColumnWhenAllSixteenAreSubmittedTogether() {
            Mapped mapped = mapEveryTextField();

            for (Arguments argument : textFields().toList()) {
                TextField field = (TextField) argument.get()[0];
                assertEquals(padded(field.submitted(), field.width()),
                        field.mapped().apply(mapped),
                        field.label() + " holds another component's value, so two request "
                                + "components are wired to the same setter or to each other's. The "
                                + "sixteen submitted values are distinct precisely so that a swap "
                                + "fails on content rather than passing on a matching width");
            }
        }

        @ParameterizedTest(name = "{0} leaves an absent value absent")
        @MethodSource(
                "com.carddemo.account.api.AccountRecordMapperTest#textFields")
        @DisplayName("stays absent when the request supplies nothing, spaces or LOW-VALUES")
        void staysAbsentWhenTheRequestSuppliesNothingSpacesOrLowValues(TextField field) {
            assertNull(field.mapped().apply(map(field, null)),
                    field.label() + " is absent, so the stored value stands and the column is not "
                            + "overwritten with padding");
            assertEquals("  ", field.mapped().apply(map(field, "  ")),
                    field.label() + " holding spaces counts as absent at "
                            + "app/cbl/COACTUPC.cbl:L2184, so it is returned unchanged rather than "
                            + "padded to its declared width");
            assertEquals(LOW_VALUES, field.mapped().apply(map(field, LOW_VALUES)),
                    field.label() + " holding LOW-VALUES counts as absent in the same test, so it "
                            + "is returned unchanged");
        }

        @ParameterizedTest(name = "{0} keeps a value longer than its field")
        @MethodSource(
                "com.carddemo.account.api.AccountRecordMapperTest#textFields")
        @DisplayName("returns a value longer than its field unchanged, for the edit to refuse")
        void returnsAValueLongerThanItsFieldUnchanged(TextField field) {
            String overWidth = "x".repeat(field.width() + 1);

            String stored = field.mapped().apply(map(field, overWidth));

            assertEquals(overWidth, stored,
                    field.label() + " is not shortened here. The width constraint on the request "
                            + "component refuses an over-width value with a message, and silently "
                            + "cutting it would answer 200 on a value the caller never sent");
        }
    }

    @Nested
    @DisplayName("The customer identifier")
    class Identifier {

        @Test
        @DisplayName("is padded with leading zeros, not trailing spaces")
        void isPaddedWithLeadingZeros() {
            String stored = mapCustomer("customerId", "42").customer().getCustomerId();

            assertEquals("000000042", stored,
                    "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5 is numeric, and a numeric MOVE "
                            + "right-justifies the digits over zeros");
            assertEquals(9, stored.length(), "the identifier occupies its whole field");
        }

        @Test
        @DisplayName("keeps a value already at its declared width")
        void keepsAValueAlreadyAtItsDeclaredWidth() {
            assertEquals("000000001",
                    mapCustomer("customerId", "000000001").customer().getCustomerId(),
                    "an identifier at nine characters is stored as submitted");
        }

        @Test
        @DisplayName("stays absent when the request supplies nothing, spaces or LOW-VALUES")
        void staysAbsentWhenTheRequestSuppliesNothing() {
            assertNull(mapCustomer("customerId", null).customer().getCustomerId(),
                    "an absent identifier leaves the stored value standing");
            assertNull(mapCustomer("customerId", "   ").customer().getCustomerId(),
                    "spaces count as absent, so no zero-padded field is built from them");
            assertNull(mapCustomer("customerId", LOW_VALUES).customer().getCustomerId(),
                    "LOW-VALUES counts as absent in the same test");
        }
    }

    @Nested
    @DisplayName("A date")
    class Dates {

        @ParameterizedTest(name = "{0} reaches its column as ten characters")
        @ValueSource(strings = {"openDate", "expirationDate", "reissueDate"})
        @DisplayName("is reshaped from eight characters into the ten its column holds")
        void isReshapedIntoTheTenCharactersItsColumnHolds(String component) {
            Mapped mapped = mapAccount(component, "20150302");

            assertEquals("2015-03-02", storedAccountDate(mapped, component),
                    component + " arrives as ACUP-NEW-OPEN-DATE PIC X(08) and reaches a PIC X(10) "
                            + "column, so the mapper inserts the two separators the stored slices "
                            + "at app/cbl/COACTUPC.cbl:L4127-L4137 expect");
        }

        @Test
        @DisplayName("carries the date of birth through the same reshaping")
        void carriesTheDateOfBirthThroughTheSameReshaping() {
            assertEquals("1978-11-04",
                    mapCustomer("dateOfBirth", "19781104").customer().getDateOfBirth(),
                    "CUST-DOB-YYYY-MM-DD at app/cpy/CVCUS01Y.cpy:L16 holds ten characters");
        }

        @ParameterizedTest(name = "{0} stays absent when nothing is supplied")
        @ValueSource(strings = {"openDate", "expirationDate", "reissueDate"})
        @DisplayName("stays absent when the request supplies nothing, spaces or LOW-VALUES")
        void staysAbsentWhenTheRequestSuppliesNothing(String component) {
            assertNull(storedAccountDate(mapAccount(component, null), component),
                    component + " absent leaves the stored date standing");
            assertNull(storedAccountDate(mapAccount(component, "        "), component),
                    component + " holding eight spaces counts as absent, so no separator-only "
                            + "value such as \"    -  -  \" reaches the column");
            assertNull(storedAccountDate(mapAccount(component, LOW_VALUES), component),
                    component + " holding LOW-VALUES counts as absent in the same test");
        }

        @Test
        @DisplayName("pads a short date to eight characters before reshaping it")
        void padsAShortDateBeforeReshapingIt() {
            assertEquals("2015-03-  ", storedAccountDate(mapAccount("openDate", "201503"),
                            "openDate"),
                    "a date shorter than its field is padded to eight characters first, so the "
                            + "reshaping never reads past the value. The calendar edit refuses the "
                            + "result, which is why the mapper does not have to");
        }

        /** Reads the account date column one component reaches. */
        private String storedAccountDate(Mapped mapped, String component) {
            return switch (component) {
                case "openDate" -> mapped.account().getOpenDate();
                case "expirationDate" -> mapped.account().getExpirationDate();
                case "reissueDate" -> mapped.account().getReissueDate();
                default -> throw new IllegalArgumentException(component + " is not an account date");
            };
        }
    }

    @Nested
    @DisplayName("The social security number")
    class SocialSecurityNumber {

        @Test
        @DisplayName("joins the three parts into the nine characters its column holds")
        void joinsTheThreePartsIntoNineCharacters() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("socialSecurityPart1", "123");
            values.put("socialSecurityPart2", "45");
            values.put("socialSecurityPart3", "6789");

            String stored = AccountRecordMapper.customerOf(customerRequest(values))
                    .getSocialSecurityNumber();

            assertEquals("123456789", stored,
                    "CUST-SSN PIC X(09) at app/cpy/CVCUS01Y.cpy:L17 holds the three parts "
                            + "app/cbl/COACTUPC.cbl:L813-L815 declares, in that order");
            assertEquals(9, stored.length(), "the number occupies its whole field");
        }

        @Test
        @DisplayName("pads each part inside its own slice rather than shifting the parts after it")
        void padsEachPartInsideItsOwnSlice() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("socialSecurityPart1", "1");
            values.put("socialSecurityPart2", "4");
            values.put("socialSecurityPart3", "67");

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> AccountRecordMapper.customerOf(customerRequest(values)),
                    "a short part pads inside its own three, two or four character slice, so the "
                            + "joined value carries a space where a digit belongs and the column "
                            + "guard refuses it rather than storing it");

            assertTrue(refused.getMessage().contains("socialSecurityNumber"),
                    "the refusal names the column, so a caller learns which value was wrong: "
                            + refused.getMessage());
            assertTrue(refused.getMessage().contains("position 2"),
                    "position 2 is where the first part's padding lands, which proves the parts "
                            + "were not concatenated end to end and shifted left: "
                            + refused.getMessage());
        }

        @ParameterizedTest(name = "part {0} missing leaves the column absent")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("stays absent unless all three parts are supplied")
        void staysAbsentUnlessAllThreePartsAreSupplied(int missingPart) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("socialSecurityPart1", missingPart == 1 ? null : "123");
            values.put("socialSecurityPart2", missingPart == 2 ? null : "45");
            values.put("socialSecurityPart3", missingPart == 3 ? null : "6789");

            assertNull(AccountRecordMapper.customerOf(customerRequest(values))
                            .getSocialSecurityNumber(),
                    "part " + missingPart + " is absent, so a partial number never reaches the "
                            + "column and the stored one stands");
        }
    }

    @Nested
    @DisplayName("A monetary value")
    class MonetaryValues {

        @Test
        @DisplayName("reaches its own column at scale two, truncated toward zero")
        void reachesItsOwnColumnAtScaleTwoTruncatedTowardZero() {
            AccountEntity mapped = AccountRecordMapper.accountOf(ACCOUNT_ID,
                    new AccountDataRequest(null, "284.005", "120.999", "100.001", null, null, null,
                            "1.239", "-2.999", null));

            assertMoney("current balance", "284.00", mapped.getCurrentBalance());
            assertMoney("credit limit", "120.99", mapped.getCreditLimit());
            assertMoney("cash credit limit", "100.00", mapped.getCashCreditLimit());
            assertMoney("current cycle credit", "1.23", mapped.getCurrentCycleCredit());
            assertMoney("current cycle debit", "-2.99", mapped.getCurrentCycleDebit());
        }

        @Test
        @DisplayName("accepts the currency symbol and separators the source tolerates")
        void acceptsTheCurrencySymbolAndSeparatorsTheSourceTolerates() {
            AccountEntity mapped = AccountRecordMapper.accountOf(ACCOUNT_ID,
                    new AccountDataRequest(null, "$1,234.56", null, null, null, null, null, null,
                            null, null));

            assertMoney("current balance", "1234.56", mapped.getCurrentBalance());
        }

        @Test
        @DisplayName("stays absent when the request supplies nothing or a value that will not convert")
        void staysAbsentWhenNothingConverts() {
            AccountEntity absent = AccountRecordMapper.accountOf(ACCOUNT_ID,
                    new AccountDataRequest(null, null, null, null, null, null, null, null, null,
                            null));
            AccountEntity refused = AccountRecordMapper.accountOf(ACCOUNT_ID,
                    new AccountDataRequest(null, "twelve", null, null, null, null, null, null, null,
                            null));

            assertNull(absent.getCurrentBalance(), "an absent amount leaves the stored value");
            assertNull(refused.getCurrentBalance(),
                    "a value that does not convert is left absent here, and the signed-decimal "
                            + "edit answers with the message app/cbl/COACTUPC.cbl:L2180-L2217 writes");
        }

        /** Asserts one stored amount by value, scale and sign, against a written-out literal. */
        private void assertMoney(String label, String expected, BigDecimal stored) {
            BigDecimal wanted = new BigDecimal(expected);
            assertEquals(wanted, stored,
                    label + " is stored as " + expected + ", truncated toward zero at the two "
                            + "fractional digits PIC S9(10)V99 declares");
            assertEquals(2, stored.scale(), label + " carries the two digits its column holds");
            assertEquals(wanted.signum(), stored.signum(), label + " keeps its sign");
        }
    }

    @Nested
    @DisplayName("The credit score")
    class CreditScore {

        @Test
        @DisplayName("reaches its column as a whole number, truncated toward zero")
        void reachesItsColumnAsAWholeNumber() {
            BigDecimal stored = mapCustomer("ficoCreditScore", "700.9").customer()
                    .getFicoCreditScore();

            assertEquals(new BigDecimal("700"), stored,
                    "CUST-FICO-CREDIT-SCORE PIC 9(03) at app/cpy/CVCUS01Y.cpy:L21 carries no "
                            + "fractional digit, so 700.9 is truncated toward zero");
            assertEquals(0, stored.scale(), "the score carries no fractional digit");
        }

        @Test
        @DisplayName("stays absent when the request supplies nothing or a value that will not convert")
        void staysAbsentWhenNothingConverts() {
            assertNull(mapCustomer("ficoCreditScore", null).customer().getFicoCreditScore(),
                    "an absent score leaves the stored value standing");
            assertNull(mapCustomer("ficoCreditScore", "seven hundred").customer()
                            .getFicoCreditScore(),
                    "a value that does not convert is left absent, and the numeric edit answers "
                            + "with the message app/cbl/COACTUPC.cbl:L2111 writes");
        }
    }

    @Nested
    @DisplayName("A request carrying no section")
    class AbsentSection {

        @Test
        @DisplayName("maps to an account holding only the identifier from the path")
        void mapsToAnAccountHoldingOnlyTheIdentifier() {
            AccountEntity mapped = AccountRecordMapper.accountOf(ACCOUNT_ID, null);

            assertEquals(ACCOUNT_ID, mapped.getAccountId(),
                    "the identifier comes from the path rather than the body");
            assertNull(mapped.getActiveStatus(), "no component is invented for an absent section");
            assertNull(mapped.getCurrentBalance(), "no amount is invented either");
            assertNull(mapped.getGroupId(), "and no group identifier");
        }

        @Test
        @DisplayName("maps to a customer holding nothing at all")
        void mapsToACustomerHoldingNothing() {
            CustomerEntity mapped = AccountRecordMapper.customerOf(null);

            assertNull(mapped.getCustomerId(), "an absent section names no customer");
            assertNull(mapped.getFirstName(), "and supplies no name");
            assertNull(mapped.getSocialSecurityNumber(), "and no social security number");
        }
    }

    @Nested
    @DisplayName("The absence test")
    class AbsenceTest {

        @Test
        @DisplayName("counts null, empty, spaces and LOW-VALUES as absent")
        void countsNullEmptySpacesAndLowValuesAsAbsent() {
            assertFalse(AccountRecordMapper.isSupplied(null), "null is absent");
            assertFalse(AccountRecordMapper.isSupplied(""), "an empty value is absent");
            assertFalse(AccountRecordMapper.isSupplied("     "), "spaces are absent");
            assertFalse(AccountRecordMapper.isSupplied(LOW_VALUES), "LOW-VALUES is absent");
            assertFalse(AccountRecordMapper.isSupplied(" \u0000 "),
                    "a mixture of the two is absent, because the source tests them together");
        }

        @Test
        @DisplayName("counts any other character as supplied")
        void countsAnyOtherCharacterAsSupplied() {
            assertTrue(AccountRecordMapper.isSupplied("Y"), "a character is a supplied value");
            assertTrue(AccountRecordMapper.isSupplied("  Y  "),
                    "a character among spaces is still a supplied value");
            assertTrue(AccountRecordMapper.isSupplied("0"),
                    "a zero is a value, not an absence");
        }
    }
}
