/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.common.dto;


/**
 * TransactionKeyDto
 * =================
 *
 * :purpose: Carry the two key fields of the add-transaction screen -- ``ACTIDIN``
 *     (``PIC X(11)``) and ``CARDNIN`` (``PIC X(16)``) -- in BOTH directions for the
 *     key-resolution step that re-expresses ``COTRN02C VALIDATE-INPUT-KEY-FIELDS``.
 *     The caller sends whichever key the operator typed and receives both, because
 *     that paragraph's cross-reference read moves the counterpart key back into its
 *     own map field (``MOVE XREF-CARD-NUM TO CARDNINI`` /
 *     ``MOVE XREF-ACCT-ID TO ACTIDINI``).
 * :output: The resolved account id and card number, each at its declared width.
 * :note: Both members are ``String`` so a fixed-width identifier keeps its leading
 *     zeros, matching every other identifier on the wire. The card number travels in
 *     a request BODY rather than a URL, for the reason recorded in the decision log:
 *     a URL is written verbatim into every intermediary's access log.
 */
public class TransactionKeyDto {

    /**
     * ``ACTIDIN`` -- the account id the operator typed, or the id resolved from a
     * supplied card number. Eleven characters, zero-padded on the way out.
     * :note: No bean-validation constraint. The width rule is ``COTRN02C`` L197-L201 and
     * the service applies it to every value, so one edit answers uniformly. A ``@Size``
     * here fired only for an OVER-width value, which answered with a ``fieldErrors`` entry
     * while every other wrong width answered with a bare message -- the same refusal in
     * two different shapes depending on which side of eleven the value fell.
     */
    private String acctId;

    /**
     * ``CARDNIN`` -- the card number the operator typed, or the number resolved from a
     * supplied account id. Sixteen characters.
     * :note: No bean-validation constraint, for the reason given on ``acctId``.
     */
    private String tranCardNum;

    /**
     * :purpose: Read the account id.
     * :returns: The account id, or ``null`` when the caller supplied none.
     */
    public String getAcctId() {
        return acctId;
    }

    /**
     * :purpose: Set the account id.
     * :param acctId: The account id.
     */
    public void setAcctId(String acctId) {
        this.acctId = acctId;
    }

    /**
     * :purpose: Read the card number.
     * :returns: The card number, or ``null`` when the caller supplied none.
     */
    public String getTranCardNum() {
        return tranCardNum;
    }

    /**
     * :purpose: Set the card number.
     * :param tranCardNum: The card number.
     */
    public void setTranCardNum(String tranCardNum) {
        this.tranCardNum = tranCardNum;
    }

    /**
     * :purpose: Describe the key without disclosing the card number, which is a PAN.
     * :returns: A diagnostic rendering that names the account id only.
     */
    @Override
    public String toString() {
        return "TransactionKeyDto{acctId=" + acctId + ", tranCardNum=***}";
    }
}
