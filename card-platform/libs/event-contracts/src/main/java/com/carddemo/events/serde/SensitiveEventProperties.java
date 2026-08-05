package com.carddemo.events.serde;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Names the properties no event may carry, and finds the first one a serialized event names.
 *
 * <p>No COBOL program and no copybook defines this class.
 *
 * <p>Every schema document closes its top-level property set with
 * {@code "additionalProperties": false} and a {@code maxProperties} ceiling. Each one carries a
 * bounded {@code extensions} object for the values a later version adds. A consumer validating
 * against version one accepts an event carrying a new {@code extensions} member, so a new field
 * ships without breaking that consumer. The member names {@code extensions} admits are
 * {@code ^[a-zA-Z][a-zA-Z0-9_]{0,39}$}, which permits an underscore, so a sensitive name spelled
 * {@code card_number} satisfies the document as readily as {@code cardNumber}. This class refuses
 * both spellings and lets every other added member pass.
 *
 * <p>Four source fields drive the list. The card verification value is
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, stored in the clear by the
 * source and emitted by no event. The full card number is
 * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, which the authorization decision
 * reads at {@code app/cbl/CBTRN02C.cbl:L382-L383} and no event carries. The signon password is
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21}, compared in the clear at
 * {@code app/cbl/COSGN00C.cbl:L223}. The customer social security number is
 * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17}.
 *
 * <p>The lists cover those four values, their known aliases, and two values the source holds
 * nowhere: a personal identification number and a Primary Account Number written under a shorter
 * name. They cover no key, no token and no secret. A search of {@code app/cpy/}, {@code app/cbl/},
 * {@code app/jcl/} and {@code app/csd/CARDDEMO.CSD} for those three words returns one hit,
 * {@code FEEDBACK-TOKEN-VALUE} at {@code app/cbl/CSUTLDTC.cbl:L61}, which holds a date-validation
 * return code. A property naming a tokenized card reference is a legitimate name here: the masked
 * card number is the tokenized form every card-carrying document declares.
 *
 * <p>A status flag is not on the list. {@code CARD-ACTIVE-STATUS PIC X(01)} at
 * {@code app/cpy/CVACT02Y.cpy:L10} and {@code ACCT-ACTIVE-STATUS PIC X(01)} at
 * {@code app/cpy/CVACT01Y.cpy:L6} reach no transaction event, and
 * {@code schemas/account-state-changed-v1.json} and the two governed card-update schemas
 * declare {@code activeStatus} as a property of their own. The five transaction and fraud documents
 * are held clear of it by {@code SchemaBackwardCompatibilityTest}, which reads their declared and
 * undeclared field names document by document.
 *
 * <p>Matching is by property name and never by value. A transaction identifier holds sixteen digits
 * at {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and an account identifier holds
 * eleven at {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, so a scan for a long
 * run of digits would refuse two properties every event carries.
 *
 * <p>{@link #APPROVED_CARD_PROPERTIES} holds the one card-number property an event may name.
 * {@code maskedCardNumber} carries twelve mask characters and the last four digits of the card
 * number, and the two state-change documents declare it alongside the three transaction documents.
 * Every other property naming a card number is refused, so {@code cardNumber} and {@code pan} are
 * both out.
 *
 * <p>Two runtime call sites read this class. {@link JsonSchemaValidatingSerializer} reads it before
 * it returns bytes, and {@link JsonSchemaValidatingDeserializer} reads it before it builds a record.
 * The consume side runs the same two screens for a reason the schema cannot cover: a document closes
 * its top-level property set, so an undeclared property is refused on the schema alone, but the
 * {@code extensions} object is open by design and a free-text property is constrained by length
 * rather than by shape. A record either screen refuses reaches the dead-letter route rather than a
 * consumer. An instance is never created, and no method holds state, so any number of producer and
 * consumer threads may call in at once.
 *
 * <p>Versions: Java 25 and {@code jackson-databind 3.1.4}. A module descriptor that omits
 * {@code <java.version>25</java.version>} compiles at release 17 with no warning.
 */
public final class SensitiveEventProperties {

    /**
     * Removes every character a folded name may not hold, which is every character outside
     * {@code a} to {@code z} and {@code 0} to {@code 9}.
     *
     * <p>{@link #isForbidden} applies this after folding to lower case, so one entry in the lists
     * below covers every spelling of a name. {@code cardNumber}, {@code card_number},
     * {@code CARD_NUMBER} and {@code card-number} all fold to {@code cardnumber}.
     *
     * <p>The pattern is compiled once. A compiled pattern is safe for concurrent use, and the
     * publish path pays no compilation cost per property.
     */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");

    /**
     * The one card-number property an event may name, in the folded form the scan compares.
     *
     * <p>{@code maskedCardNumber} is declared by
     * {@code schemas/transaction-authorized-v1.json}, {@code schemas/transaction-declined-v1.json},
     * {@code schemas/transaction-posted-v1.json}, {@code schemas/card-updated-v1.json} and
     * {@code schemas/card-updated-v2.json}. Its
     * separator spellings fold to the same entry and are spared with it.
     */
    public static final Set<String> APPROVED_CARD_PROPERTIES = Set.of("maskedcardnumber");

    /**
     * Name fragments that refuse a property outright, in the folded form the scan compares.
     *
     * <p>Each entry names a value the source holds and no event carries. A fragment catches a
     * spelling the list did not anticipate, so {@code cvvCode}, {@code cardCvv} and
     * {@code card_cvv} are all refused by the first entry.
     */
    public static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "cvv",
            "cardverification",
            "verificationvalue",
            "securitycode",
            "password",
            "passwd",
            "socialsecurity",
            "ssn");

    /**
     * Whole names that refuse a property, in the folded form the scan compares.
     *
     * <p>Each entry is short enough that a fragment match would refuse an innocent name. The word
     * {@code pin} sits inside {@code shipping}, and the word {@code pan} sits inside
     * {@code expanded}, so both are compared whole. A separator spelling such as {@code p_a_n}
     * folds to an entry and is refused with it.
     */
    public static final Set<String> FORBIDDEN_WHOLE_NAMES = Set.of(
            "pan",
            "fullpan",
            "panvalue",
            "pin",
            "pinblock",
            "pinoffset");

    /**
     * Name fragments that refuse a property unless its whole name sits in
     * {@link #APPROVED_CARD_PROPERTIES}.
     *
     * <p>{@code maskedCardNumber} holds the fragment {@code cardnumber} and is approved.
     * {@code cardNumber} holds the same fragment and is not.
     */
    public static final List<String> GUARDED_NAME_FRAGMENTS = List.of(
            "cardnumber",
            "primaryaccountnumber",
            "embosseddigits");

    /**
     * Properties whose value is free text a caller supplies, in the folded form the scan compares.
     *
     * <p>{@link #firstSensitiveValue(JsonNode)} screens the value of each entry, and of every value
     * nested under {@link #EXTENSION_PROPERTY}, because those are the only places a caller may write
     * arbitrary characters. Every other property is either an identifier, an amount or a timestamp,
     * and its document constrains the value by pattern.
     *
     * <p>The distinction is not stylistic. A legitimate CardDemo transaction identifier holds
     * sixteen digits, {@code 0000000000683580} for one, and so does a Primary Account Number (PAN).
     * Screening every property for a long digit run would refuse valid traffic, so the digit screen
     * runs where a caller is free and the pattern is not.
     */
    public static final Set<String> FREE_TEXT_PROPERTIES = Set.of(
            "description",
            "declinereasondescription",
            "merchantname",
            "merchantcity",
            "merchantzip",
            "source",
            "triggeredrules",
            "reason",
            "message",
            "culprit");

    /** The one property whose whole subtree is caller-supplied, in folded form. */
    public static final String EXTENSION_PROPERTY = "extensions";

    /**
     * Shortest run of digits a screened value may not hold: twelve.
     *
     * <p>The shortest payment card number in circulation holds twelve digits, so a shorter run
     * carries no card number. {@code app/cpy/CVACT02Y.cpy:L5} declares
     * {@code CARD-NUM PIC X(16)}, and sixteen is what this platform stores.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /**
     * Characters a screened value is stripped of before the digit run is sought: the space and the
     * hyphen.
     *
     * <p>{@code 4111 1111 1111 1111} and {@code 4111-1111-1111-1111} are how a card number is
     * written for a human reader, and both hold a sixteen-digit run once these characters go. The
     * decimal point is deliberately kept, so a monetary amount inside a description stays two runs
     * rather than becoming one.
     */
    private static final Pattern CARD_NUMBER_GROUPING = Pattern.compile("[ \\-]");

    /**
     * Shape of a United States government identifier: three digits, two digits, then four, in
     * groups separated by a space or a hyphen.
     *
     * <p>{@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L20} holds the same nine digits
     * unseparated, and the unseparated form is not sought here: nine digits is a plausible merchant
     * identifier and refusing it would refuse valid traffic. The separated form is not.
     */
    private static final Pattern GOVERNMENT_IDENTIFIER =
            Pattern.compile("(?<![0-9])[0-9]{3}[ \\-][0-9]{2}[ \\-][0-9]{4}(?![0-9])");

    /** No instance is created. */
    private SensitiveEventProperties() {
    }

    /**
     * The first property of a serialized event that no event may carry, searched depth first.
     *
     * <p>The search reads property names only. It descends into every nested object and every array
     * element, so a property inside a nested structure is found as readily as one at the top level.
     *
     * @param event the serialized event, as a JSON tree; a {@code null} tree carries nothing
     * @return the property name exactly as the event spells it, or {@code null} when the event
     *         carries no forbidden property
     */
    public static String firstForbiddenProperty(JsonNode event) {
        if (event == null) {
            return null;
        }

        if (event.isObject()) {
            for (Map.Entry<String, JsonNode> property : event.properties()) {
                if (isForbidden(property.getKey())) {
                    return property.getKey();
                }
                String nested = firstForbiddenProperty(property.getValue());
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (event.isArray()) {
            for (JsonNode element : event) {
                String nested = firstForbiddenProperty(element);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Whether one property name is a name no event may carry.
     *
     * <p>The comparison folds the name to lower case in {@link Locale#ROOT}, so the verdict does not
     * change with the default locale of the host. It then drops every character outside {@code a} to
     * {@code z} and {@code 0} to {@code 9} through {@link #NON_ALPHANUMERIC}, so one spelling of a
     * name carries the verdict for all of them. {@code card_number}, {@code CARD_NUMBER},
     * {@code card-number}, {@code card_verification_value}, {@code social_security_number},
     * {@code primary_account_number}, {@code p_a_n} and {@code c_v_v} are refused with the
     * camel-case spellings they match.
     *
     * <p>The fold spares every legitimate name. {@code maskedCardNumber},
     * {@code masked_card_number} and {@code MASKED_CARD_NUMBER} all reach
     * {@link #APPROVED_CARD_PROPERTIES} as one entry.
     *
     * @param propertyName the property name to judge; a {@code null} name is not forbidden
     * @return {@code true} when the folded name holds a forbidden fragment, equals a forbidden whole
     *         name, or holds a guarded fragment without being an approved card property
     */
    public static boolean isForbidden(String propertyName) {
        if (propertyName == null) {
            return false;
        }

        String folded =
                NON_ALPHANUMERIC.matcher(propertyName.toLowerCase(Locale.ROOT)).replaceAll("");
        for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
            if (folded.contains(fragment)) {
                return true;
            }
        }
        if (FORBIDDEN_WHOLE_NAMES.contains(folded)) {
            return true;
        }
        if (APPROVED_CARD_PROPERTIES.contains(folded)) {
            return false;
        }
        for (String fragment : GUARDED_NAME_FRAGMENTS) {
            if (folded.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first free-text property of a serialized event whose value carries a card number or a
     * separated government identifier, searched depth first.
     *
     * <p>A property qualifies for the screen when its folded name sits in
     * {@link #FREE_TEXT_PROPERTIES}, or when it sits anywhere under
     * {@link #EXTENSION_PROPERTY}. Those are the values a caller writes without a pattern to hold
     * them, and they are where a card number travels when a caller puts one there.
     *
     * <p>The screen reads a value in two forms. The text as written is sought for a run of
     * {@code [0-9]} twelve characters or longer, and the text with grouping characters removed is
     * sought for the same run, so {@code 4111 1111 1111 1111} is caught with
     * {@code 4111111111111111}. The text as written is also sought for the three-two-four shape of a
     * United States government identifier.
     *
     * @param event the serialized event, as a JavaScript Object Notation (JSON) tree; a {@code null}
     *              tree carries nothing
     * @return the name of the offending property, exactly as the event spells it, or {@code null}
     *         when no screened value carries either shape. The value itself never reaches the
     *         return, so a caller may put the name in a message
     */
    public static String firstSensitiveValue(JsonNode event) {
        return firstSensitiveValue(event, false);
    }

    /**
     * Walks one node, screening a value when the node itself is screened or the property that
     * carries it is.
     *
     * @param node     the node to walk; a {@code null} node carries nothing
     * @param screened whether every value below this node is screened, which
     *                 {@link #EXTENSION_PROPERTY} sets for its whole subtree
     * @return the offending property name, or {@code null}
     */
    private static String firstSensitiveValue(JsonNode node, boolean screened) {
        if (node == null) {
            return null;
        }

        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String folded = fold(property.getKey());
                boolean screenBelow = screened || EXTENSION_PROPERTY.equals(folded)
                        || FREE_TEXT_PROPERTIES.contains(folded);
                if (screenBelow && carriesSensitiveText(property.getValue())) {
                    return property.getKey();
                }
                String nested = firstSensitiveValue(property.getValue(), screenBelow);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (node.isArray()) {
            for (JsonNode element : node) {
                if (screened && carriesSensitiveText(element)) {
                    return EXTENSION_PROPERTY;
                }
                String nested = firstSensitiveValue(element, screened);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * Whether one node is textual and its text carries a card number or a separated government
     * identifier. Only the node itself is read, and no child of it.
     *
     * @param value the node to read
     * @return {@code true} when the text carries either shape
     */
    private static boolean carriesSensitiveText(JsonNode value) {
        if (value == null || !value.isString()) {
            return false;
        }

        String text = value.stringValue();
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (LONG_DIGIT_RUN.matcher(text).find()
                || GOVERNMENT_IDENTIFIER.matcher(text).find()) {
            return true;
        }
        return LONG_DIGIT_RUN.matcher(CARD_NUMBER_GROUPING.matcher(text).replaceAll("")).find();
    }

    /**
     * Folds one property name the way {@link #isForbidden(String)} folds it: to lower case in
     * {@link Locale#ROOT}, then with every character outside {@code a} to {@code z} and {@code 0} to
     * {@code 9} removed.
     *
     * @param propertyName the name to fold
     * @return the folded name
     */
    private static String fold(String propertyName) {
        return NON_ALPHANUMERIC.matcher(propertyName.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
