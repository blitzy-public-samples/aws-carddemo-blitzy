package com.carddemo.account.api.dto;

/**
 * Response body of the account update operation. Two components carry one message and the resulting
 * account state.
 *
 * <p>The message component models {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479}. Its condition name {@code WS-RETURN-MSG-OFF VALUE SPACES} at
 * {@code app/cbl/COACTUPC.cbl:L480} marks the slot open. Every message that program writes sits
 * under an {@code IF WS-RETURN-MSG-OFF} guard, at {@code app/cbl/COACTUPC.cbl:L2188},
 * {@code app/cbl/COACTUPC.cbl:L2206} and {@code app/cbl/COACTUPC.cbl:L2454} among others.
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L876} reopens the slot once per validation pass. One message
 * survives a pass, whichever edit fires first. The component carries that one message.
 *
 * <p>{@code WS-INFO-MSG PIC X(40)} at {@code app/cbl/COACTUPC.cbl:L463} is the second message slot
 * of that program. Its two outcome conditions, {@code 'Changes committed to database'} at
 * {@code app/cbl/COACTUPC.cbl:L474-L475} and {@code 'Changes unsuccessful. Please try again'} at
 * {@code app/cbl/COACTUPC.cbl:L476-L477}, reach the same message component here. Its four screen
 * prompts at {@code app/cbl/COACTUPC.cbl:L466-L473} reach no component of this record.
 *
 * <p>A message arrives character for character. No trim, no case fold, no width pad and no reformat
 * happens here. {@code app/cbl/COACTUPC.cbl:L2209} supplies {@code ' is not valid'} with no
 * trailing period, and {@code app/cbl/COACTUPC.cbl:L2191} supplies {@code ' must be supplied.'}
 * with one. {@code app/cbl/COACTUPC.cbl:L1515} moves a 26-character literal into
 * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53}, so a message built
 * from that name opens {@code Current Cycle Credit Limi}.
 *
 * @param message the one message a validation pass produced, up to 75 characters wide, from
 *                {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479}. An empty
 *                message marks the update accepted, matching
 *                {@code WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}.
 *                {@code api/AccountController.answerOf} substitutes
 *                {@code AccountController.UPDATE_APPLIED_MESSAGE} for that empty slot, so every
 *                response this service writes carries a text of at least one character.
 * <p>Two properties of the components are worth stating, because both are decisions rather than
 * defaults. The one message is the whole error contract: {@link AccountUpdateRequest} declares no
 * constraint and no cascade so that the ordered pass in
 * {@code domain/AccountUpdateService.editMapInputs} is the only validator, and it stops at the first
 * failure. A second, unordered pass would produce a set of messages this component cannot carry. And
 * every monetary value of the embedded {@link AccountView} reaches the wire as a two-decimal string
 * rather than as a JavaScript Object Notation number, so a balance cannot lose its scale to a binary
 * floating-point type in a caller's parser.
 *
 * @param account the account row read again after the write, as {@link AccountView}, so a caller
 *                reads it as it now stands. Null when that second read found no row.
 *                {@code src/main/resources/openapi.yaml} declares the component required and admits
 *                the null, because a record serializes every component and this one reaches the wire
 *                on every response.
 */
public record AccountUpdateResponse(String message, AccountView account) {}
