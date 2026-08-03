package com.carddemo.card.api.dto;

/**
 * One row of the card list.
 *
 * <p>This record carries three fields. The forward card browse projects exactly these three into its
 * screen row table at {@code app/cbl/COCRDLIC.cbl:L1165-L1171}, and the backward browse repeats them
 * at {@code app/cbl/COCRDLIC.cbl:L1338-L1344}. The source list carries no embossed name, no expiry
 * date and no card verification value.
 *
 * <p>The three field widths come from the card record copybook {@code app/cpy/CVACT02Y.cpy}:
 * {@code CARD-NUM PIC X(16)} at L5, {@code CARD-ACCT-ID PIC 9(11)} at L6 and
 * {@code CARD-ACTIVE-STATUS PIC X(01)} at L10. That copybook ends with a 59-byte trailing
 * {@code FILLER} at L11, which this record drops.
 *
 * <p>{@link MaskedCardNumber} serializes as a plain string, so a response body carries the same
 * shape it carried before that type existed.
 *
 * @param cardNumber   the masked card number, typed {@link MaskedCardNumber}. That type accepts the
 *                     masked form only, so this row cannot hold a full Primary Account Number
 *                     (PAN). The caller masks first, with
 *                     {@code com.carddemo.cobol.PanMasker.maskCardNumber}. Lookups and filters run
 *                     on the full sixteen characters, before the row is built.
 * @param accountId    the eleven-digit identifier of the account the card belongs to, carried as
 *                     text. {@code CARD-ACCT-ID PIC 9(11)} is numeric display, so
 *                     {@code 00000000050} holds three significant digits behind eight leading
 *                     zeros. Text keeps those zeros through serialization, and the same
 *                     eleven-character form appears in the {@code accountId} of every event schema
 *                     and in the Kafka message key.
 * @param activeStatus the one-character active status, carried through with no interpretation.
 */
public record CardSummary(MaskedCardNumber cardNumber, String accountId, String activeStatus) {

    /** Digits an account identifier holds, from {@code CARD-ACCT-ID PIC 9(11)} at L6. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Checks that the account identifier holds exactly eleven digits.
     *
     * @throws NullPointerException     when {@code accountId} is null
     * @throws IllegalArgumentException when {@code accountId} is any other width, or holds a
     *                                  character other than an ASCII digit
     */
    public CardSummary {
        if (accountId == null) {
            throw new NullPointerException("accountId is required");
        }
        if (accountId.length() != ACCOUNT_ID_DIGITS) {
            throw new IllegalArgumentException("accountId holds " + accountId.length()
                    + " characters and CARD-ACCT-ID holds " + ACCOUNT_ID_DIGITS);
        }
        for (int position = 0; position < accountId.length(); position++) {
            char digit = accountId.charAt(position);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("accountId holds a character other than a digit"
                        + " at position " + (position + 1));
            }
        }
    }
}
