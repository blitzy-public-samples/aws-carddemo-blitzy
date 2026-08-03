package com.carddemo.account.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for the account service's own {@link DeadLetterMetadata}.
 *
 * <p>The four text components reproduce {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21}, field for field, and a fifth component names every one of
 * them the record shortened. The widths are typed here from the copybook rather than read from
 * the production constants, and one test below compares the two:
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. The four
 * fields occupy 134 bytes, and each carries {@code VALUE SPACES}.</p>
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L4209} fills {@code ABEND-CULPRIT} with the eight-character
 * program name {@code LIT-THISPGM}, and lines 4205 and 4206 substitute
 * {@code UNEXPECTED ABEND OCCURRED.} for an {@code ABEND-MSG} holding low values. One test
 * below holds that the substituted text fits the message width, and holds that this record
 * performs no substitution of its own.</p>
 *
 * <p>Building the record raises nothing. It describes a failure that already happened, so a
 * second failure raised while building it would suppress the dead-letter message and leave the
 * broker redelivering the same message for ever. An over-length component therefore keeps its
 * leading characters and {@code truncatedComponents} names it.</p>
 *
 * <p>This record is service-local. Five services each declare their own record of the same
 * name. Two tests below hold the over-length policy and hold the record inside this service's
 * own package, so a move into a shared library fails here rather than in production.</p>
 */
class DeadLetterMetadataTest {

    /** Width of {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}. */
    private static final int ABEND_CODE_WIDTH = 4;

    /** Width of {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}. */
    private static final int CULPRIT_WIDTH = 8;

    /** Width of {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:L26}. */
    private static final int REASON_WIDTH = 50;

    /** Width of {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}. */
    private static final int MESSAGE_WIDTH = 72;

    /** Sum of the four widths of {@code 01 ABEND-DATA}. */
    private static final int ABEND_DATA_WIDTH = 134;

    /** Fields {@code 01 ABEND-DATA} declares at {@code app/cpy/CSMSG02Y.cpy:L22-L28}. */
    private static final int TEXT_COMPONENT_COUNT = 4;

    /**
     * Text {@code app/cbl/COACTUPC.cbl:L4206} substitutes for an {@code ABEND-MSG} holding
     * low values.
     */
    private static final String SOURCE_DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /**
     * Program name {@code LIT-THISPGM} that {@code app/cbl/COACTUPC.cbl:L4209} moves into
     * {@code ABEND-CULPRIT}.
     */
    private static final String SOURCE_PROGRAM_NAME = "COACTUPC";

    /** Package this record belongs to. A shared contract would sit elsewhere. */
    private static final String OWNING_PACKAGE = "com.carddemo.account.messaging";

    /**
     * Asserts that the record declares four {@code String} components, named and ordered as
     * {@code 01 ABEND-DATA} declares its four fields at
     * {@code app/cpy/CSMSG02Y.cpy:L22-L28}, followed by the list of components the record
     * shortened. The fifth component has no copybook ancestor.
     */
    @Test
    void declaresFourStringComponentsInTheCopybookOrder() {
        RecordComponent[] components = DeadLetterMetadata.class.getRecordComponents();
        assertNotNull(components, "DeadLetterMetadata is a record");
        assertEquals(TEXT_COMPONENT_COUNT + 1, components.length,
                "app/cpy/CSMSG02Y.cpy:L21 declares four fields, so the record declares "
                        + "four text components plus the truncation list");

        List<String> names = new ArrayList<>();
        for (RecordComponent component : components) {
            names.add(component.getName());
        }
        for (int index = 0; index < TEXT_COMPONENT_COUNT; index++) {
            assertEquals(String.class, components[index].getType(),
                    "every field of app/cpy/CSMSG02Y.cpy:L21 is alphanumeric, so component "
                            + components[index].getName() + " holds a String");
        }
        assertEquals(List.class, components[TEXT_COMPONENT_COUNT].getType(),
                "the truncation list is a List, and it follows the four copybook fields");
        assertEquals(
                List.of("abendCode", "culprit", "reason", "message", "truncatedComponents"),
                names,
                "the first four components follow the field order of "
                        + "app/cpy/CSMSG02Y.cpy:L22-L28");
    }

