package com.carddemo.notification.api;

/**
 * Error payload returned by the notification service Application Programming Interface.
 *
 * <p>Three components, the same three the card service returns: the Hypertext Transfer Protocol
 * status code, one message, and the route that failed. One shape across the platform lets a caller
 * read either service's failure without a second parser.
 *
 * <p>The route component holds the route template, with each path variable left as its
 * brace-delimited name. The framework's own problem detail carries the resolved request path
 * instead, and a resolved path holds the value the caller sent. This record carries the template,
 * so no card number reaches the response body or a log line that copies it.
 *
 * @param status the status code of the failing response
 * @param message one text describing the failure, naming no value read from the request
 * @param route the route template of the failing endpoint, for example
 *        {@code /notifications/{cardToken}}
 */
public record ApiErrorResponse(int status, String message, String route) {

    /** Longest run of digits a route template holds. A path variable name holds none. */
    private static final int MAXIMUM_DIGIT_RUN = 4;

    /**
     * Checks that the route holds a template and not a resolved path.
     *
     * @throws NullPointerException when {@code message} or {@code route} is null
     * @throws IllegalArgumentException when {@code route} holds a run of more than
     *         {@value #MAXIMUM_DIGIT_RUN} digits, the shape of a resolved card number
     */
    public ApiErrorResponse {
        if (message == null) {
            throw new NullPointerException("message is required");
        }
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
