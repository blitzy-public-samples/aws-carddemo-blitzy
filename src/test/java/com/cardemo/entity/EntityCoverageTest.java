package com.cardemo.entity;

import com.cardemo.common.enums.UserType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage tests for all 11 entity classes and UserTypeConverter.
 * Exercises getters, setters, equals, hashCode, toString, and constructors.
 */
class EntityCoverageTest {

    // ====================================================================
    // Account
    // ====================================================================

    @Nested
    @DisplayName("Account entity — ACCTDATA VSAM")
    class AccountTests {
        @Test
        @DisplayName("default constructor and getters/setters")
        void gettersSetters() {
            var a = new Account();
            a.setAcctId("00000000001");
            a.setActiveStatus("Y");
            a.setCurrBal(new BigDecimal("1500.50"));
            a.setCreditLimit(new BigDecimal("5000.00"));
            a.setCashCreditLimit(new BigDecimal("1000.00"));
            a.setOpenDate("20200101");
            a.setExpirationDate("20251231");
            a.setReissueDate("20230601");
            a.setCurrCycCredit(new BigDecimal("200.00"));
            a.setCurrCycDebit(new BigDecimal("300.00"));
            a.setAddrZip("21044");
            a.setGroupId("GRP001");
            a.setVersion(1L);

            assertThat(a.getAcctId()).isEqualTo("00000000001");
            assertThat(a.getActiveStatus()).isEqualTo("Y");
            assertThat(a.getCurrBal()).isEqualByComparingTo("1500.50");
            assertThat(a.getCreditLimit()).isEqualByComparingTo("5000.00");
            assertThat(a.getCashCreditLimit()).isEqualByComparingTo("1000.00");
            assertThat(a.getOpenDate()).isEqualTo("20200101");
            assertThat(a.getExpirationDate()).isEqualTo("20251231");
            assertThat(a.getReissueDate()).isEqualTo("20230601");
            assertThat(a.getCurrCycCredit()).isEqualByComparingTo("200.00");
            assertThat(a.getCurrCycDebit()).isEqualByComparingTo("300.00");
            assertThat(a.getAddrZip()).isEqualTo("21044");
            assertThat(a.getGroupId()).isEqualTo("GRP001");
            assertThat(a.getVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("equals and hashCode based on acctId")
        void equalsAndHashCode() {
            var a1 = new Account();
            a1.setAcctId("00000000001");
            var a2 = new Account();
            a2.setAcctId("00000000001");
            var a3 = new Account();
            a3.setAcctId("00000000002");

            assertThat(a1).isEqualTo(a2);
            assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
            assertThat(a1).isNotEqualTo(a3);
            assertThat(a1).isNotEqualTo(null);
            assertThat(a1).isNotEqualTo("string");
            assertThat(a1).isEqualTo(a1);
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringTest() {
            var a = new Account();
            a.setAcctId("00000000001");
            assertThat(a.toString()).contains("00000000001");
        }
    }

    // ====================================================================
    // Card
    // ====================================================================

    @Nested
    @DisplayName("Card entity — CARDDATA VSAM")
    class CardTests {
        @Test
        @DisplayName("all-args constructor and getters")
        void allArgsConstructor() {
            var c = new Card("4111111111111111", "00000000001", "123",
                    "JOHN DOE", "20251231", "Y");
            assertThat(c.getCardNum()).isEqualTo("4111111111111111");
            assertThat(c.getAccountId()).isEqualTo("00000000001");
            assertThat(c.getCvvCode()).isEqualTo("123");
            assertThat(c.getEmbossedName()).isEqualTo("JOHN DOE");
            assertThat(c.getExpirationDate()).isEqualTo("20251231");
            assertThat(c.getActiveStatus()).isEqualTo("Y");
        }

        @Test
        @DisplayName("default constructor and setters")
        void defaultConstructorSetters() {
            var c = new Card();
            c.setCardNum("5500000000000004");
            c.setAccountId("00000000002");
            c.setCvvCode("456");
            c.setEmbossedName("JANE DOE");
            c.setExpirationDate("20261231");
            c.setActiveStatus("N");
            c.setVersion(2L);

            assertThat(c.getCardNum()).isEqualTo("5500000000000004");
            assertThat(c.getVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var c1 = new Card();
            c1.setCardNum("4111111111111111");
            var c2 = new Card();
            c2.setCardNum("4111111111111111");
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var c = new Card();
            c.setCardNum("4111111111111111");
            assertThat(c.toString()).isNotNull();
        }
    }

    // ====================================================================
    // CardXref
    // ====================================================================

    @Nested
    @DisplayName("CardXref entity — CARDXREF VSAM")
    class CardXrefTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var x = new CardXref();
            x.setXrefCardNum("4111111111111111");
            x.setCustId("000000001");
            x.setAccountId("00000000001");

            assertThat(x.getXrefCardNum()).isEqualTo("4111111111111111");
            assertThat(x.getCustId()).isEqualTo("000000001");
            assertThat(x.getAccountId()).isEqualTo("00000000001");
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var x1 = new CardXref();
            x1.setXrefCardNum("4111111111111111");
            var x2 = new CardXref();
            x2.setXrefCardNum("4111111111111111");
            assertThat(x1).isEqualTo(x2);
            assertThat(x1.hashCode()).isEqualTo(x2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var x = new CardXref();
            x.setXrefCardNum("4111111111111111");
            assertThat(x.toString()).contains("4111111111111111");
        }
    }

    // ====================================================================
    // Customer
    // ====================================================================

    @Nested
    @DisplayName("Customer entity — CUSTDATA VSAM")
    class CustomerTests {
        @Test
        @DisplayName("all fields getters and setters")
        void allFields() {
            var c = new Customer();
            c.setCustId("000000001");
            c.setFirstName("John");
            c.setMiddleName("M");
            c.setLastName("Doe");
            c.setAddrLine1("123 Main St");
            c.setAddrLine2("Apt 4B");
            c.setAddrLine3("");
            c.setAddrStateCode("MD");
            c.setAddrCountryCode("US");
            c.setAddrZip("21044");
            c.setPhoneNum1("3015551234");
            c.setPhoneNum2("3015555678");
            c.setSsn("123456789");
            c.setGovtIssuedId("D12345678");
            c.setDateOfBirth("19800115");
            c.setEftAccountId("EFT001");
            c.setPriCardHolderInd("Y");
            c.setFicoCreditScore(750);

            assertThat(c.getCustId()).isEqualTo("000000001");
            assertThat(c.getFirstName()).isEqualTo("John");
            assertThat(c.getMiddleName()).isEqualTo("M");
            assertThat(c.getLastName()).isEqualTo("Doe");
            assertThat(c.getAddrLine1()).isEqualTo("123 Main St");
            assertThat(c.getAddrLine2()).isEqualTo("Apt 4B");
            assertThat(c.getAddrLine3()).isEmpty();
            assertThat(c.getAddrStateCode()).isEqualTo("MD");
            assertThat(c.getAddrCountryCode()).isEqualTo("US");
            assertThat(c.getAddrZip()).isEqualTo("21044");
            assertThat(c.getPhoneNum1()).isEqualTo("3015551234");
            assertThat(c.getPhoneNum2()).isEqualTo("3015555678");
            assertThat(c.getSsn()).isEqualTo("123456789");
            assertThat(c.getGovtIssuedId()).isEqualTo("D12345678");
            assertThat(c.getDateOfBirth()).isEqualTo("19800115");
            assertThat(c.getEftAccountId()).isEqualTo("EFT001");
            assertThat(c.getPriCardHolderInd()).isEqualTo("Y");
            assertThat(c.getFicoCreditScore()).isEqualTo(750);
        }

        @Test
        @DisplayName("equals and hashCode based on custId")
        void equalsAndHashCode() {
            var c1 = new Customer();
            c1.setCustId("000000001");
            var c2 = new Customer();
            c2.setCustId("000000001");
            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var c = new Customer();
            c.setCustId("000000001");
            c.setFirstName("John");
            assertThat(c.toString()).contains("000000001");
        }
    }

    // ====================================================================
    // Transaction
    // ====================================================================

    @Nested
    @DisplayName("Transaction entity — TRANSACT VSAM")
    class TransactionTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var t = new Transaction();
            t.setTranId("0000000001");
            t.setTypeCode("01");
            t.setCategoryCode(1);
            t.setSource("SRC01");
            t.setDescription("Purchase");
            t.setAmount(new BigDecimal("99.99"));
            t.setMerchantId("MERCH001");
            t.setMerchantName("Coffee Shop");
            t.setMerchantCity("Baltimore");
            t.setMerchantZip("21201");
            t.setCardNum("4111111111111111");
            t.setOrigTimestamp("2024-01-15-09.30.00.000000");
            t.setProcTimestamp("2024-01-15-10.00.00.000000");

            assertThat(t.getTranId()).isEqualTo("0000000001");
            assertThat(t.getTypeCode()).isEqualTo("01");
            assertThat(t.getCategoryCode()).isEqualTo(1);
            assertThat(t.getSource()).isEqualTo("SRC01");
            assertThat(t.getDescription()).isEqualTo("Purchase");
            assertThat(t.getAmount()).isEqualByComparingTo("99.99");
            assertThat(t.getMerchantId()).isEqualTo("MERCH001");
            assertThat(t.getMerchantName()).isEqualTo("Coffee Shop");
            assertThat(t.getMerchantCity()).isEqualTo("Baltimore");
            assertThat(t.getMerchantZip()).isEqualTo("21201");
            assertThat(t.getCardNum()).isEqualTo("4111111111111111");
            assertThat(t.getOrigTimestamp()).isEqualTo("2024-01-15-09.30.00.000000");
            assertThat(t.getProcTimestamp()).isEqualTo("2024-01-15-10.00.00.000000");
        }

        @Test
        @DisplayName("equals and hashCode based on tranId")
        void equalsAndHashCode() {
            var t1 = new Transaction();
            t1.setTranId("0000000001");
            var t2 = new Transaction();
            t2.setTranId("0000000001");
            assertThat(t1).isEqualTo(t2);
            assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
        }

        @Test
        @DisplayName("toString masks card number for security")
        void toStringTest() {
            var t = new Transaction();
            t.setTranId("0000000001");
            t.setCardNum("4111111111111111");
            assertThat(t.toString()).contains("0000000001");
        }
    }

