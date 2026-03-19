package com.cardemo.common.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive unit tests for all 14 DTO record classes translated from
 * COBOL copybooks.  Tests exercise constructors, getters, setters,
 * {@code equals}, {@code hashCode}, and {@code toString} for full coverage.
 */
class DtoRecordTest {

    // ====================================================================
    // AccountRecord (← CVACT01Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("AccountRecord — getters/setters round-trip all fields")
    void accountRecordGettersSetters() {
        var r = new AccountRecord();
        r.setAcctId("12345678901");
        r.setAcctActiveStatus("Y");
        r.setAcctCurrBal(new BigDecimal("1000.50"));
        r.setAcctCreditLimit(new BigDecimal("5000.00"));
        r.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        r.setAcctOpenDate("2020-01-15");
        r.setAcctExpiraionDate("2025-12-31");
        r.setAcctReissueDate("2023-06-01");
        r.setAcctCurrCycCredit(new BigDecimal("500.25"));
        r.setAcctCurrCycDebit(new BigDecimal("200.75"));
        r.setAcctAddrZip("90210");
        r.setAcctGroupId("GRP001");

        assertThat(r.getAcctId()).isEqualTo("12345678901");
        assertThat(r.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(r.getAcctCurrBal()).isEqualByComparingTo("1000.50");
        assertThat(r.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(r.getAcctCashCreditLimit()).isEqualByComparingTo("2000.00");
        assertThat(r.getAcctOpenDate()).isEqualTo("2020-01-15");
        assertThat(r.getAcctExpiraionDate()).isEqualTo("2025-12-31");
        assertThat(r.getAcctReissueDate()).isEqualTo("2023-06-01");
        assertThat(r.getAcctCurrCycCredit()).isEqualByComparingTo("500.25");
        assertThat(r.getAcctCurrCycDebit()).isEqualByComparingTo("200.75");
        assertThat(r.getAcctAddrZip()).isEqualTo("90210");
        assertThat(r.getAcctGroupId()).isEqualTo("GRP001");
    }

    @Test
    @DisplayName("AccountRecord — equals and hashCode contract")
    void accountRecordEqualsHashCode() {
        var a = new AccountRecord();
        a.setAcctId("11111111111");
        var b = new AccountRecord();
        b.setAcctId("11111111111");
        var c = new AccountRecord();
        c.setAcctId("22222222222");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isEqualTo(a);
    }

    @Test
    @DisplayName("AccountRecord — toString contains field values")
    void accountRecordToString() {
        var r = new AccountRecord();
        r.setAcctId("12345678901");
        r.setAcctCurrBal(new BigDecimal("999.99"));
        String s = r.toString();
        assertThat(s).contains("12345678901");
    }

    // ====================================================================
    // CardRecord (← CVACT02Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("CardRecord — getters/setters round-trip all fields")
    void cardRecordGettersSetters() {
        var r = new CardRecord();
        r.setCardNum("4111111111111111");
        r.setCardAcctId("12345678901");
        r.setCardCvvCd("123");
        r.setCardEmbossedName("JOHN DOE");
        r.setCardExpiraionDate("12/2025");
        r.setCardActiveStatus("Y");

        assertThat(r.getCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getCardAcctId()).isEqualTo("12345678901");
        assertThat(r.getCardCvvCd()).isEqualTo("123");
        assertThat(r.getCardEmbossedName()).isEqualTo("JOHN DOE");
        assertThat(r.getCardExpiraionDate()).isEqualTo("12/2025");
        assertThat(r.getCardActiveStatus()).isEqualTo("Y");
    }

    @Test
    @DisplayName("CardRecord — equals and hashCode")
    void cardRecordEqualsHashCode() {
        var a = new CardRecord();
        a.setCardNum("4111111111111111");
        var b = new CardRecord();
        b.setCardNum("4111111111111111");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(new CardRecord());
    }

    @Test
    @DisplayName("CardRecord — toString")
    void cardRecordToString() {
        var r = new CardRecord();
        r.setCardNum("4111111111111111");
        assertThat(r.toString()).isNotNull();
    }

    // ====================================================================
    // CardXrefRecord (← CVACT03Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("CardXrefRecord — getters/setters/equals/hashCode/toString")
    void cardXrefRecordFull() {
        var r = new CardXrefRecord();
        r.setXrefCardNum("4111111111111111");
        r.setXrefCustId("000000001");
        r.setXrefAcctId("12345678901");

        assertThat(r.getXrefCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getXrefCustId()).isEqualTo("000000001");
        assertThat(r.getXrefAcctId()).isEqualTo("12345678901");

        var dup = new CardXrefRecord();
        dup.setXrefCardNum("4111111111111111");
        dup.setXrefCustId("000000001");
        dup.setXrefAcctId("12345678901");
        assertThat(r).isEqualTo(dup);
        assertThat(r.hashCode()).isEqualTo(dup.hashCode());
        assertThat(r.toString()).contains("4111111111111111");
    }

    // ====================================================================
    // CategoryBalanceRecord (← CVTRA07Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("CategoryBalanceRecord — getters/setters/equals/hashCode/toString")
    void categoryBalanceRecordFull() {
        var r = new CategoryBalanceRecord();
        r.setTrancatAcctId("12345678901");
        r.setTrancatTypeCd("SA");
        r.setTrancatCd(5001);
        r.setTranCatBal(new BigDecimal("12345.67"));

        assertThat(r.getTrancatAcctId()).isEqualTo("12345678901");
        assertThat(r.getTrancatTypeCd()).isEqualTo("SA");
        assertThat(r.getTrancatCd()).isEqualTo(5001);
        assertThat(r.getTranCatBal()).isEqualByComparingTo("12345.67");

        var dup = new CategoryBalanceRecord();
        dup.setTrancatAcctId("12345678901");
        dup.setTrancatTypeCd("SA");
        dup.setTrancatCd(5001);
        dup.setTranCatBal(new BigDecimal("12345.67"));
        assertThat(r).isEqualTo(dup);
        assertThat(r.hashCode()).isEqualTo(dup.hashCode());
        assertThat(r.toString()).contains("12345678901");
    }

    // ====================================================================
    // CreditCardDisplay (← CVCRD01Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("CreditCardDisplay — getters/setters for all 9 fields")
    void creditCardDisplayGettersSetters() {
        var r = new CreditCardDisplay();
        r.setCcardAid("ENTER");
        r.setCcardNextProg("COCRDUPC");
        r.setCcardNextMapset("COCRDUP");
        r.setCcardNextMap("COCRDUPA");
        r.setCcardErrorMsg("Error msg");
        r.setCcardReturnMsg("OK");
        r.setCcAcctId("12345678901");
        r.setCcCardNum("4111111111111111");
        r.setCcCustId("000000001");

        assertThat(r.getCcardAid()).isEqualTo("ENTER");
        assertThat(r.getCcardNextProg()).isEqualTo("COCRDUPC");
        assertThat(r.getCcardNextMapset()).isEqualTo("COCRDUP");
        assertThat(r.getCcardNextMap()).isEqualTo("COCRDUPA");
        assertThat(r.getCcardErrorMsg()).isEqualTo("Error msg");
        assertThat(r.getCcardReturnMsg()).isEqualTo("OK");
        assertThat(r.getCcAcctId()).isEqualTo("12345678901");
        assertThat(r.getCcCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getCcCustId()).isEqualTo("000000001");
    }

    @Test
    @DisplayName("CreditCardDisplay — equals/hashCode/toString")
    void creditCardDisplayEqualsHashCodeToString() {
        var a = new CreditCardDisplay();
        a.setCcCardNum("4111111111111111");
        var b = new CreditCardDisplay();
        b.setCcCardNum("4111111111111111");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isNotNull();
    }

    // ====================================================================
    // CustomerRecord (← CVCUS01Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("CustomerRecord — getters/setters for all 18 fields")
    void customerRecordGettersSetters() {
        var r = new CustomerRecord();
        r.setCustId("000000001");
        r.setCustFirstName("John");
        r.setCustMiddleName("Q");
        r.setCustLastName("Public");
        r.setCustAddrLine1("123 Main St");
        r.setCustAddrLine2("Apt 4B");
        r.setCustAddrLine3("");
        r.setCustAddrStateCd("NY");
        r.setCustAddrCountryCd("US");
        r.setCustAddrZip("10001");
        r.setCustPhoneNum1("2125551234");
        r.setCustPhoneNum2("2125555678");
        r.setCustSsn("123456789");
        r.setCustGovtIssuedId("DL1234567");
        r.setCustDobYyyyMmDd("19800115");
        r.setCustEftAccountId("EFT001");
        r.setCustPriCardHolderInd("Y");
        r.setCustFicoCreditScore("750");

        assertThat(r.getCustId()).isEqualTo("000000001");
        assertThat(r.getCustFirstName()).isEqualTo("John");
        assertThat(r.getCustMiddleName()).isEqualTo("Q");
        assertThat(r.getCustLastName()).isEqualTo("Public");
        assertThat(r.getCustAddrLine1()).isEqualTo("123 Main St");
        assertThat(r.getCustAddrLine2()).isEqualTo("Apt 4B");
        assertThat(r.getCustAddrLine3()).isEqualTo("");
        assertThat(r.getCustAddrStateCd()).isEqualTo("NY");
        assertThat(r.getCustAddrCountryCd()).isEqualTo("US");
        assertThat(r.getCustAddrZip()).isEqualTo("10001");
        assertThat(r.getCustPhoneNum1()).isEqualTo("2125551234");
        assertThat(r.getCustPhoneNum2()).isEqualTo("2125555678");
        assertThat(r.getCustSsn()).isEqualTo("123456789");
        assertThat(r.getCustGovtIssuedId()).isEqualTo("DL1234567");
        assertThat(r.getCustDobYyyyMmDd()).isEqualTo("19800115");
        assertThat(r.getCustEftAccountId()).isEqualTo("EFT001");
        assertThat(r.getCustPriCardHolderInd()).isEqualTo("Y");
        assertThat(r.getCustFicoCreditScore()).isEqualTo("750");
    }

    @Test
    @DisplayName("CustomerRecord — equals/hashCode/toString")
    void customerRecordEqualsHashCodeToString() {
        var a = new CustomerRecord();
        a.setCustId("000000001");
        var b = new CustomerRecord();
        b.setCustId("000000001");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isNotNull();
        assertThat(a).isNotEqualTo(null);
    }

    // ====================================================================
    // DailyTransactionRecord (← CVTRA06Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("DailyTransactionRecord — getters/setters for all 13 fields")
    void dailyTransactionRecordGettersSetters() {
        var r = new DailyTransactionRecord();
        r.setDalytranId("0000000001");
        r.setDalytranTypeCd("SA");
        r.setDalytranCatCd(5001);
        r.setDalytranSource("ONLINE");
        r.setDalytranDesc("Test purchase");
        r.setDalytranAmt(new BigDecimal("99.99"));
        r.setDalytranMerchantId("MERCH001");
        r.setDalytranMerchantName("Test Merchant");
        r.setDalytranMerchantCity("New York");
        r.setDalytranMerchantZip("10001");
        r.setDalytranCardNum("4111111111111111");
        r.setDalytranOrigTs("2024-01-15-10.30.00.000000");
        r.setDalytranProcTs("2024-01-15-10.30.01.000000");

        assertThat(r.getDalytranId()).isEqualTo("0000000001");
        assertThat(r.getDalytranTypeCd()).isEqualTo("SA");
        assertThat(r.getDalytranCatCd()).isEqualTo(5001);
        assertThat(r.getDalytranSource()).isEqualTo("ONLINE");
        assertThat(r.getDalytranDesc()).isEqualTo("Test purchase");
        assertThat(r.getDalytranAmt()).isEqualByComparingTo("99.99");
        assertThat(r.getDalytranMerchantId()).isEqualTo("MERCH001");
        assertThat(r.getDalytranMerchantName()).isEqualTo("Test Merchant");
        assertThat(r.getDalytranMerchantCity()).isEqualTo("New York");
        assertThat(r.getDalytranMerchantZip()).isEqualTo("10001");
        assertThat(r.getDalytranCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getDalytranOrigTs()).isEqualTo("2024-01-15-10.30.00.000000");
        assertThat(r.getDalytranProcTs()).isEqualTo("2024-01-15-10.30.01.000000");
    }

    @Test
    @DisplayName("DailyTransactionRecord — equals/hashCode/toString")
    void dailyTransactionRecordEqualsHashCodeToString() {
        var a = new DailyTransactionRecord();
        a.setDalytranId("0000000001");
        a.setDalytranCardNum("4111111111111111");
        var b = new DailyTransactionRecord();
        b.setDalytranId("0000000001");
        b.setDalytranCardNum("4111111111111111");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).contains("0000000001");
    }

    // ====================================================================
    // StatementRecord (← COSTM01.cpy)
    // ====================================================================

    @Test
    @DisplayName("StatementRecord — getters/setters for all 18 fields")
    void statementRecordGettersSetters() {
        var r = new StatementRecord();
        r.setReptShortName("STMT");
        r.setReptLongName("Monthly Statement");
        r.setReptDateHeader("January 2024");
        r.setReptStartDate("01/01/2024");
        r.setReptEndDate("01/31/2024");
        r.setTranReportTransId("TRN001");
        r.setTranReportAccountId("12345678901");
        r.setTranReportTypeCd("SA");
        r.setTranReportTypeDesc("Sale");
        r.setTranReportCatCd(5001);
        r.setTranReportCatDesc("Retail");
        r.setTranReportSource("POS");
        r.setTranReportAmt(new BigDecimal("150.00"));
        r.setReptPageTotal(new BigDecimal("500.00"));
        r.setReptAccountTotal(new BigDecimal("1500.00"));
        r.setReptGrandTotal(new BigDecimal("5000.00"));
        r.setTrnxCardNum("4111111111111111");
        r.setTrnxId("TX001");

        assertThat(r.getReptShortName()).isEqualTo("STMT");
        assertThat(r.getReptLongName()).isEqualTo("Monthly Statement");
        assertThat(r.getReptDateHeader()).isEqualTo("January 2024");
        assertThat(r.getReptStartDate()).isEqualTo("01/01/2024");
        assertThat(r.getReptEndDate()).isEqualTo("01/31/2024");
        assertThat(r.getTranReportTransId()).isEqualTo("TRN001");
        assertThat(r.getTranReportAccountId()).isEqualTo("12345678901");
        assertThat(r.getTranReportTypeCd()).isEqualTo("SA");
        assertThat(r.getTranReportTypeDesc()).isEqualTo("Sale");
        assertThat(r.getTranReportCatCd()).isEqualTo(5001);
        assertThat(r.getTranReportCatDesc()).isEqualTo("Retail");
        assertThat(r.getTranReportSource()).isEqualTo("POS");
        assertThat(r.getTranReportAmt()).isEqualByComparingTo("150.00");
        assertThat(r.getReptPageTotal()).isEqualByComparingTo("500.00");
        assertThat(r.getReptAccountTotal()).isEqualByComparingTo("1500.00");
        assertThat(r.getReptGrandTotal()).isEqualByComparingTo("5000.00");
        assertThat(r.getTrnxCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getTrnxId()).isEqualTo("TX001");
    }

    @Test
    @DisplayName("StatementRecord — equals/hashCode/toString")
    void statementRecordEqualsHashCodeToString() {
        var a = new StatementRecord();
        a.setReptShortName("STMT");
        a.setTrnxId("TX001");
        var b = new StatementRecord();
        b.setReptShortName("STMT");
        b.setTrnxId("TX001");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isNotNull();
    }

    // ====================================================================
    // TransactionRecord (← CVTRA05Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("TransactionRecord — getters/setters for all 13 fields")
    void transactionRecordGettersSetters() {
        var r = new TransactionRecord();
        r.setTranId("0000000001");
        r.setTranTypeCd("SA");
        r.setTranCatCd(5001);
        r.setTranSource("ONLINE");
        r.setTranDesc("Test sale");
        r.setTranAmt(new BigDecimal("250.00"));
        r.setTranMerchantId("MERCH001");
        r.setTranMerchantName("Store A");
        r.setTranMerchantCity("Boston");
        r.setTranMerchantZip("02101");
        r.setTranCardNum("4111111111111111");
        r.setTranOrigTs("2024-01-15-10.30.00.000000");
        r.setTranProcTs("2024-01-15-10.30.01.000000");

        assertThat(r.getTranId()).isEqualTo("0000000001");
        assertThat(r.getTranTypeCd()).isEqualTo("SA");
        assertThat(r.getTranCatCd()).isEqualTo(5001);
        assertThat(r.getTranSource()).isEqualTo("ONLINE");
        assertThat(r.getTranDesc()).isEqualTo("Test sale");
        assertThat(r.getTranAmt()).isEqualByComparingTo("250.00");
        assertThat(r.getTranMerchantId()).isEqualTo("MERCH001");
        assertThat(r.getTranMerchantName()).isEqualTo("Store A");
        assertThat(r.getTranMerchantCity()).isEqualTo("Boston");
        assertThat(r.getTranMerchantZip()).isEqualTo("02101");
        assertThat(r.getTranCardNum()).isEqualTo("4111111111111111");
        assertThat(r.getTranOrigTs()).isEqualTo("2024-01-15-10.30.00.000000");
        assertThat(r.getTranProcTs()).isEqualTo("2024-01-15-10.30.01.000000");
    }

    @Test
    @DisplayName("TransactionRecord — equals/hashCode/toString")
    void transactionRecordEqualsHashCodeToString() {
        var a = new TransactionRecord();
        a.setTranId("0000000001");
        var b = new TransactionRecord();
        b.setTranId("0000000001");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).contains("0000000001");
    }

