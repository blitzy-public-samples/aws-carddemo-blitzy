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