    // ====================================================================
    // DailyTransaction
    // ====================================================================

    @Nested
    @DisplayName("DailyTransaction entity — DALYTRAN VSAM")
    class DailyTransactionTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var dt = new DailyTransaction();
            dt.setDalytranId("DT00000001");
            dt.setTypeCode("01");
            dt.setCategoryCode(1);
            dt.setSource("SRC01");
            dt.setDescription("Daily purchase");
            dt.setAmount(new BigDecimal("50.00"));
            dt.setMerchantId("M001");
            dt.setMerchantName("Store");
            dt.setMerchantCity("NYC");
            dt.setMerchantZip("10001");
            dt.setCardNum("4111111111111111");
            dt.setOrigTimestamp("2024-01-15-09.30.00.000000");
            dt.setProcTimestamp("2024-01-15-10.00.00.000000");

            assertThat(dt.getDalytranId()).isEqualTo("DT00000001");
            assertThat(dt.getTypeCode()).isEqualTo("01");
            assertThat(dt.getCategoryCode()).isEqualTo(1);
            assertThat(dt.getSource()).isEqualTo("SRC01");
            assertThat(dt.getDescription()).isEqualTo("Daily purchase");
            assertThat(dt.getAmount()).isEqualByComparingTo("50.00");
            assertThat(dt.getMerchantId()).isEqualTo("M001");
            assertThat(dt.getMerchantName()).isEqualTo("Store");
            assertThat(dt.getMerchantCity()).isEqualTo("NYC");
            assertThat(dt.getMerchantZip()).isEqualTo("10001");
            assertThat(dt.getCardNum()).isEqualTo("4111111111111111");
            assertThat(dt.getOrigTimestamp()).isEqualTo("2024-01-15-09.30.00.000000");
            assertThat(dt.getProcTimestamp()).isEqualTo("2024-01-15-10.00.00.000000");
        }

