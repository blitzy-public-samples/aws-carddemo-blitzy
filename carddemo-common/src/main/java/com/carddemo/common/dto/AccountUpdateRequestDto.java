/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.dto;

import java.math.BigDecimal;

/**
 * :purpose: Inbound DTO for the account update screen (COACTUPC, CICS CAUP). Carries the editable account and customer master fields plus the optimistic-lock ``version`` snapshot the client read when the screen was displayed; the account id and customer id primary keys are never carried here (the account id identifies the target through the request path and the customer id is derived server-side). Monetary fields are BigDecimal.
 * :output: A mutable carrier of the editable account and customer master fields and the version snapshot the server compares before rewriting.
 */
public class AccountUpdateRequestDto {

    /**
     * :purpose: the optimistic-lock version of the account record as read at display
     *  time. The server compares it against the current record before rewriting, which
     *  is the ``COACTUPC`` read-snapshot-compare-rewrite check
     *  (``DATA-WAS-CHANGED-BEFORE-UPDATE``): a mismatch is reported as the legacy
     *  conflict "Record changed by some one else. Please review" instead of overwriting
     *  the concurrent change (AAP 0.6.2).
     * :note: The snapshot is mandatory, but it is enforced by that comparison (a missing
     *  snapshot can never match, so the rewrite is refused) rather than by a bean-validation
     *  constraint. Body constraints are evaluated during argument resolution, i.e. before
     *  the handler runs, which would pre-empt the account-id edit
     *  (``2210-EDIT-ACCOUNT``) and replace its verbatim legacy message.
     */
    private Long version;

    /** :purpose: the account active status (``ACCT-ACTIVE-STATUS``). */
    private String acctActiveStatus;

    /** :purpose: the current balance (``ACCT-CURR-BAL``) at scale 2. */
    private BigDecimal acctCurrBal;

    /** :purpose: the credit limit (``ACCT-CREDIT-LIMIT``) at scale 2. */
    private BigDecimal acctCreditLimit;

    /** :purpose: the cash credit limit (``ACCT-CASH-CREDIT-LIMIT``) at scale 2. */
    private BigDecimal acctCashCreditLimit;

    /** :purpose: the current-cycle credit total (``ACCT-CURR-CYC-CREDIT``) at scale 2. */
    private BigDecimal acctCurrCycCredit;

    /** :purpose: the current-cycle debit total (``ACCT-CURR-CYC-DEBIT``) at scale 2. */
    private BigDecimal acctCurrCycDebit;

    /** :purpose: the account open date (``ACCT-OPEN-DATE``, YYYY-MM-DD). */
    private String acctOpenDate;

    /** :purpose: the account expiration date (legacy-spelled ``ACCT-EXPIRAION-DATE``, YYYY-MM-DD). */
    private String acctExpiraionDate;

    /** :purpose: the account reissue date (``ACCT-REISSUE-DATE``, YYYY-MM-DD). */
    private String acctReissueDate;

    /** :purpose: the account group id (``ACCT-GROUP-ID``). */
    private String acctGroupId;

    /** :purpose: the customer first name (``CUST-FIRST-NAME``). */
    private String custFirstName;

    /** :purpose: the customer middle name (``CUST-MIDDLE-NAME``). */
    private String custMiddleName;

    /** :purpose: the customer last name (``CUST-LAST-NAME``). */
    private String custLastName;

    /** :purpose: customer address line 1 (``CUST-ADDR-LINE-1``). */
    private String custAddrLine1;

    /** :purpose: customer address line 2 (``CUST-ADDR-LINE-2``). */
    private String custAddrLine2;

    /** :purpose: customer address line 3 (``CUST-ADDR-LINE-3``). */
    private String custAddrLine3;

    /** :purpose: customer state code (``CUST-ADDR-STATE-CD``). */
    private String custAddrStateCd;

    /** :purpose: customer country code (``CUST-ADDR-COUNTRY-CD``). */
    private String custAddrCountryCd;

    /** :purpose: customer postal code (``CUST-ADDR-ZIP``). */
    private String custAddrZip;

    /** :purpose: customer phone number 1 (``CUST-PHONE-NUM-1``). */
    private String custPhoneNum1;

    /** :purpose: customer phone number 2 (``CUST-PHONE-NUM-2``). */
    private String custPhoneNum2;

    /** :purpose: customer SSN (``CUST-SSN``); masked by the presentation layer. */
    private String custSsn;

