/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.reporting.mapper;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * :purpose: Hand-written, stateless mapper that assembles the account
 *   ``StatementModel`` (customer identity and address, account id, current
 *   balance, FICO score, card number, transaction lines, and the scale-2
 *   running total) from the shared ``carddemo-common`` domain entities
 *   ``Account``, ``Customer``, ``CardXref`` and ``Transaction``. It re-expresses
 *   the field-assembly logic of the legacy batch statement engine ``CBSTM03A``
 *   (paragraphs ``5000-CREATE-STATEMENT`` and ``6000-WRITE-TRANS``), reproducing
 *   the two ``STRING ... DELIMITED BY ' '`` concatenations (customer name and
 *   address line 3) and the per-account ``WS-TOTAL-AMT`` accumulation.
 * :output: The field-level source-of-truth ``StatementModel`` consumed by the
 *   plain-text and HTML statement renderers in ``batch/StatementGenerationJob``;
 *   monetary figures are normalized to scale 2 (``RoundingMode.HALF_UP``).
 *   Performs no persistence, I/O, rendering, logging or date reformatting.
 */
@Component
public class StatementMapper {

    /**
     * :purpose: Build one complete account statement for a single account/card,
     *   re-expressing ``CBSTM03A`` ``5000-CREATE-STATEMENT`` together with the
     *   per-card loop that invokes ``6000-WRITE-TRANS`` and accumulates
     *   ``WS-TOTAL-AMT``. The customer name and address line 3 reproduce the
     *   COBOL ``STRING ... DELIMITED BY ' '`` assemblies (first whitespace token
     *   of each part joined by single spaces); the final trailing space produced
     *   by the COBOL move is removed while inner single-space joins are preserved.
     *   The running total starts at scale-2 zero, adds each transaction's scale-2
     *   amount, and is returned at scale 2.
     * :param account: the owning account supplying the account id and current
     *   balance; when ``null`` those fields are left unset.
     * :param customer: the owning customer supplying the assembled name, address
     *   lines and FICO score; when ``null`` those fields are left unset.
     * :param cardXref: the card cross-reference supplying the grouping card
     *   number; may be ``null``.
     * :param transactions: the transactions belonging to this account/card; may
     *   be ``null`` or empty, in which case the total is scale-2 zero.
     * :returns: the assembled ``StatementModel`` with its transaction lines and
     *   scale-2 running total.
     */
    public StatementModel toStatement(Account account,
                                      Customer customer,
                                      CardXref cardXref,
                                      List<Transaction> transactions) {
        StatementModel model = new StatementModel();

        if (customer != null) {
            // ST-NAME (CBSTM03A L462-L469): first token of first/middle/last
            // joined by single spaces, with the COBOL trailing space removed.
            String customerName = firstToken(customer.getCustFirstName()) + " "
                    + firstToken(customer.getCustMiddleName()) + " "
                    + firstToken(customer.getCustLastName()) + " ";
            model.setCustomerName(customerName.stripTrailing());

            // ST-ADD1 / ST-ADD2 (L470-L471): copied verbatim.
            model.setAddressLine1(customer.getCustAddrLine1());
            model.setAddressLine2(customer.getCustAddrLine2());

            // ST-ADD3 (L472-L481): first token of line3/state/country/zip joined
            // by single spaces, with the COBOL trailing space removed.
            String addressLine3 = firstToken(customer.getCustAddrLine3()) + " "
                    + firstToken(customer.getCustAddrStateCd()) + " "
                    + firstToken(customer.getCustAddrCountryCd()) + " "
                    + firstToken(customer.getCustAddrZip()) + " ";
            model.setAddressLine3(addressLine3.stripTrailing());

            // ST-FICO-SCORE (L485).
            model.setFicoScore(customer.getCustFicoCreditScore());
        }

        if (account != null) {
            // ST-ACCT-ID (L483).
            model.setAccountId(account.getAcctId());
            // ST-CURR-BAL (L484): scale-2 monetary field.
            model.setCurrentBalance(scale2(account.getAcctCurrBal()));
        }

        // Grouping card number (XREF-CARD-NUM); carried for rendering only.
        if (cardXref != null) {
            model.setCardNumber(cardXref.getXrefCardNum());
        }

        // Transaction lines and running total: WS-TOTAL-AMT reset to zero per
        // account (L325), then ADD TRNX-AMT TO WS-TOTAL-AMT per line (L429).
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<StatementTransaction> lines = new ArrayList<>();
        if (transactions != null) {
            for (Transaction transaction : transactions) {
                if (transaction == null) {
                    continue;
                }
                StatementTransaction line = toStatementTransaction(transaction);
                lines.add(line);
                if (line.getAmount() != null) {
                    total = total.add(line.getAmount());
                }
            }
        }
        model.setTransactions(lines);
        model.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));

        return model;
    }

    /**
     * :purpose: Map one posted transaction into a statement line, re-expressing
     *   the ``6000-WRITE-TRANS`` moves (``TRNX-ID`` / ``TRNX-DESC`` / ``TRNX-AMT``)
     *   plus the full ``TRNX-RECORD`` field carry so the model is a complete
     *   field source-of-truth. The amount is normalized to scale 2
     *   (``RoundingMode.HALF_UP``); the 26-character origination and processing
     *   timestamps are copied verbatim as strings without reformatting.
     * :param transaction: the posted transaction to map; may be ``null``.
     * :returns: the populated ``StatementTransaction`` with a scale-2 amount, or
     *   ``null`` when ``transaction`` is ``null``.
     */
    public StatementTransaction toStatementTransaction(Transaction transaction) {
        if (transaction == null) {
            return null;
        }
        StatementTransaction line = new StatementTransaction();
        line.setTranId(transaction.getTranId());
        line.setDescription(transaction.getTranDesc());
        line.setAmount(scale2(transaction.getTranAmt()));
        line.setTypeCd(transaction.getTranTypeCd());
        line.setCatCd(transaction.getTranCatCd());
        line.setSource(transaction.getTranSource());
        line.setCardNum(transaction.getTranCardNum());
        line.setMerchantId(transaction.getTranMerchantId());
        line.setMerchantName(transaction.getTranMerchantName());
        line.setMerchantCity(transaction.getTranMerchantCity());
        line.setMerchantZip(transaction.getTranMerchantZip());
        line.setOrigTs(transaction.getTranOrigTs());
        line.setProcTs(transaction.getTranProcTs());
        return line;
    }

    /**
     * :purpose: Normalize a monetary value to scale 2 using ``RoundingMode.HALF_UP``
     *   so that migrated financial figures preserve the COBOL packed-decimal scale.
     * :param value: the monetary value to normalize; may be ``null``.
     * :returns: the value at scale 2, or ``null`` when ``value`` is ``null``.
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * :purpose: Reproduce COBOL ``DELIMITED BY ' '`` by returning the first
     *   whitespace-delimited token of a space-padded field (the characters up to,
     *   but not including, the first space).
     * :param value: the source field value; may be ``null``.
     * :returns: the substring before the first space, the whole string when it
     *   contains no space, or the empty string when ``value`` is ``null``.
     */
    private static String firstToken(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.indexOf(' ');
        return idx < 0 ? value : value.substring(0, idx);
    }

    /**
     * :purpose: The assembled per-account statement carrying the customer
     *   identity and address lines, the account id, the current balance, the
     *   FICO score, the grouping card number, the statement transaction lines
     *   and the scale-2 running total; consumed by the text and HTML statement
     *   renderers in ``batch/StatementGenerationJob``.
     */
    public static class StatementModel {

        private String customerName;
        private String addressLine1;
        private String addressLine2;
        private String addressLine3;
        private Long accountId;
        private String cardNumber;
        private BigDecimal currentBalance;
        private Integer ficoScore;
        private List<StatementTransaction> transactions = new ArrayList<>();
        private BigDecimal totalAmount;

        /**
         * :purpose: Create an empty statement model with an initialized, empty
         *   transaction list.
         */
        public StatementModel() {
        }

        /** :returns: the assembled customer name. */
        public String getCustomerName() {
            return customerName;
        }

        /** :param customerName: the assembled customer name to set. */
        public void setCustomerName(String customerName) {
            this.customerName = customerName;
        }

        /** :returns: the first address line. */
        public String getAddressLine1() {
            return addressLine1;
        }

        /** :param addressLine1: the first address line to set. */
        public void setAddressLine1(String addressLine1) {
            this.addressLine1 = addressLine1;
        }

        /** :returns: the second address line. */
        public String getAddressLine2() {
            return addressLine2;
        }

        /** :param addressLine2: the second address line to set. */
        public void setAddressLine2(String addressLine2) {
            this.addressLine2 = addressLine2;
        }

        /** :returns: the assembled third address line. */
        public String getAddressLine3() {
            return addressLine3;
        }

        /** :param addressLine3: the assembled third address line to set. */
        public void setAddressLine3(String addressLine3) {
            this.addressLine3 = addressLine3;
        }

        /** :returns: the account identifier. */
        public Long getAccountId() {
            return accountId;
        }

        /** :param accountId: the account identifier to set. */
        public void setAccountId(Long accountId) {
            this.accountId = accountId;
        }

        /** :returns: the grouping card number. */
        public String getCardNumber() {
            return cardNumber;
        }

        /** :param cardNumber: the grouping card number to set. */
        public void setCardNumber(String cardNumber) {
            this.cardNumber = cardNumber;
        }

        /** :returns: the current account balance at scale 2. */
        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        /** :param currentBalance: the current account balance at scale 2 to set. */
        public void setCurrentBalance(BigDecimal currentBalance) {
            this.currentBalance = currentBalance;
        }

        /** :returns: the customer FICO credit score. */
        public Integer getFicoScore() {
            return ficoScore;
        }

        /** :param ficoScore: the customer FICO credit score to set. */
        public void setFicoScore(Integer ficoScore) {
            this.ficoScore = ficoScore;
        }

        /** :returns: the statement transaction lines. */
        public List<StatementTransaction> getTransactions() {
            return transactions;
        }

        /** :param transactions: the statement transaction lines to set. */
        public void setTransactions(List<StatementTransaction> transactions) {
            this.transactions = transactions;
        }

        /** :returns: the scale-2 running total of the transaction amounts. */
        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        /** :param totalAmount: the scale-2 running total to set. */
        public void setTotalAmount(BigDecimal totalAmount) {
            this.totalAmount = totalAmount;
        }
    }

    /**
     * :purpose: A single statement transaction line derived from the ``COSTM01``
     *   ``TRNX-RECORD`` layout / the ``Transaction`` entity, carrying the full
     *   transaction field set with the monetary amount normalized to scale 2.
     */
    public static class StatementTransaction {

        private String tranId;
        private String description;
        private BigDecimal amount;
        private String typeCd;
        private Integer catCd;
        private String source;
        private String cardNum;
        private Long merchantId;
        private String merchantName;
        private String merchantCity;
        private String merchantZip;
        private String origTs;
        private String procTs;

        /**
         * :purpose: Create an empty statement transaction line.
         */
        public StatementTransaction() {
        }

        /** :returns: the transaction identifier. */
        public String getTranId() {
            return tranId;
        }

        /** :param tranId: the transaction identifier to set. */
        public void setTranId(String tranId) {
            this.tranId = tranId;
        }

        /** :returns: the transaction description. */
        public String getDescription() {
            return description;
        }

        /** :param description: the transaction description to set. */
        public void setDescription(String description) {
            this.description = description;
        }

        /** :returns: the transaction amount at scale 2. */
        public BigDecimal getAmount() {
            return amount;
        }

        /** :param amount: the transaction amount at scale 2 to set. */
        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        /** :returns: the transaction type code. */
        public String getTypeCd() {
            return typeCd;
        }

        /** :param typeCd: the transaction type code to set. */
        public void setTypeCd(String typeCd) {
            this.typeCd = typeCd;
        }

        /** :returns: the transaction category code. */
        public Integer getCatCd() {
            return catCd;
        }

        /** :param catCd: the transaction category code to set. */
        public void setCatCd(Integer catCd) {
            this.catCd = catCd;
        }

        /** :returns: the transaction origination source. */
        public String getSource() {
            return source;
        }

        /** :param source: the transaction origination source to set. */
        public void setSource(String source) {
            this.source = source;
        }

        /** :returns: the card number associated with the transaction. */
        public String getCardNum() {
            return cardNum;
        }

        /** :param cardNum: the card number associated with the transaction to set. */
        public void setCardNum(String cardNum) {
            this.cardNum = cardNum;
        }

        /** :returns: the merchant identifier. */
        public Long getMerchantId() {
            return merchantId;
        }

        /** :param merchantId: the merchant identifier to set. */
        public void setMerchantId(Long merchantId) {
            this.merchantId = merchantId;
        }

        /** :returns: the merchant name. */
        public String getMerchantName() {
            return merchantName;
        }

        /** :param merchantName: the merchant name to set. */
        public void setMerchantName(String merchantName) {
            this.merchantName = merchantName;
        }

        /** :returns: the merchant city. */
        public String getMerchantCity() {
            return merchantCity;
        }

        /** :param merchantCity: the merchant city to set. */
        public void setMerchantCity(String merchantCity) {
            this.merchantCity = merchantCity;
        }

        /** :returns: the merchant postal code. */
        public String getMerchantZip() {
            return merchantZip;
        }

        /** :param merchantZip: the merchant postal code to set. */
        public void setMerchantZip(String merchantZip) {
            this.merchantZip = merchantZip;
        }

        /** :returns: the 26-character origination timestamp string. */
        public String getOrigTs() {
            return origTs;
        }

        /** :param origTs: the 26-character origination timestamp string to set. */
        public void setOrigTs(String origTs) {
            this.origTs = origTs;
        }

        /** :returns: the 26-character processing timestamp string. */
        public String getProcTs() {
            return procTs;
        }

        /** :param procTs: the 26-character processing timestamp string to set. */
        public void setProcTs(String procTs) {
            this.procTs = procTs;
        }
    }

}
