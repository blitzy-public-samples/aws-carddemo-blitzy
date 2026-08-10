package com.carddemo.card.api.dto;

import com.carddemo.events.EventEnvelope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound payload for {@code PUT /cards/{cardToken}}.
 *
 * <p>Five components carry the update. They appear in the order the source payload group declares
 * them. That group is {@code CCUP-NEW-CARDDATA}, opened at {@code app/cbl/COCRDUPC.cbl:L307}, and
 * the {@code CCUP-NEW-EXPIRAION-DATE} subgroup opened at L309 holds the three expiry parts. The
 * group holds exactly these five items, at L308, L310, L311, L312 and L313.
 *
 * <p>The card number is not a component of this record, and it is not the path variable either. In
 * the source it is: transaction {@code CCUP} at {@code app/csd/CARDDEMO.CSD:L367-L369} names the row
 * by its card number. Here the path carries the CARD TOKEN, so no Primary Account Number (PAN)
 * reaches a request line. {@code api/CardController} holds that token to its width and character
 * class, resolves it to the stored row, and passes that row's card number to
 * {@code domain/CardUpdateService}, which still runs step one of the source edit chain over the
 * resolved number. The decision is recorded under "Every route that names one card names it by that
 * card's token" in {@code card-platform/docs/decision-log.md}.
 *
 * <p>The three expiry parts stay separate. The card update program joins year, month and day with
 * hyphens into one ten-character date at {@code app/cbl/COCRDUPC.cbl:L1467-L1474}, and the caller
 * performs that step.
 *
 * <p>This record declares the shape and the constraints, and the caller holds the edit order and
 * surfaces the first failing message. Every constraint below names a constant from
 * {@link CardValidationMessages}. Rationale for the body-carried identity and for the caller
 * holding the edit order: {@code card-platform/docs/decision-log.md}.
 *
 * <p>One field of the source payload stays absent. The source declares a card verification value
 * at {@code app/cbl/COCRDUPC.cbl:L306} and this record accepts no component for it. The card
 * update map {@code app/bms/COCRDUP.bms} declares no field for it either, and
 * {@code entity/CardEntity#applyUpdate} changes it never.
 *
 * <p>The expiry day carries the width of its source field and no calendar rule. The source edits
 * the name, the active status, the expiry month and the expiry year, and it edits no day, so this
 * record adds no test of which days a month holds. It does bound the day to the two characters
 * {@code CCUP-NEW-EXPDAY PIC X(2)} holds, because a 3270 field two characters wide cannot deliver a
 * third character and a Representational State Transfer request can.
 *
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
 */
public record CardUpdateRequest(

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
     * <p>The rendering a record carries by default prints all five components. Among them sit the
     * cardholder's embossed name and the expiry date in three parts. A name and an expiry date are
     * two of the three values a card-not-present authorization asks for, so the default rendering of
     * this one record carries most of what it takes to use the card. A log line, an assertion
     * failure, a debugger view or the message of an exception that interpolated the request would
     * persist all of it.
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
        for (String value : new String[] {embossedName, expiryYear, expiryMonth, expiryDay,
                activeStatus}) {
            if (value != null && !value.isBlank()) {
                supplied++;
            }
        }
        return "CardUpdateRequest[" + supplied + " of 5 components supplied, every value "
                + EventEnvelope.WITHHELD + "]";
    }
}
