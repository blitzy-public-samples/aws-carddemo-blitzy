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
package com.carddemo.reporting;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.reporting.mapper.StatementMapper;
import com.carddemo.reporting.mapper.StatementMapper.StatementModel;
import com.carddemo.reporting.mapper.StatementMapper.StatementTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Pure JUnit 5 unit test for {@link StatementMapper} that verifies the
 *   COBOL-to-Java field-mapping and monetary-precision logic of the account
 *   statement mapper: the ``firstToken`` name/address assembly re-expressing the
 *   ``CBSTM03A`` ``STRING ... DELIMITED BY ' '`` concatenations, and the scale-2
 *   truncate-toward-zero normalization of the current balance, the per-line amount
 *   and the running total.
 * :output: Assertions confirming byte-exact assembled strings and drift-free
 *   scale-2 monetary values. The test runs under Surefire as a plain POJO test
 *   with no application context, no mocking framework and no persistence layer,
 *   because the mapper has no collaborators.
 */
class StatementMapperTest {

    /** Obviously-synthetic 16-character card number used in place of any real PAN. */
    private static final String SYNTHETIC_CARD = "0000000000000000";

    /** System under test; the mapper is stateless and constructed directly. */
    private StatementMapper mapper;

    /**
     * :purpose: Create a fresh, stateless {@link StatementMapper} before each test.
     */
    @BeforeEach
    void setUp() {
        mapper = new StatementMapper();
    }

    /**
     * :purpose: Verify the scalar identity fields (account id, grouping card
     *   number and FICO score) are copied straight through from the source
     *   entities into the assembled statement model.
     */
    @Test
    void toStatementMapsScalarIdentityFields() {
        Account account = newAccount(1000000001L, new BigDecimal("500.00"));
        Customer customer = newCustomer("JOHN", null, "DOE",
                null, null, null, null, null, null, 742);
        CardXref cardXref = newCardXref(SYNTHETIC_CARD, 9L, 1000000001L);

        StatementModel model = mapper.toStatement(account, customer, cardXref, List.of());

        assertThat(model.getAccountId()).isEqualTo(1000000001L);
        assertThat(model.getCardNumber()).isEqualTo(SYNTHETIC_CARD);
        assertThat(model.getFicoScore()).isEqualTo(742);
    }

    /**
     * :purpose: Verify ``customerName`` is the first whitespace-delimited token of
     *   each of the first, middle and last name joined by single spaces (COBOL
     *   ``DELIMITED BY ' '``), with the trailing space removed.
     */
    @Test
    void customerNameUsesFirstTokenOfEachNamePart() {
        Customer customer = newCustomer("JOHN SMITH", "A", "DOE JR",
                null, null, null, null, null, null, 700);

        StatementModel model = mapper.toStatement(null, customer, null, List.of());

        assertThat(model.getCustomerName()).isEqualTo("JOHN A DOE");
    }

    /**
     * :purpose: Verify a ``null`` name part contributes the empty string (not
     *   ``null``), preserving the interior double space produced by the
     *   single-space joins around an empty middle token.
     */
    @Test
    void firstTokenReturnsEmptyStringForNullMiddleName() {
        Customer customer = newCustomer("ANN", null, "LEE",
                null, null, null, null, null, null, 700);

        StatementModel model = mapper.toStatement(null, customer, null, List.of());

        assertThat(model.getCustomerName()).isEqualTo("ANN  LEE");
    }

    /**
     * :purpose: Verify address lines 1 and 2 are copied as whole fields, without
     *   tokenizing or trimming interior spaces.
     */
    @Test
    void addressLine1AndLine2CopiedAsWholeFields() {
        Customer customer = newCustomer("JOHN", "A", "DOE",
                "123 MAIN ST", "SUITE 100 B", null, null, null, null, 700);

        StatementModel model = mapper.toStatement(null, customer, null, List.of());

        assertThat(model.getAddressLine1()).isEqualTo("123 MAIN ST");
        assertThat(model.getAddressLine2()).isEqualTo("SUITE 100 B");
    }

