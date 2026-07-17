/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.rule;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Composite validation rule for a United States phone number, the Java reproduction of the COBOL
 * edit paragraph {@code 1260-EDIT-US-PHONE-NUM} of {@code legacy/cbl/COACTUPC.cbl}
 * (source {@code app/cbl/COACTUPC.cbl}, lines L2225&ndash;L2429). The legacy program stores the
 * number in an {@code X(15)} buffer formatted {@code (999)999-9999} and edits it as three
 * fixed-width sub-fields (see {@code legacy/cbl/COACTUPC.cbl:L82-L104}):
 * <ul>
 *   <li>{@code WS-EDIT-US-PHONE-NUMA} &mdash; area code, {@code PIC X(3)} (redefined {@code 9(3)});</li>
 *   <li>{@code WS-EDIT-US-PHONE-NUMB} &mdash; prefix, {@code PIC X(3)} (redefined {@code 9(3)});</li>
 *   <li>{@code WS-EDIT-US-PHONE-NUMC} &mdash; line number, {@code PIC X(4)} (redefined {@code 9(4)}).</li>
 * </ul>
 *
 * <p>Because the phone number is a composite of three sub-fields, this rule cannot use the
 * single-argument {@link ValidationRule#validate(String, String)} Strategy signature; it therefore
 * does <em>not</em> implement {@link ValidationRule} and instead exposes a four-argument
 * {@link #validate(String, String, String, String)} entry point, reusing the shared COBOL edit
 * primitives declared as {@code static} helpers on {@link ValidationRule}
 * ({@link ValidationRule#isBlank(String)}, {@link ValidationRule#isAllDigits(String)},
 * {@link ValidationRule#isZeroValue(String)}, {@link ValidationRule#label(String)}).</p>
 *
 * <p><strong>Optional-when-all-blank.</strong> A phone number is not mandatory: when the area
 * code, prefix, and line number are all blank ({@code SPACES}/{@code LOW-VALUES}) the whole field
 * is valid ({@code legacy/cbl/COACTUPC.cbl:L2234-L2244}). The legacy condition contains a known
 * bug at L2237&ndash;L2239 &mdash; its third {@code AND}-clause tests {@code WS-EDIT-US-PHONE-NUMA}
 * a second time where it should test {@code WS-EDIT-US-PHONE-NUMC}. This class implements the
 * <em>intent</em> (optional only when all three parts are blank) rather than reproducing the
 * defect; the fix is a faithful, behaviour-preserving migration.</p>
 *
 * <p><strong>First-message-wins.</strong> The legacy paragraph runs three sub-edits in order
 * (area code &rarr; prefix &rarr; line number). Each sub-edit sets {@code INPUT-ERROR} on failure
 * but writes its screen message only while the message latch is still off
 * ({@code WS-RETURN-MSG-OFF}), and each failing sub-edit {@code GO TO}es the next one, so the
 * <em>first</em> failing sub-edit's message is the caller-visible outcome. This class reproduces
 * that behaviour by returning immediately on the first failing sub-edit.</p>
 *
 * <p><strong>Area-code lookup.</strong> The area code must be a member of the COBOL
 * {@code VALID-GENERAL-PURP-CODE} set defined in {@code legacy/cpy/CSLKPCDY.cpy}
 * (the {@code 88}-level list beginning at {@code legacy/cpy/CSLKPCDY.cpy:L521}); those 410
 * general-purpose North American Numbering Plan area codes are embedded verbatim in
 * {@link #VALID_AREA_CODES}.</p>
 *
 * <p>The rule is stateless and side-effect free; the single instance is safe to share as a
 * singleton Spring bean, and no field is ever mutated. Outcome messages describe the offending
 * field by its supplied label only and never carry a raw value, so they are safe to log.</p>
 */
@Component
public final class UsPhoneRule {

    /**
     * The 410 general-purpose North American Numbering Plan area codes accepted by the legacy
     * {@code VALID-GENERAL-PURP-CODE} condition ({@code legacy/cpy/CSLKPCDY.cpy:L521} onward),
     * embedded verbatim and in the same order as the copybook. The set is immutable
     * ({@link Set#of(Object...)}), so it is safe to share across threads; {@link Set#of(Object...)}
     * additionally rejects duplicate elements at class-initialization time, which guards the list
     * against an accidental repeated code.
     */
    private static final Set<String> VALID_AREA_CODES = Set.of(
        "201","202","203","204","205","206","207","208","209","210","212","213","214","215","216","217","218","219","220","223",
        "224","225","226","228","229","231","234","236","239","240","242","246","248","249","250","251","252","253","254","256",
        "260","262","264","267","268","269","270","272","276","279","281","284","289","301","302","303","304","305","306","307",
        "308","309","310","312","313","314","315","316","317","318","319","320","321","323","325","326","330","331","332","334",
        "336","337","339","340","341","343","345","346","347","351","352","360","361","364","365","367","368","380","385","386",
        "401","402","403","404","405","406","407","408","409","410","412","413","414","415","416","417","418","419","423","424",
        "425","430","431","432","434","435","437","438","440","441","442","443","445","447","448","450","458","463","464","469",
        "470","473","474","475","478","479","480","484","501","502","503","504","505","506","507","508","509","510","512","513",
        "514","515","516","517","518","519","520","530","531","534","539","540","541","548","551","559","561","562","563","564",
        "567","570","571","572","573","574","575","579","580","581","582","585","586","587","601","602","603","604","605","606",
        "607","608","609","610","612","613","614","615","616","617","618","619","620","623","626","628","629","630","631","636",
        "639","640","641","646","647","649","650","651","656","657","658","659","660","661","662","664","667","669","670","671",
        "672","678","680","681","682","683","684","689","701","702","703","704","705","706","707","708","709","712","713","714",
        "715","716","717","718","719","720","721","724","725","726","727","731","732","734","737","740","742","743","747","753",
        "754","757","758","760","762","763","765","767","769","770","771","772","773","774","775","778","779","780","781","782",
        "784","785","786","787","801","802","803","804","805","806","807","808","809","810","812","813","814","815","816","817",
        "818","819","820","825","826","828","829","830","831","832","838","839","840","843","845","847","848","849","850","854",
        "856","857","858","859","860","862","863","864","865","867","868","869","870","872","873","876","878","901","902","903",
        "904","905","906","907","908","909","910","912","913","914","915","916","917","918","919","920","925","928","929","930",
        "931","934","936","937","938","939","940","941","943","945","947","948","949","951","952","954","956","959","970","971",
        "972","973","978","979","980","983","984","985","986","989");

    /** Number of characters in the area-code sub-field ({@code WS-EDIT-US-PHONE-NUMA}, {@code PIC X(3)}). */
    private static final int AREA_CODE_LENGTH = 3;

    /** Number of characters in the prefix sub-field ({@code WS-EDIT-US-PHONE-NUMB}, {@code PIC X(3)}). */
    private static final int PREFIX_LENGTH = 3;

    /** Number of characters in the line-number sub-field ({@code WS-EDIT-US-PHONE-NUMC}, {@code PIC X(4)}). */
    private static final int LINE_NUMBER_LENGTH = 4;

    /** Separator inserted between the field label and the message body ({@code TRIM(name)} + {@code ": ..."}). */
    private static final String LABEL_SEPARATOR = ": ";

    // Area-code message bodies (COBOL literals from legacy/cbl/COACTUPC.cbl:L2246 onward).
    private static final String AREA_CODE_REQUIRED = "Area code must be supplied.";
    private static final String AREA_CODE_NOT_NUMERIC = "Area code must be A 3 digit number.";
    private static final String AREA_CODE_ZERO = "Area code cannot be zero";
    private static final String AREA_CODE_NOT_VALID = "Not valid North America general purpose area code";

    // Prefix message bodies (COBOL literals from legacy/cbl/COACTUPC.cbl:L2325 onward).
    private static final String PREFIX_REQUIRED = "Prefix code must be supplied.";
    private static final String PREFIX_NOT_NUMERIC = "Prefix code must be A 3 digit number.";
    private static final String PREFIX_ZERO = "Prefix code cannot be zero";

    // Line-number message bodies (COBOL literals from legacy/cbl/COACTUPC.cbl:L2370 onward).
    private static final String LINE_NUMBER_REQUIRED = "Line number code must be supplied.";
    private static final String LINE_NUMBER_NOT_NUMERIC = "Line number code must be A 4 digit number.";
    private static final String LINE_NUMBER_ZERO = "Line number code cannot be zero";

    /**
     * Validates a United States phone number supplied as three fixed-width parts, reproducing the
     * COBOL paragraph {@code 1260-EDIT-US-PHONE-NUM} ({@code legacy/cbl/COACTUPC.cbl:L2225-L2429}).
     *
     * <p>The number is optional: when {@code areaCode}, {@code prefix}, and {@code lineNumber} are
     * all blank the result is valid ({@code legacy/cbl/COACTUPC.cbl:L2234-L2244}, intent-preserving
     * &mdash; see the class documentation). Otherwise the three sub-edits run in order (area code,
     * prefix, line number) and the <em>first</em> failing sub-edit's message is returned, mirroring
     * the legacy first-message-wins latch ({@code WS-RETURN-MSG-OFF}).</p>
     *
     * @param fieldName  the human-readable field label substituted into the outcome message
     *                   (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be {@code null} or padded, in
     *                   which case it is normalized by {@link ValidationRule#label(String)}
     * @param areaCode   the area-code part ({@code WS-EDIT-US-PHONE-NUMA}); may be {@code null}
     *                   (COBOL {@code LOW-VALUES}) or blank (COBOL {@code SPACES})
     * @param prefix     the prefix part ({@code WS-EDIT-US-PHONE-NUMB}); may be {@code null} or blank
     * @param lineNumber the line-number part ({@code WS-EDIT-US-PHONE-NUMC}); may be {@code null} or blank
     * @return {@link ValidationResult#valid()} when the number is absent (all parts blank) or every
     *         part passes its sub-edit; otherwise {@link ValidationResult#invalid(String)} carrying
     *         the first failing sub-edit's exact COBOL screen message
     */
    public ValidationResult validate(String fieldName, String areaCode, String prefix, String lineNumber) {
        if (allBlank(areaCode, prefix, lineNumber)) {
            // Not mandatory to enter a phone number (legacy/cbl/COACTUPC.cbl:L2234).
            return ValidationResult.valid();
        }
        ValidationResult area = editAreaCode(fieldName, areaCode);
        if (area.isInvalid()) {
            return area;
        }
        ValidationResult pre = editPrefix(fieldName, prefix);
        if (pre.isInvalid()) {
            return pre;
        }
        return editLineNumber(fieldName, lineNumber);
    }

    /**
     * Reports whether all three phone parts are "not supplied", reproducing the intent of the
     * optional-number guard at {@code legacy/cbl/COACTUPC.cbl:L2234-L2244}. Each part is tested with
     * {@link ValidationRule#isBlank(String)}, so a {@code null} part models COBOL {@code LOW-VALUES}
     * and an empty/all-whitespace part models {@code SPACES}.
     *
     * @param areaCode   the area-code part; may be {@code null}
     * @param prefix     the prefix part; may be {@code null}
     * @param lineNumber the line-number part; may be {@code null}
     * @return {@code true} only when every part is blank (the phone number is therefore optional)
     */
    private static boolean allBlank(String areaCode, String prefix, String lineNumber) {
        return ValidationRule.isBlank(areaCode)
            && ValidationRule.isBlank(prefix)
            && ValidationRule.isBlank(lineNumber);
    }

    /**
     * Edits the area-code sub-field, reproducing {@code EDIT-AREA-CODE}
     * ({@code legacy/cbl/COACTUPC.cbl:L2246-L2323}). The checks run in the legacy order and the
     * first failure wins: not supplied &rarr; not a 3-digit number &rarr; zero &rarr; not a valid
     * general-purpose area code.
     *
     * @param fieldName the field label for the outcome message
     * @param areaCode  the area-code part as entered
     * @return {@link ValidationResult#valid()} if the area code passes every check, otherwise the
     *         first failing check's message
     */
    private static ValidationResult editAreaCode(String fieldName, String areaCode) {
        if (ValidationRule.isBlank(areaCode)) {
            return failure(fieldName, AREA_CODE_REQUIRED);
        }
        String code = areaCode.strip();
        if (!isFixedWidthNumeric(code, AREA_CODE_LENGTH)) {
            return failure(fieldName, AREA_CODE_NOT_NUMERIC);
        }
        if (ValidationRule.isZeroValue(code)) {
            return failure(fieldName, AREA_CODE_ZERO);
        }
        if (!VALID_AREA_CODES.contains(code)) {
            return failure(fieldName, AREA_CODE_NOT_VALID);
        }
        return ValidationResult.valid();
    }

    /**
     * Edits the prefix sub-field, reproducing {@code EDIT-US-PHONE-PREFIX}
     * ({@code legacy/cbl/COACTUPC.cbl:L2325-L2368}). The checks run in the legacy order and the
     * first failure wins: not supplied &rarr; not a 3-digit number &rarr; zero. There is no
     * lookup-set check for the prefix.
     *
     * @param fieldName the field label for the outcome message
     * @param prefix    the prefix part as entered
     * @return {@link ValidationResult#valid()} if the prefix passes every check, otherwise the
     *         first failing check's message
     */
    private static ValidationResult editPrefix(String fieldName, String prefix) {
        if (ValidationRule.isBlank(prefix)) {
            return failure(fieldName, PREFIX_REQUIRED);
        }
        String code = prefix.strip();
        if (!isFixedWidthNumeric(code, PREFIX_LENGTH)) {
            return failure(fieldName, PREFIX_NOT_NUMERIC);
        }
        if (ValidationRule.isZeroValue(code)) {
            return failure(fieldName, PREFIX_ZERO);
        }
        return ValidationResult.valid();
    }

    /**
     * Edits the line-number sub-field, reproducing {@code EDIT-US-PHONE-LINENUM}
     * ({@code legacy/cbl/COACTUPC.cbl:L2370-L2422}). The checks run in the legacy order and the
     * first failure wins: not supplied &rarr; not a 4-digit number &rarr; zero. There is no
     * lookup-set check for the line number.
     *
     * @param fieldName  the field label for the outcome message
     * @param lineNumber the line-number part as entered
     * @return {@link ValidationResult#valid()} if the line number passes every check, otherwise the
     *         first failing check's message
     */
    private static ValidationResult editLineNumber(String fieldName, String lineNumber) {
        if (ValidationRule.isBlank(lineNumber)) {
            return failure(fieldName, LINE_NUMBER_REQUIRED);
        }
        String code = lineNumber.strip();
        if (!isFixedWidthNumeric(code, LINE_NUMBER_LENGTH)) {
            return failure(fieldName, LINE_NUMBER_NOT_NUMERIC);
        }
        if (ValidationRule.isZeroValue(code)) {
            return failure(fieldName, LINE_NUMBER_ZERO);
        }
        return ValidationResult.valid();
    }

    /**
     * Reports whether a phone part satisfies the COBOL {@code IS NUMERIC} class test for a
     * fixed-width numeric field. Because the legacy field is exactly {@code width} characters wide,
     * a short entry is space-padded and therefore fails {@code IS NUMERIC}; this method reproduces
     * that by requiring the (already-stripped) value to consist solely of ASCII digits
     * ({@link ValidationRule#isAllDigits(String)}) <em>and</em> to be exactly {@code width}
     * characters long.
     *
     * @param code  the stripped phone part
     * @param width the exact number of digits the field must contain (3 for area/prefix, 4 for line)
     * @return {@code true} when {@code code} is exactly {@code width} ASCII digits; {@code false}
     *         otherwise (blank, non-digit, too short, or too long)
     */
    private static boolean isFixedWidthNumeric(String code, int width) {
        return ValidationRule.isAllDigits(code) && code.length() == width;
    }

    /**
     * Builds a failing {@link ValidationResult} whose message is the trimmed field label followed
     * by {@code ": "} and the supplied message body, reproducing the COBOL
     * {@code STRING FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) ': ...' INTO WS-RETURN-MSG} construction.
     *
     * @param fieldName     the raw field label (COBOL {@code WS-EDIT-VARIABLE-NAME}); may be
     *                      {@code null} or padded and is normalized by
     *                      {@link ValidationRule#label(String)}
     * @param messageBody   the exact COBOL message body (without the leading {@code ": "})
     * @return an invalid {@code ValidationResult} carrying {@code label + ": " + messageBody}
     */
    private static ValidationResult failure(String fieldName, String messageBody) {
        return ValidationResult.invalid(ValidationRule.label(fieldName) + LABEL_SEPARATOR + messageBody);
    }
}
