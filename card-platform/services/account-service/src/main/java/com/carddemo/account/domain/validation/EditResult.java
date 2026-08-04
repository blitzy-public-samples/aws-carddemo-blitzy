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
 * <p>The message reaches the caller exactly as the edit supplied it. Leading spaces,
 * trailing spaces, and punctuation all survive unchanged. {@code PIC X(75)} is the
 * storage width of the fixed-width source field; this type sets no width limit.</p>
 *
 * <p>The canonical constructor accepts any combination of the two components and
 * enforces no relationship between them. {@link #ok()} and {@link #failure(String)}
 * build the two combinations this package uses.</p>
 *
 * @param valid   true when the field passed the edit
 * @param message the text the edit produced; nullable
 */
public record EditResult(boolean valid, String message) {

    /**
     * Verdict for a field that passed its edit.
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
     * Reports whether the message slot holds text. The test is
     * {@code message != null && !message.isBlank()}.
     *
     * <p>{@link String#isBlank()} is true for an empty string and for a string of white space only.
     * This method therefore returns false for a null message, an empty message and a white-space
     * message alike.</p>
     *
     * @return true when the message holds at least one character that is not white space
     */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
