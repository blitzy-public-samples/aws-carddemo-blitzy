package com.carddemo.account.api.dto;

/**
 * Response body of the account read operation. Two components carry one message and the account
 * state the read returned.
 *
 * <p>The message component models {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTVWC.cbl:L117}, which {@code app/cbl/COACTVWC.cbl:L532} sends to the screen.
 * Every message that program writes sits under an {@code IF WS-RETURN-MSG-OFF} guard, so one
 * message survives a pass and this component carries it.
 *
 * <p>An account the master file does not hold reaches the message built at
 * {@code app/cbl/COACTVWC.cbl:L796-L806}, and no account state accompanies it.
 *
 * <p>A message arrives character for character. No trim, no case fold, no width pad and no reformat
 * happens here. The literal at {@code app/cbl/COACTVWC.cbl:L800} reads
 * {@code ' Acct Master file.Resp:'} with no space after the period.
 *
 * <p>Design decisions for the shape of this record: {@code card-platform/docs/decision-log.md}.
 *
 * @param message the one message a read produced, up to 75 characters wide, from
 *                {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTVWC.cbl:L117}. An empty
 *                message marks the read successful. Nullable.
 * @param account the account state the read returned, as {@link AccountView}. Populated on a
 *                successful read. Nullable.
 */
public record AccountReadResponse(String message, AccountView account) {}
