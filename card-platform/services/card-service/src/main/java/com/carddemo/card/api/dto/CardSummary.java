package com.carddemo.card.api.dto;

import java.math.BigDecimal;

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
 * <p>The masked card number and the dropped trailing filler each carry an entry in
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param cardNumber   the masked card number. {@code CardController} applies
 *                     {@code com.carddemo.cobol.PanMasker} before it builds this record. This record
 *                     masks nothing itself. Lookups and filters run on the full sixteen characters.
 * @param accountId    the eleven-digit identifier of the account the card belongs to.
 * @param activeStatus the one-character active status, carried through with no interpretation.
 */
public record CardSummary(String cardNumber, BigDecimal accountId, String activeStatus) {
}
