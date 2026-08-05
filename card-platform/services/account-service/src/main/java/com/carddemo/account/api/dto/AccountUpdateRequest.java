package com.carddemo.account.api.dto;

/**
 * The body of an account update request, as a caller submits it.
 *
 * <p>Transformed from the group {@code 05 ACUP-NEW-DETAILS.} at
 * {@code app/cbl/COACTUPC.cbl:L757}. That group holds one identifier and two subordinate groups, and
 * this record holds the two groups. {@code 10 ACUP-NEW-ACCT-DATA.} opens at
 * {@code app/cbl/COACTUPC.cbl:L758} and closes at {@code app/cbl/COACTUPC.cbl:L796}.
 * {@code 10 ACUP-NEW-CUST-DATA.} opens at {@code app/cbl/COACTUPC.cbl:L797}.
 *
 * <p>Two further components carry the state the caller was shown, transformed from
 * {@code 05 ACUP-OLD-DETAILS.} at {@code app/cbl/COACTUPC.cbl:L669}. Its two subordinate groups
 * {@code 10 ACUP-OLD-ACCT-DATA.} at {@code app/cbl/COACTUPC.cbl:L670} and
 * {@code 10 ACUP-OLD-CUST-DATA.} at {@code app/cbl/COACTUPC.cbl:L709} carry the same field shapes
 * as their new counterparts, so the same two record types serve both.
 * {@code domain/ConcurrentChangeDetector} compares the re-read rows against that baseline, from
 * {@code app/cbl/COACTUPC.cbl:L4109-L4193}. A body carrying neither baseline component reaches the
 * search-key edit at {@code app/cbl/COACTUPC.cbl:L1433-L1449} and no write.
 *
 * <p>No field edit runs on either baseline component. {@code 1200-EDIT-MAP-INPUTS} at
 * {@code app/cbl/COACTUPC.cbl:L1470-L1676} reads {@code ACUP-NEW-DETAILS} alone, so the baseline
 * carries no cascade marker.
 *
 * <p>{@code api/AccountController} binds this record to the body of
 * {@code PUT /accounts/{accountId}}, one route on the Representational State Transfer (REST) surface
 * of the account service.
 *
 * <h2>The path carries the identity, and the body carries none</h2>
 *
 * <p>{@code ACUP-NEW-ACCT-ID-X PIC X(11)} at {@code app/cbl/COACTUPC.cbl:L759} opens the source
 * group, and this record declares no component for it. On a 3270 screen the identifier was an input
 * field the operator keyed beside the rest of the form; on this surface it is the path segment of the
 * resource being replaced. Carrying it in both places would raise a question no rule here answered:
 * which one wins when the two disagree. Declaring it once removes the question rather than answering
 * it.
 *
 * <p>{@code domain/validation/AccountIdValidator} still holds the rule for that identifier — eleven
 * digits, at least one of them not zero, from {@code app/cbl/COACTUPC.cbl:L1802-L1803} — and
 * {@code AccountUpdateService.editSearchKey} applies it to the value the path supplied. The omission
 * of the component is recorded in {@code card-platform/docs/traceability-matrix.md}.
 *
 * <h2>One message survives a validation pass, so one validator runs</h2>
 *
 * <p>This record declares no constraint and no cascade marker, and that absence is the contract
 * rather than an oversight. {@code AccountUpdateService.editMapInputs} is the single validator: it
 * runs the field edits of {@code app/cbl/COACTUPC.cbl:L1205-L1280} in source order and stops at the
 * first failure, yielding one {@code domain/validation/EditResult}.
 *
 * <p>A cascade marker on either component would run a second, competing pass. A bean validator
 * answers with a {@code Set} of violations in unspecified order, so a form with two bad fields would
 * produce two messages and no rule to say which the caller sees first. That contradicts the source
 * and it contradicts {@link AccountUpdateResponse}, which carries exactly one text.
 *
 * <p>{@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479} carries that one text, and
 * the condition name {@code WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}
 * guards every write to it, at {@code app/cbl/COACTUPC.cbl:L1791} and at
 * {@code app/cbl/COACTUPC.cbl:L1805}. So the first failure is the only failure reported, and
 * {@code EditResult} carries no violation list, no field-error map and no count.
 *
 * <p>{@link AccountDataRequest} and {@link CustomerDataRequest} keep their own component bounds.
 * Those bounds document the shape each field holds and a caller may validate either record directly.
 * They are not reached through this record, because reaching them would be the competing pass.
 *
 * <p>The record holds no customer key of its own.
 * {@code 10 ACUP-NEW-ACCT-DATA.} closes at {@code app/cbl/COACTUPC.cbl:L796} with
 * {@code ACUP-NEW-GROUP-ID PIC X(10)}, and {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 * {@code app/cbl/COACTUPC.cbl:L798} sits inside the customer group. The record also declares no
 * foreign key, no version column, no status check and no card field. Rationale for every choice above
 * sits in {@code card-platform/docs/decision-log.md}, the component-by-component field mapping in
 * {@code card-platform/docs/traceability-matrix.md}, and the source findings in
 * {@code card-platform/docs/business-rule-flags.md}.
 *
 * @param accountData  the account section of the request, from
 *                     {@code 10 ACUP-NEW-ACCT-DATA.} at {@code app/cbl/COACTUPC.cbl:L758} through
 *                     {@code app/cbl/COACTUPC.cbl:L796}, less the identifier that group opens with
 * @param customerData the customer section of the request, from
 *                     {@code 10 ACUP-NEW-CUST-DATA.} at {@code app/cbl/COACTUPC.cbl:L797} onward,
 *                     opening with {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 *                     {@code app/cbl/COACTUPC.cbl:L798}
 */
public record AccountUpdateRequest(

        AccountDataRequest accountData,

        CustomerDataRequest customerData) {

    /**
     * Names both components and withholds every value.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints whatever the two composed records render.
     *
     * <p>Each component appears as {@link CustomerDataRequest#WITHHELD}, the redaction marker this
     * package declares. A reader learns which record a log line belongs to and reads no value the
     * record holds.
     *
     * @return a rendering that names both components and discloses none, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[accountData=" + CustomerDataRequest.WITHHELD
                + ", customerData=" + CustomerDataRequest.WITHHELD + "]";
    }
}