    /**
     * Asserts that each published maximum equals the width of the copybook field it carries,
     * that the four widths sum to the 134 bytes of {@code 01 ABEND-DATA}, and that a value at
     * a component's width reaches it unchanged while one character more is cut back to it.
     */
    @Test
    void eachComponentBoundsAtItsCopybookFieldWidth() {
        assertEquals(ABEND_DATA_WIDTH,
                ABEND_CODE_WIDTH + CULPRIT_WIDTH + REASON_WIDTH + MESSAGE_WIDTH,
                "the four fields of app/cpy/CSMSG02Y.cpy:L21 occupy 134 bytes");
        assertEquals(ABEND_CODE_WIDTH, DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                "ABEND-CODE PIC X(4) at app/cpy/CSMSG02Y.cpy:L22 is 4 characters wide");
        assertEquals(CULPRIT_WIDTH, DeadLetterMetadata.CULPRIT_MAX_LENGTH,
                "ABEND-CULPRIT PIC X(8) at app/cpy/CSMSG02Y.cpy:L24 is 8 characters wide");
        assertEquals(REASON_WIDTH, DeadLetterMetadata.REASON_MAX_LENGTH,
                "ABEND-REASON PIC X(50) at app/cpy/CSMSG02Y.cpy:L26 is 50 characters wide");
        assertEquals(MESSAGE_WIDTH, DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                "ABEND-MSG PIC X(72) at app/cpy/CSMSG02Y.cpy:L28 is 72 characters wide");

        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("abendCode", ABEND_CODE_WIDTH);
        widths.put("culprit", CULPRIT_WIDTH);
        widths.put("reason", REASON_WIDTH);
        widths.put("message", MESSAGE_WIDTH);

        for (Map.Entry<String, Integer> field : widths.entrySet()) {
            int width = field.getValue();
            String atWidth = "X".repeat(width);
            String pastWidth = "X".repeat(width + 1);

            assertEquals(width, componentOf(field.getKey(), atWidth).length(),
                    "component " + field.getKey() + " holds a value of width " + width
                            + " unchanged");
            assertEquals(width, componentOf(field.getKey(), pastWidth).length(),
                    "component " + field.getKey() + " cuts a value of width " + (width + 1)
                            + " back to " + width);
        }
    }

    /**
     * Asserts that a value one character past its maximum keeps its leading characters, one
     * component at a time, and that {@link DeadLetterMetadata#truncatedComponents()} names
     * that component and no other.
     */
    @Test
    void aValueOnePastItsMaximumIsTruncatedAndNamedInTheTruncationList() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("abendCode", ABEND_CODE_WIDTH);
        widths.put("culprit", CULPRIT_WIDTH);
        widths.put("reason", REASON_WIDTH);
        widths.put("message", MESSAGE_WIDTH);

        assertEquals(TEXT_COMPONENT_COUNT, widths.size(),
                "one case covers each of the four components");

