package com.carddemo.card.domain;

import java.io.Serial;

/**
 * Reports a list filter that carries something other than the digits its Picture clause admits.
 *
 * <p>Transformed from the two filter edits of the card list program. {@code 2210-EDIT-ACCOUNT} at
 * {@code app/cbl/COCRDLIC.cbl:L1003-L1030} tests {@code IF CC-ACCT-ID IS NOT NUMERIC} at
 * {@code app/cbl/COCRDLIC.cbl:L1017}, and {@code 2220-EDIT-CARD} at
 * {@code app/cbl/COCRDLIC.cbl:L1036-L1066} tests {@code IF CC-CARD-NUM IS NOT NUMERIC} at
 * {@code app/cbl/COCRDLIC.cbl:L1052}. Each sets {@code INPUT-ERROR} and moves one message.
 *
 * <p>A blank filter and an all-zero filter are absent rather than invalid.
 * {@code app/cbl/COCRDLIC.cbl:L1007-L1013} and {@code app/cbl/COCRDLIC.cbl:L1042-L1048} take that
 * branch and set the blank condition, so neither reaches this type.
 *
 * <p>{@link #filter()} names which edit failed. The message text of each edit belongs to the
 * boundary that answers the caller, and this type carries none: the width and the field name are
 * what a diagnostic needs, and neither is a value a caller sent.
 *
 * <p>The account edit runs first and moves its message unconditionally at
 * {@code app/cbl/COCRDLIC.cbl:L1021-L1023}, while the card edit moves its own behind
 * {@code IF WS-ERROR-MSG-OFF} at {@code app/cbl/COCRDLIC.cbl:L1056}. A request whose two filters are
 * both invalid therefore reports the account edit, which is the order the caller of this type
 * follows.
 */
public final class CardFilterRejectedException extends IllegalArgumentException {

    /** Version of the serialized form, which no caller writes. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Which filter edit failed. */
    private final Filter filter;

    /**
     * Builds a rejection naming one filter and the width its Picture clause declares.
     *
     * @param filter which filter edit failed
     * @param width  the width the Picture clause declares, named in the message
     */
    public CardFilterRejectedException(Filter filter, int width) {
        super(filter.fieldName() + " holds something other than " + width + " digits");
        this.filter = filter;
    }

    /**
     * Returns which filter edit failed.
     *
     * @return the failing filter, never {@code null}
     */
    public Filter filter() {
        return filter;
    }

    /** The two filters the card list applies. */
    public enum Filter {

        /**
         * The account identifier filter, from {@code CC-ACCT-ID} tested at
         * {@code app/cbl/COCRDLIC.cbl:L1017}.
         */
        ACCOUNT_ID("accountIdFilter"),

        /**
         * The card number filter, from {@code CC-CARD-NUM} tested at
         * {@code app/cbl/COCRDLIC.cbl:L1052}.
         */
        CARD_NUMBER("cardNumberFilter");

        /** The field name a diagnostic reports, which is no value a caller sent. */
        private final String fieldName;

        /**
         * Binds one filter to the field name a diagnostic reports.
         *
         * @param fieldName the field name
         */
        Filter(String fieldName) {
            this.fieldName = fieldName;
        }

        /**
         * Returns the field name a diagnostic reports.
         *
         * @return the field name, never blank
         */
        public String fieldName() {
            return fieldName;
        }
    }
}
