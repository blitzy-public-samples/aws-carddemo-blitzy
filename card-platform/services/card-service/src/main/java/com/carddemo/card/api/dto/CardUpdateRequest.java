package com.carddemo.card.api.dto;

import com.carddemo.events.EventEnvelope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound payload for {@code PUT /cards}.
 *
 * <p>Six components carry the update. They appear in the order the source payload group declares
 * them. That group is {@code CCUP-NEW-CARDDATA}, opened at {@code app/cbl/COCRDUPC.cbl:L307}, and
 * the {@code CCUP-NEW-EXPIRAION-DATE} subgroup opened at L309 holds the three expiry parts.
 *
 * <p>The card number arrives here, in the body, and not as a path variable. A path reaches an
 * access log, a reverse proxy log, a distributed trace, a browser history and the instance member
 * of a default error document. A full Primary Account Number (PAN) belongs in none of them, and a
 * request body reaches none of them by default. The route is therefore {@code PUT /cards} with the
 * key inside the payload. That also matches the source, where
 * {@code CCUP-NEW-CARDID PIC X(16)} at {@code app/cbl/COCRDUPC.cbl:L305} is part of the same
 * payload group as the five fields below.
 *
 * <p>The three expiry parts stay separate. The card update program joins year, month and day with
 * hyphens into one ten-character date at {@code app/cbl/COCRDUPC.cbl:L1467-L1474}, and the caller
 * performs that step.
 *
 * <p>This record declares the shape and the constraints, and the caller holds the edit order and
 * surfaces the first failing message. Every constraint below names a constant from
 * {@link CardValidationMessages}, so a new edit costs one annotation here and one constant there.
 *
 * <p>One field of the source payload stays absent. The source declares a card verification value
 * at {@code app/cbl/COCRDUPC.cbl:L306} and this record accepts no component for it. The card
 * update map {@code app/bms/COCRDUP.bms} declares no field for it either, and
 * {@code entity/CardEntity#applyUpdate} changes it never.
 *
 * <p>The card-number component carries the width and character class the source tests and nothing
 * more. {@code app/cbl/COCRDUPC.cbl:L784} tests a card number for sixteen digits only, so no
 * checksum rule appears here: adding one would refuse a card the source accepts.
 *
 * <p>The expiry day carries the width of its source field and no calendar rule. The source edits
 * the name, the active status, the expiry month and the expiry year, and it edits no day, so this
 * record adds no test of which days a month holds. It does bound the day to the two characters
 * {@code CCUP-NEW-EXPDAY PIC X(2)} holds, because a 3270 field two characters wide cannot deliver a
 * third character and a Representational State Transfer request can.
 *
 * @param cardNumber the sixteen-digit card number this update names, from
 *        {@code CCUP-NEW-CARDID PIC X(16)} at {@code app/cbl/COCRDUPC.cbl:L305} and held as
 *        {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. A missing value takes
 *        {@link CardValidationMessages#PROMPT_FOR_CARD}, the text at
 *        {@code app/cbl/COCRDUPC.cbl:L180} set at L774. A value that is not sixteen digits takes
 *        {@link CardValidationMessages#CARD_FILTER_NOT_NUMERIC}, the text at L789 set at L790
 *        under the test at L784. The value reaches no response body and no log line. A response
 *        carries the masked form, which {@link CardDetailResponse} and {@link CardSummary} enforce
 *        in their canonical constructors, and {@link ApiErrorResponse} refuses a route that holds
 *        a resolved identifier.
 * @param embossedName cardholder name embossed on the card, from
 *        {@code CCUP-NEW-CRDNAME PIC X(50)} at {@code app/cbl/COCRDUPC.cbl:L308} and held as
 *        {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}, which fixes the
 *        width at 50. The pattern admits up to fifty characters, each an upper-case letter, a
 *        lower-case letter or a space, matching the 52-character {@code LIT-ALL-ALPHA-FROM} literal
 *        at L255 and L257. Paragraph {@code 1230-EDIT-NAME} copies the value to
 *        {@code CARD-NAME-CHECK PIC X(50)} on L823, converts every letter to a space on L824 through
 *        L826, then accepts only spaces on L828. A missing value takes
 *        {@link CardValidationMessages#PROMPT_FOR_NAME}, set at L817, and any other character takes
 *        {@link CardValidationMessages#NAME_MUST_BE_ALPHA}, set at L834.
 * @param expiryYear four-digit expiry year, from {@code CCUP-NEW-EXPYEAR PIC X(4)} at
 *        {@code app/cbl/COCRDUPC.cbl:L310}. Accepts 1950 through 2099, from
 *        {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at {@code app/cbl/COCRDUPC.cbl:L99}. That
 *        condition name sits on {@code CARD-YEAR-CHECK-N PIC 9(4)}, the numeric redefine on L97 and
 *        L98, and paragraph {@code 1260-EDIT-EXPIRY-YEAR} reads it on L934. A missing value and an
 *        out-of-range value both take
 *        {@link CardValidationMessages#CARD_EXPIRY_YEAR_NOT_VALID}, set at L922 and at L940.
 * @param expiryMonth two-digit expiry month, from {@code CCUP-NEW-EXPMON PIC X(2)} at
 *        {@code app/cbl/COCRDUPC.cbl:L311}. Accepts 1 through 12, from
 *        {@code 88 VALID-MONTH VALUES 1 THRU 12.} at {@code app/cbl/COCRDUPC.cbl:L95}. That
 *        condition name sits on {@code CARD-MONTH-CHECK-N PIC 9(2)}, the numeric redefine on L93 and
 *        L94, and paragraph {@code 1250-EDIT-EXPIRY-MON} reads it on L898. A missing value and an
 *        out-of-range value both take
 *        {@link CardValidationMessages#CARD_EXPIRY_MONTH_NOT_VALID}, set at L889 and at L904.
 * @param expiryDay two-digit expiry day, from {@code CCUP-NEW-EXPDAY PIC X(2)} at
 *        {@code app/cbl/COCRDUPC.cbl:L312}. The source edits the name, the active status, the expiry
 *        month and the expiry year, and it edits no day. Paragraph
 *        {@code 1260-EDIT-EXPIRY-YEAR-EXIT.} closes the edit chain at L945 and
 *        {@code 2000-DECIDE-ACTION.} opens on L948, so no paragraph between them reaches the day.
 *        L621 moves the day in and L1471 joins it into the reassembled date. This component is
 *        bound to two digits and nothing more: no calendar-day rule is applied, so any two digits
 *        pass, including 31 in a thirty-day month, exactly as the source accepts it.
 * @param activeStatus one-character active status, from {@code CCUP-NEW-CRDSTCD PIC X(1)} at
 *        {@code app/cbl/COCRDUPC.cbl:L313}. Accepts upper-case {@code Y} and upper-case {@code N},
 *        from {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at {@code app/cbl/COCRDUPC.cbl:L91}.
 *        Paragraph {@code 1240-EDIT-CARDSTATUS} moves the value to
 *        {@code FLG-YES-NO-CHECK PIC X(1)} on L861 and tests that condition name on L863, and it
 *        folds no case. A missing value and any other character both take
 *        {@link CardValidationMessages#CARD_STATUS_MUST_BE_YES_NO}, set at L856 and at L869.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public record CardUpdateRequest(

        @NotBlank(message = CardValidationMessages.PROMPT_FOR_CARD)
        @Pattern(regexp = "[0-9]{16}",
                message = CardValidationMessages.CARD_FILTER_NOT_NUMERIC)
        String cardNumber,

        @NotBlank(message = CardValidationMessages.PROMPT_FOR_NAME)
        @Pattern(regexp = "[A-Za-z ]{0,50}",
                message = CardValidationMessages.NAME_MUST_BE_ALPHA)
        String embossedName,

        @NotBlank(message = CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID)
        @Pattern(regexp = "[0-9]{4}",
                message = CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID)
        @Min(value = 1950, message = CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID)
        @Max(value = 2099, message = CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID)
        String expiryYear,

        @NotBlank(message = CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID)
        @Pattern(regexp = "[0-9]{2}",
                message = CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID)
        @Min(value = 1, message = CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID)
        @Max(value = 12, message = CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID)
        String expiryMonth,

        @NotBlank(message = CardValidationMessages.ADDITIVE_CARD_EXPIRY_DAY_WIDTH)
        @Pattern(regexp = "[0-9]{2}",
                message = CardValidationMessages.ADDITIVE_CARD_EXPIRY_DAY_WIDTH)
        String expiryDay,

        @NotBlank(message = CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO)
        @Pattern(regexp = "[YN]",
                message = CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO)
        String activeStatus) {

    /**
     * Returns a rendering that names which components arrived and withholds every value.
     *
     * <p>The rendering a record carries by default prints all six components. The first is the
     * full sixteen-digit Primary Account Number, and beside it sit the cardholder's embossed name
     * and the expiry date in three parts. A number, a name and an expiry date together are what a
     * card-not-present authorization asks for, so the default rendering of this one record is
     * enough to use the card. A log line, an assertion failure, a debugger view or the message of
     * an exception that interpolated the request would persist all of it.
     *
     * <p>Nothing is masked here; every value is withheld. Masking needs a value of the declared
     * width, and this record carries values that have not yet passed their constraints. What is
     * reported instead is the count of components that arrived, which separates a sparse request
     * from a full one and identifies no card.
     *
     * @return the count of supplied components, and no value
     */
    @Override
    public String toString() {
        int supplied = 0;
        for (String value : new String[] {cardNumber, embossedName, expiryYear, expiryMonth,
                expiryDay, activeStatus}) {
            if (value != null && !value.isBlank()) {
                supplied++;
            }
        }
        return "CardUpdateRequest[" + supplied + " of 6 components supplied, every value "
                + EventEnvelope.WITHHELD + "]";
    }
}
