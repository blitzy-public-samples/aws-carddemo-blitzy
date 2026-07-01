/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.model;

import java.math.BigDecimal;

/**
 * Models the COBOL {@code 01 ACCOUNT-RECORD} defined in copybook
 * {@code app/cpy/CVACT01Y.cpy} (RECLN 300).
 *
 * <p>In CBACT04C the account master (DDNAME {@code ACCTFILE}) is opened
 * {@code I-O}: it is read at {@code 1100-GET-ACCT-DATA} (CBACT04C L372-391) and
 * then rewritten at {@code 1050-UPDATE-ACCOUNT} (CBACT04C L350-356). That makes
 * {@code Account} the <b>only mutated model</b> in this module. The paragraph
 * mutates exactly three fields before the {@code REWRITE}:
 * <ul>
 *   <li>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL} (CBACT04C L352)
 *       &rarr; {@link #setCurrBal(BigDecimal)}</li>
 *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-CREDIT}    (CBACT04C L353)
 *       &rarr; {@link #setCurrCycCredit(BigDecimal)}</li>
 *   <li>{@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}     (CBACT04C L354)
 *       &rarr; {@link #setCurrCycDebit(BigDecimal)}</li>
 * </ul>
 * followed by {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD} (CBACT04C
 * L356). Accordingly, mutators are exposed for those three fields ONLY; the
 * remaining nine fields are {@code final} and are set once at construction time.
 *
 * <p>This type is a passive data holder. It performs NO parsing, fixed-width
 * framing, overpunch decoding, validation, or I/O — those responsibilities
 * belong to {@code io.AccountRepository}, which decodes the 300-byte record
 * into an {@code Account}, retains the original raw bytes, and overlays only the
 * three mutated fields on write to guarantee a byte-exact rewrite of every
 * unmodelled byte (including the trailing {@code FILLER X(178)}). It also holds
 * NO business logic: the interest arithmetic and cycle-field zeroing live in the
 * {@code service} layer (minimal-change / behavior-preservation rule, AAP
 * §0.7). This keeps the port a faithful, reviewable image of the copybook.
 *
 * <p>Field mapping (from {@code CVACT01Y.cpy}; every numeric field is USAGE
 * DISPLAY zoned decimal — no {@code COMP-3} and no {@code REDEFINES}):
 * <ul>
 *   <li>Signed money fields {@code S9(10)V99} are modeled as {@link BigDecimal}
 *       carried at scale 2 (overpunch sign + implied {@code V99} decimal are
 *       decoded/encoded by {@code support.ZonedDecimal} in the {@code io}
 *       layer). {@code BigDecimal} — never {@code double}/{@code float} — is
 *       mandatory to preserve COBOL fixed-decimal semantics exactly.</li>
 *   <li>Alphanumeric {@code X(n)} and unsigned {@code 9(n)} fields are modeled
 *       as {@link String} to preserve leading zeros and fixed width verbatim.</li>
 * </ul>
 *
 * <p>Reverse-engineered from {@code app/cpy/CVACT01Y.cpy} (structure) and
 * {@code app/cbl/CBACT04C.cbl} {@code 1050-UPDATE-ACCOUNT} L350-356 (mutation
 * contract). Strictly additive; no build/runtime dependency on {@code app/}.
 */
public class Account {

    // ---------------------------------------------------------------------
    // Fields — EXACT order, names, and types from CVACT01Y.cpy ACCOUNT-RECORD.
    // Nine fields are final (immutable after construction); three are mutable
    // because CBACT04C 1050-UPDATE-ACCOUNT (L350-356) rewrites them.
    // The trailing COBOL FILLER X(178) (cols 123-300) is deliberately NOT
    // modeled here — io.AccountRepository preserves those raw bytes on rewrite.
    // ---------------------------------------------------------------------

    /** {@code ACCT-ID PIC 9(11)} (cols 1-11) — account id; VSAM primary key. Immutable. */
    private final String acctId;

    /** {@code ACCT-ACTIVE-STATUS PIC X(01)} (col 12) — active-status flag. Immutable. */
    private final String activeStatus;

    /**
     * {@code ACCT-CURR-BAL PIC S9(10)V99} (cols 13-24) — current balance.
     * <b>MUTABLE:</b> CBACT04C L352 {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}
     * posts the accumulated monthly interest. {@link BigDecimal} at scale 2.
     */
    private BigDecimal currBal;

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (cols 25-36) — credit limit. Preserved (immutable). */
    private final BigDecimal creditLimit;

