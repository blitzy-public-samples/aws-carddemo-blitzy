package com.carddemo.card.api.dto;

import java.util.Objects;

/**
 * Body returned by {@code PUT /cards/{cardNumber}}.
 *
 * <p>Three components carry the result: the outcome, at most one message, and a
 * refreshed snapshot of the stored card. The canonical constructor checks the
 * relationship between the outcome and the snapshot, and the seven factory methods
 * below build the seven outcomes.</p>
 *
 * <p>Paragraph {@code 9300-CHECK-CHANGE-IN-REC} at
 * {@code app/cbl/COCRDUPC.cbl:L1498} re-reads the stored card and compares six
 * saved values at lines 1503 to 1508. Lines 1499 to 1501 fold the freshly read
 * embossed name to upper case before that comparison. Line 1511 marks the record
 * changed, and lines 1512 to 1517 refresh all six saved values.</p>
 *
 * <p>{@link RefreshedCard} carries five of those six values. The card
 * verification value, three numeric digits declared at
 * {@code app/cpy/CVACT02Y.cpy:L7} and compared at
 * {@code app/cbl/COCRDUPC.cbl:L1503}, is absent from this record and from every
 * type nested inside it.</p>
 *
 * <p>The message slot holds one text and never a list. Fifteen
 * {@code IF WS-RETURN-MSG-OFF} guards in {@code app/cbl/COCRDUPC.cbl} let every
 * guarded field edit run and keep the first failing text. The caller runs those
 * edits and picks that text. Every fixed text this record carries comes from
 * {@link CardValidationMessages}.</p>
 *
 * @param outcome what happened to the stored card record. The canonical constructor
 *        rejects null.
 * @param message the single text this response carries; nullable. The canonical
 *        constructor accepts any text with any outcome, and {@link #updated()} is
 *        the one factory that leaves the slot empty.
 * @param refreshedCard the five values re-read from the stored card. The canonical
 *        constructor requires one for
 *        {@link UpdateOutcome#CHANGED_BEFORE_UPDATE} and rejects one for every
 *        other outcome.
 */
