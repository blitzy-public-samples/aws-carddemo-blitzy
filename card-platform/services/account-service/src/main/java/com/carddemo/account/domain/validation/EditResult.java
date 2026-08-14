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
 * <p>The message reaches the caller exactly as the edit supplied it, spaces and punctuation
 * included. {@code PIC X(75)} is the storage width of the source field; this type sets no width
 * limit.</p>
 *
 * @param valid   true when the field passed the edit
 * @param message the text the edit produced; nullable
 */
public record EditResult(boolean valid, String message) {

    /**
     * @return a passing verdict whose message is null
     */
    public static EditResult ok() {
        return new EditResult(true, null);
    }

    /**
     * @param message the text to carry, stored character for character
     * @return a failing verdict carrying the supplied message
     */
    public static EditResult failure(String message) {
        return new EditResult(false, message);
    }

    /**
     * @return true when the message holds at least one character that is not white space, so a
     *         null, empty or all-space message reads false alike
     */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