    /** {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} (cols 37-48) — cash credit limit. Preserved (immutable). */
    private final BigDecimal cashCreditLimit;

    /** {@code ACCT-OPEN-DATE PIC X(10)} (cols 49-58) — account open date. Preserved (immutable). */
    private final String openDate;

    /**
     * {@code ACCT-EXPIRAION-DATE PIC X(10)} (cols 59-68) — expiration date.
     * NOTE: the COBOL field name is misspelled "EXPIRAION" in CVACT01Y.cpy;
     * the Java field {@code expirationDate} uses the corrected spelling. Only
     * the byte position (cols 59-68) is contractually significant. Preserved
     * (immutable).
     */
    private final String expirationDate;

    /** {@code ACCT-REISSUE-DATE PIC X(10)} (cols 69-78) — reissue date. Preserved (immutable). */
    private final String reissueDate;

    /**
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} (cols 79-90) — current-cycle credit.
     * <b>MUTABLE:</b> CBACT04C L353 {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT}
     * zeroes it during the account update. {@link BigDecimal} at scale 2.
     */
    private BigDecimal currCycCredit;

    /**
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} (cols 91-102) — current-cycle debit.
     * <b>MUTABLE:</b> CBACT04C L354 {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT}
     * zeroes it during the account update. {@link BigDecimal} at scale 2.
     */
    private BigDecimal currCycDebit;

    /**
     * {@code ACCT-ADDR-ZIP PIC X(10)} (cols 103-112) — address ZIP. Preserved (immutable).
     * NOTE (fixture behavior, do NOT "fix"): in {@code app/data/ASCII/acctdata.txt}
     * this column actually holds the {@code A000000000}-looking value while
     * {@link #groupId} (cols 113-122) is blank. Carried verbatim to preserve the
     * golden-master behavior.
     */
    private final String addrZip;

    /**
     * {@code ACCT-GROUP-ID PIC X(10)} (cols 113-122) — account group id. Preserved (immutable).
     * <b>Drives the DISCGRP lookup key</b> (CBACT04C L210-212:
     * {@code ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD}).
     * NOTE (fixture behavior, do NOT "fix"): in
     * {@code app/data/ASCII/acctdata.txt} this column is blank (10 spaces) for
     * all 50 accounts, so the assembled DISCGRP key misses and the DEFAULT-group
     * fallback (CBACT04C L436-439) fires. Carried verbatim.
     */
    private final String groupId;

    /**
     * Constructs an {@code Account} from the twelve modeled fields, in the exact
     * order they appear in {@code CVACT01Y.cpy}. Invoked by
     * {@code io.AccountRepository} after it decodes a 300-byte record (overpunch
     * zoned-decimal money fields decoded to {@link BigDecimal} at scale 2).
     *
     * <p>Values are stored verbatim (no trimming, padding, swapping, or rounding);
     * the model is a faithful image of the copybook layout.
     *
     * @param acctId          {@code ACCT-ID 9(11)} — account id (key)
     * @param activeStatus    {@code ACCT-ACTIVE-STATUS X(01)} — active-status flag
     * @param currBal         {@code ACCT-CURR-BAL S9(10)V99} — current balance (mutable)
     * @param creditLimit     {@code ACCT-CREDIT-LIMIT S9(10)V99} — credit limit
     * @param cashCreditLimit {@code ACCT-CASH-CREDIT-LIMIT S9(10)V99} — cash credit limit
     * @param openDate        {@code ACCT-OPEN-DATE X(10)} — open date
     * @param expirationDate  {@code ACCT-EXPIRAION-DATE X(10)} — expiration date (source name misspelled)
     * @param reissueDate     {@code ACCT-REISSUE-DATE X(10)} — reissue date
     * @param currCycCredit   {@code ACCT-CURR-CYC-CREDIT S9(10)V99} — current-cycle credit (mutable)
     * @param currCycDebit    {@code ACCT-CURR-CYC-DEBIT S9(10)V99} — current-cycle debit (mutable)
     * @param addrZip         {@code ACCT-ADDR-ZIP X(10)} — address ZIP
     * @param groupId         {@code ACCT-GROUP-ID X(10)} — account group id (drives DISCGRP key)
     */
    public Account(String acctId,
                   String activeStatus,
                   BigDecimal currBal,
                   BigDecimal creditLimit,
                   BigDecimal cashCreditLimit,
                   String openDate,
                   String expirationDate,
                   String reissueDate,
                   BigDecimal currCycCredit,
                   BigDecimal currCycDebit,
                   String addrZip,
                   String groupId) {
        this.acctId = acctId;
        this.activeStatus = activeStatus;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
        this.addrZip = addrZip;
        this.groupId = groupId;
    }

