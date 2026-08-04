package com.carddemo.card.api.dto;

import com.carddemo.cobol.PanMasker;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Error payload returned by the card service Application Programming Interface.
 *
 * <p>Every failing card endpoint returns these three components: the Hypertext Transfer
 * Protocol status code, one message, and the route that failed. The caller that builds the
 * record sets all three components.
 *
 * <p>The message component replaces the single working-storage field
 * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, whose blank-state
 * condition name {@code WS-RETURN-MSG-OFF} appears on line 174.
 * {@code app/cbl/COCRDSLC.cbl} declares the counterpart field at line 134. That field holds
 * one text, so this component holds one text. {@link CardValidationMessages} records which
 * writes to the source field carry a guard and which do not.
 *
 * @param status the status code of the failing response.
 * @param message the first failing message, one of the texts {@link CardValidationMessages}
 *        declares, carried character for character. A response carries this one message and
 *        no other.
 * @param route the route template of the failing endpoint, with each path variable left as its
 *        brace-delimited name. The caller supplies the mapping pattern, for example
 *        {@code /cards} or {@code /cards/detail}, so no card number and no account identifier
 *        reaches the response body or a log line that copies it. No route of this service carries a
 *        card number as a path variable at all: a card number arrives in a request body.
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