    /**
     * :purpose: Verify ``addressLine3`` is the first token of line 3, state,
     *   country and ZIP joined by single spaces with the trailing space removed.
     */
    @Test
    void addressLine3AssembledFromFirstTokens() {
        Customer customer = newCustomer("JOHN", "A", "DOE",
                null, null, "APT 5", "WA X", "USA Y", "99999 Z", 700);

        StatementModel model = mapper.toStatement(null, customer, null, List.of());

        assertThat(model.getAddressLine3()).isEqualTo("APT WA USA 99999");
    }

    /**
     * :purpose: Verify the current balance is normalized to scale 2; a scale-1
     *   input is padded to two fractional digits.
     */
    @Test
    void currentBalanceScaledToTwoHalfUp() {
        Account account = newAccount(1000000001L, new BigDecimal("1234.5"));

        StatementModel model = mapper.toStatement(account, null, null, List.of());

        assertMoney(model.getCurrentBalance(), new BigDecimal("1234.50"));
    }

    /**
     * :purpose: Verify the current balance truncates toward zero (not ``HALF_UP``
     *   or ``HALF_EVEN``): ``100.005`` drops to ``100.00`` at scale 2, because the
     *   legacy ``MOVE`` into the ``V99`` receiver carries no ``ROUNDED`` phrase.
     */
    @Test
    void currentBalanceRoundingTruncates() {
        Account account = newAccount(1000000001L, new BigDecimal("100.005"));

        StatementModel model = mapper.toStatement(account, null, null, List.of());

        assertMoney(model.getCurrentBalance(), new BigDecimal("100.00"));
    }

    /**
     * :purpose: Verify the running total is the scale-2 sum of the scaled
     *   per-transaction amounts and that one line is produced per input.
     */
    @Test
    void totalAmountSumsScaledTransactionAmounts() {
        List<Transaction> transactions = List.of(
                newTransaction("0000000000000001", new BigDecimal("100.50")),
                newTransaction("0000000000000002", new BigDecimal("200.25")));

        StatementModel model = mapper.toStatement(null, null, null, transactions);

        assertMoney(model.getTotalAmount(), new BigDecimal("300.75"));
        assertThat(model.getTransactions()).hasSize(2);
    }