    /** :purpose: customer government-issued id (``CUST-GOVT-ISSUED-ID``); masked by the presentation layer. */
    private String custGovtIssuedId;

    /** :purpose: customer date of birth (``CUST-DOB-YYYY-MM-DD``). */
    private String custDobYyyyMmDd;

    /** :purpose: customer EFT account id (``CUST-EFT-ACCOUNT-ID``). */
    private String custEftAccountId;

    /** :purpose: primary card-holder indicator (``CUST-PRI-CARD-HOLDER-IND``). */
    private String custPriCardHolderInd;

    /** :purpose: customer FICO credit score (``CUST-FICO-CREDIT-SCORE``). */
    private Integer custFicoCreditScore;

    /**
     * :purpose: Create an empty AccountUpdateRequestDto. Required for JSON (Jackson) serialization.
     */
    public AccountUpdateRequestDto() {
    }

    /**
     * :purpose: Return the optimistic-lock version snapshot read at display time.
     * :output: the ``version`` value the server compares before rewriting.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * :purpose: Set the optimistic-lock version snapshot read at display time.
     * :param version: the ``version`` value echoed back from the view response.
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * :purpose: Return the account active status (``ACCT-ACTIVE-STATUS``).
     * :output: the ``acctActiveStatus`` value.
     */
    public String getAcctActiveStatus() {
        return acctActiveStatus;
    }

    /**
     * :purpose: Set the account active status (``ACCT-ACTIVE-STATUS``).
     * :param acctActiveStatus: the ``acctActiveStatus`` value.
     */
    public void setAcctActiveStatus(String acctActiveStatus) {
        this.acctActiveStatus = acctActiveStatus;
    }

    /**
     * :purpose: Return the current balance (``ACCT-CURR-BAL``) at scale 2.
     * :output: the ``acctCurrBal`` value.
     */
    public BigDecimal getAcctCurrBal() {
        return acctCurrBal;
    }

    /**
     * :purpose: Set the current balance (``ACCT-CURR-BAL``) at scale 2.
     * :param acctCurrBal: the ``acctCurrBal`` value.
     */
    public void setAcctCurrBal(BigDecimal acctCurrBal) {
        this.acctCurrBal = acctCurrBal;
    }

    /**
     * :purpose: Return the credit limit (``ACCT-CREDIT-LIMIT``) at scale 2.
     * :output: the ``acctCreditLimit`` value.
     */
    public BigDecimal getAcctCreditLimit() {
        return acctCreditLimit;
    }

    /**
     * :purpose: Set the credit limit (``ACCT-CREDIT-LIMIT``) at scale 2.
     * :param acctCreditLimit: the ``acctCreditLimit`` value.
     */
    public void setAcctCreditLimit(BigDecimal acctCreditLimit) {
        this.acctCreditLimit = acctCreditLimit;
    }

    /**
     * :purpose: Return the cash credit limit (``ACCT-CASH-CREDIT-LIMIT``) at scale 2.
     * :output: the ``acctCashCreditLimit`` value.
     */
    public BigDecimal getAcctCashCreditLimit() {
        return acctCashCreditLimit;
    }

    /**
     * :purpose: Set the cash credit limit (``ACCT-CASH-CREDIT-LIMIT``) at scale 2.
     * :param acctCashCreditLimit: the ``acctCashCreditLimit`` value.
     */
    public void setAcctCashCreditLimit(BigDecimal acctCashCreditLimit) {
        this.acctCashCreditLimit = acctCashCreditLimit;
    }

    /**
     * :purpose: Return the current-cycle credit total (``ACCT-CURR-CYC-CREDIT``) at scale 2.
     * :output: the ``acctCurrCycCredit`` value.
     */
    public BigDecimal getAcctCurrCycCredit() {
        return acctCurrCycCredit;
    }

    /**
     * :purpose: Set the current-cycle credit total (``ACCT-CURR-CYC-CREDIT``) at scale 2.
     * :param acctCurrCycCredit: the ``acctCurrCycCredit`` value.
     */
    public void setAcctCurrCycCredit(BigDecimal acctCurrCycCredit) {
        this.acctCurrCycCredit = acctCurrCycCredit;
    }

    /**
     * :purpose: Return the current-cycle debit total (``ACCT-CURR-CYC-DEBIT``) at scale 2.
     * :output: the ``acctCurrCycDebit`` value.
     */
    public BigDecimal getAcctCurrCycDebit() {
        return acctCurrCycDebit;
    }