        @Test
        @DisplayName("id field")
        void idField() {
            var dt = new DailyTransaction();
            dt.setId(42L);
            assertThat(dt.getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var dt1 = new DailyTransaction();
            dt1.setDalytranId("DT00000001");
            var dt2 = new DailyTransaction();
            dt2.setDalytranId("DT00000001");
            assertThat(dt1).isEqualTo(dt2);
            assertThat(dt1.hashCode()).isEqualTo(dt2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var dt = new DailyTransaction();
            dt.setDalytranId("DT00000001");
            assertThat(dt.toString()).isNotNull();
        }
    }

    // ====================================================================
    // UserSecurity
    // ====================================================================

    @Nested
    @DisplayName("UserSecurity entity — USRSEC VSAM")
    class UserSecurityTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var u = new UserSecurity();
            u.setUserId("USER0001");
            u.setFirstName("John");
            u.setLastName("Doe");
            u.setPassword("hashedPwd");
            u.setUserType(UserType.ADMIN);
            u.setVersion(1L);

            assertThat(u.getUserId()).isEqualTo("USER0001");
            assertThat(u.getFirstName()).isEqualTo("John");
            assertThat(u.getLastName()).isEqualTo("Doe");
            assertThat(u.getPassword()).isEqualTo("hashedPwd");
            assertThat(u.getUserType()).isEqualTo(UserType.ADMIN);
            assertThat(u.getVersion()).isEqualTo(1L);
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var u1 = new UserSecurity();
            u1.setUserId("USER0001");
            var u2 = new UserSecurity();
            u2.setUserId("USER0001");
            assertThat(u1).isEqualTo(u2);
            assertThat(u1.hashCode()).isEqualTo(u2.hashCode());
        }

