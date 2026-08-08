package com.carddemo.card.api.dto;

/**
 * Error payload returned by the card service Application Programming Interface.
 *
 * <p>Every failing card endpoint returns these three components: the Hypertext Transfer
 * Protocol status code, one message, and the route that failed. The caller that builds the
 * record sets all three components.
 *
 * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} holds one text, so this
 * record holds one text.
 *
 * @param status the status code of the failing response
 * @param message the first failing message, one of the texts {@link CardValidationMessages}
 *        declares, carried character for character. A response carries this one message and
 *        no other.
 * @param route the route template of the failing endpoint, with each path variable left as its
 *        brace-delimited name. The caller supplies the mapping pattern, for example
 *        {@code /cards} or {@code /cards/{cardNumber}}, so no card number and no account identifier
 *        reaches the response body or a log line that copies it. The two routes that name one card
 *        carry it as a path variable, and the template holds the name {@code cardNumber} in place of
 *        the value.
 */
public record ApiErrorResponse(int status, String message, String route) {

    /** Longest run of digits a route template holds. A path variable name holds none. */
    private static final int MAXIMUM_DIGIT_RUN = 4;

    /**
     * Checks that the route holds a template and not a resolved path.
     *
     * @throws NullPointerException     when {@code route} is null
     * @throws IllegalArgumentException when {@code route} holds a run of more than four digits,
     *                                  the shape of a resolved card number or account identifier
     */
    public ApiErrorResponse {
        if (route == null) {
            throw new NullPointerException("route is required");
        }
        int run = 0;
        for (int position = 0; position < route.length(); position++) {
            char character = route.charAt(position);
            run = character >= '0' && character <= '9' ? run + 1 : 0;
            if (run > MAXIMUM_DIGIT_RUN) {
                throw new IllegalArgumentException("route holds a run of more than "
                        + MAXIMUM_DIGIT_RUN + " digits and a route template holds a path"
                        + " variable name");
            }
        }
    }
}