    /**
     * :purpose: Set the current-cycle debit total (``ACCT-CURR-CYC-DEBIT``) at scale 2.
     * :param acctCurrCycDebit: the ``acctCurrCycDebit`` value.
     */
    public void setAcctCurrCycDebit(BigDecimal acctCurrCycDebit) {
        this.acctCurrCycDebit = acctCurrCycDebit;
    }

    /**
     * :purpose: Return the account open date (``ACCT-OPEN-DATE``, YYYY-MM-DD).
     * :output: the ``acctOpenDate`` value.
     */
    public String getAcctOpenDate() {
        return acctOpenDate;
    }

    /**
     * :purpose: Set the account open date (``ACCT-OPEN-DATE``, YYYY-MM-DD).
     * :param acctOpenDate: the ``acctOpenDate`` value.
     */
    public void setAcctOpenDate(String acctOpenDate) {
        this.acctOpenDate = acctOpenDate;
    }

    /**
     * :purpose: Return the account expiration date (legacy-spelled ``ACCT-EXPIRAION-DATE``, YYYY-MM-DD).
     * :output: the ``acctExpiraionDate`` value.
     */
    public String getAcctExpiraionDate() {
        return acctExpiraionDate;
    }

    /**
     * :purpose: Set the account expiration date (legacy-spelled ``ACCT-EXPIRAION-DATE``, YYYY-MM-DD).
     * :param acctExpiraionDate: the ``acctExpiraionDate`` value.
     */
    public void setAcctExpiraionDate(String acctExpiraionDate) {
        this.acctExpiraionDate = acctExpiraionDate;
    }

    /**
     * :purpose: Return the account reissue date (``ACCT-REISSUE-DATE``, YYYY-MM-DD).
     * :output: the ``acctReissueDate`` value.
     */
    public String getAcctReissueDate() {
        return acctReissueDate;
    }

    /**
     * :purpose: Set the account reissue date (``ACCT-REISSUE-DATE``, YYYY-MM-DD).
     * :param acctReissueDate: the ``acctReissueDate`` value.
     */
    public void setAcctReissueDate(String acctReissueDate) {
        this.acctReissueDate = acctReissueDate;
    }

    /**
     * :purpose: Return the account group id (``ACCT-GROUP-ID``).
     * :output: the ``acctGroupId`` value.
     */
    public String getAcctGroupId() {
        return acctGroupId;
    }

    /**
     * :purpose: Set the account group id (``ACCT-GROUP-ID``).
     * :param acctGroupId: the ``acctGroupId`` value.
     */
    public void setAcctGroupId(String acctGroupId) {
        this.acctGroupId = acctGroupId;
    }

