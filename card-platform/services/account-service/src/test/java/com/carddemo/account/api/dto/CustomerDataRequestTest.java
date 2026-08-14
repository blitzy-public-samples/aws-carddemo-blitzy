package com.carddemo.account.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.events.EventEnvelope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the width constraint on {@code addressLine2} and the text this request renders.
 *
 * <p>Two concerns meet in this record. It declares the widths its stored columns hold, and it carries
 * the personally identifying detail of one customer. A record renders every component unless it
 * overrides the method the compiler generates, so the override is what keeps a social security
 * number and a government issued identifier out of a log line.
 *
 * <p>The social security number below is the value of the first row of
 * {@code app/data/ASCII/custdata.txt}, split into the three parts the source declares at
 * {@code app/cbl/COACTUPC.cbl:L813-L815}.
 */
class CustomerDataRequestTest {

    /** Builds validators for the constraint tests. */
    private static ValidatorFactory factory;

    /** Runs the constraints declared on the record components. */
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * Builds a request whose {@code addressLine2} holds the given number of characters.
     *
     * @param length characters to place in {@code addressLine2}
     * @return a request that satisfies every other constraint
     */
    private static CustomerDataRequest withAddressLine2OfLength(int length) {
        // The two telephone numbers carry the punctuated stored form the redefinition at
        // app/cbl/COACTUPC.cbl:L83-L96 slices, with area codes drawn from the 980 listed in
        // app/cpy/CSLKPCDY.cpy, so only the address line under test can report a violation.
        return new CustomerDataRequest("000000001", "Aniya", "M", "Von", "Address line one",
                "x".repeat(length), "A city", "NY", "USA", "10036", "(345)563-7159",
                "(443)197-1271", "020", "97", "3888", "GOV00000000000000001", "19750101",
                "0000000001", "Y", "650");
    }

    @Test
    @DisplayName("an address line two of fifty characters passes validation")
    void acceptsFiftyCharacterAddressLine2() {
        Set<ConstraintViolation<CustomerDataRequest>> violations = validator.validate(
                withAddressLine2OfLength(CustomerDataRequest.ADDRESS_LINE_2_MAX_LENGTH));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("an address line two of fifty-one characters fails validation")
    void rejectsFiftyFirstCharacterOfAddressLine2() {
        Set<ConstraintViolation<CustomerDataRequest>> violations = validator.validate(
                withAddressLine2OfLength(CustomerDataRequest.ADDRESS_LINE_2_MAX_LENGTH + 1));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath())
                .hasToString("addressLine2");
    }

    /**
     * Builds a request whose {@code addressZip} holds the given value.
     *
     * <p>The state stays {@code NY}, so the cross-field edit at
     * {@code app/cbl/COACTUPC.cbl:L1665-L1669} pairs {@code NY} with the first two characters and
     * only the postal code under test can report a violation.
     *
     * @param addressZip the postal code to place in the request
     * @return a request that satisfies every other constraint
     */
    private static CustomerDataRequest withAddressZip(String addressZip) {
        return new CustomerDataRequest("000000001", "Aniya", "M", "Von", "Address line one",
                "Address line two", "A city", "NY", "USA", addressZip, "(345)563-7159",
                "(443)197-1271", "020", "97", "3888", "GOV00000000000000001", "19750101",
                "0000000001", "Y", "650");
    }

