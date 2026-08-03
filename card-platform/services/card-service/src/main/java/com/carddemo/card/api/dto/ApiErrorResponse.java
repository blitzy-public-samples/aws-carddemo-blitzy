package com.carddemo.card.api.dto;

/**
 * Error payload returned by the card service Application Programming Interface.
 *
 * <p>Every failing card endpoint returns these three components: the Hypertext Transfer
 * Protocol status code, one message, and the request path. The exception handler methods on
 * {@code CardController} build the record and set the status code.
 *
 * <p>The message component carries the first failing message. That component replaces the
 * single working-storage field {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COCRDUPC.cbl:L173}, whose blank-state condition name
 * {@code WS-RETURN-MSG-OFF} appears on line 174. {@code app/cbl/COCRDSLC.cbl} declares the
 * counterpart field at line 134.
 *
 * <p>The card update program wraps every message write in {@code IF WS-RETURN-MSG-OFF} and
 * writes only while the field is still blank. Those fifteen guards run between lines 730 and
 * 1445 of {@code app/cbl/COCRDUPC.cbl}, so the first failing edit keeps the field.
 * {@code CardUpdateService} holds the edit order and picks that first message. A new edit
 * adds a constraint and its message text, and leaves this record unchanged.
 *
 * <p>The status code each source outcome maps to carries an entry in
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param status the status code of the failing response.
 * @param message the first failing message, one of the texts {@link CardValidationMessages}
 *        declares, carried character for character. A response carries this one message and
 *        no other.
 * @param path the request path that produced the failing response.
 */
public record ApiErrorResponse(int status, String message, String path) {
}
