package com.carddemo.card.api.dto;

import com.carddemo.events.EventEnvelope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound payload for {@code POST /cards/detail}.
 *
 * <p>Two components carry the search, in the order the source edits them. The card detail program
 * {@code app/cbl/COCRDSLC.cbl} performs {@code 2210-EDIT-ACCOUNT} first at
 * {@code app/cbl/COCRDSLC.cbl:L630-L631} and {@code 2220-EDIT-CARD} second at
 * {@code app/cbl/COCRDSLC.cbl:L633-L634}, both from {@code 2200-EDIT-MAP-INPUTS} at
 * {@code app/cbl/COCRDSLC.cbl:L608}, and the two components below appear in that order.
 *
 * <p>The card number arrives here, in the body, and not as a path variable. A path reaches an
 * access log, a reverse proxy log, a distributed trace, a browser history and the instance member
 * of a default error document, and a full Primary Account Number (PAN) belongs in none of them; a
 * request body reaches none of those by default. The route is a {@code POST} for that reason alone
 * and changes nothing, which {@code config/SecurityConfig} records beside the rule that admits it.
 *
 * <p>Both components are required. {@code app/cbl/COCRDSLC.cbl:L651-L657} refuses a missing account
 * and {@code app/cbl/COCRDSLC.cbl:L691-L697} refuses a missing card, so neither is optional here
 * even though the same two fields act as optional filters on the card list screen of
 * {@code app/cbl/COCRDLIC.cbl}.
 *
 * <p>Two rules sit above these constraints and the caller applies both, because neither is a
 * property of one field on its own.
 *
 * <ul>
 * <li><b>An all-zero value counts as no value.</b> The blank test of each edit reads three
 * conditions, and the third is the numeric redefine against zero:
 * {@code CC-ACCT-ID-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:L653} and
 * {@code CC-CARD-NUM-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:L693}. Eleven zeros and sixteen
 * zeros are therefore refused as absent rather than as malformed, and the pattern below admits
 * both.</li>
 * <li><b>Both components absent take one text.</b>
 * {@code app/cbl/COCRDSLC.cbl:L637-L640} tests {@code FLG-ACCTFILTER-BLANK AND
 * FLG-CARDFILTER-BLANK} after both edits have run and under no guard, so it overwrites whatever
 * either edit wrote, and the answer is
 * {@link CardValidationMessages#NO_SEARCH_CRITERIA_RECEIVED}.</li>
 * </ul>
 *
 * <p>{@code api/CardController} holds both rules, and this record holds the two field constraints.
 *
 * <p>The read that follows keys on the card number alone.
 * {@code app/cbl/COCRDSLC.cbl:L740} moves the card number into the read key and the account move
 * above it at {@code app/cbl/COCRDSLC.cbl:L739} is commented out, so the account component is
 * edited and then not used as a key. The key is reproduced and the account is compared after the
 * read: a row belonging to another account is answered as an absent row. The source needed no such
 * comparison because it granted every signed-on user every card; here the account is a
 * caller-supplied key, and an unchecked one would let an entitlement for one card reach a row under
 * any account identifier a caller cared to send. {@code api/CardController#readCard} carries the
 * citations and the register of flagged rules carries the departure.
 *
 * @param accountId the eleven-digit account identifier, from {@code CC-ACCT-ID PIC X(11)} at
 *        {@code app/cpy/CVCRD01Y.cpy:L34}. A missing value takes
 *        {@link CardValidationMessages#PROMPT_FOR_ACCT}, set at
 *        {@code app/cbl/COCRDSLC.cbl:L657}, and a value holding any other character takes
 *        {@link CardValidationMessages#ACCOUNT_FILTER_NOT_NUMERIC}, moved into the message field at
 *        {@code app/cbl/COCRDSLC.cbl:L669-L671}. The test at {@code app/cbl/COCRDSLC.cbl:L665}
 *        reads {@code IS NOT NUMERIC} on an eleven-character alphanumeric field, so a shorter value
 *        arrives space-padded and fails it, which is why the pattern requires all eleven digits.
 * @param cardNumber the sixteen-digit card number, from {@code CC-CARD-NUM PIC X(16)} at
 *        {@code app/cpy/CVCRD01Y.cpy:L37}. A missing value takes
 *        {@link CardValidationMessages#PROMPT_FOR_CARD}, set at
 *        {@code app/cbl/COCRDSLC.cbl:L697}, and a value holding any other character takes
 *        {@link CardValidationMessages#CARD_FILTER_NOT_NUMERIC}, moved into the message field at
 *        {@code app/cbl/COCRDSLC.cbl:L710-L712}. {@code app/cbl/COCRDSLC.cbl:L706} tests the width
 *        and the character class and nothing else, so no checksum rule appears here: adding one
 *        would refuse a card the source accepts.
 */
public record CardDetailRequest(

        @NotBlank(message = CardValidationMessages.PROMPT_FOR_ACCT)
        @Pattern(regexp = "[0-9]{11}",
                message = CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC)
        String accountId,

        @NotBlank(message = CardValidationMessages.PROMPT_FOR_CARD)
        @Pattern(regexp = "[0-9]{16}",
                message = CardValidationMessages.CARD_FILTER_NOT_NUMERIC)
        String cardNumber) {

    /**
     * Returns a rendering that names which components arrived and withholds both values.
     *
     * <p>The rendering a record carries by default prints the full sixteen-digit Primary Account
     * Number beside the account identifier it belongs to. A log line, an assertion failure, a
     * debugger view or the message of an exception that interpolated the request would persist
     * both.
     *
     * <p>Nothing is masked rather than withheld. Masking needs a value of the declared width, and
     * this record exists to carry values that have not yet passed their constraints, so a malformed
     * number would either break the masker or be printed as it arrived. What is reported instead is
     * whether each component arrived, which separates an empty request from a complete one and
     * identifies no card and no account.
     *
     * @return one line naming the class and, for each component, whether a value arrived
     */
    @Override
    public String toString() {
        return "CardDetailRequest[accountId=" + presence(accountId)
                + ", cardNumber=" + presence(cardNumber) + "]";
    }

    /**
     * Reports whether one component arrived, and never what it holds.
     *
     * @param value the component to report on
     * @return {@code absent} when the component holds no character, and the withheld marker
     *         otherwise
     */
    private static String presence(String value) {
        return value == null || value.isBlank() ? "absent" : EventEnvelope.WITHHELD;
    }
}