    // ====================================================================
    // TransactionDetail (← CVTRA02Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("TransactionDetail — getters/setters/equals/hashCode/toString")
    void transactionDetailFull() {
        var r = new TransactionDetail();
        r.setDisAcctGroupId("GRP001");
        r.setDisTranTypeCd("SA");
        r.setDisTranCatCd(5001);
        r.setDisIntRate(new BigDecimal("15.50"));

        assertThat(r.getDisAcctGroupId()).isEqualTo("GRP001");
        assertThat(r.getDisTranTypeCd()).isEqualTo("SA");
        assertThat(r.getDisTranCatCd()).isEqualTo(5001);
        assertThat(r.getDisIntRate()).isEqualByComparingTo("15.50");

        var dup = new TransactionDetail();
        dup.setDisAcctGroupId("GRP001");
        dup.setDisTranTypeCd("SA");
        dup.setDisTranCatCd(5001);
        dup.setDisIntRate(new BigDecimal("15.50"));
        assertThat(r).isEqualTo(dup);
        assertThat(r.hashCode()).isEqualTo(dup.hashCode());
        assertThat(r.toString()).isNotNull();
    }

    // ====================================================================
    // TransactionDisplay (← CVTRA01Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("TransactionDisplay — getters/setters for all 7 fields")
    void transactionDisplayGettersSetters() {
        var r = new TransactionDisplay();
        r.setTrancatAcctId("12345678901");
        r.setTrancatTypeCd("SA");
        r.setTrancatCd(5001);
        r.setTranCatBal(new BigDecimal("1234.56"));
        r.setTranTypeDesc("Sale");
        r.setTranCatDesc("Retail");
        r.setFormattedBalance("$1,234.56");

        assertThat(r.getTrancatAcctId()).isEqualTo("12345678901");
        assertThat(r.getTrancatTypeCd()).isEqualTo("SA");
        assertThat(r.getTrancatCd()).isEqualTo(5001);
        assertThat(r.getTranCatBal()).isEqualByComparingTo("1234.56");
        assertThat(r.getTranTypeDesc()).isEqualTo("Sale");
        assertThat(r.getTranCatDesc()).isEqualTo("Retail");
        assertThat(r.getFormattedBalance()).isEqualTo("$1,234.56");
    }

