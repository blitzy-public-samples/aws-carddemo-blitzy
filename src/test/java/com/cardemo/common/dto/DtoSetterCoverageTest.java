/*
 * DtoSetterCoverageTest.java — Covers DTO setter methods that are missed
 * by the existing DtoRecordTest (which covers constructors + getters).
 */
package com.cardemo.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DTO Setter Coverage Tests")
class DtoSetterCoverageTest {

    @Nested
    @DisplayName("AccountRecord setters")
    class AccountRecordSetters {
        @Test
        void allSetters() {
            AccountRecord r = new AccountRecord();
            r.setAcctId("A1");
            r.setAcctActiveStatus("Y");
            r.setAcctCurrBal(new BigDecimal("100.00"));
            r.setAcctCreditLimit(new BigDecimal("5000.00"));
            r.setAcctCashCreditLimit(new BigDecimal("2000.00"));
            r.setAcctOpenDate("20200101");
            r.setAcctExpiraionDate("20281231");
            r.setAcctReissueDate("20240101");
            r.setAcctCurrCycCredit(new BigDecimal("50.00"));
            r.setAcctCurrCycDebit(new BigDecimal("25.00"));
            r.setAcctAddrZip("10001");
            r.setAcctGroupId("GRP01");
            assertThat(r.getAcctId()).isEqualTo("A1");
            assertThat(r.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(r.getAcctCurrBal()).isEqualByComparingTo("100.00");
            assertThat(r.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
            assertThat(r.getAcctCashCreditLimit()).isEqualByComparingTo("2000.00");
            assertThat(r.getAcctOpenDate()).isEqualTo("20200101");
            assertThat(r.getAcctExpiraionDate()).isEqualTo("20281231");
            assertThat(r.getAcctReissueDate()).isEqualTo("20240101");
            assertThat(r.getAcctCurrCycCredit()).isEqualByComparingTo("50.00");
            assertThat(r.getAcctCurrCycDebit()).isEqualByComparingTo("25.00");
            assertThat(r.getAcctAddrZip()).isEqualTo("10001");
            assertThat(r.getAcctGroupId()).isEqualTo("GRP01");
        }
    }

    @Nested
    @DisplayName("CardRecord setters")
    class CardRecordSetters {
        @Test
        void allSetters() {
            CardRecord r = new CardRecord();
            r.setCardNum("4111111111111111");
            r.setCardAcctId("00000000001");
            r.setCardCvvCd("123");
            r.setCardEmbossedName("JOHN DOE");
            r.setCardExpiraionDate("20281231");
            r.setCardActiveStatus("Y");
            assertThat(r.getCardNum()).isEqualTo("4111111111111111");
            assertThat(r.getCardAcctId()).isEqualTo("00000000001");
            assertThat(r.getCardCvvCd()).isEqualTo("123");
            assertThat(r.getCardEmbossedName()).isEqualTo("JOHN DOE");
            assertThat(r.getCardExpiraionDate()).isEqualTo("20281231");
            assertThat(r.getCardActiveStatus()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("CardXrefRecord setters")
    class CardXrefRecordSetters {
        @Test
        void allSetters() {
            CardXrefRecord r = new CardXrefRecord();
            r.setXrefCardNum("4111111111111111");
            r.setXrefCustId("000000001");
            r.setXrefAcctId("00000000001");
            assertThat(r.getXrefCardNum()).isEqualTo("4111111111111111");
            assertThat(r.getXrefCustId()).isEqualTo("000000001");
            assertThat(r.getXrefAcctId()).isEqualTo("00000000001");
        }
    }

    @Nested
    @DisplayName("CustomerRecord setters")
    class CustomerRecordSetters {
        @Test
        void allSetters() {
            CustomerRecord r = new CustomerRecord();
            r.setCustId("C1");
            r.setCustFirstName("John");
            r.setCustMiddleName("M");
            r.setCustLastName("Doe");
            r.setCustAddrLine1("123 Main St");
            r.setCustAddrLine2("Apt 4");
            r.setCustAddrLine3("");
            r.setCustAddrStateCd("NY");
            r.setCustAddrCountryCd("US");
            r.setCustAddrZip("10001");
            r.setCustPhoneNum1("5551234567");
            r.setCustPhoneNum2("5559876543");
            r.setCustSsn("123456789");
            r.setCustGovtIssuedId("D12345678");
            r.setCustDobYyyyMmDd("19800101");
            r.setCustEftAccountId("EFT001");
            r.setCustPriCardHolderInd("Y");
            r.setCustFicoCreditScore("750");
            assertThat(r.getCustId()).isEqualTo("C1");
            assertThat(r.getCustFirstName()).isEqualTo("John");
            assertThat(r.getCustMiddleName()).isEqualTo("M");
            assertThat(r.getCustLastName()).isEqualTo("Doe");
            assertThat(r.getCustAddrLine1()).isEqualTo("123 Main St");
            assertThat(r.getCustAddrLine2()).isEqualTo("Apt 4");
            assertThat(r.getCustAddrLine3()).isEqualTo("");
            assertThat(r.getCustAddrStateCd()).isEqualTo("NY");
            assertThat(r.getCustAddrCountryCd()).isEqualTo("US");
            assertThat(r.getCustAddrZip()).isEqualTo("10001");
            assertThat(r.getCustPhoneNum1()).isEqualTo("5551234567");
            assertThat(r.getCustPhoneNum2()).isEqualTo("5559876543");
            assertThat(r.getCustSsn()).isEqualTo("123456789");
            assertThat(r.getCustGovtIssuedId()).isEqualTo("D12345678");
            assertThat(r.getCustDobYyyyMmDd()).isEqualTo("19800101");
            assertThat(r.getCustEftAccountId()).isEqualTo("EFT001");
            assertThat(r.getCustPriCardHolderInd()).isEqualTo("Y");
            assertThat(r.getCustFicoCreditScore()).isEqualTo("750");
        }
    }

    @Nested
    @DisplayName("TransactionRecord setters")
    class TransactionRecordSetters {
        @Test
        void allSetters() {
            TransactionRecord r = new TransactionRecord();
            r.setTranId("T001");
            r.setTranTypeCd("01");
            r.setTranCatCd(5001);
            r.setTranSource("ONLINE");
            r.setTranDesc("Purchase");
            r.setTranAmt(new BigDecimal("99.99"));
            r.setTranMerchantId("M001");
            r.setTranMerchantName("Merch");
            r.setTranMerchantCity("NYC");
            r.setTranMerchantZip("10001");
            r.setTranCardNum("4111111111111111");
            r.setTranOrigTs("2024-01-15-10.30.00.000000");
            r.setTranProcTs("2024-01-15-10.30.01.000000");
            assertThat(r.getTranId()).isEqualTo("T001");
            assertThat(r.getTranTypeCd()).isEqualTo("01");
            assertThat(r.getTranCatCd()).isEqualTo(5001);
            assertThat(r.getTranSource()).isEqualTo("ONLINE");
            assertThat(r.getTranDesc()).isEqualTo("Purchase");
            assertThat(r.getTranAmt()).isEqualByComparingTo("99.99");
            assertThat(r.getTranMerchantId()).isEqualTo("M001");
            assertThat(r.getTranMerchantName()).isEqualTo("Merch");
            assertThat(r.getTranMerchantCity()).isEqualTo("NYC");
            assertThat(r.getTranMerchantZip()).isEqualTo("10001");
            assertThat(r.getTranCardNum()).isEqualTo("4111111111111111");
            assertThat(r.getTranOrigTs()).isEqualTo("2024-01-15-10.30.00.000000");
            assertThat(r.getTranProcTs()).isEqualTo("2024-01-15-10.30.01.000000");
        }
    }

    @Nested
    @DisplayName("DailyTransactionRecord setters")
    class DailyTransactionRecordSetters {
        @Test
        void allSetters() {
            DailyTransactionRecord r = new DailyTransactionRecord();
            r.setDalytranId("D001");
            r.setDalytranTypeCd("02");
            r.setDalytranCatCd(5002);
            r.setDalytranSource("BATCH");
            r.setDalytranDesc("Payment");
            r.setDalytranAmt(new BigDecimal("150.00"));
            r.setDalytranMerchantId("M002");
            r.setDalytranMerchantName("Shop");
            r.setDalytranMerchantCity("LA");
            r.setDalytranMerchantZip("90001");
            r.setDalytranCardNum("5500000000000004");
            r.setDalytranOrigTs("2024-02-01-08.00.00.000000");
            r.setDalytranProcTs("2024-02-01-08.00.01.000000");
            assertThat(r.getDalytranId()).isEqualTo("D001");
            assertThat(r.getDalytranTypeCd()).isEqualTo("02");
            assertThat(r.getDalytranCatCd()).isEqualTo(5002);
            assertThat(r.getDalytranSource()).isEqualTo("BATCH");
            assertThat(r.getDalytranDesc()).isEqualTo("Payment");
            assertThat(r.getDalytranAmt()).isEqualByComparingTo("150.00");
            assertThat(r.getDalytranMerchantId()).isEqualTo("M002");
            assertThat(r.getDalytranMerchantName()).isEqualTo("Shop");
            assertThat(r.getDalytranMerchantCity()).isEqualTo("LA");
            assertThat(r.getDalytranMerchantZip()).isEqualTo("90001");
            assertThat(r.getDalytranCardNum()).isEqualTo("5500000000000004");
            assertThat(r.getDalytranOrigTs()).isEqualTo("2024-02-01-08.00.00.000000");
            assertThat(r.getDalytranProcTs()).isEqualTo("2024-02-01-08.00.01.000000");
        }
    }

    @Nested
    @DisplayName("CategoryBalanceRecord setters")
    class CategoryBalanceRecordSetters {
        @Test
        void allSetters() {
            CategoryBalanceRecord r = new CategoryBalanceRecord();
            r.setTrancatAcctId("00000000001");
            r.setTrancatTypeCd("01");
            r.setTrancatCd(5001);
            r.setTranCatBal(new BigDecimal("500.00"));
            assertThat(r.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(r.getTrancatTypeCd()).isEqualTo("01");
            assertThat(r.getTrancatCd()).isEqualTo(5001);
            assertThat(r.getTranCatBal()).isEqualByComparingTo("500.00");
        }
    }

    @Nested
    @DisplayName("UserSecurityRecord setters")
    class UserSecurityRecordSetters {
        @Test
        void allSetters() {
            UserSecurityRecord r = new UserSecurityRecord();
            r.setSecUsrId("USR001");
            r.setSecUsrFname("Admin");
            r.setSecUsrLname("User");
            r.setSecUsrType("A");
            r.setSecUsrPwd("hidden");
            assertThat(r.getSecUsrId()).isEqualTo("USR001");
            assertThat(r.getSecUsrFname()).isEqualTo("Admin");
            assertThat(r.getSecUsrLname()).isEqualTo("User");
            assertThat(r.getSecUsrType()).isEqualTo("A");
            assertThat(r.getSecUsrPwd()).isEqualTo("hidden");
        }
    }

    @Nested
    @DisplayName("StatementRecord setters")
    class StatementRecordSetters {
        @Test
        void headerSetters() {
            StatementRecord r = new StatementRecord();
            r.setReptShortName("CardDemo");
            r.setReptLongName("CardDemo Credit Card Statement");
            r.setReptDateHeader("Statement Date");
            r.setReptStartDate("2024-01-01");
            r.setReptEndDate("2024-01-31");
            assertThat(r.getReptShortName()).isEqualTo("CardDemo");
            assertThat(r.getReptLongName()).isEqualTo("CardDemo Credit Card Statement");
            assertThat(r.getReptDateHeader()).isEqualTo("Statement Date");
            assertThat(r.getReptStartDate()).isEqualTo("2024-01-01");
            assertThat(r.getReptEndDate()).isEqualTo("2024-01-31");
        }

        @Test
        void transactionSetters() {
            StatementRecord r = new StatementRecord();
            r.setTranReportTransId("T001");
            r.setTranReportAccountId("00000000001");
            r.setTranReportTypeCd("01");
            r.setTranReportTypeDesc("Purchase");
            r.setTranReportCatCd(5001);
            r.setTranReportCatDesc("Retail");
            r.setTranReportSource("ONLINE");
            r.setTranReportAmt(new BigDecimal("99.99"));
            assertThat(r.getTranReportTransId()).isEqualTo("T001");
            assertThat(r.getTranReportAccountId()).isEqualTo("00000000001");
            assertThat(r.getTranReportTypeCd()).isEqualTo("01");
            assertThat(r.getTranReportTypeDesc()).isEqualTo("Purchase");
            assertThat(r.getTranReportCatCd()).isEqualTo(5001);
            assertThat(r.getTranReportCatDesc()).isEqualTo("Retail");
            assertThat(r.getTranReportSource()).isEqualTo("ONLINE");
            assertThat(r.getTranReportAmt()).isEqualByComparingTo("99.99");
        }

        @Test
        void totalAndCardSetters() {
            StatementRecord r = new StatementRecord();
            r.setReptPageTotal(new BigDecimal("500.00"));
            r.setReptAccountTotal(new BigDecimal("2500.00"));
            r.setReptGrandTotal(new BigDecimal("10000.00"));
            r.setTrnxCardNum("4111111111111111");
            r.setTrnxId("TX001");
            assertThat(r.getReptPageTotal()).isEqualByComparingTo("500.00");
            assertThat(r.getReptAccountTotal()).isEqualByComparingTo("2500.00");
            assertThat(r.getReptGrandTotal()).isEqualByComparingTo("10000.00");
            assertThat(r.getTrnxCardNum()).isEqualTo("4111111111111111");
            assertThat(r.getTrnxId()).isEqualTo("TX001");
        }
    }

    @Nested
    @DisplayName("CreditCardDisplay setters")
    class CreditCardDisplaySetters {
        @Test
        void allSetters() {
            CreditCardDisplay r = new CreditCardDisplay();
            r.setCcardAid("A");
            r.setCcardNextProg("COMEN01");
            r.setCcardNextMapset("COMEN01");
            r.setCcardNextMap("COMEN0A");
            r.setCcardErrorMsg("Error");
            r.setCcardReturnMsg("Return");
            r.setCcAcctId("00000000001");
            r.setCcCardNum("4111111111111111");
            r.setCcCustId("000000001");
            assertThat(r.getCcardAid()).isEqualTo("A");
            assertThat(r.getCcardNextProg()).isEqualTo("COMEN01");
            assertThat(r.getCcardNextMapset()).isEqualTo("COMEN01");
            assertThat(r.getCcardNextMap()).isEqualTo("COMEN0A");
            assertThat(r.getCcardErrorMsg()).isEqualTo("Error");
            assertThat(r.getCcardReturnMsg()).isEqualTo("Return");
            assertThat(r.getCcAcctId()).isEqualTo("00000000001");
            assertThat(r.getCcCardNum()).isEqualTo("4111111111111111");
            assertThat(r.getCcCustId()).isEqualTo("000000001");
        }
    }

    @Nested
    @DisplayName("TransactionDisplay setters")
    class TransactionDisplaySetters {
        @Test
        void allSetters() {
            TransactionDisplay r = new TransactionDisplay();
            r.setTrancatAcctId("00000000001");
            r.setTrancatTypeCd("01");
            r.setTrancatCd(5001);
            r.setTranCatBal(new BigDecimal("500.00"));
            r.setTranTypeDesc("Purchase");
            r.setTranCatDesc("Retail");
            r.setFormattedBalance("$500.00");
            assertThat(r.getTrancatAcctId()).isEqualTo("00000000001");
            assertThat(r.getTrancatTypeCd()).isEqualTo("01");
            assertThat(r.getTrancatCd()).isEqualTo(5001);
            assertThat(r.getTranCatBal()).isEqualByComparingTo("500.00");
            assertThat(r.getTranTypeDesc()).isEqualTo("Purchase");
            assertThat(r.getTranCatDesc()).isEqualTo("Retail");
            assertThat(r.getFormattedBalance()).isEqualTo("$500.00");
        }
    }

    @Nested
    @DisplayName("TransactionDetail setters")
    class TransactionDetailSetters {
        @Test
        void allSetters() {
            TransactionDetail r = new TransactionDetail();
            r.setDisAcctGroupId("GRP01");
            r.setDisTranTypeCd("01");
            r.setDisTranCatCd(5001);
            r.setDisIntRate(new BigDecimal("15.99"));
            assertThat(r.getDisAcctGroupId()).isEqualTo("GRP01");
            assertThat(r.getDisTranTypeCd()).isEqualTo("01");
            assertThat(r.getDisTranCatCd()).isEqualTo(5001);
            assertThat(r.getDisIntRate()).isEqualByComparingTo("15.99");
        }
    }

    @Nested
    @DisplayName("TransactionListItem setters")
    class TransactionListItemSetters {
        @Test
        void allSetters() {
            TransactionListItem r = new TransactionListItem();
            r.setTranType("01");
            r.setTranTypeDesc("Purchase");
            assertThat(r.getTranType()).isEqualTo("01");
            assertThat(r.getTranTypeDesc()).isEqualTo("Purchase");
        }
    }

    @Nested
    @DisplayName("TransactionReportItem setters")
    class TransactionReportItemSetters {
        @Test
        void allSetters() {
            TransactionReportItem r = new TransactionReportItem();
            r.setTranTypeCd("01");
            r.setTranCatCd(5001);
            r.setTranCatTypeDesc("Retail Purchase");
            assertThat(r.getTranTypeCd()).isEqualTo("01");
            assertThat(r.getTranCatCd()).isEqualTo(5001);
            assertThat(r.getTranCatTypeDesc()).isEqualTo("Retail Purchase");
        }
    }
}
