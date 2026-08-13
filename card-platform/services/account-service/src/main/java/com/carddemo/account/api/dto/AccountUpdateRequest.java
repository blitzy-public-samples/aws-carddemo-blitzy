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
 * <p>The record declares two components and nothing else: no account identifier, no customer key,
 * no foreign key, no version column, no status field and no card field.
 * {@code ACUP-NEW-ACCT-ID-X PIC X(11)} at {@code app/cbl/COACTUPC.cbl:L759} opens the source group
 * and reaches this surface as the path segment instead; the omission is recorded in
 * {@code card-platform/docs/traceability-matrix.md}, and
 * {@code AccountUpdateService.editSearchKey} applies the identifier edit of
 * {@code app/cbl/COACTUPC.cbl:L1802-L1803} to the path value.
 *
 * <p>The record also declares no constraint and no cascade marker.
 * {@code AccountUpdateService.editMapInputs} is the single validator: it runs the field edits of
 * {@code app/cbl/COACTUPC.cbl:L1205-L1280} in source order and stops at the first failure, yielding
 * the one {@code domain/validation/EditResult} that {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} models. The component bounds {@link AccountDataRequest} and
 * {@link CustomerDataRequest} declare are not reached through this record either, and Bean
 * Validation is not what enforces them on this route: {@code api/AccountRecordMapper} reads every
 * submitted value against its declared width before it maps anything, and answers the width message
 * {@code domain/validation/DeclaredWidthValidator} composes. A cascade marker would enforce those
 * bounds and would also run every field edit a second time, in no defined order, against the
 * ordered single message the source produces — which is why the bounds are enforced ahead of the
 * mapping instead.
 *
 * <p>{@code toString()} names both components and withholds every value. Rationale for the identity,
 * cascade and validation-order choices: {@code card-platform/docs/decision-log.md}. Field mapping:
 * {@code card-platform/docs/traceability-matrix.md}. Source findings:
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
     * @return a rendering that names both components and discloses none, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[accountData=" + CustomerDataRequest.WITHHELD
                + ", customerData=" + CustomerDataRequest.WITHHELD + "]";
    }
}