public record CardUpdateResponse(
        UpdateOutcome outcome,
        String message,
        RefreshedCard refreshedCard) {

    /**
     * Marks an outcome that carries no fixed text. {@link UpdateOutcome#UPDATED} carries
     * no message at all, and {@link UpdateOutcome#VALIDATION_REJECTED} carries the text
     * of the edit that failed.
     */
    private static final String NO_REQUIRED_MESSAGE = null;

    /**
     * Checks the whole outcome, message and snapshot matrix.
     *
     * <p>{@link UpdateOutcome#UPDATED} carries no message.
     * {@link UpdateOutcome#VALIDATION_REJECTED} carries the text of the failing edit,
     * so any non-blank text is valid there. Each of the five remaining outcomes
     * carries the one fixed text {@link UpdateOutcome#requiredMessage()} names.
     * {@link UpdateOutcome#CHANGED_BEFORE_UPDATE} is the one outcome that carries a
     * snapshot, and it always carries one.</p>
     *
     * @throws NullPointerException when the outcome is null
     * @throws IllegalArgumentException when the message does not match the outcome, or
     *         when {@link UpdateOutcome#CHANGED_BEFORE_UPDATE} arrives with no
     *         snapshot, or when any other outcome arrives with one
     */
    public CardUpdateResponse {
        Objects.requireNonNull(outcome, "outcome is required");
        String requiredMessage = outcome.requiredMessage();
        if (outcome == UpdateOutcome.UPDATED) {
            if (message != null) {
                throw new IllegalArgumentException("UPDATED carries no message");
            }
        } else if (requiredMessage == null) {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException(
                        outcome + " requires the text of the failing edit");
            }
        } else if (!requiredMessage.equals(message)) {
            throw new IllegalArgumentException(
                    outcome + " carries one fixed text and this message is not that text");
        }
        boolean changedByAnotherWriter = outcome == UpdateOutcome.CHANGED_BEFORE_UPDATE;
        if (changedByAnotherWriter && refreshedCard == null) {
            throw new IllegalArgumentException(
                    "CHANGED_BEFORE_UPDATE requires a refreshed card snapshot");
        }
        if (!changedByAnotherWriter && refreshedCard != null) {
            throw new IllegalArgumentException(
                    "CHANGED_BEFORE_UPDATE is the only outcome that carries a refreshed"
                            + " card snapshot, and this outcome is " + outcome);
        }
    }

    /**
     * Reports a rewritten card record.
     *
     * @return an {@link UpdateOutcome#UPDATED} response with no message and no
     *         snapshot
     */
    public static CardUpdateResponse updated() {
        return new CardUpdateResponse(UpdateOutcome.UPDATED, null, null);
    }

    /**
     * Reports that the submitted values match the stored values.
     *
     * @return a {@link UpdateOutcome#NO_CHANGE_DETECTED} response carrying
     *         {@link CardValidationMessages#NO_CHANGES_DETECTED}
     */
    public static CardUpdateResponse noChangeDetected() {
        return new CardUpdateResponse(
                UpdateOutcome.NO_CHANGE_DETECTED,
                CardValidationMessages.NO_CHANGES_DETECTED,
                null);
    }

    /**
     * Reports the first field edit that rejected its value.
     *
     * @param firstFailingMessage text of that edit, taken from
     *        {@link CardValidationMessages} and carried character for character
     * @return a {@link UpdateOutcome#VALIDATION_REJECTED} response carrying that
     *         text
     * @throws NullPointerException when the supplied text is null
     */
    public static CardUpdateResponse validationRejected(String firstFailingMessage) {
        Objects.requireNonNull(firstFailingMessage, "firstFailingMessage is required");
        return new CardUpdateResponse(
                UpdateOutcome.VALIDATION_REJECTED, firstFailingMessage, null);
    }

    /**
     * Reports that no stored card matches the numbers supplied.
     *
     * @return a {@link UpdateOutcome#CARD_NOT_FOUND} response carrying
     *         {@link CardValidationMessages#DID_NOT_FIND_ACCTCARD_COMBO}
     */
    public static CardUpdateResponse cardNotFound() {
        return new CardUpdateResponse(
                UpdateOutcome.CARD_NOT_FOUND,
                CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO,
                null);
    }

    /**
     * Reports that another writer changed the stored card first.
     *
     * @param refreshedCard the five values re-read from the stored card
     * @return a {@link UpdateOutcome#CHANGED_BEFORE_UPDATE} response carrying
     *         {@link CardValidationMessages#DATA_WAS_CHANGED_BEFORE_UPDATE} and the
     *         supplied snapshot
     * @throws NullPointerException when the supplied snapshot is null
     */
    public static CardUpdateResponse changedBeforeUpdate(RefreshedCard refreshedCard) {
        Objects.requireNonNull(refreshedCard, "refreshedCard is required");
        return new CardUpdateResponse(
                UpdateOutcome.CHANGED_BEFORE_UPDATE,
                CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE,
                refreshedCard);
    }

    /**
     * Reports that the read for update did not return the stored card.
     *
     * @return a {@link UpdateOutcome#LOCK_NOT_ACQUIRED} response carrying
     *         {@link CardValidationMessages#COULD_NOT_LOCK_FOR_UPDATE}
     */
    public static CardUpdateResponse lockNotAcquired() {
        return new CardUpdateResponse(
                UpdateOutcome.LOCK_NOT_ACQUIRED,
                CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                null);
    }

    /**
     * Reports that the rewrite failed after the read for update had succeeded.
     *
     * @return an {@link UpdateOutcome#UPDATE_FAILED_AFTER_LOCK} response carrying
     *         {@link CardValidationMessages#LOCKED_BUT_UPDATE_FAILED}
     */
    public static CardUpdateResponse updateFailedAfterLock() {
        return new CardUpdateResponse(
                UpdateOutcome.UPDATE_FAILED_AFTER_LOCK,
                CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                null);
    }

    /**
     * Reports whether the message slot holds text. The test is
     * {@code message != null && !message.isBlank()}, and {@link String#isBlank()} is
     * true for an empty string and for a string of white space only, so a null
     * message, an empty message and a white-space message all report false. The
     * source condition name {@code WS-RETURN-MSG-OFF VALUE SPACES} at
     * {@code app/cbl/COCRDUPC.cbl:L174} tests one fixed-width field for spaces.
     *
     * @return true when the message holds at least one character that is not white
     *         space
     */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }

    /**
     * Reports whether this response carries a refreshed snapshot. Only
     * {@link UpdateOutcome#CHANGED_BEFORE_UPDATE} carries one.
     *
     * @return true when {@link #refreshedCard()} holds a snapshot
     */
    public boolean hasRefreshedCard() {
        return refreshedCard != null;
    }

    /**
     * What happened to the stored card record on this call.
     *
     * <p>Each constant names one outcome the card update program reaches, and cites
     * the line that sets its text.</p>
     */
    public enum UpdateOutcome {

        /**
         * The stored card record was rewritten. The rewrite runs at
         * {@code app/cbl/COCRDUPC.cbl:L1477-L1483} and line 1488 tests it for
         * success. {@link #updated()} builds this outcome with no message and no
         * snapshot.
         */
        UPDATED(NO_REQUIRED_MESSAGE),

        /**
         * The submitted values match the stored values, so nothing was written.
         * Lines 680 and 681 of {@code app/cbl/COCRDUPC.cbl} compare the whole new
         * card group against the whole old card group, after folding both to upper
         * case. Line 682 sets the text.
         *
         * <p>A caller who changes only letter case reaches this outcome, which
         * carries {@link CardValidationMessages#NO_CHANGES_DETECTED}.</p>
         */
        NO_CHANGE_DETECTED(CardValidationMessages.NO_CHANGES_DETECTED),

        /**
         * One field edit rejected its value, so nothing was written. The first of
         * the fifteen guarded edits sits at {@code app/cbl/COCRDUPC.cbl:L730} and
         * the last at line 1445. This outcome carries the text of the first failing
         * edit, taken from {@link CardValidationMessages}.
         */
        VALIDATION_REJECTED(NO_REQUIRED_MESSAGE),

        /**
         * No stored card matches the account and card numbers supplied.
         * {@code app/cbl/COCRDUPC.cbl:L1400} sets the text, under the guard on line
         * 1399, in the not-found branch of the card read. This outcome carries
         * {@link CardValidationMessages#DID_NOT_FIND_ACCTCARD_COMBO}.
         */
        CARD_NOT_FOUND(CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO),

        /**
         * Another writer changed the stored card between the read and the rewrite.
         * The comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508} fails, line
         * 1511 sets the text, and lines 1512 to 1517 refresh the saved values.
         *
         * <p>This is the one outcome that carries a {@link RefreshedCard},
         * alongside
         * {@link CardValidationMessages#DATA_WAS_CHANGED_BEFORE_UPDATE}.</p>
         */
        CHANGED_BEFORE_UPDATE(CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE),

        /**
         * The read for update did not return the stored card, so no rewrite ran.
         * Line 1441 of {@code app/cbl/COCRDUPC.cbl} tests that read and line 1446
         * sets the text, under the guard on line 1445. This outcome carries
         * {@link CardValidationMessages#COULD_NOT_LOCK_FOR_UPDATE}.
         */
        LOCK_NOT_ACQUIRED(CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE),

        /**
         * The rewrite failed after the read for update had succeeded. Line 1488 of
         * {@code app/cbl/COCRDUPC.cbl} tests the rewrite and line 1491 sets the
         * text. This outcome carries
         * {@link CardValidationMessages#LOCKED_BUT_UPDATE_FAILED}.
         */
        UPDATE_FAILED_AFTER_LOCK(CardValidationMessages.LOCKED_BUT_UPDATE_FAILED);

        /**
         * The fixed text this outcome carries, or {@code null} when it carries none and
         * when it carries the text of a failing edit.
         */
        private final String requiredMessage;

        /**
         * Records the fixed text of one outcome.
         *
         * @param requiredMessage the one text this outcome carries, or {@code null} for
         *        {@link #UPDATED} and {@link #VALIDATION_REJECTED}
         */
        UpdateOutcome(String requiredMessage) {
            this.requiredMessage = requiredMessage;
        }

        /**
         * Returns the fixed text this outcome carries.
         *
         * <p>{@link #UPDATED} carries no message and {@link #VALIDATION_REJECTED}
         * carries the text of the failing edit, so both return {@code null}. The
         * canonical constructor of {@link CardUpdateResponse} separates those two.</p>
         *
         * @return the one text this outcome carries, or {@code null}
         */
        public String requiredMessage() {
            return requiredMessage;
        }
    }

    /**
     * The five values re-read from the stored card when another writer changed it
     * first.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L1512-L1517} refreshes the saved values once
     * the comparison at lines 1503 to 1508 fails. This record carries five of the
     * six values refreshed there. Every component holds text, matching the
     * character slices the source compares.</p>
     *
     * <p>The three expiry components slice
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}. The
     * source joins year, month and day with hyphens at
     * {@code app/cbl/COCRDUPC.cbl:L1467-L1473}.</p>
     *
     * @param embossedName cardholder name held on the stored card, up to fifty
     *        characters. Source {@code CARD-EMBOSSED-NAME PIC X(50)} at
     *        {@code app/cpy/CVACT02Y.cpy:L8}, folded to upper case at
     *        {@code app/cbl/COCRDUPC.cbl:L1499-L1501}, compared on line 1504 and
     *        refreshed on line 1513.
     * @param expiryYear four-character year slice. Source
     *        {@code CARD-EXPIRAION-DATE(1:4)}, compared at
     *        {@code app/cbl/COCRDUPC.cbl:L1505} and refreshed on line 1514.
     * @param expiryMonth two-character month slice. Source
     *        {@code CARD-EXPIRAION-DATE(6:2)}, compared at
     *        {@code app/cbl/COCRDUPC.cbl:L1506} and refreshed on line 1515.
     * @param expiryDay two-character day slice. Source
     *        {@code CARD-EXPIRAION-DATE(9:2)}, compared at
     *        {@code app/cbl/COCRDUPC.cbl:L1507} and refreshed on line 1516.
     * @param activeStatus one-character active status flag. Source
     *        {@code CARD-ACTIVE-STATUS PIC X(01)} at
     *        {@code app/cpy/CVACT02Y.cpy:L10}, compared at
     *        {@code app/cbl/COCRDUPC.cbl:L1508} and refreshed on line 1517.
     */
    public record RefreshedCard(
            String embossedName,
            String expiryYear,
            String expiryMonth,
            String expiryDay,
            String activeStatus) {

        /**
         * Checks that every component holds a value. The source moves a value into
         * each saved field at {@code app/cbl/COCRDUPC.cbl:L1513-L1517}.
         *
         * @throws NullPointerException when any component is null
         */
        public RefreshedCard {
            Objects.requireNonNull(embossedName, "embossedName is required");
            Objects.requireNonNull(expiryYear, "expiryYear is required");
            Objects.requireNonNull(expiryMonth, "expiryMonth is required");
            Objects.requireNonNull(expiryDay, "expiryDay is required");
            Objects.requireNonNull(activeStatus, "activeStatus is required");
        }
    }
}
