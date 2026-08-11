package com.carddemo.events.serde;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
 * {@code schemas/account-state-changed-v1.json} and the governed card-update schemas
 * declare {@code activeStatus} as a property of their own. The transaction and fraud documents
 * are held clear of it by {@code SchemaBackwardCompatibilityTest}, which reads their declared and
 * undeclared field names document by document.
 *
 * <p>Matching is by property name and never by value. A transaction identifier holds sixteen digits
 * at {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and an account identifier holds
 * eleven at {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}, so a scan for a long
 * run of digits would refuse two properties every event carries.
 *
 * <p>{@link #FREE_TEXT_PROPERTIES} is the exception, and its values are screened by shape as well as
 * by name. Four shapes are refused there: an unbroken run of twelve or more digits, that same run
 * once the space and the hyphen are removed, a card number grouped by a space, hyphen, point, slash
 * or underscore, and a card verification value that a label such as {@code CVV} or
 * {@code security code} introduces. The grouped shape is read rather than erased, so
 * {@code 4111.1111.1111.1111} is refused while the decimal amount {@code 1234567890.12} is not: a
 * card number is written in groups of at least three digits and an amount ends in a group of two.
 *
 * <p>{@link #NARRATIVE_PROPERTIES} is screened harder than that, and the difference is the point of
 * the split. A narrative property holds prose a caller wrote, so a value that is only three or four
 * digits carries no prose and is refused, and an unbroken run of nine or more digits is refused as
 * the unseparated form of {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L20}. Two
 * screened properties are deliberately NOT narrative: a postal code legitimately holds nine digits,
 * and the three dead-letter diagnostics are written by this platform rather than by a caller and a
 * refusal there would suppress the dead letter itself. That sub-section records each exclusion and
 * its reason, which is what makes the policy field-specific rather than uniform.
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
 * <p>Versions: Java 25 and {@code jackson-databind 3.1.5}, the version {@code card-platform/pom.xml}
 * pins above the one the imported bill of materials resolves. A module descriptor that omits
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
     * <p>{@code maskedCardNumber} is declared by every governed version of
     * {@code TransactionAuthorized}, {@code TransactionDeclined}, {@code TransactionPosted} and
     * {@code CardUpdated}; {@code SchemaBackwardCompatibilityTest} reads that set out of the schema
     * table rather than from a list here. Its separator spellings fold to the same entry and are
     * spared with it.
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
            "verificationcode",
            "securitycode",
            "cardsecurity",
            "password",
            "passwd",
            "passphrase",
            "passcode",
            "socialsecurity",
            "ssn");

    /**
     * Whole names that refuse a property, in the folded form the scan compares.
     *
     * <p>Each entry is short enough that a fragment match would refuse an innocent name. The word
     * {@code pin} sits inside {@code shipping}, and the word {@code pan} sits inside
     * {@code expanded}, so both are compared whole. A separator spelling such as {@code p_a_n}
     * folds to an entry and is refused with it.
     *
     * <p>The card-scheme abbreviations for a verification value are here rather than in
     * {@link #FORBIDDEN_NAME_FRAGMENTS} for the same reason: {@code cid} sits inside
     * {@code incidentId} and {@code csc} inside {@code cscReference}, so each is compared whole. A
     * producer that means a correlation identifier writes {@code correlationId}, which no entry
     * matches. The set covers the abbreviations the four card schemes use — {@code CVV} and
     * {@code CVV2}, {@code CVC} and {@code CVC2}, {@code CV2}, {@code CID}, {@code CSC},
     * {@code CVN}, {@code CVD}, {@code CAV2} and {@code CAVV} — because a name none of them
     * anticipated is exactly how three digits reach a topic. {@code cvv} and {@code cvv2} are
     * additionally caught as fragments.
     */
    public static final Set<String> FORBIDDEN_WHOLE_NAMES = Set.of(
            "pan",
            "fullpan",
            "panvalue",
            "pin",
            "pinblock",
            "pinoffset",
            "cvc",
            "cvc2",
            "cv2",
            "cid",
            "csc",
            "cvn",
            "cvd",
            "cav2",
            "cavv");

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
            "firstname",
            "middlename",
            "lastname",
            "addressline1",
            "addressline2",
            "addressline3",
            "zipcode",
            "reason",
            "message",
            "culprit");

    /**
     * Properties whose value is narrative text a caller writes, in the folded form the scan compares.
     *
     * <p>These take two screens the other screened properties do not, because they are the properties
     * a caller fills with prose and prose has no shape a pattern can pin. A narrative value that is
     * ONLY three or four digits carries no narrative at all, and three or four digits is the width of
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and of the four-digit schemes.
     * An unbroken run of nine or more digits inside one is the unseparated form of
     * {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L20}, which
     * {@link #GOVERNMENT_IDENTIFIER} cannot see because that pattern reads the separated form.
     *
     * <p>A security review found both gaps by writing a verification value into
     * {@code description} and an unseparated government identifier into {@code merchantName}, and
     * both reached a topic. {@link #NARRATIVE_BARE_CODE} and {@link #NARRATIVE_DIGIT_RUN} are what
     * refuse them now.
     *
     * <p>The seven cardholder properties are here because {@code CustomerContextChanged} carries the
     * ten fields {@code app/cbl/CBSTM03A.CBL:L458-L504} renders on a statement, and until this
     * review nothing screened them: a Primary Account Number written into an address line by an
     * account update travelled to the notification read model unexamined.
     *
     * <p>{@code merchantZip} and {@code zipCode} are screened but are NOT narrative.
     * {@code app/data/ASCII/dailytran.txt} holds five-digit and hyphenated nine-digit postal codes
     * and {@code app/data/ASCII/custdata.txt} holds both forms too, so a rule refusing a nine-digit
     * run would refuse valid traffic. They keep the card-number and separated-identifier screens,
     * which no postal code matches.
     *
     * <p>{@code reason}, {@code message} and {@code culprit} are screened and are not narrative
     * either, and the reason is availability rather than shape. Those three are the diagnostic
     * components of {@code app/cpy/CSMSG02Y.cpy:L21-L29} that a dead-letter envelope carries; this
     * platform fills them with a failure's class name and fixed wording and never with a caller's
     * value, and a refusal raised while writing a dead letter suppresses that dead letter and leaves
     * the broker redelivering the same record for ever. They keep the shape screens, which still
     * refuse a card number or a separated identifier.
     */
    public static final Set<String> NARRATIVE_PROPERTIES = Set.of(
            "description",
            "declinereasondescription",
            "merchantname",
            "merchantcity",
            "source",
            "triggeredrules",
            "firstname",
            "middlename",
            "lastname",
            "addressline1",
            "addressline2",
            "addressline3");

    /** The one property whose whole subtree is caller-supplied, in folded form. */
    public static final String EXTENSION_PROPERTY = "extensions";

    /**
     * Extension names that put a value in a card-code context, in the folded form the scan compares.
     *
     * <p>A verification value is three or four digits, and three or four digits carry no shape a
     * screen can recognise on their own: a merchant category code holds four and a transaction
     * category code holds four. {@link #LABELLED_SECURITY_CODE} therefore needs a label beside the
     * digits, and a property <em>name</em> is a label the value itself does not carry. Inside the
     * extensions object, where a caller chooses the names, a name carrying {@code code} is that
     * label: {@code authCode}, {@code cardCode} and {@code secCode} all fold to a name holding it,
     * and each refuses a bare three- or four-digit value.
     *
     * <p>This applies to extension members only, and it must. {@code declineReasonCode},
     * {@code merchantCategoryCode} and {@code transactionTypeCode} are declared properties whose
     * documents pin them to exactly that width, so a rule reading every property this way would
     * refuse every declined and every authorized event.
     */
    public static final List<String> CODE_CONTEXT_EXTENSION_FRAGMENTS = List.of("code");

    /**
     * Extension names that say the value is a credential, in the folded form the scan compares.
     *
     * <p>Any non-blank value under one of these is refused, whatever its shape, because the name
     * has already declared what it carries and no event on this platform carries a credential. A
     * password and a passphrase are refused by {@link #FORBIDDEN_NAME_FRAGMENTS} wherever they
     * appear; these names are refused inside the extensions object, where a caller invents them.
     */
    public static final List<String> CREDENTIAL_CONTEXT_EXTENSION_FRAGMENTS = List.of(
            "secret",
            "credential",
            "apikey",
            "accesskey",
            "privatekey",
            "sessionkey",
            "bearer",
            "token");

    /**
     * Shortest run of digits a screened value may not hold: twelve.
     *
     * <p>The shortest payment card number in circulation holds twelve digits, so a shorter run
     * carries no card number. {@code app/cpy/CVACT02Y.cpy:L5} declares
     * {@code CARD-NUM PIC X(16)}, and sixteen is what this platform stores.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /**
     * One chain of digit groups joined by punctuation, whatever that punctuation is.
     *
     * <p>A screened value is read chain by chain, and each chain's digits are counted with its
     * punctuation removed, so a card number written for a human reader is found however it is
     * punctuated: {@code 4111 1111 1111 1111}, {@code 4111-1111-1111-1111},
     * {@code 4111.1111.1111.1111}, {@code 4111/1111/1111/1111}, {@code 4111(1111)1111(1111)},
     * {@code 4111*1111*1111*1111} and any mixture of those all carry the same sixteen digits. A
     * named separator set cannot do this: free text admits every printable character, so a chain
     * joins on everything that is not a digit or a letter rather than on a list of the separators
     * anybody thought of.
     *
     * <p>A letter ends a chain, which is what keeps two unrelated numbers apart:
     * {@code REF 1234 SEQ 5678} is two chains of four digits, because the character before
     * {@code SEQ} is a space but the character after it is a letter. Joining across letters would
     * gather every digit in a sentence into one number and refuse ordinary text.
     *
     * <p>Counting the digits of a chain is not on its own enough to call it a card number, and
     * {@link #carriesCollapsedCardNumber(String)} is where the group widths decide that. Erasing the
     * punctuation and looking only for a long run would read the amount {@code 1234567890.12} as a
     * twelve-digit card.
     */
    private static final Pattern PUNCTUATED_DIGIT_CHAIN =
            Pattern.compile("(?<![0-9])[0-9]++(?:[^0-9A-Za-z]++[0-9]++)*+(?![0-9])");

    /** Matches every character of a chain that is not a digit. */
    private static final Pattern NON_DIGIT = Pattern.compile("[^0-9]++");

    /**
     * Longest run of digits a payment card number holds: nineteen.
     *
     * <p>ISO/IEC 7812 bounds a primary account number at nineteen digits, and
     * {@link #SHORTEST_CARD_NUMBER_DIGITS} bounds it below at twelve. The pair is what makes
     * {@link #SEPARATED_CARD_NUMBER} a card-number shape rather than a long-number shape.
     */
    private static final int LONGEST_CARD_NUMBER_DIGITS = 19;

    /** Shortest run of digits a payment card number holds: twelve. */
    private static final int SHORTEST_CARD_NUMBER_DIGITS = 12;

    /**
     * A card number written with a separator between digit groups, in any of the five separators a
     * reader uses.
     *
     * <p>This pattern admits one separator between two digits, and stripping more than
     * those two would merge a monetary amount into one run: {@code 1234567890.12} holds twelve
     * digits and is an amount, not a card. This pattern reads the grouping instead of erasing it, so
     * a dotted or slashed card number is caught without a decimal amount becoming one.
     *
     * <p>Each separator occurrence has to sit BETWEEN two digits and only one may sit between any
     * two, which is what keeps the two shapes apart. {@code 4111.1111.1111.1111} matches, and so do
     * {@code 4111/1111/1111/1111} and the mixed {@code 4111-1111.1111 1111}.
     * {@code 1234567890.12} does not, because a decimal amount reaching twelve digits still has to
     * pass the group test below: its digit groups are ten and two, and no card number in circulation
     * is written that way.
     *
     * <p>The three capturing-free groups are the leading digit, the eleven-to-eighteen
     * digit-and-optional-separator repeats and the trailing digit. Lookarounds at both ends refuse a
     * longer digit run, so a twenty-digit identifier is not read as a nineteen-digit card.
     */
    private static final Pattern SEPARATED_CARD_NUMBER = Pattern.compile(
            "(?<![0-9])[0-9](?:[ \\-./_]?[0-9]){"
                    + (SHORTEST_CARD_NUMBER_DIGITS - 1) + ","
                    + (LONGEST_CARD_NUMBER_DIGITS - 1) + "}(?![0-9])");

    /**
     * Digit-group sizes a separated candidate may hold to be read as a card number.
     *
     * <p>A card number is written in groups of four, or four then six then five for the fifteen-digit
     * schemes, or in one unbroken run. A decimal amount is written as one long group, a point, then
     * two digits, and a date as four, two and two. Requiring every group to hold at least three
     * digits separates the two: {@code 4111.1111.1111.1111} passes and {@code 1234567890.12} does
     * not, because its trailing group holds two.
     */
    private static final int SMALLEST_CARD_NUMBER_GROUP = 3;

    /**
     * A label naming a card verification value, followed by three or four digits.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} holds three digits, and
     * four-digit schemes exist, so both widths are sought. Three digits alone carry no meaning and
     * are not screened: a merchant category code holds four digits and a category code holds four,
     * so refusing every short run would refuse valid traffic. The label is what makes the run
     * sensitive, which is why the two are sought together.
     *
     * <p>{@link #FORBIDDEN_NAME_FRAGMENTS} refuses a PROPERTY named for a verification value. This
     * pattern refuses a verification value written inside free text, which no property name reveals:
     * {@code CVV 123}, {@code cvc: 4321} and {@code security code = 999} all match.
     */
    private static final Pattern LABELLED_SECURITY_CODE = Pattern.compile(
            "(?i)(?:cvv2?|cvc2?|cv2|cid|csc"
                    + "|card[ \\-_]?verification(?:[ \\-_]?(?:value|code|number))?"
                    + "|security[ \\-_]?code|card[ \\-_]?security[ \\-_]?code)"
                    + "[ \\-_:=.#]{0,4}[0-9]{3,4}(?![0-9])");

    /**
     * A run of exactly three or four digits, with no digit on either side of it.
     *
     * <p>The width of a card verification value: {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7} holds three, and four-digit schemes exist. The run carries no
     * meaning on its own, so it is only refused where the property name supplies the label —
     * {@link #namesWhatTheValueCarries(String, JsonNode)} is the one caller.
     */
    private static final Pattern SHORT_CODE_RUN =
            Pattern.compile("(?<![0-9])[0-9]{3,4}(?![0-9])");

    /**
     * A narrative value that is nothing but three or four digits, with only blank space around it.
     *
     * <p>The width of a card verification value, and the one place a bare short run means something:
     * a narrative property holds prose, and a value carrying no prose at all carries whatever the
     * caller put there instead. {@code description} reading {@code "123"} is the shape a security
     * review used to move a verification value past the screens.
     *
     * <p>The rule is deliberately the WHOLE value and not a run inside it. Ordinary narrative holds
     * short runs — {@code "Purchase at STORE 101"} and {@code "Pizza 4 U"} both do — and refusing
     * those would refuse valid traffic, which is how a screen gets switched off.
     *
     * <p>{@link #NARRATIVE_PROPERTIES} records which properties this applies to, and why the postal
     * codes and the dead-letter diagnostics are outside that set.
     */
    private static final Pattern NARRATIVE_BARE_CODE =
            Pattern.compile("\\s*[0-9]{3,4}\\s*");

    /**
     * An unbroken run of nine or more digits inside a narrative value.
     *
     * <p>{@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L20} holds nine digits with no
     * separator, and {@link #GOVERNMENT_IDENTIFIER} reads only the three-two-four separated form,
     * so the unseparated form travelled inside narrative text unexamined. Nine is also wide enough
     * to carry {@code ACCT-ID PIC 9(11)} and {@code CUST-ID PIC 9(09)}, which belong in their own
     * properties and not in prose.
     *
     * <p>{@link #LONG_DIGIT_RUN} refuses twelve or more everywhere a value is screened. This pattern
     * lowers the floor to nine for narrative properties alone, because the properties where a nine
     * to eleven digit run is legitimate — a postal code, a merchant identifier — are not narrative.
     * A monetary amount is not narrative either, and an amount reaching nine digits carries a
     * decimal point, so its digits are two runs and not one.
     */
    private static final Pattern NARRATIVE_DIGIT_RUN =
            Pattern.compile("(?<![0-9])[0-9]{9,}(?![0-9])");

    /**
     * Shape of a United States government identifier: three digits, two digits, then four, in
     * groups separated by punctuation.
     *
     * <p>{@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L20} holds the same nine digits
     * unseparated, and the unseparated form is not sought here: nine digits is a plausible merchant
     * identifier and refusing it would refuse valid traffic. The separated form is not, and the
     * separator is not restricted to the space and the hyphen a human usually types:
     * {@code 020.97.3888} and {@code 020/97/3888} name the same identifier as
     * {@code 020-97-3888}.
     *
     * <p>The group widths are what keep this pattern off ordinary data. A ten-character date such
     * as {@code 2022-06-10} cannot match, because the first group must be exactly three digits with
     * no digit before it and a separator after it. A telephone number such as
     * {@code 800-000-0000} cannot match either, because the middle group must be exactly two
     * digits.
     */
    private static final Pattern GOVERNMENT_IDENTIFIER = Pattern.compile(
            "(?<![0-9])[0-9]{3}[^0-9A-Za-z]{1,3}[0-9]{2}[^0-9A-Za-z]{1,3}[0-9]{4}(?![0-9])");

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

        String folded = fold(propertyName);
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
     * The first {@link #EXTENSION_PROPERTY} object one serialized event carries, searched depth first.
     *
     * <p>The PRODUCE side alone calls this. No record of this platform declares an extensions
     * property, so an event about to be stored or published that carries one carries a property its
     * own record cannot have written: something built the JSON by hand. Refusing it closes the one
     * open subtree of every document on the side where this platform is the author.
     *
     * <p>The consume side deliberately does not call it. Every schema document declares
     * {@code extensions} as the channel additive evolution travels through, and refusing it on read
     * would refuse the enriched record a later version publishes, which is the compatibility this
     * platform promises. {@link #firstSensitiveValue(JsonNode)} screens those values instead.
     *
     * @param event the serialized event, as a JSON tree; a {@code null} tree carries nothing
     * @return the property name exactly as the event spells it, or {@code null} when the event
     *         carries no extensions object. No value from the object reaches the return
     */
    public static String firstExtensionProperty(JsonNode event) {
        if (event == null) {
            return null;
        }

        if (event.isObject()) {
            for (Map.Entry<String, JsonNode> property : event.properties()) {
                if (EXTENSION_PROPERTY.equals(fold(property.getKey()))) {
                    return property.getKey();
                }
                String nested = firstExtensionProperty(property.getValue());
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (event.isArray()) {
            for (JsonNode element : event) {
                String nested = firstExtensionProperty(element);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * The first screened property of a serialized event whose value carries a card number, a
     * government identifier or a card verification code, searched depth first.
     *
     * <p>Two screens run, and which one a property takes is decided by its name.
     *
     * <ul>
     * <li>A property whose folded name sits in {@link #FREE_TEXT_PROPERTIES}, and every property
     * inside an {@link #EXTENSION_PROPERTY} object, takes the STRUCTURED screen: the value is sought
     * for a run of {@code [0-9]} twelve characters or longer, both as written and with grouping
     * characters removed so {@code 4111 1111 1111 1111} is caught with {@code 4111111111111111},
     * and for the three-two-four shape of a United States government identifier, and for a
     * verification code beside a word that names one.</li>
     * <li>A property whose folded name also sits in {@link #NARRATIVE_PROPERTIES} takes the
     * NARRATIVE screen, which is the structured screen plus two shapes that only a prose field can
     * carry: any unbroken run of nine digits or more, and a value that is nothing but three or four
     * digits. A postal code and a credit score keep the structured screen for exactly that reason,
     * because both are legitimately those shapes.</li>
     * </ul>
     *
     * @param event the serialized event, as a JavaScript Object Notation (JSON) tree; a {@code null}
     *              tree carries nothing
     * @return the name of the offending property, exactly as the event spells it, or {@code null}
     *         when no screened value carries any of those shapes. The value itself never reaches the
     *         return, so a caller may put the name in a message
     */
    public static String firstSensitiveValue(JsonNode event) {
        return firstSensitiveValue(event, Screen.NONE, false, EXTENSION_PROPERTY);
    }

    /**
     * Walks one node, screening a value when the node itself is screened, when the property that
     * carries it is, or when the property is an extension member whose own name says what it holds.
     *
     * @param node         the node to walk; a {@code null} node carries nothing
     * @param screen       which screen every value below this node takes, inherited from the
     *                     property that carries it
     * @param inExtensions whether this node is the extensions object or a node inside it, which is
     *                     where the property names are the caller's and the name screens apply to
     *                     the value
     * @param owner        the property name a failure reports, which stays the property that owns
     *                     an array rather than the index of an element inside it
     * @return the offending property name, or {@code null}
     */
    private static String firstSensitiveValue(JsonNode node, Screen screen, boolean inExtensions,
            String owner) {

        if (node == null) {
            return null;
        }

        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String folded = fold(property.getKey());
                boolean extensionsBelow = inExtensions || EXTENSION_PROPERTY.equals(folded);
                Screen screenBelow = screenFor(screen, folded, extensionsBelow);
                if (screenBelow != Screen.NONE
                        && carriesSensitiveText(property.getValue(), screenBelow)) {
                    return property.getKey();
                }
                if (inExtensions && namesWhatTheValueCarries(folded, property.getValue())) {
                    return property.getKey();
                }
                String nested = firstSensitiveValue(property.getValue(), screenBelow,
                        extensionsBelow, property.getKey());
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (node.isArray()) {
            for (JsonNode element : node) {
                // The name reported is the property that OWNS the array, not the array's own
                // position and not the subtree the screen was inherited from. A card number inside
                // triggeredRules used to be reported as "extensions", which named a property the
                // event did not carry and sent a reader looking in the wrong place.
                if (screen != Screen.NONE && carriesSensitiveText(element, screen)) {
                    return owner;
                }
                String nested = firstSensitiveValue(element, screen, inExtensions, owner);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /**
     * The screen that applies to one property's value, and to everything below it.
     *
     * <p>An inherited screen is never weakened. A value under {@link #EXTENSION_PROPERTY} takes the
     * structured screen and the two name-context screens of
     * {@link #namesWhatTheValueCarries(String, JsonNode)}, and not the narrative screen. The reason is
     * the contract rather than the risk: every schema document declares {@code extensions} as the
     * channel additive evolution travels through, and a nine-digit merchant reference or a
     * four-character settlement batch written there is ordinary traffic a consumer must keep reading.
     * The produce side closes the subtree instead — {@link #firstExtensionProperty(JsonNode)} refuses
     * an event carrying one at all, because no record of this platform declares the property — so the
     * open reading applies to a foreign producer's record and never to one of ours.
     *
     * @param inherited      the screen the parent node passed down
     * @param foldedName     the property's folded name
     * @param extensionsHere whether this property is the extensions object or sits inside it
     * @return the screen to apply to this property's value
     */
    private static Screen screenFor(Screen inherited, String foldedName, boolean extensionsHere) {
        if (NARRATIVE_PROPERTIES.contains(foldedName)) {
            return Screen.NARRATIVE;
        }
        if (extensionsHere || FREE_TEXT_PROPERTIES.contains(foldedName)) {
            return inherited == Screen.NONE ? Screen.STRUCTURED : inherited;
        }
        return inherited;
    }

    /**
     * How thoroughly one value is read.
     *
     * <p>{@link #NARRATIVE_PROPERTIES} states which properties take which, and why a postal code and
     * a dead-letter diagnostic take the lesser screen.
     */
    private enum Screen {

        /** The value is not screened: its document pins it to a shape a caller cannot fill. */
        NONE,

        /**
         * Card-number, separated-identifier and labelled-verification-value shapes only. For a
         * screened value whose legitimate content includes a nine-digit run, such as a postal code.
         */
        STRUCTURED,

        /**
         * Every {@link #STRUCTURED} shape, and additionally a value that is nothing but three or
         * four digits and a value holding an unbroken run of nine or more digits.
         */
        NARRATIVE
    }

    /**
     * Whether one extension member's own name says what its value carries.
     *
     * <p>Two screens, and the property name selects which one applies. A name in a card-code
     * context refuses a bare three- or four-digit value, which is the width of
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and of the four-digit
     * schemes. A name in a credential context refuses any non-blank value.
     *
     * <p>Neither screen reads the value into a message, and neither runs outside the extensions
     * object: {@link #CODE_CONTEXT_EXTENSION_FRAGMENTS} records why.
     *
     * @param foldedName the extension member's name, folded
     * @param value      the value that member carries
     * @return {@code true} when the name and the value together are refused
     */
    private static boolean namesWhatTheValueCarries(String foldedName, JsonNode value) {
        if (value == null || !value.isString()) {
            return false;
        }
        String text = value.stringValue();
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String fragment : CREDENTIAL_CONTEXT_EXTENSION_FRAGMENTS) {
            if (foldedName.contains(fragment)) {
                return true;
            }
        }
        for (String fragment : CODE_CONTEXT_EXTENSION_FRAGMENTS) {
            if (foldedName.contains(fragment)
                    && (SHORT_CODE_RUN.matcher(text).find()
                            || SHORT_CODE_RUN.matcher(
                                    Normalizer.normalize(text, Normalizer.Form.NFKC)).find())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one node is textual and its text carries a card number, a separated government
     * identifier or a labelled card verification value. Only the node itself is read, and no child
     * of it.
     *
     * <p>Four screens run. An unbroken long digit run is sought as written, a separated government
     * identifier and a labelled verification value are sought in the same pass, a card number
     * punctuated by anything at all is sought through
     * {@link #carriesCollapsedCardNumber(String)}, and a card number grouped by one of the five
     * separators a reader types is sought through {@link #carriesSeparatedCardNumber(String)}. A
     * value failing any one screen is refused.
     *
     * <p>The text is read as it arrived, which catches an unpunctuated run, and again normalized
     * under {@link java.text.Normalizer.Form#NFKC}, which folds a full-width digit such as
     * {@code ４} onto {@code 4} and a compatibility separator onto its plain form, so a value that
     * looks like a card number to a reader is one to this screen as well.
     *
     * <p>Neither card-number screen erases punctuation and then looks for a long run. Both read the
     * digit groups the punctuation makes, because an amount and a date are punctuated digits too:
     * {@code 1234567890.12} carries twelve digits and is an amount, and
     * {@code 2026-08-07 19:12:06} carries fourteen and is a moment.
     *
     * <p>A {@link Screen#NARRATIVE} value takes two further screens, which
     * {@link #carriesNarrativeShape(String)} applies: a value that is nothing but three or four
     * digits, and a value holding an unbroken run of nine or more.
     *
     * @param value  the node to read
     * @param screen how thoroughly to read it, which the property carrying it decides
     * @return {@code true} when the text carries any of the shapes that screen refuses
     */
    private static boolean carriesSensitiveText(JsonNode value, Screen screen) {
        if (value == null || !value.isString() || screen == Screen.NONE) {
            return false;
        }

        String text = value.stringValue();
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (carriesSensitiveShape(text) || carriesSensitiveShape(normalized)) {
            return true;
        }
        if (carriesCollapsedCardNumber(text) || carriesCollapsedCardNumber(normalized)
                || carriesSeparatedCardNumber(text) || carriesSeparatedCardNumber(normalized)) {
            return true;
        }
        return screen == Screen.NARRATIVE
                && (carriesNarrativeShape(text) || carriesNarrativeShape(normalized));
    }

    /**
     * Whether one form of a narrative value is a bare card code or holds an unseparated identifier.
     *
     * <p>Both forms of the value are read, as written and normalized under
     * {@link java.text.Normalizer.Form#NFKC}, so a full-width digit such as {@code \uff14} counts
     * as the digit it renders as.
     *
     * @param text one form of the screened value
     * @return {@code true} when the value is only three or four digits, or holds an unbroken run of
     *         nine or more
     */
    private static boolean carriesNarrativeShape(String text) {
        return NARRATIVE_BARE_CODE.matcher(text).matches()
                || NARRATIVE_DIGIT_RUN.matcher(text).find();
    }

    /**
     * Whether one form of a value holds a long digit run, a separated government identifier or a
     * labelled security code.
     *
     * @param text one form of the screened value
     * @return {@code true} when that form holds any of those shapes
     */
    private static boolean carriesSensitiveShape(String text) {
        return LONG_DIGIT_RUN.matcher(text).find()
                || GOVERNMENT_IDENTIFIER.matcher(text).find()
                || LABELLED_SECURITY_CODE.matcher(text).find();
    }

    /**
     * Whether one text carries a card number punctuated by anything at all.
     *
     * <p>{@link #PUNCTUATED_DIGIT_CHAIN} finds each chain of digit groups joined by punctuation, and
     * a chain is read as a card number when its digits number between
     * {@link #SHORTEST_CARD_NUMBER_DIGITS} and {@link #LONGEST_CARD_NUMBER_DIGITS} and every one of
     * its groups holds at least {@link #SMALLEST_CARD_NUMBER_GROUP} digits.
     *
     * <p>This is the general form of {@link #carriesSeparatedCardNumber(String)}, which reads the
     * five separators a reader types. A chain joins on every character that is not a digit or a
     * letter, so {@code 4111(1111)1111(1111)} and {@code 4111*1111*1111*1111} are caught as well.
     * The group test is what keeps the generality safe: an amount ends in a two-digit group and a
     * date carries two of them, so neither is read as a card however wide the chain is.
     *
     * @param text the text to read
     * @return {@code true} when the text carries a punctuated card number
     */
    private static boolean carriesCollapsedCardNumber(String text) {
        Matcher chain = PUNCTUATED_DIGIT_CHAIN.matcher(text);
        while (chain.find()) {
            String candidate = chain.group();
            int digits = NON_DIGIT.matcher(candidate).replaceAll("").length();
            if (digits >= SHORTEST_CARD_NUMBER_DIGITS
                    && digits <= LONGEST_CARD_NUMBER_DIGITS
                    && everyPunctuatedGroupIsWideEnough(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every digit group of one punctuated chain holds at least
     * {@link #SMALLEST_CARD_NUMBER_GROUP} digits.
     *
     * @param chain one chain of digit groups joined by punctuation
     * @return {@code true} when no group is narrower than the smallest a card number is written in
     */
    private static boolean everyPunctuatedGroupIsWideEnough(String chain) {
        for (String group : NON_DIGIT.split(chain)) {
            if (!group.isEmpty() && group.length() < SMALLEST_CARD_NUMBER_GROUP) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one text carries a card number written with separators between its digit groups.
     *
     * <p>{@link #SEPARATED_CARD_NUMBER} finds a candidate of twelve to nineteen digits whose
     * separators each sit between two digits. A candidate is read as a card number only when every
     * one of its digit groups holds at least {@link #SMALLEST_CARD_NUMBER_GROUP} digits, which is
     * what keeps a decimal amount and a date out: an amount ends in a two-digit group and a date
     * carries two two-digit groups.
     *
     * @param text the text to read
     * @return {@code true} when the text carries a separated card number
     */
    private static boolean carriesSeparatedCardNumber(String text) {
        Matcher candidate = SEPARATED_CARD_NUMBER.matcher(text);
        while (candidate.find()) {
            if (everyGroupIsWideEnough(candidate.group())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether every digit group of one candidate holds at least
     * {@link #SMALLEST_CARD_NUMBER_GROUP} digits.
     *
     * @param candidate the matched candidate, which holds digits and separators only
     * @return {@code true} when no group is narrower than the smallest a card number is written in
     */
    private static boolean everyGroupIsWideEnough(String candidate) {
        for (String group : candidate.split("[ \\-./_]")) {
            if (group.length() < SMALLEST_CARD_NUMBER_GROUP) {
                return false;
            }
        }
        return true;
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
        String normalized = Normalizer.normalize(propertyName, Normalizer.Form.NFKC);
        return NON_ALPHANUMERIC.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
