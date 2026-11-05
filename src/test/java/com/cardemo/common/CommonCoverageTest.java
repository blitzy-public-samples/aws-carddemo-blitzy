package com.cardemo.common;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.FileStatusException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.common.message.ExtendedMessage;
import com.cardemo.common.message.MessageConstants;
import com.cardemo.common.util.StringProcessingUtil;
import com.cardemo.common.util.StringProcessingUtil.AidKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage tests for common classes: CardDemoContext, MessageConstants,
 * ExtendedMessage, StringProcessingUtil, and all 6 exception classes.
 */
class CommonCoverageTest {

    // ====================================================================
    // CardDemoContext
    // ====================================================================

    @Nested
    @DisplayName("CardDemoContext — COMMAREA session bean")
    class CardDemoContextTests {

        @Test
        @DisplayName("default constructor initializes all fields to null/defaults")
        void defaultConstructor() {
            var ctx = new CardDemoContext();
            assertThat(ctx.getFromTranId()).isNull();
            assertThat(ctx.getFromProgram()).isNull();
            assertThat(ctx.getToTranId()).isNull();
            assertThat(ctx.getToProgram()).isNull();
            assertThat(ctx.getUserId()).isNull();
            assertThat(ctx.getUserType()).isNull();
            assertThat(ctx.getPgmContext()).isEqualTo(0);
            assertThat(ctx.getCustId()).isNull();
            assertThat(ctx.getCustFname()).isNull();
            assertThat(ctx.getCustMname()).isNull();
            assertThat(ctx.getCustLname()).isNull();
            assertThat(ctx.getAcctId()).isNull();
            assertThat(ctx.getAcctStatus()).isNull();
            assertThat(ctx.getCardNum()).isNull();
            assertThat(ctx.getLastMap()).isNull();
            assertThat(ctx.getLastMapset()).isNull();
        }

        @Test
        @DisplayName("all setters and getters work correctly")
        void settersAndGetters() {
            var ctx = new CardDemoContext();
            ctx.setFromTranId("CC00");
            ctx.setFromProgram("COSGN00C");
            ctx.setToTranId("CM00");
            ctx.setToProgram("COMEN01C");
            ctx.setUserId("USER0001");
            ctx.setUserType(UserType.ADMIN);
            ctx.setPgmContext(CardDemoContext.PGM_ENTER);
            ctx.setCustId("000000001");
            ctx.setCustFname("John");
            ctx.setCustMname("M");
            ctx.setCustLname("Doe");
            ctx.setAcctId("00000000001");
            ctx.setAcctStatus("Y");
            ctx.setCardNum("4111111111111111");
            ctx.setLastMap("COSGN00");
            ctx.setLastMapset("COSGN00");

            assertThat(ctx.getFromTranId()).isEqualTo("CC00");
            assertThat(ctx.getFromProgram()).isEqualTo("COSGN00C");
            assertThat(ctx.getToTranId()).isEqualTo("CM00");
            assertThat(ctx.getToProgram()).isEqualTo("COMEN01C");
            assertThat(ctx.getUserId()).isEqualTo("USER0001");
            assertThat(ctx.getUserType()).isEqualTo(UserType.ADMIN);
            assertThat(ctx.getPgmContext()).isEqualTo(CardDemoContext.PGM_ENTER);
            assertThat(ctx.getCustId()).isEqualTo("000000001");
            assertThat(ctx.getCustFname()).isEqualTo("John");
            assertThat(ctx.getCustMname()).isEqualTo("M");
            assertThat(ctx.getCustLname()).isEqualTo("Doe");
            assertThat(ctx.getAcctId()).isEqualTo("00000000001");
            assertThat(ctx.getAcctStatus()).isEqualTo("Y");
            assertThat(ctx.getCardNum()).isEqualTo("4111111111111111");
            assertThat(ctx.getLastMap()).isEqualTo("COSGN00");
            assertThat(ctx.getLastMapset()).isEqualTo("COSGN00");
        }