    @Test
    @DisplayName("TransactionDisplay — equals/hashCode/toString")
    void transactionDisplayEqualsHashCodeToString() {
        var a = new TransactionDisplay();
        a.setTrancatAcctId("12345678901");
        var b = new TransactionDisplay();
        b.setTrancatAcctId("12345678901");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isNotNull();
    }

    // ====================================================================
    // TransactionListItem (← CVTRA03Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("TransactionListItem — getters/setters/equals/hashCode/toString")
    void transactionListItemFull() {
        var r = new TransactionListItem();
        r.setTranType("SA");
        r.setTranTypeDesc("Sale");

        assertThat(r.getTranType()).isEqualTo("SA");
        assertThat(r.getTranTypeDesc()).isEqualTo("Sale");

        var dup = new TransactionListItem();
        dup.setTranType("SA");
        dup.setTranTypeDesc("Sale");
        assertThat(r).isEqualTo(dup);
        assertThat(r.hashCode()).isEqualTo(dup.hashCode());
        assertThat(r.toString()).isNotNull();
    }

    // ====================================================================
    // TransactionReportItem (← CVTRA04Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("TransactionReportItem — getters/setters/equals/hashCode/toString")
    void transactionReportItemFull() {
        var r = new TransactionReportItem();
        r.setTranTypeCd("SA");
        r.setTranCatCd(5001);
        r.setTranCatTypeDesc("Sale - Retail");

        assertThat(r.getTranTypeCd()).isEqualTo("SA");
        assertThat(r.getTranCatCd()).isEqualTo(5001);
        assertThat(r.getTranCatTypeDesc()).isEqualTo("Sale - Retail");

        var dup = new TransactionReportItem();
        dup.setTranTypeCd("SA");
        dup.setTranCatCd(5001);
        dup.setTranCatTypeDesc("Sale - Retail");
        assertThat(r).isEqualTo(dup);
        assertThat(r.hashCode()).isEqualTo(dup.hashCode());
        assertThat(r.toString()).isNotNull();
    }

    // ====================================================================
    // UserSecurityRecord (← CSUSR01Y.cpy)
    // ====================================================================

    @Test
    @DisplayName("UserSecurityRecord — getters/setters for all 5 fields")
    void userSecurityRecordGettersSetters() {
        var r = new UserSecurityRecord();
        r.setSecUsrId("ADMIN001");
        r.setSecUsrFname("Admin");
        r.setSecUsrLname("User");
        r.setSecUsrPwd("hashedpwd");
        r.setSecUsrType("A");

        assertThat(r.getSecUsrId()).isEqualTo("ADMIN001");
        assertThat(r.getSecUsrFname()).isEqualTo("Admin");
        assertThat(r.getSecUsrLname()).isEqualTo("User");
        assertThat(r.getSecUsrPwd()).isEqualTo("hashedpwd");
        assertThat(r.getSecUsrType()).isEqualTo("A");
    }

    @Test
    @DisplayName("UserSecurityRecord — equals/hashCode/toString")
    void userSecurityRecordEqualsHashCodeToString() {
        var a = new UserSecurityRecord();
        a.setSecUsrId("ADMIN001");
        var b = new UserSecurityRecord();
        b.setSecUsrId("ADMIN001");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.toString()).isNotNull();
    }
}
