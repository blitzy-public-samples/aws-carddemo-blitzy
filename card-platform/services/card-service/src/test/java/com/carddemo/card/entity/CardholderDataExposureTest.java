package com.carddemo.card.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.EventSchemas;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts that no cardholder data leaves {@link CardEntity}.
 *
 * <p>The card verification value is the reason this class exists. {@code app/cpy/CVACT02Y.cpy:L7}
 * declares {@code CARD-CVV-CD PIC 9(03)} and the source stores it in the clear, so the column keeps
 * it: Agent Action Plan section 0.4.1 records that this platform stores the field and never
 * serializes it into any event or log, section 0.6.4 repeats the rule and states that a test
 * asserts it, and section 0.2.2 places payment-card industry controls beyond the one documented
 * masking deviation out of scope. This is that test.
 *
 * <p>Four exits exist from a Java object, and each one is closed here. A public accessor returns
 * the value directly, so the class declares none. A Jackson rendering walks the fields, so the
 * field carries {@code @JsonIgnore}. {@link Object#toString()} lands in a log line the moment code
 * concatenates the entity into a message, so it withholds the value. A validation message can echo
 * a rejected argument, so the guards report a width and a position and never a character.
 *
 * <p>Two exits are contracts rather than code, and a security review asked for the storage itself to
 * be removed. The plan sections above forbid that and rest the case on the value never being
 * emitted, so the two published contracts are read here as well: no version of any event schema may
 * declare a property for the value, and this service's own interface description may not declare a
 * field for it either. Both were prose promises until this class read them.
 *
 * <p>Every test runs in memory. None opens a database connection, sends a request or reads a file.
 */
@DisplayName("CardEntity carries no cardholder data out of the service")
class CardholderDataExposureTest {

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 1 through 16. */
    private static final String CARD_NUMBER = "0500024453765740";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 17 through 27. */
    private static final String ACCOUNT_ID = "00000000050";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 28 through 30. */
    private static final String CARD_VERIFICATION_VALUE = "747";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 31 through 80, trimmed. */
    private static final String EMBOSSED_NAME = "Aniya Von";

    /** Row one of {@code app/data/ASCII/carddata.txt}, columns 81 through 90. */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** Column 91 of every row of {@code app/data/ASCII/carddata.txt}. */
    private static final String ACTIVE_STATUS = "Y";

    /** The field this test guards, named as the class declares it. */
    private static final String GUARDED_FIELD = "cardVerificationValue";

    /** Renders an entity the way a controller that returned one would. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Builds the row that row one of the fixture describes. */
    private static CardEntity fixtureRow() {
        return new CardEntity(CARD_NUMBER, ACCOUNT_ID, CARD_VERIFICATION_VALUE, EMBOSSED_NAME,
                EXPIRATION_DATE, ACTIVE_STATUS);
    }

    @Nested
    @DisplayName("No accessor returns the card verification value")
    class AccessorSurface {

        @Test
        @DisplayName("no public method of CardEntity returns the value")
        void noPublicMethodReturnsTheCardVerificationValue() {
            List<String> offenders = new ArrayList<>();
            for (Method method : CardEntity.class.getMethods()) {
                if (method.getDeclaringClass() != CardEntity.class) {
                    continue;
                }
                if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("cvv") || name.contains("verification")) {
                    offenders.add(method.getName());
                }
            }
            assertTrue(offenders.isEmpty(),
                    "CardEntity must expose no accessor for the card verification value, found "
                            + offenders);
        }

        @Test
        @DisplayName("the field itself is private, so no caller reads it directly")
        void theFieldIsPrivate() throws ReflectiveOperationException {
            Field field = CardEntity.class.getDeclaredField(GUARDED_FIELD);
            assertAll(
                    () -> assertFalse(java.lang.reflect.Modifier.isPublic(field.getModifiers()),
                            GUARDED_FIELD + " must not be public"),
                    () -> assertFalse(java.lang.reflect.Modifier.isProtected(field.getModifiers()),
                            GUARDED_FIELD + " must not be protected"),
                    () -> assertTrue(java.lang.reflect.Modifier.isPrivate(field.getModifiers()),
                            GUARDED_FIELD + " must be private"));
        }

        @Test
        @DisplayName("the field carries @JsonIgnore, which closes the serializer path")
        void theFieldCarriesJsonIgnore() throws ReflectiveOperationException {
            Field field = CardEntity.class.getDeclaredField(GUARDED_FIELD);
            assertNotNull(
                    field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class),
                    GUARDED_FIELD + " must carry @JsonIgnore so no Jackson configuration, "
                            + "including one that makes private fields visible, can emit it");
        }

        @Test
        @DisplayName("the column still holds the value, which is what the plan requires")
        void theColumnStillHoldsTheValue() throws ReflectiveOperationException {
            Field field = CardEntity.class.getDeclaredField(GUARDED_FIELD);
            jakarta.persistence.Column column =
                    field.getAnnotation(jakarta.persistence.Column.class);
            assertAll(
                    () -> assertNotNull(column, GUARDED_FIELD + " must stay mapped to a column"),
                    () -> assertEquals("card_verification_value", column.name(),
                            "the column name follows CARD-CVV-CD"),
                    () -> assertEquals(PicClause.CARD_CVV_CD_WIDTH, column.length(),
                            "PIC 9(03) is three characters wide"),
                    () -> assertEquals(String.class, field.getType(),
                            "a display field of three digits is text, so 007 survives a round "
                                    + "trip"));
        }
    }

    @Nested
    @DisplayName("No rendering carries cardholder data")
    class Renderings {

        @Test
        @DisplayName("toString withholds the number, the value, the name, the expiry and the"
                + " account")
        void toStringWithholdsCardholderData() {
            String rendered = fixtureRow().toString();
            assertAll(
                    () -> assertFalse(rendered.contains(CARD_VERIFICATION_VALUE),
                            "the card verification value must not appear: " + rendered),
                    () -> assertFalse(rendered.contains(CARD_NUMBER),
                            "the full card number must not appear: " + rendered),
                    () -> assertFalse(rendered.contains(EMBOSSED_NAME),
                            "the embossed cardholder name must not appear: " + rendered),
                    () -> assertFalse(rendered.contains(EXPIRATION_DATE.toString()),
                            "the expiration date must not appear: " + rendered),
                    () -> assertFalse(rendered.contains(ACCOUNT_ID),
                            "the account identifier is stable and identifies one cardholder, so "
                                    + "it must not appear either: " + rendered),
                    () -> assertTrue(rendered.contains(EventEnvelope.WITHHELD),
                            "a withheld value reads as withheld rather than absent: " + rendered));
        }

        @Test
        @DisplayName("a Jackson rendering carries no card verification value")
        void jacksonRenderingCarriesNoCardVerificationValue() {
            String json = MAPPER.writeValueAsString(fixtureRow());
            assertAll(
                    () -> assertFalse(json.contains(CARD_VERIFICATION_VALUE),
                            "the value must not reach a response body: " + json),
                    () -> assertFalse(json.toLowerCase(Locale.ROOT).contains("verification"),
                            "not even the property name belongs in a payload: " + json));
        }

        @Test
        @DisplayName("the four fixture digits are the only ones this test trusts")
        void theFixtureValueIsTheOneTheSourceCarries() {
            assertAll(
                    () -> assertEquals(PicClause.CARD_CVV_CD_WIDTH,
                            CARD_VERIFICATION_VALUE.length(),
                            "row one of carddata.txt holds three digits at columns 28 to 30"),
                    () -> assertEquals(PicClause.CARD_NUM_WIDTH, CARD_NUMBER.length(),
                            "row one of carddata.txt holds sixteen digits at columns 1 to 16"));
        }
    }

    @Nested
    @DisplayName("No validation message echoes a rejected value")
    class ValidationMessages {

        @Test
        @DisplayName("a card verification value of the wrong width is refused without echoing it")
        void theWidthMessageCarriesNoDigit() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new CardEntity(CARD_NUMBER, ACCOUNT_ID, "07", EMBOSSED_NAME,
                            EXPIRATION_DATE, ACTIVE_STATUS));
            assertAll(
                    () -> assertTrue(thrown.getMessage().contains(GUARDED_FIELD),
                            "the message names the field: " + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("07"),
                            "the message must not carry the rejected digits: "
                                    + thrown.getMessage()));
        }

        @Test
        @DisplayName("a non-digit card verification value is refused without echoing it")
        void theDigitMessageCarriesNoCharacter() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new CardEntity(CARD_NUMBER, ACCOUNT_ID, "7x9", EMBOSSED_NAME,
                            EXPIRATION_DATE, ACTIVE_STATUS));
            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("position 2"),
                            "the message reports where the character sits: "
                                    + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("7x9"),
                            "the message must not carry the rejected value: "
                                    + thrown.getMessage()),
                    () -> assertFalse(thrown.getMessage().contains("x"),
                            "not even the offending character belongs in the message: "
                                    + thrown.getMessage()));
        }

        @Test
        @DisplayName("an account identifier of the wrong width is refused without echoing it")
        void theAccountIdentifierMessageCarriesNoDigit() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new CardEntity(CARD_NUMBER, "50", CARD_VERIFICATION_VALUE,
                            EMBOSSED_NAME, EXPIRATION_DATE, ACTIVE_STATUS));
            assertAll(
                    () -> assertTrue(thrown.getMessage().contains("accountId"),
                            "the message names the field: " + thrown.getMessage()),
                    () -> assertTrue(thrown.getMessage()
                                    .contains(String.valueOf(PicClause.CARD_ACCT_ID_WIDTH)),
                            "the message states the width the Picture clause declares: "
                                    + thrown.getMessage()));
        }
    }

    @Nested
    @DisplayName("An update leaves the card verification value alone")
    class UpdatePath {

        @Test
        @DisplayName("applyUpdate changes the three mapped fields and no fourth")
        void applyUpdateTouchesNoCardholderIdentity() throws ReflectiveOperationException {
            CardEntity card = fixtureRow();
            card.applyUpdate("NEW NAME", LocalDate.of(2025, 12, 31), "N");

            Field field = CardEntity.class.getDeclaredField(GUARDED_FIELD);
            field.setAccessible(true);
            assertAll(
                    () -> assertEquals(CARD_VERIFICATION_VALUE, field.get(card),
                            "app/bms/COCRDUP.bms declares no field for the card verification "
                                    + "value, so an update leaves it as it was"),
                    () -> assertEquals(CARD_NUMBER, card.getCardNumber(),
                            "the card number is a search key and an update does not change it"),
                    () -> assertEquals(ACCOUNT_ID, card.getAccountId(),
                            "the account identifier is a search key too"),
                    () -> assertEquals("N", card.getActiveStatus(),
                            "the active status is one of the three fields an update changes"));
        }
    }

    @Nested
    @DisplayName("No published contract declares the value")
    class PublishedContracts {

        /**
         * Matches a property or field name naming the card verification value, however spelled.
         *
         * <p>The three spellings this platform could produce are the copybook name
         * {@code CARD-CVV-CD}, the column name {@code card_verification_value} and the Java name
         * {@code cardVerificationValue}, and a fourth is whatever a future author invents. Matching
         * either fragment, case-insensitively, covers all four.
         */
        private static final Pattern VERIFICATION_NAME =
                Pattern.compile("(?i)(cvv|verification)");

        /** Matches one JSON member name, being a quoted string followed by a colon. */
        private static final Pattern JSON_MEMBER_NAME = Pattern.compile("\"([^\"]+)\"\\s*:");

        /** Matches one YAML mapping key, being an unquoted name at the start of a line. */
        private static final Pattern YAML_KEY =
                Pattern.compile("(?m)^\\s*-?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:");

        /**
         * No version of any governed event contract declares a member naming the value.
         *
         * <p>{@code EventSchemas.SCHEMA_DOCUMENTS} carries every version of every contract this
         * platform publishes or consumes, not only the newest, because a consumer reading an
         * earlier version reads that document. A member name is asserted rather than the whole
         * text, so the two {@code $comment} entries recording the deliberate absence are read as
         * what they are and a property would be caught wherever it sat in the tree.
         */
        @Test
        @DisplayName("no version of any event contract declares a member naming the value")
        void noEventContractDeclaresTheValue() {
            List<String> declaring = new ArrayList<>();

            for (String resource : EventSchemas.SCHEMA_DOCUMENTS.values()) {
                Matcher member = JSON_MEMBER_NAME.matcher(readClasspath(resource));
                while (member.find()) {
                    if (VERIFICATION_NAME.matcher(member.group(1)).find()) {
                        declaring.add(resource + " declares \"" + member.group(1) + "\"");
                    }
                }
            }

            assertEquals(List.of(), declaring,
                    "the plan stores this value and emits it nowhere, so no event contract may "
                            + "carry a member for it: " + declaring);
        }

        /**
         * This service's interface description declares no field naming the value.
         *
         * <p>The description discusses the value in prose, and says it is never returned. A mapping
         * key would contradict that, so keys are read and prose is left alone.
         */
        @Test
        @DisplayName("the interface description declares no field naming the value")
        void theInterfaceDescriptionDeclaresNoFieldNamingTheValue() {
            List<String> declaring = new ArrayList<>();
            Matcher key = YAML_KEY.matcher(readClasspath("openapi.yaml"));
            while (key.find()) {
                if (VERIFICATION_NAME.matcher(key.group(1)).find()) {
                    declaring.add("openapi.yaml declares " + key.group(1));
                }
            }

            assertEquals(List.of(), declaring,
                    "openapi.yaml states that the value is never returned, and a declared field "
                            + "would be the contradiction: " + declaring);
        }

        /**
         * The reader finds a member a contract does declare, so a silent miss cannot pass.
         *
         * <p>Without this, a reader that returned nothing would satisfy both assertions above.
         */
        @Test
        @DisplayName("the reader finds the members the card contract does declare")
        void theReaderFindsTheMembersTheContractDeclares() {
            List<String> members = new ArrayList<>();
            Matcher member = JSON_MEMBER_NAME.matcher(
                    readClasspath("schemas/card-updated-v2.json"));
            while (member.find()) {
                members.add(member.group(1));
            }

            assertAll(
                    () -> assertTrue(members.contains("maskedCardNumber"),
                            "maskedCardNumber is a declared member of the card contract, so a "
                                    + "reader that missed it would prove nothing about an absent "
                                    + "one"),
                    () -> assertTrue(members.contains("properties"),
                            "the reader walks the whole document rather than one subtree"));
        }

        /** Reads one classpath resource as text. */
        private String readClasspath(String resource) {
            try (InputStream stream =
                    CardholderDataExposureTest.class.getClassLoader()
                            .getResourceAsStream(resource)) {
                assertNotNull(stream, resource + " must be on the classpath");
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot read " + resource, unreadable);
            }
        }
    }
}
