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
 * <p>Rationale for the shape of this record: {@code card-platform/docs/decision-log.md}. Field
 * mapping and the source constructs that reach no component:
 * {@code card-platform/docs/traceability-matrix.md}. Source findings behind the message text:
 * {@code card-platform/docs/business-rule-flags.md}.
 *
 * @param message the one message a validation pass produced, up to 75 characters wide, from
 *                {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479}. An empty
 *                message marks the update accepted, matching
 *                {@code WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}.
 *                Nullable.
 * @param account the resulting account state, as {@link AccountView}. Populated on an accepted
 *                update. Nullable.
 */
public record AccountUpdateResponse(String message, AccountView account) {}
