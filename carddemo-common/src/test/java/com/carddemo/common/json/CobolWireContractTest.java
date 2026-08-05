package com.carddemo.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.BillPaymentResponseDto;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionListItemDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * :purpose: Verify the JSON wire contract of the CardDemo numeric DTO fields: a ``PIC 9(n)``
 *     identifier is transmitted as a zero-padded string of its declared width and a
 *     ``PIC S9(p)V99`` amount as a plain string carrying its exact scale, while the
 *     corresponding request DTOs still accept both the string and the numeric form.
 * :output: JUnit 5 / AssertJ assertions only; no Spring context and no external resource.
 */
final class CobolWireContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Nested
    @DisplayName("Fixed-width identifier formatting")
    class FixedWidth {

        @Test
        @DisplayName("a short value is zero-padded to the declared PIC 9(n) width")
        void padsToDeclaredWidth() {
            assertThat(CobolWireFormat.fixedWidth(1L, CobolWireFormat.ACCOUNT_ID_WIDTH))
                    .isEqualTo("00000000001")
                    .hasSize(11);
            assertThat(CobolWireFormat.fixedWidth(7L, CobolWireFormat.CUSTOMER_ID_WIDTH))
                    .isEqualTo("000000007")
                    .hasSize(9);
            assertThat(CobolWireFormat.fixedWidth(5L, CobolWireFormat.TRAN_CATEGORY_WIDTH))
                    .isEqualTo("0005")
                    .hasSize(4);
        }

        @Test
        @DisplayName("a value already at the declared width is emitted unchanged")
        void leavesFullWidthValueUnchanged() {
            assertThat(CobolWireFormat.fixedWidth(12345678901L, CobolWireFormat.ACCOUNT_ID_WIDTH))
                    .isEqualTo("12345678901");
        }

        @Test
        @DisplayName("a value wider than the declared width keeps every digit rather than truncating")
        void neverTruncates() {
            assertThat(CobolWireFormat.fixedWidth(123456789012L, CobolWireFormat.ACCOUNT_ID_WIDTH))
                    .isEqualTo("123456789012");
        }
    }

    @Nested
    @DisplayName("Monetary scale formatting")
    class Money {

        @Test
        @DisplayName("a value below the declared scale is extended to scale 2")
        void extendsToScaleTwo() {
            assertThat(CobolWireFormat.scaled(new BigDecimal("194"))).isEqualTo("194.00");
            assertThat(CobolWireFormat.scaled(new BigDecimal("1234.5"))).isEqualTo("1234.50");
            assertThat(CobolWireFormat.scaled(new BigDecimal("-25"))).isEqualTo("-25.00");
            assertThat(CobolWireFormat.scaled(BigDecimal.ZERO)).isEqualTo("0.00");
        }

        @Test
        @DisplayName("a value at or above the declared scale keeps every stored digit")
        void keepsExistingPrecision() {
            assertThat(CobolWireFormat.scaled(new BigDecimal("250.75"))).isEqualTo("250.75");
            assertThat(CobolWireFormat.scaled(new BigDecimal("1.234"))).isEqualTo("1.234");
        }

        @Test
        @DisplayName("a large value renders in plain notation, never exponential")
        void rendersPlainNotation() {
            assertThat(CobolWireFormat.scaled(new BigDecimal("9999999999.99")))
                    .isEqualTo("9999999999.99")
                    .doesNotContain("E");
        }
    }

    @Nested
    @DisplayName("Outbound DTO serialization")
    class Outbound {

        @Test
        @DisplayName("the account view carries zero-padded ids and scale-2 money as strings")
        void accountViewUsesStrings() {
            AccountViewResponseDto dto = new AccountViewResponseDto();
            dto.setVersion(3L);
            dto.setAcctId(11L);
            dto.setCustId(7L);
            dto.setCustFicoCreditScore(700);
            dto.setAcctCurrBal(new BigDecimal("1234.5"));
            dto.setAcctCreditLimit(new BigDecimal("-25"));

            String json = mapper.writeValueAsString(dto);

            assertThat(json).contains("\"acctId\":\"00000000011\"");
            assertThat(json).contains("\"custId\":\"000000007\"");
            assertThat(json).contains("\"custFicoCreditScore\":\"700\"");
            assertThat(json).contains("\"acctCurrBal\":\"1234.50\"");
            assertThat(json).contains("\"acctCreditLimit\":\"-25.00\"");
            // The optimistic-lock counter has no COBOL field, so it stays a JSON number.
            assertThat(json).contains("\"version\":3");
        }

        @Test
        @DisplayName("the card list row and card detail carry an 11-digit account id string")
        void cardPayloadsUseStrings() {
            CardListItemDto row = new CardListItemDto();
            row.setCardAcctId(1L);
            assertThat(mapper.writeValueAsString(row)).contains("\"cardAcctId\":\"00000000001\"");

            CardDetailResponseDto detail = new CardDetailResponseDto();
            detail.setCardAcctId(1L);
            detail.setCustId(2L);
            String json = mapper.writeValueAsString(detail);
            assertThat(json).contains("\"cardAcctId\":\"00000000001\"");
            assertThat(json).contains("\"custId\":\"000000002\"");
        }

        @Test
        @DisplayName("the transaction payloads carry a 4-digit category, 9-digit merchant id and scale-2 amount")
        void transactionPayloadsUseStrings() {
            TransactionViewResponseDto view = new TransactionViewResponseDto();
            view.setTranCatCd(5001);
            view.setTranMerchantId(123456789L);
            view.setTranAmt(new BigDecimal("250.75"));
            String json = mapper.writeValueAsString(view);
            assertThat(json).contains("\"tranCatCd\":\"5001\"");
            assertThat(json).contains("\"tranMerchantId\":\"123456789\"");
            assertThat(json).contains("\"tranAmt\":\"250.75\"");

            TransactionListItemDto row = new TransactionListItemDto();
            row.setTranAmt(new BigDecimal("5"));
            assertThat(mapper.writeValueAsString(row)).contains("\"tranAmt\":\"5.00\"");
        }

        @Test
        @DisplayName("the bill-payment balance carries scale 2 as a string")
        void billPaymentBalanceUsesString() {
            BillPaymentResponseDto dto = new BillPaymentResponseDto();
            dto.setCurrentBalance(new BigDecimal("0.00"));
            assertThat(mapper.writeValueAsString(dto)).contains("\"currentBalance\":\"0.00\"");
        }
    }

    @Nested
    @DisplayName("Inbound request binding")
    class Inbound {

        @Test
        @DisplayName("an account update binds money, score and version from their string form")
        void accountUpdateAcceptsStrings() {
            AccountUpdateRequestDto dto = mapper.readValue(
                    "{\"version\":\"4\",\"acctCurrBal\":\"1234.56\",\"custFicoCreditScore\":\"700\"}",
                    AccountUpdateRequestDto.class);

            assertThat(dto.getVersion()).isEqualTo(4L);
            assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("1234.56");
            assertThat(dto.getAcctCurrBal().scale()).isEqualTo(2);
            assertThat(dto.getCustFicoCreditScore()).isEqualTo(700);
        }

        @Test
        @DisplayName("an account update still binds the numeric form")
        void accountUpdateAcceptsNumbers() {
            AccountUpdateRequestDto dto = mapper.readValue(
                    "{\"version\":4,\"acctCurrBal\":1234.56,\"custFicoCreditScore\":700}",
                    AccountUpdateRequestDto.class);

            assertThat(dto.getVersion()).isEqualTo(4L);
            assertThat(dto.getAcctCurrBal()).isEqualByComparingTo("1234.56");
            assertThat(dto.getCustFicoCreditScore()).isEqualTo(700);
        }

        @Test
        @DisplayName("a transaction add binds a zero-padded category and merchant id without losing digits")
        void transactionAddAcceptsPaddedStrings() {
            TransactionAddRequestDto dto = mapper.readValue(
                    "{\"tranCatCd\":\"0005\",\"tranAmt\":\"250.75\",\"tranMerchantId\":\"000000042\"}",
                    TransactionAddRequestDto.class);

            assertThat(dto.getTranCatCd()).isEqualTo(5);
            assertThat(dto.getTranAmt()).isEqualByComparingTo("250.75");
            assertThat(dto.getTranMerchantId()).isEqualTo(42L);
        }
    }
}
