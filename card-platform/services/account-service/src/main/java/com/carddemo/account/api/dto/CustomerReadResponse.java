package com.carddemo.account.api.dto;

/**
 * Response body of the customer read operation. Two components carry one message and the customer
 * state the read returned.
 *
 * <p>The message component models {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTVWC.cbl:L117}. Paragraph {@code 9400-GETCUSTDATA-BYCUST} at
 * {@code app/cbl/COACTVWC.cbl:L825} writes the one message a failed read produces.
 *
 * <p>A customer the master file does not hold reaches the message built at
 * {@code app/cbl/COACTVWC.cbl:L846-L856}, and no customer state accompanies it.
 *
 * <p>A message arrives character for character. The literal at
 * {@code app/cbl/COACTVWC.cbl:L850} reads {@code ' in customer master.Resp: '} with one trailing
 * space, and {@code app/cbl/COACTVWC.cbl:L852} reads {@code ' REAS:'} in upper case.
 *
 * <p>Design decisions for the shape of this record: {@code card-platform/docs/decision-log.md}.
 *
 * @param message  the one message a read produced, up to 75 characters wide, from
 *                 {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTVWC.cbl:L117}. An empty
 *                 message marks the read successful. Nullable.
 * @param customer the customer state the read returned, as {@link CustomerView}. Populated on a
 *                 successful read. Nullable.
 */
public record CustomerReadResponse(String message, CustomerView customer) {}
