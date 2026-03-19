/*
 * StatementDataTest.java — Unit tests for StatementProcessor inner data classes
 * Covers StatementData and TransactionLine inner classes (0% coverage gap).
 */
package com.cardemo.batch.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for inner data classes within StatementProcessor — StatementData and
 * TransactionLine — which were at 0% coverage.
 */
class StatementDataTest {

    @Nested
    @DisplayName("StatementProcessor.StatementData")
    class StatementDataTests {

        @Test
        @DisplayName("Default constructor initializes zero amounts and empty list")
        void defaultConstructor() {
            StatementProcessor.StatementData data = new StatementProcessor.StatementData();
            assertThat(data.getCurrentBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(data.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(data.getTransactionLines()).isNotNull().isEmpty();
            assertThat(data.getCustomerName()).isNull();
            assertThat(data.getCustomerAddress()).isNull();
            assertThat(data.getAccountId()).isNull();
            assertThat(data.getCardNumber()).isNull();
            assertThat(data.getPlainTextStatement()).isNull();
            assertThat(data.getHtmlStatement()).isNull();
            assertThat(data.getFicoScore()).isZero();
        }

        @Test
        @DisplayName("All setters and getters work correctly")
        void settersAndGetters() {
            StatementProcessor.StatementData data = new StatementProcessor.StatementData();

            data.setCustomerName("JOHN DOE");
            assertThat(data.getCustomerName()).isEqualTo("JOHN DOE");

            data.setCustomerAddress("123 MAIN ST\nSPRINGFIELD IL 62701");
            assertThat(data.getCustomerAddress()).isEqualTo("123 MAIN ST\nSPRINGFIELD IL 62701");

            data.setAccountId("00000000001");
            assertThat(data.getAccountId()).isEqualTo("00000000001");

            data.setCardNumber("4111111111111111");
            assertThat(data.getCardNumber()).isEqualTo("4111111111111111");

            data.setCurrentBalance(new BigDecimal("1500.50"));
            assertThat(data.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("1500.50"));

            data.setFicoScore(750);
            assertThat(data.getFicoScore()).isEqualTo(750);

            data.setTotalAmount(new BigDecimal("250.99"));
            assertThat(data.getTotalAmount()).isEqualByComparingTo(new BigDecimal("250.99"));

            data.setPlainTextStatement("PLAIN TEXT STATEMENT CONTENT");
            assertThat(data.getPlainTextStatement()).isEqualTo("PLAIN TEXT STATEMENT CONTENT");

            data.setHtmlStatement("<html><body>HTML STATEMENT</body></html>");
            assertThat(data.getHtmlStatement()).isEqualTo("<html><body>HTML STATEMENT</body></html>");
        }

        @Test
        @DisplayName("TransactionLines list can be set and retrieved")
        void transactionLinesList() {
            StatementProcessor.StatementData data = new StatementProcessor.StatementData();

            StatementProcessor.TransactionLine line1 = new StatementProcessor.TransactionLine();
            line1.setTransactionId("TRAN0000001");
            line1.setDescription("Test Purchase");
            line1.setAmount(new BigDecimal("99.99"));

            StatementProcessor.TransactionLine line2 = new StatementProcessor.TransactionLine();
            line2.setTransactionId("TRAN0000002");
            line2.setDescription("Another Purchase");
            line2.setAmount(new BigDecimal("50.00"));

            List<StatementProcessor.TransactionLine> lines = new ArrayList<>();
            lines.add(line1);
            lines.add(line2);

            data.setTransactionLines(lines);
            assertThat(data.getTransactionLines()).hasSize(2);
            assertThat(data.getTransactionLines().get(0).getTransactionId()).isEqualTo("TRAN0000001");
            assertThat(data.getTransactionLines().get(1).getAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        }
    }

    @Nested
    @DisplayName("StatementProcessor.TransactionLine")
    class TransactionLineTests {

        @Test
        @DisplayName("Default constructor initializes amount to zero")
        void defaultConstructor() {
            StatementProcessor.TransactionLine line = new StatementProcessor.TransactionLine();
            assertThat(line.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(line.getTransactionId()).isNull();
            assertThat(line.getDescription()).isNull();
        }

        @Test
        @DisplayName("All setters and getters work correctly")
        void settersAndGetters() {
            StatementProcessor.TransactionLine line = new StatementProcessor.TransactionLine();

            line.setTransactionId("TRAN0000099");
            assertThat(line.getTransactionId()).isEqualTo("TRAN0000099");

            line.setDescription("PAYMENT TO UTILITY COMPANY");
            assertThat(line.getDescription()).isEqualTo("PAYMENT TO UTILITY COMPANY");

            line.setAmount(new BigDecimal("1234.56"));
            assertThat(line.getAmount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("Negative amounts are stored correctly")
        void negativeAmount() {
            StatementProcessor.TransactionLine line = new StatementProcessor.TransactionLine();
            line.setAmount(new BigDecimal("-50.00"));
            assertThat(line.getAmount()).isEqualByComparingTo(new BigDecimal("-50.00"));
        }
    }
}