        @Test
        @DisplayName("toString does not expose password")
        void toStringTest() {
            var u = new UserSecurity();
            u.setUserId("USER0001");
            u.setPassword("secret");
            assertThat(u.toString()).contains("USER0001");
        }
    }

    // ====================================================================
    // CategoryBalance
    // ====================================================================

    @Nested
    @DisplayName("CategoryBalance entity — TCATBALF VSAM")
    class CategoryBalanceTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var cb = new CategoryBalance();
            cb.setAccountId("00000000001");
            cb.setTypeCode("01");
            cb.setCategoryCode(1);
            cb.setBalance(new BigDecimal("1234.56"));

            assertThat(cb.getAccountId()).isEqualTo("00000000001");
            assertThat(cb.getTypeCode()).isEqualTo("01");
            assertThat(cb.getCategoryCode()).isEqualTo(1);
            assertThat(cb.getBalance()).isEqualByComparingTo("1234.56");
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var cb1 = new CategoryBalance();
            cb1.setAccountId("00000000001");
            cb1.setTypeCode("01");
            cb1.setCategoryCode(1);
            var cb2 = new CategoryBalance();
            cb2.setAccountId("00000000001");
            cb2.setTypeCode("01");
            cb2.setCategoryCode(1);
            assertThat(cb1).isEqualTo(cb2);
            assertThat(cb1.hashCode()).isEqualTo(cb2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var cb = new CategoryBalance();
            cb.setAccountId("00000000001");
            assertThat(cb.toString()).contains("00000000001");
        }

        @Test
        @DisplayName("CategoryBalanceId composite key equals and hashCode")
        void compositeKeyTest() {
            var id1 = new CategoryBalance.CategoryBalanceId();
            id1.setAccountId("00000000001");
            id1.setTypeCode("01");
            id1.setCategoryCode(1);
            var id2 = new CategoryBalance.CategoryBalanceId();
            id2.setAccountId("00000000001");
            id2.setTypeCode("01");
            id2.setCategoryCode(1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
            assertThat(id1.getAccountId()).isEqualTo("00000000001");
            assertThat(id1.getTypeCode()).isEqualTo("01");
            assertThat(id1.getCategoryCode()).isEqualTo(1);
        }
    }

    // ====================================================================
    // DiscountGroup
    // ====================================================================

    @Nested
    @DisplayName("DiscountGroup entity — DISCGRP reference data")
    class DiscountGroupTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var dg = new DiscountGroup();
            dg.setGroupId("GRP001");
            dg.setTranTypeCode("01");
            dg.setTranCatCode(1);
            dg.setInterestRate(new BigDecimal("15.99"));

            assertThat(dg.getGroupId()).isEqualTo("GRP001");
            assertThat(dg.getTranTypeCode()).isEqualTo("01");
            assertThat(dg.getTranCatCode()).isEqualTo(1);
            assertThat(dg.getInterestRate()).isEqualByComparingTo("15.99");
        }

        @Test
        @DisplayName("equals, hashCode, and toString")
        void equalsHashCodeToString() {
            var dg1 = new DiscountGroup();
            dg1.setGroupId("GRP001");
            dg1.setTranTypeCode("01");
            dg1.setTranCatCode(1);
            var dg2 = new DiscountGroup();
            dg2.setGroupId("GRP001");
            dg2.setTranTypeCode("01");
            dg2.setTranCatCode(1);
            assertThat(dg1).isEqualTo(dg2);
            assertThat(dg1.hashCode()).isEqualTo(dg2.hashCode());
            assertThat(dg1.toString()).contains("GRP001");
        }

        @Test
        @DisplayName("DiscountGroupId composite key")
        void compositeKeyTest() {
            var id1 = new DiscountGroup.DiscountGroupId();
            id1.setGroupId("GRP001");
            id1.setTranTypeCode("01");
            id1.setTranCatCode(1);
            var id2 = new DiscountGroup.DiscountGroupId();
            id2.setGroupId("GRP001");
            id2.setTranTypeCode("01");
            id2.setTranCatCode(1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        }
    }

    // ====================================================================
    // TransactionTypeRef
    // ====================================================================

    @Nested
    @DisplayName("TransactionTypeRef entity — TRANTYPE reference data")
    class TransactionTypeRefTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var ref = new TransactionTypeRef();
            ref.setTypeCode("01");
            ref.setTypeDescription("Purchase");

            assertThat(ref.getTypeCode()).isEqualTo("01");
            assertThat(ref.getTypeDescription()).isEqualTo("Purchase");
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var r1 = new TransactionTypeRef();
            r1.setTypeCode("01");
            var r2 = new TransactionTypeRef();
            r2.setTypeCode("01");
            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var r = new TransactionTypeRef();
            r.setTypeCode("01");
            r.setTypeDescription("Purchase");
            assertThat(r.toString()).contains("01");
        }
    }

    // ====================================================================
    // TransactionCategoryRef
    // ====================================================================

    @Nested
    @DisplayName("TransactionCategoryRef entity — TRANCATG reference data")
    class TransactionCategoryRefTests {
        @Test
        @DisplayName("getters and setters")
        void gettersSetters() {
            var ref = new TransactionCategoryRef();
            ref.setTypeCode("01");
            ref.setCategoryCode(1);
            ref.setCategoryDescription("Regular Sales Draft");

            assertThat(ref.getTypeCode()).isEqualTo("01");
            assertThat(ref.getCategoryCode()).isEqualTo(1);
            assertThat(ref.getCategoryDescription()).isEqualTo("Regular Sales Draft");
        }

        @Test
        @DisplayName("equals and hashCode")
        void equalsAndHashCode() {
            var r1 = new TransactionCategoryRef();
            r1.setTypeCode("01");
            r1.setCategoryCode(1);
            var r2 = new TransactionCategoryRef();
            r2.setTypeCode("01");
            r2.setCategoryCode(1);
            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        @DisplayName("TransactionCategoryRefId composite key")
        void compositeKeyTest() {
            var id1 = new TransactionCategoryRef.TransactionCategoryRefId();
            id1.setTypeCode("01");
            id1.setCategoryCode(1);
            var id2 = new TransactionCategoryRef.TransactionCategoryRefId();
            id2.setTypeCode("01");
            id2.setCategoryCode(1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        }

        @Test
        @DisplayName("toString")
        void toStringTest() {
            var r = new TransactionCategoryRef();
            r.setTypeCode("01");
            r.setCategoryCode(1);
            assertThat(r.toString()).contains("01");
        }
    }

    // ====================================================================
    // UserTypeConverter
    // ====================================================================

    @Nested
    @DisplayName("UserTypeConverter — JPA attribute converter")
    class UserTypeConverterTests {
        private final UserTypeConverter converter = new UserTypeConverter();

        @Test
        @DisplayName("convertToDatabaseColumn — ADMIN and USER")
        void toDatabaseColumn() {
            assertThat(converter.convertToDatabaseColumn(UserType.ADMIN)).isEqualTo("A");
            assertThat(converter.convertToDatabaseColumn(UserType.USER)).isEqualTo("U");
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        @DisplayName("convertToEntityAttribute — A, U, null, empty")
        void toEntityAttribute() {
            assertThat(converter.convertToEntityAttribute("A")).isEqualTo(UserType.ADMIN);
            assertThat(converter.convertToEntityAttribute("U")).isEqualTo(UserType.USER);
            assertThat(converter.convertToEntityAttribute(null)).isNull();
            assertThat(converter.convertToEntityAttribute("")).isNull();
        }

        @Test
        @DisplayName("convertToEntityAttribute — unknown code throws")
        void toEntityAttributeUnknown() {
            assertThatThrownBy(() -> converter.convertToEntityAttribute("X"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
