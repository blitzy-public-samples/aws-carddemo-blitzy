package com.carddemo.authorization.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.events.DeclineReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that the request body never discloses a Primary Account Number (PAN) through the text it
 * renders, and that the decision path still reads the whole number.
 *
 * <p>A record renders every component unless it overrides the method the compiler generates.
 * {@link AuthorizationRequest} carries a full card number, so the override is what keeps that number
 * out of a log line, an exception message and a debugger view.
 *
 * <p>The card number below is the sixteen-digit value of the first row of
 * {@code app/data/ASCII/carddata.txt}. A rendering that contains it has leaked it.
 */
class AuthorizationRequestRedactionTest {

    /** Card number of the first fixture row, and a value no rendering may disclose. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** The first twelve digits of {@link #CARD_NUMBER}, which a masked form replaces. */
    private static final String HIDDEN_DIGITS = "050002445376";

    /** The last four digits of {@link #CARD_NUMBER}, which a masked form keeps. */
    private static final String VISIBLE_DIGITS = "5740";

    /**
     * Builds a request carrying the fixture card number and one distinctive value per component.
     *
     * @return a request whose every component holds content
     */
    private static AuthorizationRequest fullyPopulatedRequest() {
        return new AuthorizationRequest("0000000000000042", "01", "0001", "POS",
                "a purchase description", "-00001234.56", "000000123", "a merchant name",
                "a merchant city", "0000012345", CARD_NUMBER, "2026-08-03 18:31:53.613000",
                "2026-08-03-18.31.53.610000", "00000000017");
    }

    @Test
    @DisplayName("the rendering masks the card number and keeps only its last four digits")
    void masksCardNumber() {
        String rendered = fullyPopulatedRequest().toString();

        assertThat(rendered).doesNotContain(CARD_NUMBER).doesNotContain(HIDDEN_DIGITS);
        assertThat(rendered).contains("************" + VISIBLE_DIGITS);
    }

    @Test
    @DisplayName("the rendering discloses no other component value")
    void withholdsEveryOtherValue() {
        String rendered = fullyPopulatedRequest().toString();

        assertThat(rendered)
                .doesNotContain("0000000000000042")
                .doesNotContain("a purchase description")
                .doesNotContain("-00001234.56")
                .doesNotContain("000000123")
                .doesNotContain("a merchant name")
                .doesNotContain("a merchant city")
                .doesNotContain("0000012345")
                .doesNotContain("00000000017")
                .doesNotContain("2026-08-03");
    }

    @Test
    @DisplayName("the rendering names every component and marks each as withheld or absent")
    void namesEveryComponent() {
        String rendered = new AuthorizationRequest(null, null, null, null, null, "-00001234.56",
                "000000123", null, null, null, null, "2026-08-03 18:31:53.613000", null,
                "00000000017").toString();

        assertThat(rendered).startsWith("AuthorizationRequest[");
        assertThat(rendered).contains("transactionId=" + AuthorizationRequest.ABSENT);
        assertThat(rendered).contains("amount=" + AuthorizationRequest.WITHHELD);
        assertThat(rendered).contains("cardNumber=" + AuthorizationRequest.ABSENT);
        assertThat(rendered).contains("accountId=" + AuthorizationRequest.WITHHELD);
    }

    @Test
    @DisplayName("a card number the plain grammar rejects is withheld, not partly disclosed")
    void withholdsMalformedCardNumber() {
        String rendered = new AuthorizationRequest(null, null, null, null, null, "-00001234.56",
                "000000123", null, null, null, "not-a-card", "2026-08-03 18:31:53.613000", null,
                null).toString();

        assertThat(rendered).contains("cardNumber=" + AuthorizationRequest.WITHHELD);
        assertThat(rendered).doesNotContain("not-a-card");
    }

    @Test
    @DisplayName("a short card number is widened to sixteen digits and then masked")
    void masksCanonicalFormOfShortCardNumber() {
        String rendered = new AuthorizationRequest(null, null, null, null, null, "-00001234.56",
                "000000123", null, null, null, "12345", "2026-08-03 18:31:53.613000", null, null)
                .toString();

        // canonicalCardNumber widens 12345 to 0000000000012345, which is the value the
        // cross-reference lookup keys on. The masker then keeps that value's last four digits,
        // which is the whole of what app/cpy/CVACT03Y.cpy:L5 requires a caller to supply.
        assertThat(rendered).contains("cardNumber=************2345");
        assertThat(rendered).doesNotContain("0000000000012345");
    }

    @Test
    @DisplayName("the decision path still reads all sixteen digits")
    void decisionPathKeepsFullCardNumber() {
        AuthorizationRequest request = fullyPopulatedRequest();

        assertThat(request.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(request.canonicalCardNumber()).isEqualTo(CARD_NUMBER);
    }

    @Test
    @DisplayName("the response withholds the account identifier and keeps the decline reason")
    void responseWithholdsAccountIdentifier() {
        String rendered = new AuthorizationResponse("0000000000000042", "00000000017", false,
                DeclineReason.OVER_CREDIT_LIMIT, DeclineReason.OVER_CREDIT_LIMIT.description())
                .toString();

        assertThat(rendered).doesNotContain("00000000017");
        assertThat(rendered).contains("0000000000000042")
                .contains(DeclineReason.OVER_CREDIT_LIMIT.description());
    }
}