    /**
     * :purpose: Return the customer first name (``CUST-FIRST-NAME``).
     * :output: the ``custFirstName`` value.
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * :purpose: Set the customer first name (``CUST-FIRST-NAME``).
     * :param custFirstName: the ``custFirstName`` value.
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /**
     * :purpose: Return the customer middle name (``CUST-MIDDLE-NAME``).
     * :output: the ``custMiddleName`` value.
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * :purpose: Set the customer middle name (``CUST-MIDDLE-NAME``).
     * :param custMiddleName: the ``custMiddleName`` value.
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /**
     * :purpose: Return the customer last name (``CUST-LAST-NAME``).
     * :output: the ``custLastName`` value.
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * :purpose: Set the customer last name (``CUST-LAST-NAME``).
     * :param custLastName: the ``custLastName`` value.
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /**
     * :purpose: Return customer address line 1 (``CUST-ADDR-LINE-1``).
     * :output: the ``custAddrLine1`` value.
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * :purpose: Set customer address line 1 (``CUST-ADDR-LINE-1``).
     * :param custAddrLine1: the ``custAddrLine1`` value.
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /**
     * :purpose: Return customer address line 2 (``CUST-ADDR-LINE-2``).
     * :output: the ``custAddrLine2`` value.
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * :purpose: Set customer address line 2 (``CUST-ADDR-LINE-2``).
     * :param custAddrLine2: the ``custAddrLine2`` value.
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /**
     * :purpose: Return customer address line 3 (``CUST-ADDR-LINE-3``).
     * :output: the ``custAddrLine3`` value.
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * :purpose: Set customer address line 3 (``CUST-ADDR-LINE-3``).
     * :param custAddrLine3: the ``custAddrLine3`` value.
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /**
     * :purpose: Return customer state code (``CUST-ADDR-STATE-CD``).
     * :output: the ``custAddrStateCd`` value.
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * :purpose: Set customer state code (``CUST-ADDR-STATE-CD``).
     * :param custAddrStateCd: the ``custAddrStateCd`` value.
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /**
     * :purpose: Return customer country code (``CUST-ADDR-COUNTRY-CD``).
     * :output: the ``custAddrCountryCd`` value.
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * :purpose: Set customer country code (``CUST-ADDR-COUNTRY-CD``).
     * :param custAddrCountryCd: the ``custAddrCountryCd`` value.
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /**
     * :purpose: Return customer postal code (``CUST-ADDR-ZIP``).
     * :output: the ``custAddrZip`` value.
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * :purpose: Set customer postal code (``CUST-ADDR-ZIP``).
     * :param custAddrZip: the ``custAddrZip`` value.
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /**
     * :purpose: Return customer phone number 1 (``CUST-PHONE-NUM-1``).
     * :output: the ``custPhoneNum1`` value.
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * :purpose: Set customer phone number 1 (``CUST-PHONE-NUM-1``).
     * :param custPhoneNum1: the ``custPhoneNum1`` value.
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /**
     * :purpose: Return customer phone number 2 (``CUST-PHONE-NUM-2``).
     * :output: the ``custPhoneNum2`` value.
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * :purpose: Set customer phone number 2 (``CUST-PHONE-NUM-2``).
     * :param custPhoneNum2: the ``custPhoneNum2`` value.
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * :purpose: Return customer SSN (``CUST-SSN``); masked by the presentation layer.
     * :output: the ``custSsn`` value.
     */
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * :purpose: Set customer SSN (``CUST-SSN``); masked by the presentation layer.
     * :param custSsn: the ``custSsn`` value.
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * :purpose: Return customer government-issued id (``CUST-GOVT-ISSUED-ID``); masked by the presentation layer.
     * :output: the ``custGovtIssuedId`` value.
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * :purpose: Set customer government-issued id (``CUST-GOVT-ISSUED-ID``); masked by the presentation layer.
     * :param custGovtIssuedId: the ``custGovtIssuedId`` value.
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /**
     * :purpose: Return customer date of birth (``CUST-DOB-YYYY-MM-DD``).
     * :output: the ``custDobYyyyMmDd`` value.
     */
    public String getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * :purpose: Set customer date of birth (``CUST-DOB-YYYY-MM-DD``).
     * :param custDobYyyyMmDd: the ``custDobYyyyMmDd`` value.
     */
    public void setCustDobYyyyMmDd(String custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /**
     * :purpose: Return customer EFT account id (``CUST-EFT-ACCOUNT-ID``).
     * :output: the ``custEftAccountId`` value.
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * :purpose: Set customer EFT account id (``CUST-EFT-ACCOUNT-ID``).
     * :param custEftAccountId: the ``custEftAccountId`` value.
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /**
     * :purpose: Return primary card-holder indicator (``CUST-PRI-CARD-HOLDER-IND``).
     * :output: the ``custPriCardHolderInd`` value.
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * :purpose: Set primary card-holder indicator (``CUST-PRI-CARD-HOLDER-IND``).
     * :param custPriCardHolderInd: the ``custPriCardHolderInd`` value.
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /**
     * :purpose: Return customer FICO credit score (``CUST-FICO-CREDIT-SCORE``).
     * :output: the ``custFicoCreditScore`` value.
     */
    public Integer getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * :purpose: Set customer FICO credit score (``CUST-FICO-CREDIT-SCORE``).
     * :param custFicoCreditScore: the ``custFicoCreditScore`` value.
     */
    public void setCustFicoCreditScore(Integer custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }


    // ------------------------------------------------------------------------
    // ACUP-OLD-* display-time snapshot (COACTUPC ``ACUP-OLD-ACCT-DATA`` /
    // ``ACUP-OLD-CUST-DATA``). The legacy program stores the values rendered on the
    // screen and, before rewriting, compares the freshly re-read record field by
    // field against them; any difference aborts with
    // ``DATA-WAS-CHANGED-BEFORE-UPDATE`` rather than overwriting a concurrent edit
    // (COACTUPC 9300-CHECK-CHANGE-IN-REC, L4131-4189). Callers echo the values they
    // read from the view response here; when the snapshot is omitted the check is
    // skipped, preserving the behaviour of callers that do not carry it.
    // ------------------------------------------------------------------------

    /** ``ACUP-OLD-ACTIVE-STATUS`` — display-time snapshot of {@code acctActiveStatus}. */
    private String oldAcctActiveStatus;
    /** ``ACUP-OLD-CURR-BAL`` — display-time snapshot of {@code acctCurrBal}. */
    private BigDecimal oldAcctCurrBal;
    /** ``ACUP-OLD-CREDIT-LIMIT`` — display-time snapshot of {@code acctCreditLimit}. */
    private BigDecimal oldAcctCreditLimit;
    /** ``ACUP-OLD-CASH-CREDIT-LIMIT`` — display-time snapshot of {@code acctCashCreditLimit}. */
    private BigDecimal oldAcctCashCreditLimit;
    /** ``ACUP-OLD-CURR-CYC-CREDIT`` — display-time snapshot of {@code acctCurrCycCredit}. */
    private BigDecimal oldAcctCurrCycCredit;
    /** ``ACUP-OLD-CURR-CYC-DEBIT`` — display-time snapshot of {@code acctCurrCycDebit}. */
    private BigDecimal oldAcctCurrCycDebit;
    /** ``ACUP-OLD-OPEN-DATE`` — display-time snapshot of {@code acctOpenDate}. */
    private String oldAcctOpenDate;
    /** ``ACUP-OLD-EXPIRAION-DATE`` — display-time snapshot of {@code acctExpiraionDate}. */
    private String oldAcctExpiraionDate;
    /** ``ACUP-OLD-REISSUE-DATE`` — display-time snapshot of {@code acctReissueDate}. */
    private String oldAcctReissueDate;
    /** ``ACUP-OLD-GROUP-ID`` — display-time snapshot of {@code acctGroupId}. */
    private String oldAcctGroupId;
    /** ``ACUP-OLD-CUST-FIRST-NAME`` — display-time snapshot of {@code custFirstName}. */
    private String oldCustFirstName;
    /** ``ACUP-OLD-CUST-MIDDLE-NAME`` — display-time snapshot of {@code custMiddleName}. */
    private String oldCustMiddleName;
    /** ``ACUP-OLD-CUST-LAST-NAME`` — display-time snapshot of {@code custLastName}. */
    private String oldCustLastName;
    /** ``ACUP-OLD-CUST-ADDR-LINE-1`` — display-time snapshot of {@code custAddrLine1}. */
    private String oldCustAddrLine1;
    /** ``ACUP-OLD-CUST-ADDR-LINE-2`` — display-time snapshot of {@code custAddrLine2}. */
    private String oldCustAddrLine2;
    /** ``ACUP-OLD-CUST-ADDR-LINE-3`` — display-time snapshot of {@code custAddrLine3}. */
    private String oldCustAddrLine3;
    /** ``ACUP-OLD-CUST-ADDR-STATE-CD`` — display-time snapshot of {@code custAddrStateCd}. */
    private String oldCustAddrStateCd;
    /** ``ACUP-OLD-CUST-ADDR-COUNTRY-CD`` — display-time snapshot of {@code custAddrCountryCd}. */
    private String oldCustAddrCountryCd;
    /** ``ACUP-OLD-CUST-ADDR-ZIP`` — display-time snapshot of {@code custAddrZip}. */
    private String oldCustAddrZip;
    /** ``ACUP-OLD-CUST-PHONE-NUM-1`` — display-time snapshot of {@code custPhoneNum1}. */
    private String oldCustPhoneNum1;
    /** ``ACUP-OLD-CUST-PHONE-NUM-2`` — display-time snapshot of {@code custPhoneNum2}. */
    private String oldCustPhoneNum2;
    /** ``ACUP-OLD-CUST-SSN`` — display-time snapshot of {@code custSsn}. */
    private String oldCustSsn;
    /** ``ACUP-OLD-CUST-GOVT-ISSUED-ID`` — display-time snapshot of {@code custGovtIssuedId}. */
    private String oldCustGovtIssuedId;
    /** ``ACUP-OLD-CUST-DOB-YYYY-MM-DD`` — display-time snapshot of {@code custDobYyyyMmDd}. */
    private String oldCustDobYyyyMmDd;
    /** ``ACUP-OLD-CUST-EFT-ACCOUNT-ID`` — display-time snapshot of {@code custEftAccountId}. */
    private String oldCustEftAccountId;
    /** ``ACUP-OLD-CUST-PRI-HOLDER-IND`` — display-time snapshot of {@code custPriCardHolderInd}. */
    private String oldCustPriCardHolderInd;
    /** ``ACUP-OLD-CUST-FICO-SCORE`` — display-time snapshot of {@code custFicoCreditScore}. */
    private Integer oldCustFicoCreditScore;

    /**
     * :purpose: Return the display-time snapshot of {@code acctActiveStatus} (``ACUP-OLD-ACTIVE-STATUS``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldAcctActiveStatus() {
        return oldAcctActiveStatus;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctActiveStatus} (``ACUP-OLD-ACTIVE-STATUS``).
     * :param oldAcctActiveStatus: the value the caller read before editing.
     */
    public void setOldAcctActiveStatus(String oldAcctActiveStatus) {
        this.oldAcctActiveStatus = oldAcctActiveStatus;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctCurrBal} (``ACUP-OLD-CURR-BAL``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public BigDecimal getOldAcctCurrBal() {
        return oldAcctCurrBal;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctCurrBal} (``ACUP-OLD-CURR-BAL``).
     * :param oldAcctCurrBal: the value the caller read before editing.
     */
    public void setOldAcctCurrBal(BigDecimal oldAcctCurrBal) {
        this.oldAcctCurrBal = oldAcctCurrBal;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctCreditLimit} (``ACUP-OLD-CREDIT-LIMIT``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public BigDecimal getOldAcctCreditLimit() {
        return oldAcctCreditLimit;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctCreditLimit} (``ACUP-OLD-CREDIT-LIMIT``).
     * :param oldAcctCreditLimit: the value the caller read before editing.
     */
    public void setOldAcctCreditLimit(BigDecimal oldAcctCreditLimit) {
        this.oldAcctCreditLimit = oldAcctCreditLimit;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctCashCreditLimit} (``ACUP-OLD-CASH-CREDIT-LIMIT``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public BigDecimal getOldAcctCashCreditLimit() {
        return oldAcctCashCreditLimit;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctCashCreditLimit} (``ACUP-OLD-CASH-CREDIT-LIMIT``).
     * :param oldAcctCashCreditLimit: the value the caller read before editing.
     */
    public void setOldAcctCashCreditLimit(BigDecimal oldAcctCashCreditLimit) {
        this.oldAcctCashCreditLimit = oldAcctCashCreditLimit;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctCurrCycCredit} (``ACUP-OLD-CURR-CYC-CREDIT``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public BigDecimal getOldAcctCurrCycCredit() {
        return oldAcctCurrCycCredit;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctCurrCycCredit} (``ACUP-OLD-CURR-CYC-CREDIT``).
     * :param oldAcctCurrCycCredit: the value the caller read before editing.
     */
    public void setOldAcctCurrCycCredit(BigDecimal oldAcctCurrCycCredit) {
        this.oldAcctCurrCycCredit = oldAcctCurrCycCredit;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctCurrCycDebit} (``ACUP-OLD-CURR-CYC-DEBIT``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public BigDecimal getOldAcctCurrCycDebit() {
        return oldAcctCurrCycDebit;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctCurrCycDebit} (``ACUP-OLD-CURR-CYC-DEBIT``).
     * :param oldAcctCurrCycDebit: the value the caller read before editing.
     */
    public void setOldAcctCurrCycDebit(BigDecimal oldAcctCurrCycDebit) {
        this.oldAcctCurrCycDebit = oldAcctCurrCycDebit;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctOpenDate} (``ACUP-OLD-OPEN-DATE``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldAcctOpenDate() {
        return oldAcctOpenDate;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctOpenDate} (``ACUP-OLD-OPEN-DATE``).
     * :param oldAcctOpenDate: the value the caller read before editing.
     */
    public void setOldAcctOpenDate(String oldAcctOpenDate) {
        this.oldAcctOpenDate = oldAcctOpenDate;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctExpiraionDate} (``ACUP-OLD-EXPIRAION-DATE``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldAcctExpiraionDate() {
        return oldAcctExpiraionDate;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctExpiraionDate} (``ACUP-OLD-EXPIRAION-DATE``).
     * :param oldAcctExpiraionDate: the value the caller read before editing.
     */
    public void setOldAcctExpiraionDate(String oldAcctExpiraionDate) {
        this.oldAcctExpiraionDate = oldAcctExpiraionDate;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctReissueDate} (``ACUP-OLD-REISSUE-DATE``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldAcctReissueDate() {
        return oldAcctReissueDate;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctReissueDate} (``ACUP-OLD-REISSUE-DATE``).
     * :param oldAcctReissueDate: the value the caller read before editing.
     */
    public void setOldAcctReissueDate(String oldAcctReissueDate) {
        this.oldAcctReissueDate = oldAcctReissueDate;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code acctGroupId} (``ACUP-OLD-GROUP-ID``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldAcctGroupId() {
        return oldAcctGroupId;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code acctGroupId} (``ACUP-OLD-GROUP-ID``).
     * :param oldAcctGroupId: the value the caller read before editing.
     */
    public void setOldAcctGroupId(String oldAcctGroupId) {
        this.oldAcctGroupId = oldAcctGroupId;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custFirstName} (``ACUP-OLD-CUST-FIRST-NAME``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustFirstName() {
        return oldCustFirstName;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custFirstName} (``ACUP-OLD-CUST-FIRST-NAME``).
     * :param oldCustFirstName: the value the caller read before editing.
     */
    public void setOldCustFirstName(String oldCustFirstName) {
        this.oldCustFirstName = oldCustFirstName;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custMiddleName} (``ACUP-OLD-CUST-MIDDLE-NAME``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustMiddleName() {
        return oldCustMiddleName;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custMiddleName} (``ACUP-OLD-CUST-MIDDLE-NAME``).
     * :param oldCustMiddleName: the value the caller read before editing.
     */
    public void setOldCustMiddleName(String oldCustMiddleName) {
        this.oldCustMiddleName = oldCustMiddleName;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custLastName} (``ACUP-OLD-CUST-LAST-NAME``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustLastName() {
        return oldCustLastName;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custLastName} (``ACUP-OLD-CUST-LAST-NAME``).
     * :param oldCustLastName: the value the caller read before editing.
     */
    public void setOldCustLastName(String oldCustLastName) {
        this.oldCustLastName = oldCustLastName;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrLine1} (``ACUP-OLD-CUST-ADDR-LINE-1``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrLine1() {
        return oldCustAddrLine1;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrLine1} (``ACUP-OLD-CUST-ADDR-LINE-1``).
     * :param oldCustAddrLine1: the value the caller read before editing.
     */
    public void setOldCustAddrLine1(String oldCustAddrLine1) {
        this.oldCustAddrLine1 = oldCustAddrLine1;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrLine2} (``ACUP-OLD-CUST-ADDR-LINE-2``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrLine2() {
        return oldCustAddrLine2;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrLine2} (``ACUP-OLD-CUST-ADDR-LINE-2``).
     * :param oldCustAddrLine2: the value the caller read before editing.
     */
    public void setOldCustAddrLine2(String oldCustAddrLine2) {
        this.oldCustAddrLine2 = oldCustAddrLine2;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrLine3} (``ACUP-OLD-CUST-ADDR-LINE-3``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrLine3() {
        return oldCustAddrLine3;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrLine3} (``ACUP-OLD-CUST-ADDR-LINE-3``).
     * :param oldCustAddrLine3: the value the caller read before editing.
     */
    public void setOldCustAddrLine3(String oldCustAddrLine3) {
        this.oldCustAddrLine3 = oldCustAddrLine3;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrStateCd} (``ACUP-OLD-CUST-ADDR-STATE-CD``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrStateCd() {
        return oldCustAddrStateCd;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrStateCd} (``ACUP-OLD-CUST-ADDR-STATE-CD``).
     * :param oldCustAddrStateCd: the value the caller read before editing.
     */
    public void setOldCustAddrStateCd(String oldCustAddrStateCd) {
        this.oldCustAddrStateCd = oldCustAddrStateCd;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrCountryCd} (``ACUP-OLD-CUST-ADDR-COUNTRY-CD``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrCountryCd() {
        return oldCustAddrCountryCd;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrCountryCd} (``ACUP-OLD-CUST-ADDR-COUNTRY-CD``).
     * :param oldCustAddrCountryCd: the value the caller read before editing.
     */
    public void setOldCustAddrCountryCd(String oldCustAddrCountryCd) {
        this.oldCustAddrCountryCd = oldCustAddrCountryCd;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custAddrZip} (``ACUP-OLD-CUST-ADDR-ZIP``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustAddrZip() {
        return oldCustAddrZip;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custAddrZip} (``ACUP-OLD-CUST-ADDR-ZIP``).
     * :param oldCustAddrZip: the value the caller read before editing.
     */
    public void setOldCustAddrZip(String oldCustAddrZip) {
        this.oldCustAddrZip = oldCustAddrZip;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custPhoneNum1} (``ACUP-OLD-CUST-PHONE-NUM-1``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustPhoneNum1() {
        return oldCustPhoneNum1;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custPhoneNum1} (``ACUP-OLD-CUST-PHONE-NUM-1``).
     * :param oldCustPhoneNum1: the value the caller read before editing.
     */
    public void setOldCustPhoneNum1(String oldCustPhoneNum1) {
        this.oldCustPhoneNum1 = oldCustPhoneNum1;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custPhoneNum2} (``ACUP-OLD-CUST-PHONE-NUM-2``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustPhoneNum2() {
        return oldCustPhoneNum2;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custPhoneNum2} (``ACUP-OLD-CUST-PHONE-NUM-2``).
     * :param oldCustPhoneNum2: the value the caller read before editing.
     */
    public void setOldCustPhoneNum2(String oldCustPhoneNum2) {
        this.oldCustPhoneNum2 = oldCustPhoneNum2;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custSsn} (``ACUP-OLD-CUST-SSN``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustSsn() {
        return oldCustSsn;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custSsn} (``ACUP-OLD-CUST-SSN``).
     * :param oldCustSsn: the value the caller read before editing.
     */
    public void setOldCustSsn(String oldCustSsn) {
        this.oldCustSsn = oldCustSsn;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custGovtIssuedId} (``ACUP-OLD-CUST-GOVT-ISSUED-ID``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustGovtIssuedId() {
        return oldCustGovtIssuedId;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custGovtIssuedId} (``ACUP-OLD-CUST-GOVT-ISSUED-ID``).
     * :param oldCustGovtIssuedId: the value the caller read before editing.
     */
    public void setOldCustGovtIssuedId(String oldCustGovtIssuedId) {
        this.oldCustGovtIssuedId = oldCustGovtIssuedId;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custDobYyyyMmDd} (``ACUP-OLD-CUST-DOB-YYYY-MM-DD``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustDobYyyyMmDd() {
        return oldCustDobYyyyMmDd;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custDobYyyyMmDd} (``ACUP-OLD-CUST-DOB-YYYY-MM-DD``).
     * :param oldCustDobYyyyMmDd: the value the caller read before editing.
     */
    public void setOldCustDobYyyyMmDd(String oldCustDobYyyyMmDd) {
        this.oldCustDobYyyyMmDd = oldCustDobYyyyMmDd;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custEftAccountId} (``ACUP-OLD-CUST-EFT-ACCOUNT-ID``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustEftAccountId() {
        return oldCustEftAccountId;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custEftAccountId} (``ACUP-OLD-CUST-EFT-ACCOUNT-ID``).
     * :param oldCustEftAccountId: the value the caller read before editing.
     */
    public void setOldCustEftAccountId(String oldCustEftAccountId) {
        this.oldCustEftAccountId = oldCustEftAccountId;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custPriCardHolderInd} (``ACUP-OLD-CUST-PRI-HOLDER-IND``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public String getOldCustPriCardHolderInd() {
        return oldCustPriCardHolderInd;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custPriCardHolderInd} (``ACUP-OLD-CUST-PRI-HOLDER-IND``).
     * :param oldCustPriCardHolderInd: the value the caller read before editing.
     */
    public void setOldCustPriCardHolderInd(String oldCustPriCardHolderInd) {
        this.oldCustPriCardHolderInd = oldCustPriCardHolderInd;
    }

    /**
     * :purpose: Return the display-time snapshot of {@code custFicoCreditScore} (``ACUP-OLD-CUST-FICO-SCORE``).
     * :output: the value the caller read before editing, or ``null`` when the caller
     *     supplied no snapshot.
     */
    public Integer getOldCustFicoCreditScore() {
        return oldCustFicoCreditScore;
    }

    /**
     * :purpose: Set the display-time snapshot of {@code custFicoCreditScore} (``ACUP-OLD-CUST-FICO-SCORE``).
     * :param oldCustFicoCreditScore: the value the caller read before editing.
     */
    public void setOldCustFicoCreditScore(Integer oldCustFicoCreditScore) {
        this.oldCustFicoCreditScore = oldCustFicoCreditScore;
    }

}
