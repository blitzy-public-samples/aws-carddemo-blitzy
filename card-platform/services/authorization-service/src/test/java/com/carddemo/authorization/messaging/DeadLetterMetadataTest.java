package com.carddemo.authorization.messaging;

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
 * Tests for the authorization service's own {@link DeadLetterMetadata}.
 *
 * <p>The four components reproduce {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21}, field for field. The widths are typed here from the
 * copybook rather than read from the production constants:
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28. The four
 * fields occupy 134 bytes. Each carries {@code VALUE SPACES} on the line below its
 * declaration.</p>
 *
 * <p>Building the record raises nothing. It describes a failure that already happened, so a
 * second failure raised while building it would suppress the dead-letter message and leave
 * the broker redelivering the same message for ever. An over-length component therefore keeps
 * its leading characters, a null component becomes the empty string, and
 * {@code truncatedComponents} names every component the record shortened.</p>
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

    /** Package this record belongs to. A shared contract would sit elsewhere. */
    private static final String OWNING_PACKAGE = "com.carddemo.authorization.messaging";

    /**
     * Asserts that the record declares four {@code String} components, named and ordered as
     * {@code 01 ABEND-DATA} declares its four fields at
     * {@code app/cpy/CSMSG02Y.cpy:L22-L28}, followed by the list of components the record
     * shortened. The fifth component has no copybook ancestor and reports the truncation the
     * copybook widths make possible.
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
     * The widths in the copybook are maxima here, and the boundary value is accepted.
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
    void aValueOnePastItsMaximumKeepsItsLeadingCharacters() {
        Map<String, String> overLength = new LinkedHashMap<>();
        overLength.put("abendCode", "A".repeat(ABEND_CODE_WIDTH + 1));
        overLength.put("culprit", "B".repeat(CULPRIT_WIDTH + 1));
        overLength.put("reason", "C".repeat(REASON_WIDTH + 1));
        overLength.put("message", "D".repeat(MESSAGE_WIDTH + 1));

        Map<String, DeadLetterMetadata> records = new LinkedHashMap<>();
        records.put("abendCode",
                DeadLetterMetadata.of(overLength.get("abendCode"), "", "", ""));
        records.put("culprit",
                DeadLetterMetadata.of("", overLength.get("culprit"), "", ""));
        records.put("reason",
                DeadLetterMetadata.of("", "", overLength.get("reason"), ""));
        records.put("message",
                DeadLetterMetadata.of("", "", "", overLength.get("message")));

        Map<String, Integer> maxima = new LinkedHashMap<>();
        maxima.put("abendCode", ABEND_CODE_WIDTH);
        maxima.put("culprit", CULPRIT_WIDTH);
        maxima.put("reason", REASON_WIDTH);
        maxima.put("message", MESSAGE_WIDTH);

        assertEquals(TEXT_COMPONENT_COUNT, records.size(),
                "one construction covers each of the four components");

        for (Map.Entry<String, DeadLetterMetadata> entry : records.entrySet()) {
            String component = entry.getKey();
            DeadLetterMetadata metadata = entry.getValue();
            int maximum = maxima.get(component);
            String kept = overLength.get(component).substring(0, maximum);

            assertEquals(List.of(component), metadata.truncatedComponents(),
                    "the truncation list names " + component + " and no other component");
            assertEquals(kept, componentOf(metadata, component),
                    component + " keeps its leading " + maximum + " characters");
        }
    }

    /** Returns the component of {@code metadata} that {@code component} names. */
    private static String componentOf(DeadLetterMetadata metadata, String component) {
        return switch (component) {
            case "abendCode" -> metadata.abendCode();
            case "culprit" -> metadata.culprit();
            case "reason" -> metadata.reason();
            case "message" -> metadata.message();
            default -> throw new IllegalArgumentException(component);
        };
    }

    /**
     * Asserts that the constructor pads nothing and trims nothing. A space is printable ASCII,
     * so leading and trailing spaces survive, and a short value keeps its length rather than
     * being padded out to the copybook width.
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
                "a null reason becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L27");
        assertEquals("", DeadLetterMetadata.of("0902", "relay", "reason", null).message(),
                "a null message becomes the empty string, matching VALUE SPACES at "
                        + "app/cpy/CSMSG02Y.cpy:L29");

        DeadLetterMetadata oneNull =
                DeadLetterMetadata.of("0902", null, "reason", "message");
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
     * from a length, a hash or an identity.
     */
    @Test
    void theRenderedRecordAddsNoValueOfItsOwn() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("ABCD", "relay",
                "outbox row refused by the broker", "the broker closed the connection");
        String rendered = metadata.toString();

        for (int index = 0; index < rendered.length(); index++) {
            char character = rendered.charAt(index);
            assertFalse(Character.isDigit(character),
                    "the rendered record holds a digit at position " + index
                            + ", and no component supplied one");
        }
        assertTrue(rendered.contains("abendCode"),
                "the rendered record names its components");
    }

    /**
     * Asserts that this record stays inside the authorization service. A shared,
     * interoperable dead-letter payload would sit in {@code com.carddemo.events} alongside
     * the event contracts, and the widths and the over-length policy would then bind every
     * service. Neither holds: the record is service-local and carries no framework
     * annotation that would publish it.
     */
    @Test
    void theRecordIsServiceLocalAndNotASharedContract() {
        assertEquals(OWNING_PACKAGE, DeadLetterMetadata.class.getPackageName(),
                "DeadLetterMetadata belongs to the authorization service alone");
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

        DeadLetterMetadata atMaximum = DeadLetterMetadata.of("",
                tooLong.substring(0, CULPRIT_WIDTH), "", "");
        assertEquals(truncated.culprit(), atMaximum.culprit(),
                "the value already cut to width " + CULPRIT_WIDTH + " reaches the component "
                        + "unchanged, which shows the truncation is about length alone");
        assertTrue(atMaximum.truncatedComponents().isEmpty(),
                "a value of exactly the maximum length is not recorded as shortened");
    }
}