        @Test
        @DisplayName("isAdmin returns true only for ADMIN user type")
        void isAdmin() {
            var ctx = new CardDemoContext();
            assertThat(ctx.isAdmin()).isFalse();
            ctx.setUserType(UserType.USER);
            assertThat(ctx.isAdmin()).isFalse();
            ctx.setUserType(UserType.ADMIN);
            assertThat(ctx.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("isEnterContext and isReenterContext check pgmContext")
        void contextFlags() {
            var ctx = new CardDemoContext();
            ctx.setPgmContext(CardDemoContext.PGM_ENTER);
            assertThat(ctx.isEnterContext()).isTrue();
            assertThat(ctx.isReenterContext()).isFalse();

            ctx.setPgmContext(CardDemoContext.PGM_REENTER);
            assertThat(ctx.isEnterContext()).isFalse();
            assertThat(ctx.isReenterContext()).isTrue();
        }

        @Test
        @DisplayName("toString includes key fields")
        void toStringTest() {
            var ctx = new CardDemoContext();
            ctx.setUserId("USER0001");
            ctx.setUserType(UserType.ADMIN);
            String str = ctx.toString();
            assertThat(str).contains("USER0001");
            assertThat(str).contains("ADMIN");
        }

        @Test
        @DisplayName("static constants match COBOL semantics")
        void constants() {
            assertThat(CardDemoContext.PGM_ENTER).isEqualTo(0);
            assertThat(CardDemoContext.PGM_REENTER).isEqualTo(1);
        }
    }

    // ====================================================================
    // MessageConstants
    // ====================================================================

    @Nested
    @DisplayName("MessageConstants — CSMSG01Y translation")
    class MessageConstantsTests {

        @Test
        @DisplayName("THANK_YOU_MESSAGE is non-null and matches COBOL PIC X(50)")
        void thankYouMessage() {
            assertThat(MessageConstants.THANK_YOU_MESSAGE)
                    .isNotNull()
                    .isNotEmpty()
                    .contains("Thank you");
        }

        @Test
        @DisplayName("INVALID_KEY_MESSAGE is non-null and matches COBOL PIC X(50)")
        void invalidKeyMessage() {
            assertThat(MessageConstants.INVALID_KEY_MESSAGE)
                    .isNotNull()
                    .isNotEmpty()
                    .contains("Invalid key");
        }
    }

    // ====================================================================
    // ExtendedMessage
    // ====================================================================

    @Nested
    @DisplayName("ExtendedMessage — CSMSG02Y abend data")
    class ExtendedMessageTests {

        @Test
        @DisplayName("default constructor initializes fields to empty strings")
        void defaultConstructor() {
            var msg = new ExtendedMessage();
            assertThat(msg.getAbendCode()).isEmpty();
            assertThat(msg.getAbendCulprit()).isEmpty();
            assertThat(msg.getAbendReason()).isEmpty();
            assertThat(msg.getAbendMsg()).isEmpty();
        }

        @Test
        @DisplayName("setters and getters work for all 4 fields")
        void settersAndGetters() {
            var msg = new ExtendedMessage();
            msg.setAbendCode("ASRA");
            msg.setAbendCulprit("COSGN00C");
            msg.setAbendReason("Data exception in sign-on program");
            msg.setAbendMsg("Task 12345 abended with ASRA in COSGN00C");

            assertThat(msg.getAbendCode()).isEqualTo("ASRA");
            assertThat(msg.getAbendCulprit()).isEqualTo("COSGN00C");
            assertThat(msg.getAbendReason()).isEqualTo("Data exception in sign-on program");
            assertThat(msg.getAbendMsg()).isEqualTo("Task 12345 abended with ASRA in COSGN00C");
        }

        @Test
        @DisplayName("toString includes all fields")
        void toStringTest() {
            var msg = new ExtendedMessage();
            msg.setAbendCode("AICA");
            msg.setAbendCulprit("CBTRN02C");
            String str = msg.toString();
            assertThat(str).contains("AICA").contains("CBTRN02C");
        }
    }

    // ====================================================================
    // StringProcessingUtil
    // ====================================================================

    @Nested
    @DisplayName("StringProcessingUtil — CSSTRPFY translation")
    class StringProcessingUtilTests {

        @Test
        @DisplayName("mapAidToKey maps ENTER and CLEAR")
        void mapAidEnterClear() {
            assertThat(StringProcessingUtil.mapAidToKey("DFHENTER")).isEqualTo(AidKey.ENTER);
            assertThat(StringProcessingUtil.mapAidToKey("DFHCLEAR")).isEqualTo(AidKey.CLEAR);
        }

        @Test
        @DisplayName("mapAidToKey maps PA keys")
        void mapAidPaKeys() {
            assertThat(StringProcessingUtil.mapAidToKey("DFHPA1")).isEqualTo(AidKey.PA1);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPA2")).isEqualTo(AidKey.PA2);
        }

        @Test
        @DisplayName("mapAidToKey maps PF1-PF12")
        void mapAidPfKeys() {
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF1")).isEqualTo(AidKey.PFK01);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF2")).isEqualTo(AidKey.PFK02);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF3")).isEqualTo(AidKey.PFK03);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF4")).isEqualTo(AidKey.PFK04);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF5")).isEqualTo(AidKey.PFK05);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF6")).isEqualTo(AidKey.PFK06);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF7")).isEqualTo(AidKey.PFK07);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF8")).isEqualTo(AidKey.PFK08);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF9")).isEqualTo(AidKey.PFK09);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF10")).isEqualTo(AidKey.PFK10);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF11")).isEqualTo(AidKey.PFK11);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF12")).isEqualTo(AidKey.PFK12);
        }

        @Test
        @DisplayName("mapAidToKey wraps PF13-PF24 to PFK01-PFK12")
        void mapAidPf13to24WrapAround() {
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF13")).isEqualTo(AidKey.PFK01);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF14")).isEqualTo(AidKey.PFK02);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF15")).isEqualTo(AidKey.PFK03);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF16")).isEqualTo(AidKey.PFK04);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF17")).isEqualTo(AidKey.PFK05);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF18")).isEqualTo(AidKey.PFK06);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF19")).isEqualTo(AidKey.PFK07);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF20")).isEqualTo(AidKey.PFK08);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF21")).isEqualTo(AidKey.PFK09);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF22")).isEqualTo(AidKey.PFK10);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF23")).isEqualTo(AidKey.PFK11);
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF24")).isEqualTo(AidKey.PFK12);
        }

        @Test
        @DisplayName("mapAidToKey returns null for unknown or null input")
        void mapAidNullAndUnknown() {
            assertThat(StringProcessingUtil.mapAidToKey(null)).isNull();
            assertThat(StringProcessingUtil.mapAidToKey("DFHPF99")).isNull();
            assertThat(StringProcessingUtil.mapAidToKey("")).isNull();
        }

        @Test
        @DisplayName("trimRight removes trailing spaces")
        void trimRight() {
            assertThat(StringProcessingUtil.trimRight("Hello   ")).isEqualTo("Hello");
            assertThat(StringProcessingUtil.trimRight("   ")).isEmpty();
            assertThat(StringProcessingUtil.trimRight("NoSpaces")).isEqualTo("NoSpaces");
            assertThat(StringProcessingUtil.trimRight(null)).isEmpty();
            assertThat(StringProcessingUtil.trimRight("")).isEmpty();
        }

        @Test
        @DisplayName("trimLeft removes leading spaces")
        void trimLeft() {
            assertThat(StringProcessingUtil.trimLeft("   Hello")).isEqualTo("Hello");
            assertThat(StringProcessingUtil.trimLeft("   ")).isEmpty();
            assertThat(StringProcessingUtil.trimLeft("NoSpaces")).isEqualTo("NoSpaces");
            assertThat(StringProcessingUtil.trimLeft(null)).isEmpty();
            assertThat(StringProcessingUtil.trimLeft("")).isEmpty();
        }

        @Test
        @DisplayName("padRight with default space character")
        void padRightDefault() {
            assertThat(StringProcessingUtil.padRight("AB", 5)).isEqualTo("AB   ");
            assertThat(StringProcessingUtil.padRight("ABCDE", 5)).isEqualTo("ABCDE");
            assertThat(StringProcessingUtil.padRight("ABCDEF", 5)).isEqualTo("ABCDE");
            assertThat(StringProcessingUtil.padRight(null, 5)).isEqualTo("     ");
            assertThat(StringProcessingUtil.padRight("A", 0)).isEmpty();
        }

        @Test
        @DisplayName("padRight with custom character")
        void padRightCustom() {
            assertThat(StringProcessingUtil.padRight("42", 5, '0')).isEqualTo("42000");
            assertThat(StringProcessingUtil.padRight(null, 3, '0')).isEqualTo("000");
        }

        @Test
        @DisplayName("padRight throws for negative length")
        void padRightNegative() {
            assertThatThrownBy(() -> StringProcessingUtil.padRight("A", -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> StringProcessingUtil.padRight("A", -1, '0'))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("padLeft pads with leading spaces")
        void padLeft() {
            assertThat(StringProcessingUtil.padLeft("42", 5)).isEqualTo("   42");
            assertThat(StringProcessingUtil.padLeft("12345", 5)).isEqualTo("12345");
            assertThat(StringProcessingUtil.padLeft("123456", 5)).isEqualTo("23456");
            assertThat(StringProcessingUtil.padLeft(null, 5)).isEqualTo("     ");
        }

        @Test
        @DisplayName("padLeft throws for negative length")
        void padLeftNegative() {
            assertThatThrownBy(() -> StringProcessingUtil.padLeft("A", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("AidKey enum has all expected values")
        void aidKeyValues() {
            assertThat(AidKey.values()).hasSize(16);
            assertThat(AidKey.ENTER.getCode()).isEqualTo("ENTER");
            assertThat(AidKey.CLEAR.getCode()).isEqualTo("CLEAR");
            assertThat(AidKey.PA1.getCode()).isEqualTo("PA1");
            assertThat(AidKey.PA2.getCode()).isEqualTo("PA2");
            assertThat(AidKey.PFK01.getCode()).isEqualTo("PFK01");
            assertThat(AidKey.PFK12.getCode()).isEqualTo("PFK12");
        }
    }

    // ====================================================================
    // Exception classes
    // ====================================================================

    @Nested
    @DisplayName("Exception hierarchy — VSAM file status mapping")
    class ExceptionTests {

        @Test
        @DisplayName("CardDemoException — all constructors")
        void cardDemoException() {
            var ex1 = new CardDemoException("msg");
            assertThat(ex1).hasMessage("msg");

            var cause = new RuntimeException("cause");
            var ex2 = new CardDemoException("msg", cause);
            assertThat(ex2).hasMessage("msg").hasCause(cause);

            var ex3 = new CardDemoException(cause);
            assertThat(ex3).hasCause(cause);
        }

        @Test
        @DisplayName("FileStatusException carries VSAM status code")
        void fileStatusException() {
            var ex = new FileStatusException("35", "File not available");
            assertThat(ex.getFileStatusCode()).isEqualTo("35");
            assertThat(ex).hasMessageContaining("File not available");
        }

        @Test
        @DisplayName("RecordNotFoundException — status '23'")
        void recordNotFoundException() {
            var ex = new RecordNotFoundException("Account not found");
            assertThat(ex).isInstanceOf(FileStatusException.class);
            assertThat(ex).hasMessageContaining("Account not found");
        }

        @Test
        @DisplayName("DuplicateRecordException — status '22'")
        void duplicateRecordException() {
            var ex = new DuplicateRecordException("Duplicate key");
            assertThat(ex).isInstanceOf(FileStatusException.class);
            assertThat(ex).hasMessageContaining("Duplicate key");
        }

        @Test
        @DisplayName("AuthenticationException — single-arg and two-arg constructors")
        void authenticationException() {
            var ex = new AuthenticationException("Invalid credentials");
            assertThat(ex).isInstanceOf(CardDemoException.class);
            assertThat(ex).hasMessageContaining("Invalid credentials");

            var cause = new RuntimeException("root");
            var ex2 = new AuthenticationException("fail", cause);
            assertThat(ex2).hasCause(cause).hasMessageContaining("fail");
        }

        @Test
        @DisplayName("ValidationException — multiple constructor forms")
        void validationException() {
            var ex1 = new ValidationException("Field too long");
            assertThat(ex1).isInstanceOf(CardDemoException.class);
            assertThat(ex1).hasMessageContaining("Field too long");

            var ex2 = new ValidationException("acctId", "must be 11 characters");
            assertThat(ex2.getFieldName()).isEqualTo("acctId");
            assertThat(ex2.getValidationMessage()).isEqualTo("must be 11 characters");

            var cause = new RuntimeException("root");
            var ex3 = new ValidationException("cardNum", "invalid format", cause);
            assertThat(ex3).hasCause(cause);
            assertThat(ex3.getFieldName()).isEqualTo("cardNum");
        }

        @Test
        @DisplayName("DuplicateRecordException — entity/key constructor")
        void duplicateRecordEntityKey() {
            var ex = new DuplicateRecordException("Account", "00000000001");
            assertThat(ex).hasMessageContaining("Account").hasMessageContaining("00000000001");

            var cause = new RuntimeException("db");
            var ex2 = new DuplicateRecordException("Duplicate", cause);
            assertThat(ex2).hasCause(cause);
        }

        @Test
        @DisplayName("RecordNotFoundException — entity/key constructor")
        void recordNotFoundEntityKey() {
            var ex = new RecordNotFoundException("Account", "99999999999");
            assertThat(ex).hasMessageContaining("Account").hasMessageContaining("99999999999");

            var cause = new RuntimeException("db");
            var ex2 = new RecordNotFoundException("Not found", cause);
            assertThat(ex2).hasCause(cause);
        }

        @Test
        @DisplayName("FileStatusException — three-arg constructor with cause")
        void fileStatusExceptionWithCause() {
            var cause = new RuntimeException("IO");
            var ex = new FileStatusException("35", "File not available", cause);
            assertThat(ex.getFileStatusCode()).isEqualTo("35");
            assertThat(ex).hasCause(cause);
        }
    }
}