    /**
     * Every postal-code form the read model can return has to be accepted back.
     *
     * <p>{@code CUST-ADDR-ZIP PIC X(10)} at {@code app/cpy/CVCUS01Y.cpy:L14} holds ten characters,
     * the column {@code customer.address_zip} is {@code VARCHAR(10)}, and {@code GET} returns the
     * value as stored. {@code app/cbl/COACTUPC.cbl:L1606} moves that whole field into the edit area
     * and {@code app/cbl/COACTUPC.cbl:L1607} then edits five characters of it, so the source accepts
     * every one of these.
     *
     * <p>The seed data holds both forms: thirty of the fifty customers carry a ZIP+4 and the other
     * twenty carry five digits padded to ten. Editing the full width refused all fifty, so a value
     * this service had just returned could not be sent back.
     */
    @Test
    @DisplayName("a ten-character postal code passes, ZIP+4 and space-padded alike")
    void acceptsTheTenCharacterPostalCodeTheReadModelReturns() {
        for (String stored : new String[] {
            "10036-1234",
            "10036     ",
            "10036",
            "1003612345".substring(0, 5) + "-6716",
        }) {
            Set<ConstraintViolation<CustomerDataRequest>> violations =
                    validator.validate(withAddressZip(stored));

            assertThat(violations)
                    .as("'%s' is a value GET returns, so PUT has to take it back", stored)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the postal code is edited on its first five characters only")
    void editsThePostalCodeOnItsFirstFiveCharactersOnly() {
        assertThat(validator.validate(withAddressZip("1003X-1234")))
                .as("a non-numeric character inside the edited width fails")
                .isNotEmpty();
        assertThat(validator.validate(withAddressZip("10036-ABCD")))
                .as("characters past the edited width are never inspected, so they cannot fail")
                .isEmpty();
    }

    @Test
    @DisplayName("a postal code past the ten characters the field holds fails validation")
    void rejectsAPostalCodePastTheHeldWidth() {
        Set<ConstraintViolation<CustomerDataRequest>> violations =
                validator.validate(withAddressZip("10036-12345"));

        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getPropertyPath()).hasToString("addressZip"));
    }

    @Test
    @DisplayName("the postal-code widths are the source field width and the edited width")
    void declaresBothPostalCodeWidths() {
        assertThat(CustomerDataRequest.ADDRESS_ZIP_MAX_LENGTH)
                .as("CUST-ADDR-ZIP PIC X(10) at app/cpy/CVCUS01Y.cpy:L14, and address_zip"
                        + " VARCHAR(10)")
                .isEqualTo(10);
        assertThat(CustomerDataRequest.ADDRESS_ZIP_EDITED_LENGTH)
                .as("MOVE 5 TO WS-EDIT-ALPHANUM-LENGTH at app/cbl/COACTUPC.cbl:L1607")
                .isEqualTo(5);
        assertThat(CustomerDataRequest.ADDRESS_ZIP_EDITED_LENGTH)
                .isLessThan(CustomerDataRequest.ADDRESS_ZIP_MAX_LENGTH);
    }

    @Test
    @DisplayName("the declared width matches the width the source copybook member declares")
    void declaresSourceWidth() {
        assertThat(CustomerDataRequest.ADDRESS_LINE_2_MAX_LENGTH)
                .isEqualTo(CustomerDataRequest.ADDRESS_LINE_1_MAX_LENGTH)
                .isEqualTo(50);
    }

    @Test
    @DisplayName("the rendering discloses no social security number and no government identifier")
    void withholdsSensitiveDetail() {
        String rendered = withAddressLine2OfLength(10).toString();

        assertThat(rendered)
                .doesNotContain("020")
                .doesNotContain("3888")
                .doesNotContain("GOV00000000000000001")
                .doesNotContain("19750101")
                .doesNotContain("Aniya")
                .doesNotContain("Von")
                .doesNotContain("Address line one")
                .doesNotContain("0000000001")
                .doesNotContain("650");
    }

    @Test
    @DisplayName("the rendering names all twenty components and marks each withheld or absent")
    void namesEveryComponent() {
        String rendered = new CustomerDataRequest("000000001", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null)
                .toString();

        assertThat(rendered).startsWith("CustomerDataRequest[");
        assertThat(rendered).contains("customerId=" + CustomerDataRequest.WITHHELD);
        assertThat(rendered).contains("socialSecurityPart1=" + CustomerDataRequest.ABSENT);
        assertThat(rendered).contains("governmentIssuedId=" + CustomerDataRequest.ABSENT);
        assertThat(rendered).contains("addressLine2=" + CustomerDataRequest.ABSENT);
    }

    @Test
    @DisplayName("the customer view withholds every value it holds")
    void customerViewWithholdsEveryValue() {
        String rendered = new CustomerView("000000001", 650, "19750101", "Aniya", "M", "Von",
                "Address line one", "Address line two", "A city", "NY", "10036", "USA",
                "000000000000001", "000000000000002", "0000000003", "Y").toString();

        assertThat(rendered).startsWith("CustomerView[");
        assertThat(rendered).doesNotContain("Aniya").doesNotContain("Von")
                .doesNotContain("19750101").doesNotContain("650")
                .doesNotContain("Address line one");
        assertThat(rendered).contains("firstName=" + EventEnvelope.WITHHELD);
    }
}
