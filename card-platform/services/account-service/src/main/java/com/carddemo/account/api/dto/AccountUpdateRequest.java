package com.carddemo.account.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * The body of an account update request, as a caller submits it.
 *
 * <p>Transformed from the group {@code 05 ACUP-NEW-DETAILS.} at
 * {@code app/cbl/COACTUPC.cbl:L757}. That group holds one identifier and two subordinate groups,
 * and this record holds the same three parts in the same order.
 * {@code 10 ACUP-NEW-ACCT-DATA.} opens at {@code app/cbl/COACTUPC.cbl:L758} and closes at
 * {@code app/cbl/COACTUPC.cbl:L796}. {@code 10 ACUP-NEW-CUST-DATA.} opens at
 * {@code app/cbl/COACTUPC.cbl:L797}.
 *
 * <p>{@code api/AccountController} binds this record to the body of
 * {@code PUT /accounts/{accountId}}, one route on the Representational State Transfer (REST)
 * surface of the account service.
 *
 * <p>The record declares one width bound and two cascade markers. Each cascade carries a validator
 * pass into the components of the composed record, and no field edit runs here.
 * {@code domain/validation/AccountIdValidator} holds the rule for {@link #accountId()}: eleven
 * digits, at least one of them not zero, from {@code app/cbl/COACTUPC.cbl:L1802-L1803}. The record
 * declares no presence bound, no format bound and no numeric bound on that component.
 *
 * <p>One message survives a validation pass. {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COACTUPC.cbl:L479} carries it, and the condition name
 * {@code WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480} guards every write
 * to it, at {@code app/cbl/COACTUPC.cbl:L1791} and at {@code app/cbl/COACTUPC.cbl:L1805}.
 * {@code domain/validation/EditResult} carries that one message. This record declares no violation
 * list, no field-error map and no count.
 *
 * <p>The record holds no customer key of its own.
 * {@code 10 ACUP-NEW-ACCT-DATA.} closes at {@code app/cbl/COACTUPC.cbl:L796} with
 * {@code ACUP-NEW-GROUP-ID PIC X(10)}, and {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 * {@code app/cbl/COACTUPC.cbl:L798} sits inside the customer group. The record also declares no
 * foreign key, no version column, no status check and no card field. Rationale for every choice
 * above sits in {@code card-platform/docs/decision-log.md}, the component-by-component field
 * mapping in {@code card-platform/docs/traceability-matrix.md}, and the source findings in
 * {@code card-platform/docs/business-rule-flags.md}.
 *
 * @param accountId    eleven characters of account identifier,
 *                     {@code ACUP-NEW-ACCT-ID-X PIC X(11)} at
 *                     {@code app/cbl/COACTUPC.cbl:L759}. The numeric redefine
 *                     {@code ACUP-NEW-ACCT-ID PIC 9(11)} at
 *                     {@code app/cbl/COACTUPC.cbl:L760-L761} covers the same eleven bytes. Row 1
 *                     of {@code app/data/ASCII/acctdata.txt} carries {@code 00000000001} at
 *                     columns 1 through 11, and text keeps those ten leading zeros through
 *                     serialization. {@link #ACCOUNT_ID_MAX_LENGTH} bounds the width, and
 *                     {@code domain/validation/AccountIdValidator} holds the two branches that
 *                     reject a value
 * @param accountData  the account section of the request, from
 *                     {@code 10 ACUP-NEW-ACCT-DATA.} at {@code app/cbl/COACTUPC.cbl:L758} through
 *                     {@code app/cbl/COACTUPC.cbl:L796}. The cascade marker reaches the ten
 *                     component bounds and edits that {@link AccountDataRequest} declares
 * @param customerData the customer section of the request, from
 *                     {@code 10 ACUP-NEW-CUST-DATA.} at {@code app/cbl/COACTUPC.cbl:L797} onward,
 *                     opening with {@code ACUP-NEW-CUST-ID-X PIC X(09)} at
 *                     {@code app/cbl/COACTUPC.cbl:L798}. The cascade marker reaches the twenty
 *                     component bounds and edits that {@link CustomerDataRequest} declares
 */
public record AccountUpdateRequest(

        @Size(max = ACCOUNT_ID_MAX_LENGTH)
        String accountId,

        @Valid
        AccountDataRequest accountData,

        @Valid
        CustomerDataRequest customerData) {

    /**
     * Width of {@link #accountId()}, from {@code ACUP-NEW-ACCT-ID-X PIC X(11)} at
     * {@code app/cbl/COACTUPC.cbl:L759}. The numeric redefine at
     * {@code app/cbl/COACTUPC.cbl:L760-L761} covers the same eleven bytes, and
     * {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5} declares the stored column at
     * the same width.
     */
    public static final int ACCOUNT_ID_MAX_LENGTH = 11;

    /**
     * Names all three components and withholds every value.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier, the key every event of this account carries,
     * and it prints whatever the two composed records render.
     *
     * <p>Each component appears as {@link CustomerDataRequest#WITHHELD}, the redaction marker this
     * package declares. A reader learns which record a log line belongs to and reads no value the
     * record holds.
     *
     * @return a rendering that names all three components and discloses none, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountUpdateRequest[accountId=" + CustomerDataRequest.WITHHELD + ", accountData="
                + CustomerDataRequest.WITHHELD + ", customerData=" + CustomerDataRequest.WITHHELD
                + "]";
    }
}