    /**
     * :purpose: Verify an empty transaction list yields a scale-2 zero total and
     *   an empty (non-``null``) transaction list.
     */
    @Test
    void totalAmountForEmptyTransactionListIsZeroScaleTwo() {
        StatementModel model = mapper.toStatement(null, null, null, List.of());

        assertThat(model.getTotalAmount().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(model.getTotalAmount().scale()).isEqualTo(2);
        assertThat(model.getTransactions()).isNotNull().isEmpty();
    }

    /**
     * :purpose: Verify every field of a single transaction is carried into the
     *   statement line, with the amount normalized to scale 2 and the synthetic
     *   card number preserved verbatim.
     */
    @Test
    void toStatementTransactionMapsEveryField() {
        Transaction transaction = new Transaction();
        transaction.setTranId("0000000000000123");
        transaction.setTranDesc("PURCHASE AT STORE");
        transaction.setTranAmt(new BigDecimal("42.50"));
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(5);
        transaction.setTranSource("POS");
        transaction.setTranCardNum(SYNTHETIC_CARD);
        transaction.setTranMerchantId(9000000001L);
        transaction.setTranMerchantName("ACME STORE");
        transaction.setTranMerchantCity("SEATTLE");
        transaction.setTranMerchantZip("98101");
        transaction.setTranOrigTs("2024-01-15-10.30.00.123456");
        transaction.setTranProcTs("2024-01-16-08.15.00.654321");

        StatementTransaction line = mapper.toStatementTransaction(transaction);

        assertThat(line.getTranId()).isEqualTo("0000000000000123");
        assertThat(line.getDescription()).isEqualTo("PURCHASE AT STORE");
        assertMoney(line.getAmount(), new BigDecimal("42.50"));
        assertThat(line.getTypeCd()).isEqualTo("01");
        assertThat(line.getCatCd()).isEqualTo(5);
        assertThat(line.getSource()).isEqualTo("POS");
        assertThat(line.getCardNum()).isEqualTo(SYNTHETIC_CARD);
        assertThat(line.getMerchantId()).isEqualTo(9000000001L);
        assertThat(line.getMerchantName()).isEqualTo("ACME STORE");
        assertThat(line.getMerchantCity()).isEqualTo("SEATTLE");
        assertThat(line.getMerchantZip()).isEqualTo("98101");
        assertThat(line.getOrigTs()).isEqualTo("2024-01-15-10.30.00.123456");
        assertThat(line.getProcTs()).isEqualTo("2024-01-16-08.15.00.654321");
    }

    /**
     * :purpose: Verify one statement line is built per input transaction and the
     *   lines preserve the input order.
     */
    @Test
    void toStatementBuildsOneStatementTransactionPerInputInOrder() {
        List<Transaction> transactions = List.of(
                newTransaction("0000000000000001", new BigDecimal("10.00")),
                newTransaction("0000000000000002", new BigDecimal("20.00")),
                newTransaction("0000000000000003", new BigDecimal("30.00")));

        StatementModel model = mapper.toStatement(null, null, null, transactions);

        assertThat(model.getTransactions()).hasSize(3);
        assertThat(model.getTransactions().get(0).getTranId()).isEqualTo("0000000000000001");
        assertThat(model.getTransactions().get(1).getTranId()).isEqualTo("0000000000000002");
        assertThat(model.getTransactions().get(2).getTranId()).isEqualTo("0000000000000003");
    }

    /**
     * :purpose: Assert a monetary value equals the expected amount by numeric
     *   comparison and carries exactly scale 2.
     * :param actual: the monetary value produced by the mapper.
     * :param expected: the expected monetary value (built with the string ctor).
     */
    private static void assertMoney(BigDecimal actual, BigDecimal expected) {
        assertThat(actual.compareTo(expected)).isZero();
        assertThat(actual.scale()).isEqualTo(2);
    }

    /**
     * :purpose: Build a {@link Customer} fixture from the fields consumed by the
     *   mapper; ``null`` arguments are applied verbatim so the mapper's
     *   null-handling can be exercised.
     * :param first: the customer first name.
     * :param middle: the customer middle name.
     * :param last: the customer last name.
     * :param addr1: address line 1.
     * :param addr2: address line 2.
     * :param addr3: address line 3.
     * :param state: the address state code.
     * :param country: the address country code.
     * :param zip: the address ZIP code.
     * :param fico: the FICO credit score.
     * :returns: a populated {@link Customer} fixture.
     */
    private static Customer newCustomer(String first, String middle, String last,
                                        String addr1, String addr2, String addr3,
                                        String state, String country, String zip,
                                        Integer fico) {
        Customer customer = new Customer();
        customer.setCustFirstName(first);
        customer.setCustMiddleName(middle);
        customer.setCustLastName(last);
        customer.setCustAddrLine1(addr1);
        customer.setCustAddrLine2(addr2);
        customer.setCustAddrLine3(addr3);
        customer.setCustAddrStateCd(state);
        customer.setCustAddrCountryCd(country);
        customer.setCustAddrZip(zip);
        customer.setCustFicoCreditScore(fico);
        return customer;
    }

    /**
     * :purpose: Build an {@link Account} fixture with an id and, when supplied, a
     *   current balance (already built with the string constructor at the call
     *   site).
     * :param acctId: the account identifier.
     * :param currBal: the current balance, or ``null`` to leave it unset.
     * :returns: a populated {@link Account} fixture.
     */
    private static Account newAccount(Long acctId, BigDecimal currBal) {
        Account account = new Account();
        account.setAcctId(acctId);
        if (currBal != null) {
            account.setAcctCurrBal(currBal);
        }
        return account;
    }

    /**
     * :purpose: Build a {@link CardXref} fixture linking a card number to a
     *   customer and account.
     * :param cardNum: the (synthetic) card number.
     * :param custId: the linked customer id.
     * :param acctId: the linked account id.
     * :returns: a populated {@link CardXref} fixture.
     */
    private static CardXref newCardXref(String cardNum, Long custId, Long acctId) {
        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(cardNum);
        cardXref.setXrefCustId(custId);
        cardXref.setXrefAcctId(acctId);
        return cardXref;
    }

    /**
     * :purpose: Build a minimal {@link Transaction} fixture carrying an id and a
     *   monetary amount (already built with the string constructor at the call
     *   site).
     * :param tranId: the transaction identifier.
     * :param tranAmt: the transaction amount.
     * :returns: a populated {@link Transaction} fixture.
     */
    private static Transaction newTransaction(String tranId, BigDecimal tranAmt) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranAmt(tranAmt);
        return transaction;
    }
}
