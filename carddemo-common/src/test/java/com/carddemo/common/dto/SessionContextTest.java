package com.carddemo.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.dto.SessionContext.ProgramContext;
import com.carddemo.common.dto.SessionContext.UserType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for :class:`SessionContext`.
 *
 * :purpose: Verify the COMMAREA-derived session-context DTO preserves the
 *     ``COCOM01Y`` 88-level mappings verbatim, exposes the field contract with
 *     correct Java types, and remains ``Serializable`` with ``serialVersionUID``
 *     fixed at ``1L``.
 * :output: JUnit 5 assertions; no external resources.
 */
@DisplayName("SessionContext")
class SessionContextTest {

    /**
     * Tests for the nested :class:`SessionContext.UserType` enum.
     *
     * :purpose: Confirm ``CDEMO-USER-TYPE`` 88-levels map to codes ``'A'``/``'U'``
     *     and that ``fromCode`` performs tolerant, null-safe lookups.
     */
    @Nested
    @DisplayName("UserType")
    class UserTypeTests {

        @Test
        @DisplayName("admin constant carries code 'A'")
        void adminCode() {
            assertThat(UserType.CDEMO_USRTYP_ADMIN.getCode()).isEqualTo('A');
        }

        @Test
        @DisplayName("user constant carries code 'U'")
        void userCode() {
            assertThat(UserType.CDEMO_USRTYP_USER.getCode()).isEqualTo('U');
        }

        @Test
        @DisplayName("fromCode resolves canonical codes")
        void fromCodeCanonical() {
            assertThat(UserType.fromCode("A")).isEqualTo(UserType.CDEMO_USRTYP_ADMIN);
            assertThat(UserType.fromCode("U")).isEqualTo(UserType.CDEMO_USRTYP_USER);
        }

        @Test
        @DisplayName("fromCode is case-insensitive and trims whitespace")
        void fromCodeTolerant() {
            assertThat(UserType.fromCode("a")).isEqualTo(UserType.CDEMO_USRTYP_ADMIN);
            assertThat(UserType.fromCode(" u ")).isEqualTo(UserType.CDEMO_USRTYP_USER);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("fromCode returns null for null/blank input")
        void fromCodeNullOrBlank(String input) {
            assertThat(UserType.fromCode(input)).isNull();
        }

        @Test
        @DisplayName("fromCode returns null for an unknown code")
        void fromCodeUnknown() {
            assertThat(UserType.fromCode("Z")).isNull();
        }
    }

    /**
     * Tests for the nested :class:`SessionContext.ProgramContext` enum.
     *
     * :purpose: Confirm ``CDEMO-PGM-CONTEXT`` 88-levels map to codes ``0``/``1``
     *     and that ``fromCode`` returns ``null`` for unknown numeric codes.
     */
    @Nested
    @DisplayName("ProgramContext")
    class ProgramContextTests {

