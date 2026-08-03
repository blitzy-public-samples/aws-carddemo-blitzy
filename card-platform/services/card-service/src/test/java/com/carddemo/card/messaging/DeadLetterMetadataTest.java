package com.carddemo.card.messaging;

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
 * Tests for the card service's own {@link DeadLetterMetadata}.
 *
 * <p>The four text components reproduce {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21}, field for field, and a fifth component names every one of
 * them the record shortened. The widths are typed here from the copybook rather than read from
 * the production constants:
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. The four
 * fields occupy 134 bytes, and each carries {@code VALUE SPACES}.</p>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl:L1020} moves the eight-character program name
 * {@code LIT-THISPGM}, declared at line 219, into {@code ABEND-CULPRIT}. Line 1021 moves a
 * four-character code, line 1022 moves {@code SPACES} into {@code ABEND-REASON}, and line
 * 1534 substitutes {@code UNEXPECTED ABEND OCCURRED.} for an {@code ABEND-MSG} holding low
 * values. Two tests below hold those values against the component widths, and hold that the
 * constructor substitutes nothing.</p>
 *
 * <p>Building the record raises nothing. It describes a failure that already happened, so a
 * second failure raised while building it would suppress the dead-letter message and leave the
 * broker redelivering the same message for ever. An over-length component therefore keeps its
 * leading characters and {@code truncatedComponents} names it.</p>
 *
 * <p>This record is service-local. Five services each declare their own record of the same
 * name. Two tests below hold the over-length policy and hold the record inside this service's
 * own package, so a move into a shared library fails here rather than in production.</p>
 *
 * <p>No component carries a card number, masked or unmasked, and none carries a card
 * verification value. One test holds that the rendered record introduces no value of its
 * own, so nothing numeric reaches an operator that a caller did not supply.</p>
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
     * Program name {@code LIT-THISPGM}, declared {@code PIC X(8)} at
     * {@code app/cbl/COCRDUPC.cbl:L219} and moved into {@code ABEND-CULPRIT} at line 1020.
     */
    private static final String SOURCE_PROGRAM_NAME = "COCRDUPC";

    /** Code {@code app/cbl/COCRDUPC.cbl:L1021} moves into {@code ABEND-CODE}. */
    private static final String SOURCE_ABEND_CODE = "0001";

    /**
     * Text {@code app/cbl/COCRDUPC.cbl:L1534} substitutes for an {@code ABEND-MSG} holding
     * low values.
     */
    private static final String SOURCE_DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /** Package this record belongs to. A shared contract would sit elsewhere. */
    private static final String OWNING_PACKAGE = "com.carddemo.card.messaging";

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
     * and that the four widths sum to the 134 bytes of {@code 01 ABEND-DATA}.
     */
    @Test
    void eachPublishedMaximumEqualsItsCopybookFieldWidth() {
        assertEquals(ABEND_CODE_WIDTH, DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                "ABEND-CODE PIC X(4) at app/cpy/CSMSG02Y.cpy:L22 is 4 characters wide");
        assertEquals(CULPRIT_WIDTH, DeadLetterMetadata.CULPRIT_MAX_LENGTH,
                "ABEND-CULPRIT PIC X(8) at app/cpy/CSMSG02Y.cpy:L24 is 8 characters wide");
        assertEquals(REASON_WIDTH, DeadLetterMetadata.REASON_MAX_LENGTH,
                "ABEND-REASON PIC X(50) at app/cpy/CSMSG02Y.cpy:L26 is 50 characters wide");
        assertEquals(MESSAGE_WIDTH, DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                "ABEND-MSG PIC X(72) at app/cpy/CSMSG02Y.cpy:L28 is 72 characters wide");
        assertEquals(ABEND_DATA_WIDTH,
                ABEND_CODE_WIDTH + CULPRIT_WIDTH + REASON_WIDTH + MESSAGE_WIDTH,
                "the four fields of app/cpy/CSMSG02Y.cpy:L21 occupy 134 bytes");
    }

    /**
     * Asserts that a value of exactly the maximum length reaches the component unchanged.
     * The copybook widths are maxima here, and the boundary value is accepted.
     */
    @Test
    void aValueOfExactlyTheMaximumLengthIsAccepted() {
        String code = "A".repeat(ABEND_CODE_WIDTH);
        String culprit = "B".repeat(CULPRIT_WIDTH);
        String reason = "C".repeat(REASON_WIDTH);
        String message = "D".repeat(MESSAGE_WIDTH);

        DeadLetterMetadata metadata = DeadLetterMetadata.of(code, culprit, reason, message);

        assertEquals(code, metadata.abendCode(),
                "a value of width " + ABEND_CODE_WIDTH + " reaches abendCode unchanged");
        assertEquals(culprit, metadata.culprit(),
                "a value of width " + CULPRIT_WIDTH + " reaches culprit unchanged");
        assertEquals(reason, metadata.reason(),
                "a value of width " + REASON_WIDTH + " reaches reason unchanged");
        assertEquals(message, metadata.message(),
                "a value of width " + MESSAGE_WIDTH + " reaches message unchanged");
        assertTrue(metadata.truncatedComponents().isEmpty(),
                "a value of exactly the maximum length is not recorded as shortened");
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
            assertEquals(List.of(component), metadata.truncatedComponents(),
                    "the truncation list names " + component + " and no other component");
            assertEquals(width, lengthOfLongestComponent(metadata),
                    component + " keeps its leading " + width + " characters");
        }
    }

    /** Returns the length of the longest of the four text components of {@code metadata}. */
    private static int lengthOfLongestComponent(DeadLetterMetadata metadata) {
        return Math.max(
                Math.max(metadata.abendCode().length(), metadata.culprit().length()),
                Math.max(metadata.reason().length(), metadata.message().length()));
    }

    /**
     * Asserts that a Java class name longer than the culprit width is cut back to it. The
     * source fills {@code ABEND-CULPRIT} from an eight-character program name at
     * {@code app/cbl/COCRDUPC.cbl:L1020}, and a class name of this service runs longer, so
     * {@code fromFailure} cannot pass one through whole.
     */
    @Test
    void aJavaClassNameIsCutBackToTheCulpritWidth() {
        String className = DeadLetterMetadata.class.getSimpleName();
        assertTrue(className.length() > CULPRIT_WIDTH,
                "a Java class name of this service runs past the culprit width of "
                        + CULPRIT_WIDTH);

        DeadLetterMetadata metadata = assertDoesNotThrow(
                () -> DeadLetterMetadata.of(SOURCE_ABEND_CODE, className, "", ""),
                "building the record raised a failure of its own");

        assertEquals(className.substring(0, CULPRIT_WIDTH), metadata.culprit(),
                "app/cbl/COCRDUPC.cbl:L1020 supplies an eight-character program name, so a "
                        + "longer class name keeps only its leading " + CULPRIT_WIDTH
                        + " characters");
        assertEquals(List.of("culprit"), metadata.truncatedComponents(),
                "the truncation list names the culprit and no other component");
    }

    /**
     * Asserts that the constructor pads nothing and trims nothing. A space is printable
     * ASCII, so leading and trailing spaces survive, and a short value keeps its length rather
     * than being padded out to the copybook width.
     */
    @Test
    void nothingIsPaddedOrTrimmed() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(" 09 ", " relay  ",
                "  outbox row will not serialize  ", "  broker refused the row  ");

        assertEquals(" 09 ", metadata.abendCode(),
                "abendCode keeps its leading and trailing spaces");
        assertEquals(" relay  ", metadata.culprit(),
                "culprit keeps its leading and trailing spaces");
        assertEquals("  outbox row will not serialize  ", metadata.reason(),
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
        assertEquals("", DeadLetterMetadata.of(null, "relay", "reason", "message").abendCode(),
                "a null abendCode becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L23");
        assertEquals("", DeadLetterMetadata.of("0902", null, "reason", "message").culprit(),
                "a null culprit becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L25");
        assertEquals("", DeadLetterMetadata.of("0902", "relay", null, "message").reason(),
                "a null reason becomes the empty string, matching SPACES at "
                        + "app/cbl/COCRDUPC.cbl:L1022");
        assertEquals("", DeadLetterMetadata.of("0902", "relay", "reason", null).message(),
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
     * Asserts the published default text against {@code app/cbl/COCRDUPC.cbl:L1534}, and
     * asserts the constructor never substitutes it. A caller that wants the source behaviour
     * passes the constant, and the record stores it unchanged.
     */
    @Test
    void theDefaultMessageIsPublishedAndNeverSubstituted() {
        assertEquals(SOURCE_DEFAULT_MESSAGE, DeadLetterMetadata.DEFAULT_MESSAGE,
                "app/cbl/COCRDUPC.cbl:L1534 reads: " + SOURCE_DEFAULT_MESSAGE);
        assertTrue(DeadLetterMetadata.DEFAULT_MESSAGE.length() <= MESSAGE_WIDTH,
                "the default text holds " + DeadLetterMetadata.DEFAULT_MESSAGE.length()
                        + " characters and fits ABEND-MSG PIC X(72)");

        DeadLetterMetadata passed = DeadLetterMetadata.of(SOURCE_ABEND_CODE,
                SOURCE_PROGRAM_NAME, "", DeadLetterMetadata.DEFAULT_MESSAGE);
        assertEquals(DeadLetterMetadata.DEFAULT_MESSAGE, passed.message(),
                "a caller that passes the default text gets it back unchanged");

        assertEquals("", DeadLetterMetadata.of(SOURCE_ABEND_CODE, SOURCE_PROGRAM_NAME,
                        "", null).message(),
                "app/cbl/COCRDUPC.cbl:L1533 substitutes inside the abend routine, and this "
                        + "record substitutes nothing, so a null message stays empty");
        assertEquals("", DeadLetterMetadata.of(SOURCE_ABEND_CODE, SOURCE_PROGRAM_NAME,
                        "", "").message(),
                "an empty message stays empty and receives no substitution");
    }

    /**
     * Asserts the two source values fit their components. The program name at
     * {@code app/cbl/COCRDUPC.cbl:L219} is eight characters, exactly the culprit width, and
     * the code at line 1021 is four characters, exactly the abend code width.
     */
    @Test
    void theSourceProgramNameAndCodeFitTheirComponents() {
        assertEquals(CULPRIT_WIDTH, SOURCE_PROGRAM_NAME.length(),
                "LIT-THISPGM at app/cbl/COCRDUPC.cbl:L219 is eight characters, matching "
                        + "ABEND-CULPRIT PIC X(8)");
        assertEquals(ABEND_CODE_WIDTH, SOURCE_ABEND_CODE.length(),
                "the code at app/cbl/COCRDUPC.cbl:L1021 is four characters, matching "
                        + "ABEND-CODE PIC X(4)");

        DeadLetterMetadata metadata = DeadLetterMetadata.of(SOURCE_ABEND_CODE,
                SOURCE_PROGRAM_NAME, "", "");
        assertEquals(SOURCE_ABEND_CODE, metadata.abendCode(),
                "the code keeps its leading zeroes, because ABEND-CODE is alphanumeric");
        assertEquals(SOURCE_PROGRAM_NAME, metadata.culprit(),
                "the program name reaches culprit unchanged");
    }

    /**
     * Asserts that equality covers all four components. Four records each differ from the
     * reference in one component alone, so a component dropped from equality fails here.
     */
    @Test
    void equalityCoversAllFourComponents() {
        DeadLetterMetadata reference =
                DeadLetterMetadata.of("0902", "relay", "publish refused", "broker unreachable");
        DeadLetterMetadata same =
                DeadLetterMetadata.of("0902", "relay", "publish refused", "broker unreachable");

        assertEquals(reference, same, "two records of equal components are equal");
        assertEquals(reference.hashCode(), same.hashCode(),
                "two records of equal components share a hash code");

        assertNotEquals(reference,
                DeadLetterMetadata.of("0903", "relay", "publish refused", "broker unreachable"),
                "a different abendCode makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "outbox", "publish refused", "broker unreachable"),
                "a different culprit makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "relay", "publish failed", "broker unreachable"),
                "a different reason makes the records unequal");
        assertNotEquals(reference,
                DeadLetterMetadata.of("0902", "relay", "publish refused", "broker refused"),
                "a different message makes the records unequal");
    }

    /**
     * Asserts that the rendered record adds nothing of its own. Every component holds text
     * with no digit, so a rendered form holding a digit would come from the record itself,
     * from a length, a hash or an identity. A card number and a card verification value are
     * both wholly numeric, so this property is what keeps either out of an operator's view
     * once a caller has kept it out of the components.
     */
    @Test
    void theRenderedRecordAddsNoValueOfItsOwn() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("ABCD", "relay",
                "the field at pointer slash maskedCardNumber failed",
                "the broker closed the connection");
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
     * Asserts that this record stays inside the card service. A shared, interoperable
     * dead-letter payload would sit in {@code com.carddemo.events} alongside the event
     * contracts, and the widths and the over-length policy would then bind every service.
     * Neither holds: the record is service-local and carries no framework annotation that
     * would publish it.
     */
    @Test
    void theRecordIsServiceLocalAndNotASharedContract() {
        assertEquals(OWNING_PACKAGE, DeadLetterMetadata.class.getPackageName(),
                "DeadLetterMetadata belongs to the card service alone");
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

    /**
     * Builds a record holding {@code value} in the named component and the empty string in
     * the other three.
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
}