    // ---------------------------------------------------------------------
    // Getters — exposed for ALL twelve fields. The service/io layers read every
    // field to round-trip the record and to build the DISCGRP key from groupId.
    // ---------------------------------------------------------------------

    /** @return {@code ACCT-ID 9(11)} — account id (key). */
    public String getAcctId() {
        return acctId;
    }

    /** @return {@code ACCT-ACTIVE-STATUS X(01)} — active-status flag. */
    public String getActiveStatus() {
        return activeStatus;
    }

    /** @return {@code ACCT-CURR-BAL S9(10)V99} — current balance (scale 2). */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /** @return {@code ACCT-CREDIT-LIMIT S9(10)V99} — credit limit (scale 2). */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /** @return {@code ACCT-CASH-CREDIT-LIMIT S9(10)V99} — cash credit limit (scale 2). */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /** @return {@code ACCT-OPEN-DATE X(10)} — open date. */
    public String getOpenDate() {
        return openDate;
    }

    /** @return {@code ACCT-EXPIRAION-DATE X(10)} — expiration date (source name misspelled "EXPIRAION"). */
    public String getExpirationDate() {
        return expirationDate;
    }

    /** @return {@code ACCT-REISSUE-DATE X(10)} — reissue date. */
    public String getReissueDate() {
        return reissueDate;
    }

    /** @return {@code ACCT-CURR-CYC-CREDIT S9(10)V99} — current-cycle credit (scale 2). */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /** @return {@code ACCT-CURR-CYC-DEBIT S9(10)V99} — current-cycle debit (scale 2). */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /** @return {@code ACCT-ADDR-ZIP X(10)} — address ZIP (carried verbatim). */
    public String getAddrZip() {
        return addrZip;
    }

    /** @return {@code ACCT-GROUP-ID X(10)} — account group id; drives the DISCGRP lookup key (carried verbatim). */
    public String getGroupId() {
        return groupId;
    }

    // ---------------------------------------------------------------------
    // Setters — exposed for EXACTLY THREE fields, mirroring the only mutations
    // in CBACT04C 1050-UPDATE-ACCOUNT (L350-356). The service layer performs the
    // arithmetic and zeroing and calls these mutators; no business logic lives
    // here. No setters exist for the other nine (final) fields.
    // ---------------------------------------------------------------------

    /**
     * Sets {@code ACCT-CURR-BAL}. Mirrors CBACT04C L352
     * {@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}; the caller (service layer)
     * computes the new balance, e.g. {@code setCurrBal(getCurrBal().add(totalInterest))}.
     *
     * @param currBal the new current balance ({@link BigDecimal} at scale 2)
     */
    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    /**
     * Sets {@code ACCT-CURR-CYC-CREDIT}. Mirrors CBACT04C L353
     * {@code MOVE 0 TO ACCT-CURR-CYC-CREDIT} (the caller passes zero during the
     * account update).
     *
     * @param currCycCredit the new current-cycle credit ({@link BigDecimal} at scale 2)
     */
    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    /**
     * Sets {@code ACCT-CURR-CYC-DEBIT}. Mirrors CBACT04C L354
     * {@code MOVE 0 TO ACCT-CURR-CYC-DEBIT} (the caller passes zero during the
     * account update).
     *
     * @param currCycDebit the new current-cycle debit ({@link BigDecimal} at scale 2)
     */
    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    /**
     * Diagnostic representation for test failure messages. Not part of the
     * behavioral contract (no {@code equals}/{@code hashCode} is defined or
     * required); fields are rendered in copybook order.
     *
     * @return a human-readable summary of this account's modeled fields
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", activeStatus=" + activeStatus
                + ", currBal=" + currBal
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate=" + openDate
                + ", expirationDate=" + expirationDate
                + ", reissueDate=" + reissueDate
                + ", currCycCredit=" + currCycCredit
                + ", currCycDebit=" + currCycDebit
                + ", addrZip=" + addrZip
                + ", groupId=" + groupId
                + '}';
    }
}
