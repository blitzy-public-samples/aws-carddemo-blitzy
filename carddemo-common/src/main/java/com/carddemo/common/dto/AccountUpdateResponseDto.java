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
 * :purpose: Outbound DTO echoing the persisted account and customer state after an update (COACTUPC, CICS CAUP). Uses the same field set as the account view response; monetary fields are scale-2 BigDecimal.
 * :output: A mutable carrier of the persisted account master and owning-customer fields.
 */
public class AccountUpdateResponseDto {

    /** :purpose: the account id primary key (``ACCT-ID``). */
    private Long acctId;

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

    /** :purpose: the owning customer id (``CUST-ID``). */
    private Long custId;

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
     * :purpose: Create an empty AccountUpdateResponseDto. Required for JSON (Jackson) serialization.
     */
    public AccountUpdateResponseDto() {
    }

    /**
     * :purpose: Return the account id primary key (``ACCT-ID``).
     * :output: the ``acctId`` value.
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * :purpose: Set the account id primary key (``ACCT-ID``).
     * :param acctId: the ``acctId`` value.
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
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
     * :purpose: Return the owning customer id (``CUST-ID``).
     * :output: the ``custId`` value.
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * :purpose: Set the owning customer id (``CUST-ID``).
     * :param custId: the ``custId`` value.
     */
    public void setCustId(Long custId) {
        this.custId = custId;
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

}
