package com.carddemo.account.domain.validation;

/**
 * Outcome of one field edit: the verdict, and at most one message.
 *
 * <p>Models {@code WS-RETURN-MSG}, declared {@code PIC X(75)} at
 * app/cbl/COACTUPC.cbl:L479, together with its condition name
 * {@code WS-RETURN-MSG-OFF VALUE SPACES} at app/cbl/COACTUPC.cbl:L480. An
 * all-spaces slot marks the slot available. app/cbl/COACTUPC.cbl:L876 marks it
 * available once per validation pass, so a pass carries the first message it
 * produces and drops later ones.</p>
 *
 * <p>Every field edit in this package returns this type, and a new edit is a new
 * class that returns it. The message reaches the caller exactly as the edit
 * supplied it. Leading spaces, trailing spaces, and punctuation all survive
 * unchanged. {@code PIC X(75)} is the storage width of the fixed-width source
 * field; this type sets no width limit.</p>
 *
 * <p>Rationale for the single message slot: card-platform/docs/decision-log.md.</p>
 *
 * @param valid   true when the field passed the edit
 * @param message the text the edit produced, null when the edit passed
 */
public record EditResult(boolean valid, String message) {

    /**
     * Verdict for a field that passed its edit. Carries no message.
     *
     * @return a passing verdict whose message is null
     */
    public static EditResult ok() {
        return new EditResult(true, null);
    }

    /**
     * Verdict for a field that failed its edit.
     *
     * @param message the text to carry, stored character for character
     * @return a failing verdict carrying the supplied message
     */
    public static EditResult failure(String message) {
        return new EditResult(false, message);
    }

    /**
     * Reports whether the message slot holds text. This is the inverse of
     * {@code WS-RETURN-MSG-OFF VALUE SPACES} at app/cbl/COACTUPC.cbl:L480. A
     * null message and a message of only white space both mark the slot
     * available, so this method returns false for both.
     *
     * @return true when the message holds at least one character that is not
     *         white space
     */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