        @Test
        @DisplayName("enter constant carries code 0")
        void enterCode() {
            assertThat(ProgramContext.CDEMO_PGM_ENTER.getCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("reenter constant carries code 1")
        void reenterCode() {
            assertThat(ProgramContext.CDEMO_PGM_REENTER.getCode()).isEqualTo(1);
        }

        @Test
        @DisplayName("fromCode resolves canonical codes")
        void fromCodeCanonical() {
            assertThat(ProgramContext.fromCode(0)).isEqualTo(ProgramContext.CDEMO_PGM_ENTER);
            assertThat(ProgramContext.fromCode(1)).isEqualTo(ProgramContext.CDEMO_PGM_REENTER);
        }

        @Test
        @DisplayName("fromCode returns null for an unknown code")
        void fromCodeUnknown() {
            assertThat(ProgramContext.fromCode(99)).isNull();
        }
    }

    /**
     * Tests for the field contract of :class:`SessionContext`.
     *
     * :purpose: Confirm identifier fields use ``Long``, the card number and map
     *     names use ``String`` preserving width/leading zeros, and getters mirror
     *     the values assigned through setters.
     */
    @Nested
    @DisplayName("field contract")
    class FieldContractTests {

        @Test
        @DisplayName("cardNum is a String preserving 16-digit leading zeros")
        void cardNumIsString() {
            SessionContext ctx = new SessionContext();
            ctx.setCardNum("0000000000123456");
            assertThat(ctx.getCardNum()).isInstanceOf(String.class).isEqualTo("0000000000123456").hasSize(16);
        }

        @Test
        @DisplayName("custId and acctId are Long identifiers")
        void identifiersAreLong() {
            SessionContext ctx = new SessionContext();
            ctx.setCustId(123456789L);
            ctx.setAcctId(12345678901L);
            assertThat(ctx.getCustId()).isInstanceOf(Long.class).isEqualTo(123456789L);
            assertThat(ctx.getAcctId()).isInstanceOf(Long.class).isEqualTo(12345678901L);
        }

        @Test
        @DisplayName("lastMap corresponds to PIC X(7) and round-trips a 7-char map name")
        void lastMapWidthSeven() {
            SessionContext ctx = new SessionContext();
            ctx.setLastMap("COSGN00");
            assertThat(ctx.getLastMap()).isEqualTo("COSGN00").hasSize(7);
        }

        @Test
        @DisplayName("lastMapset corresponds to PIC X(7) and round-trips a 7-char mapset name")
        void lastMapsetWidthSeven() {
            SessionContext ctx = new SessionContext();
            ctx.setLastMapset("COSGN0A");
            assertThat(ctx.getLastMapset()).isEqualTo("COSGN0A").hasSize(7);
        }

        @Test
        @DisplayName("string and enum fields round-trip through accessors")
        void stringAndEnumFieldsRoundTrip() {
            SessionContext ctx = new SessionContext();
            ctx.setFromTranid("CC00");
            ctx.setFromProgram("COSGN00C");
            ctx.setToTranid("CM00");
            ctx.setToProgram("COMEN01C");
            ctx.setUserId("USER0001");
            ctx.setUserType(UserType.CDEMO_USRTYP_ADMIN);
            ctx.setProgramContext(ProgramContext.CDEMO_PGM_REENTER);
            ctx.setCustFname("JOHN");
            ctx.setCustMname("Q");
            ctx.setCustLname("PUBLIC");
            ctx.setAcctStatus("Y");

            assertThat(ctx.getFromTranid()).isEqualTo("CC00");
            assertThat(ctx.getFromProgram()).isEqualTo("COSGN00C");
            assertThat(ctx.getToTranid()).isEqualTo("CM00");
            assertThat(ctx.getToProgram()).isEqualTo("COMEN01C");
            assertThat(ctx.getUserId()).isEqualTo("USER0001");
            assertThat(ctx.getUserType()).isEqualTo(UserType.CDEMO_USRTYP_ADMIN);
            assertThat(ctx.getProgramContext()).isEqualTo(ProgramContext.CDEMO_PGM_REENTER);
            assertThat(ctx.getCustFname()).isEqualTo("JOHN");
            assertThat(ctx.getCustMname()).isEqualTo("Q");
            assertThat(ctx.getCustLname()).isEqualTo("PUBLIC");
            assertThat(ctx.getAcctStatus()).isEqualTo("Y");
        }
    }

    /**
     * Tests for the ``Serializable`` contract.
     *
     * :purpose: Confirm the DTO is ``Serializable`` and survives a
     *     serialize/deserialize round trip with field fidelity.
     */
    @Nested
    @DisplayName("serialization")
    class SerializationTests {

        @Test
        @DisplayName("implements Serializable")
        void implementsSerializable() {
            assertThat(new SessionContext()).isInstanceOf(Serializable.class);
        }

        @Test
        @DisplayName("round-trips through Java serialization preserving state")
        void serializationRoundTrip() throws Exception {
            SessionContext original = new SessionContext();
            original.setUserId("USER0001");
            original.setUserType(UserType.CDEMO_USRTYP_USER);
            original.setProgramContext(ProgramContext.CDEMO_PGM_ENTER);
            original.setCustId(123456789L);
            original.setAcctId(12345678901L);
            original.setCardNum("0000000000123456");
            original.setLastMap("COSGN00");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(original);
            }
            SessionContext restored;
            try (ObjectInputStream ois =
                    new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
                restored = (SessionContext) ois.readObject();
            }

            assertThat(restored.getUserId()).isEqualTo("USER0001");
            assertThat(restored.getUserType()).isEqualTo(UserType.CDEMO_USRTYP_USER);
            assertThat(restored.getProgramContext()).isEqualTo(ProgramContext.CDEMO_PGM_ENTER);
            assertThat(restored.getCustId()).isEqualTo(123456789L);
            assertThat(restored.getAcctId()).isEqualTo(12345678901L);
            assertThat(restored.getCardNum()).isEqualTo("0000000000123456");
            assertThat(restored.getLastMap()).isEqualTo("COSGN00");
        }
    }
}