        for (Map.Entry<String, Integer> field : widths.entrySet()) {
            String component = field.getKey();
            int width = field.getValue();
            String pastWidth = "X".repeat(width + 1);

            DeadLetterMetadata metadata = assertDoesNotThrow(
                    () -> constructWith(component, pastWidth),
                    "building the record with an over-length " + component
                            + " raised a failure of its own");
            assertEquals(pastWidth.substring(0, width), componentOf(component, pastWidth),
                    component + " keeps its leading " + width + " characters");
            assertEquals(List.of(component), metadata.truncatedComponents(),
                    "the truncation list names " + component + " and no other component");
        }
    }

    /**
     * Asserts that the constructor pads nothing and trims nothing. A space is printable
     * ASCII, so leading and trailing spaces survive, and a short value keeps its length rather
     * than being padded out to the copybook width.
     */
    @Test
    void nothingIsPaddedOrTrimmed() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(" 09 ", " outbox ",
                "  account row will not serialize  ", "  broker refused the row  ");

        assertEquals(" 09 ", metadata.abendCode(),
                "abendCode keeps its leading and trailing spaces");
        assertEquals(" outbox ", metadata.culprit(),
                "culprit keeps its leading and trailing spaces");
        assertEquals("  account row will not serialize  ", metadata.reason(),
                "reason keeps its leading and trailing spaces");
        assertEquals("  broker refused the row  ", metadata.message(),
                "message keeps its leading and trailing spaces");

        DeadLetterMetadata shortValues = DeadLetterMetadata.of("9", "r", "o", "b");
        assertEquals(1, shortValues.abendCode().length(),
                "a one-character abendCode is not padded to " + ABEND_CODE_WIDTH);
        assertEquals(1, shortValues.culprit().length(),
                "a one-character culprit is not padded to " + CULPRIT_WIDTH);
        assertEquals(1, shortValues.reason().length(),
                "a one-character reason is not padded to " + REASON_WIDTH);
        assertEquals(1, shortValues.message().length(),
                "a one-character message is not padded to " + MESSAGE_WIDTH);
        assertTrue(shortValues.truncatedComponents().isEmpty(),
                "a value shorter than its maximum is not recorded as shortened");
    }

    /**
     * Asserts that one null component becomes the empty string while the other three keep
     * their values, matching the {@code VALUE SPACES} clause each copybook field carries.
     */
    @Test
    void oneNullComponentBecomesTheEmptyString() {
        assertEquals("", DeadLetterMetadata.of(null, "outbox", "reason", "message").abendCode(),
                "a null abendCode becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L23");
        assertEquals("", DeadLetterMetadata.of("0902", null, "reason", "message").culprit(),
                "a null culprit becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L25");
        assertEquals("", DeadLetterMetadata.of("0902", "outbox", null, "message").reason(),
                "a null reason becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L27");
        assertEquals("", DeadLetterMetadata.of("0902", "outbox", "reason", null).message(),
                "a null message becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L29");

        DeadLetterMetadata oneNull = DeadLetterMetadata.of("0902", null, "reason", "message");
        assertEquals("0902", oneNull.abendCode(),
                "one null component leaves abendCode untouched");
        assertEquals("reason", oneNull.reason(), "one null component leaves reason untouched");
        assertEquals("message", oneNull.message(),
                "one null component leaves message untouched");
    }

    /** Asserts that four null components become four empty strings. */
    @Test
    void fourNullComponentsBecomeFourEmptyStrings() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(null, null, null, null);

        assertEquals("", metadata.abendCode(), "a null abendCode becomes the empty string");
        assertEquals("", metadata.culprit(), "a null culprit becomes the empty string");
        assertEquals("", metadata.reason(), "a null reason becomes the empty string");
        assertEquals("", metadata.message(), "a null message becomes the empty string");
        assertTrue(metadata.truncatedComponents().isEmpty(),
                "a null component is not recorded as shortened");
    }

    /** Asserts that the empty string is accepted in every component and stays empty. */
    @Test
    void theEmptyStringIsAcceptedInEveryComponent() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("", "", "", "");

        assertEquals("", metadata.abendCode(), "an empty abendCode stays empty");
        assertEquals("", metadata.culprit(), "an empty culprit stays empty");
        assertEquals("", metadata.reason(), "an empty reason stays empty");
        assertEquals("", metadata.message(), "an empty message stays empty");
    }

    /**
     * Asserts that the two values the source moves into this record fit their components,
     * and that the record substitutes neither of them. The program name
     * {@code app/cbl/COACTUPC.cbl:L4209} moves is eight characters, exactly the culprit
     * width. The text lines 4205 and 4206 substitute is twenty-six characters, inside the
     * message width.
     */
    @Test
    void theSourceValuesFitAndTheRecordSubstitutesNeither() {
        assertEquals(CULPRIT_WIDTH, SOURCE_PROGRAM_NAME.length(),
                "LIT-THISPGM at app/cbl/COACTUPC.cbl:L4209 is eight characters, matching "
                        + "ABEND-CULPRIT PIC X(8)");
        assertTrue(SOURCE_DEFAULT_MESSAGE.length() <= MESSAGE_WIDTH,
                "the text at app/cbl/COACTUPC.cbl:L4206 holds "
                        + SOURCE_DEFAULT_MESSAGE.length() + " characters and fits "
                        + "ABEND-MSG PIC X(72)");

        DeadLetterMetadata fromSourceValues = DeadLetterMetadata.of("0001",
                SOURCE_PROGRAM_NAME, "", SOURCE_DEFAULT_MESSAGE);
        assertEquals(SOURCE_PROGRAM_NAME, fromSourceValues.culprit(),
                "the program name reaches culprit unchanged");
        assertEquals(SOURCE_DEFAULT_MESSAGE, fromSourceValues.message(),
                "the substituted text reaches message unchanged");

        DeadLetterMetadata blankMessage =
                DeadLetterMetadata.of("0001", SOURCE_PROGRAM_NAME, "", null);
        assertEquals("", blankMessage.message(),
                "app/cbl/COACTUPC.cbl:L4205 substitutes inside the abend routine, and this "
                        + "record substitutes nothing, so a null message stays empty");
        assertNotEquals(SOURCE_DEFAULT_MESSAGE, blankMessage.message(),
                "the record does not supply the text of app/cbl/COACTUPC.cbl:L4206 on the "
                        + "caller's behalf");
    }

    /**
     * Asserts that equality covers all four components. Four records each differ from the
     * reference in one component alone, so a component dropped from equality fails here.
     */
    @Test
    void equalityCoversAllFourComponents() {
        DeadLetterMetadata reference =
                DeadLetterMetadata.of("0902", "outbox", "publish refused", "broker unreachable");
        DeadLetterMetadata same =
                DeadLetterMetadata.of("0902", "outbox", "publish refused", "broker unreachable");

        assertEquals(reference, same, "two records of equal components are equal");
        assertEquals(reference.hashCode(), same.hashCode(),
                "two records of equal components share a hash code");

        assertNotEquals(reference,
                DeadLetterMetadata.of("0903", "outbox", "publish refused", "broker unreachable"),
                "a different abendCode makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "relay", "publish refused", "broker unreachable"),
                "a different culprit makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "outbox", "publish failed", "broker unreachable"),
                "a different reason makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "outbox", "publish refused", "broker refused"),
                "a different message makes the records unequal");
    }

    /**
     * Asserts that the rendered record adds nothing of its own. Every component holds text
     * with no digit, so a rendered form holding a digit would come from the record itself,
     * from a length, a hash or an identity.
     */
    @Test
    void theRenderedRecordAddsNoValueOfItsOwn() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("ABCD", "outbox",
                "account row refused by the broker", "the broker closed the connection");
        String rendered = metadata.toString();

        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            assertFalse(Character.isDigit(character),
                    "the rendered record holds a digit at position " + index
                            + ", and no component supplied one");
        }
        assertTrue(rendered.contains("abendCode"), "the rendered record names its components");
    }

    /**
     * Asserts that this record stays inside the account service. A shared, interoperable
     * dead-letter payload would sit in {@code com.carddemo.events} alongside the event
     * contracts, and the widths and the over-length policy would then bind every service.
     * Neither holds: the record is service-local and carries no framework annotation that
     * would publish it.
     */
    @Test
    void theRecordIsServiceLocalAndNotASharedContract() {
        assertEquals(OWNING_PACKAGE, DeadLetterMetadata.class.getPackageName(),
                "DeadLetterMetadata belongs to the account service alone");
        assertFalse(DeadLetterMetadata.class.getPackageName().startsWith("com.carddemo.events"),
                "a shared event contract would sit under com.carddemo.events, and this "
                        + "record does not");
        assertFalse(DeadLetterMetadata.class.getPackageName().startsWith("com.carddemo.cobol"),
                "a shared compatibility type would sit under com.carddemo.cobol, and this "
                        + "record does not");

        Annotation[] annotations = DeadLetterMetadata.class.getAnnotations();
        assertEquals(0, annotations.length,
                "DeadLetterMetadata carries no annotation, so no framework publishes it as "
                        + "a shared contract");
    }

    /**
     * Asserts the over-length policy of this record, and asserts it is truncation and not
     * rejection. Building the record must not raise a failure of its own: the message it
     * describes has already failed, and a second failure here would suppress the dead-letter
     * message and leave the broker redelivering the same message for ever.
     */
    @Test
    void theOverLengthPolicyIsTruncationAndNotRejection() {
        String tooLong = "E".repeat(CULPRIT_WIDTH + 1);

        DeadLetterMetadata truncated = assertDoesNotThrow(
                () -> DeadLetterMetadata.of("", tooLong, "", ""),
                "building the record raised a failure of its own");
        assertEquals(CULPRIT_WIDTH, truncated.culprit().length(),
                "the culprit holds its leading " + CULPRIT_WIDTH + " characters");
        assertEquals(tooLong.substring(0, CULPRIT_WIDTH), truncated.culprit(),
                "the culprit keeps the leading characters of the value supplied");
        assertNotEquals(tooLong, truncated.culprit(),
                "the culprit differs from the value supplied, so the record truncated it");
        assertEquals(List.of("culprit"), truncated.truncatedComponents(),
                "the truncation list names the culprit and no other component");

        DeadLetterMetadata atMaximum =
                DeadLetterMetadata.of("", tooLong.substring(0, CULPRIT_WIDTH), "", "");
        assertEquals(truncated.culprit(), atMaximum.culprit(),
                "the value already cut to width " + CULPRIT_WIDTH + " reaches the component "
                        + "unchanged, which shows the truncation is about length alone");
        assertTrue(atMaximum.truncatedComponents().isEmpty(),
                "a value of exactly the maximum length is not recorded as shortened");
    }

    // Construction helpers. Each places one value in one component and leaves the other
    // three empty.

    /**
     * Builds a record holding {@code value} in the named component.
     *
     * @param component one of abendCode, culprit, reason or message
     * @param value     the text that component holds
     * @return the record built
     * @throws AssertionError when the component name is unknown
     */
    private static DeadLetterMetadata constructWith(String component, String value) {
        return switch (component) {
            case "abendCode" -> DeadLetterMetadata.of(value, "", "", "");
            case "culprit" -> DeadLetterMetadata.of("", value, "", "");
            case "reason" -> DeadLetterMetadata.of("", "", value, "");
            case "message" -> DeadLetterMetadata.of("", "", "", value);
            default -> throw new AssertionError(
                    "DeadLetterMetadata declares no component named " + component);
        };
    }

    /**
     * Returns the text the named component holds after construction with {@code value}.
     *
     * @param component one of abendCode, culprit, reason or message
     * @param value     the text that component holds
     * @return the text read back from that component
     * @throws AssertionError when the component name is unknown
     */
    private static String componentOf(String component, String value) {
        DeadLetterMetadata metadata = constructWith(component, value);
        return switch (component) {
            case "abendCode" -> metadata.abendCode();
            case "culprit" -> metadata.culprit();
            case "reason" -> metadata.reason();
            case "message" -> metadata.message();
            default -> throw new AssertionError(
                    "DeadLetterMetadata declares no component named " + component);
        };
    }
}
