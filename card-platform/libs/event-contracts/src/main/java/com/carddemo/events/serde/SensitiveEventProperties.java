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
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook defines this class. The reasoning behind
 * the choices it implements sits in {@code card-platform/docs/decision-log.md} (planned).
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
 * {@code schemas/account-state-changed-v1.json} and {@code schemas/card-updated-v1.json}
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
 * <p>{@link JsonSchemaValidatingSerializer} reads this class before it returns bytes, which is the
 * one runtime call site. The consume side needs no second call: every document closes its top-level
 * property set, so {@link JsonSchemaValidatingDeserializer} refuses an undeclared property on the
 * schema alone. An instance is never created, and no method holds state, so any number of producer
 * threads may call in at once.
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
     * {@code schemas/transaction-posted-v1.json} and {@code schemas/card-updated-v1.json}. Its
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
}
