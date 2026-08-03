package com.carddemo.cobol;

import java.math.BigDecimal;

/**
 * Reproduces the four COBOL numeric conversion functions the CardDemo programs read input
 * through.
 *
 * <p>Two of the four return a value: {@code FUNCTION NUMVAL} and {@code FUNCTION NUMVAL-C}. The
 * other two report whether an argument is valid: {@code FUNCTION TEST-NUMVAL} and
 * {@code FUNCTION TEST-NUMVAL-C}. The {@code -C} pair accepts a currency sign and grouping
 * commas; the plain pair accepts neither.</p>
 *
 * <p>Each COBOL gate returns 0 when the argument is valid, at {@code app/cbl/COACTUPC.cbl:L2201}
 * and at {@code app/cpy/CSUTLDPY.cpy:L126}. The two methods here named {@code isValid...} return
 * {@code true} in that same case.</p>
 *
 * <p>A caller pairs a gate with its matching conversion, as
 * {@code app/cbl/COACTUPC.cbl:L1078-L1080} does: gate, convert, then apply the target scale.
 * These methods apply no scale. {@link PicClause} carries the scale of every field the platform
 * stores.</p>
 *
 * <p>Rationale for the choices behind this class lives in
 * {@code card-platform/docs/decision-log.md}.</p>
 */
public final class NumvalParser {

    /**
     * The currency string {@code FUNCTION NUMVAL-C} accepts. No CardDemo program carries a
     * {@code CURRENCY SIGN} clause and no call site supplies the optional second argument, so
     * every one of the thirteen call sites reads the default currency symbol.
     */
    public static final char CURRENCY_SIGN = '$';

    /**
     * The grouping character {@code FUNCTION NUMVAL-C} accepts between digit groups. COBOL
     * enforces no group width, so {@code 1,00,000} carries the same value as {@code 100000}.
     */
    public static final char DIGIT_SEPARATOR = ',';

    /** The character both conversion functions accept ahead of the fraction digits. */
    public static final char DECIMAL_POINT = '.';

    /**
     * The largest count of digits an argument may hold. The CardDemo compile jobs name no
     * {@code ARITH} option, so {@code ARITH(COMPAT)} applies and the ceiling is eighteen digits.
     * Both gates reject an argument above the ceiling, and leading zeros count toward it.
     */
    public static final int MAXIMUM_DIGITS = 18;

    /** The sign that marks a positive argument, valid ahead of the digits or after them. */
    private static final char PLUS_SIGN = '+';

    /** The sign that marks a negative argument, valid ahead of the digits or after them. */
    private static final char MINUS_SIGN = '-';

    /** The one whitespace character either grammar allows. */
    private static final char SPACE = ' ';

    /** The trailing credit marker. A COBOL argument spells it in upper case. */
    private static final String CREDIT_MARKER = "CR";

    /** The trailing debit marker. A COBOL argument spells it in upper case. */
    private static final String DEBIT_MARKER = "DB";

    /**
     * The lowest of the ten characters either grammar counts as a digit. The canonical form also
     * uses it as the integer part of an argument that opens with its decimal point.
     */
    private static final char ZERO_DIGIT = '0';

    /** The highest of the ten characters either grammar counts as a digit. */
    private static final char NINE_DIGIT = '9';

    /** Names the plain function in a rejection message. */
    private static final String PLAIN_FUNCTION = "FUNCTION NUMVAL";

    /** Names the currency-tolerant function in a rejection message. */
    private static final String CURRENCY_FUNCTION = "FUNCTION NUMVAL-C";

    /** This class holds static members only. */
    private NumvalParser() {
    }

    /**
     * Converts an argument to its value, reproducing {@code FUNCTION NUMVAL}.
     *
     * <p>An argument holds digits with an optional decimal point and fraction digits. A
     * {@code +} or {@code -} may lead the digits, and one of {@code +}, {@code -}, {@code CR}, or
     * {@code DB} may trail them in its place. The last three of those mark a negative value.
     * Spaces may lead the argument, sit on either side of a sign, and trail it.</p>
     *
     * <p>A currency sign and a grouping comma are both invalid here. The returned value carries
     * the scale of the fraction digits supplied and no other scale.</p>
     *
     * <p>Call sites: {@code app/cbl/COTRN02C.cbl:L204} converts an account identifier and
     * {@code app/cbl/COTRN02C.cbl:L218} a card number, {@code app/cbl/COACTUPC.cbl:L2156} tests a
     * sliced field for zero, and {@code app/cpy/CSUTLDPY.cpy:L128} and
     * {@code app/cpy/CSUTLDPY.cpy:L172} convert a month and a day.</p>
     *
     * @param text the argument to convert; may be {@code null}
     * @return the value the argument represents
     * @throws NumberFormatException when {@link #isValidNumval(String)} returns {@code false}
     *                               for the same argument
     */
    public static BigDecimal numval(String text) {
        return convert(text, false, PLAIN_FUNCTION);
    }

    /**
     * Converts an argument to its value, reproducing {@code FUNCTION NUMVAL-C}.
     *
     * <p>An argument holds everything {@link #numval(String)} accepts, plus one
     * {@value #CURRENCY_SIGN} and a {@value #DIGIT_SEPARATOR} between digit groups. The currency
     * sign may lead the digits or follow them, and an argument holds at most one currency sign. A
     * separator sits only in the integer part, and the conversion drops every separator.</p>
     *
     * <p>Call sites: {@code app/cbl/COACTUPC.cbl:L1080},
     * {@code app/cbl/COACTUPC.cbl:L1094}, {@code app/cbl/COACTUPC.cbl:L1108},
     * {@code app/cbl/COACTUPC.cbl:L1122}, and {@code app/cbl/COACTUPC.cbl:L1136} convert five
     * account money fields, {@code app/cbl/COTRN02C.cbl:L383} and
     * {@code app/cbl/COTRN02C.cbl:L456} convert a transaction amount, and
     * {@code app/cbl/CORPT00C.cbl:L305-L325} converts six report date fields.</p>
     *
     * @param text the argument to convert; may be {@code null}
     * @return the value the argument represents
     * @throws NumberFormatException when {@link #isValidNumvalCurrency(String)} returns
     *                               {@code false} for the same argument
     */
    public static BigDecimal numvalCurrency(String text) {
        return convert(text, true, CURRENCY_FUNCTION);
    }

    /**
     * Reports whether {@link #numval(String)} converts an argument, reproducing
     * {@code FUNCTION TEST-NUMVAL}.
     *
     * <p>Returns {@code true} when the argument is valid. The COBOL gate returns 0 in that same
     * case, at {@code app/cpy/CSUTLDPY.cpy:L126} for a month and at
     * {@code app/cpy/CSUTLDPY.cpy:L170} for a day.</p>
     *
     * <p>A {@code null} argument returns {@code false}. An argument of spaces returns
     * {@code false}, and so does an argument of {@code LOW-VALUES}, which
     * {@code app/cpy/CSUTLDPY.cpy:L154-L155} screens off ahead of the gate.</p>
     *
     * @param text the argument to inspect; may be {@code null}
     * @return {@code true} when the argument is valid
     */
    public static boolean isValidNumval(String text) {
        return canonicalNumberOrNull(text, false) != null;
    }

    /**
     * Reports whether {@link #numvalCurrency(String)} converts an argument, reproducing
     * {@code FUNCTION TEST-NUMVAL-C}.
     *
     * <p>Returns {@code true} when the argument is valid, carrying the polarity
     * {@link #isValidNumval(String)} documents. A caller that reads {@code false} defers the
     * error, as the {@code CONTINUE} at {@code app/cbl/COACTUPC.cbl:L1082} does.</p>
     *
     * <p>Call sites: {@code app/cbl/COACTUPC.cbl:L2201} gates
     * {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)}, and
     * {@code app/cbl/COACTUPC.cbl:L1078}, {@code app/cbl/COACTUPC.cbl:L1092},
     * {@code app/cbl/COACTUPC.cbl:L1106}, {@code app/cbl/COACTUPC.cbl:L1120}, and
     * {@code app/cbl/COACTUPC.cbl:L1134} gate the five account money conversions.</p>
     *
     * @param text the argument to inspect; may be {@code null}
     * @return {@code true} when the argument is valid
     */
    public static boolean isValidNumvalCurrency(String text) {
        return canonicalNumberOrNull(text, true) != null;
    }

    /**
     * Converts an argument the matching gate accepts, and throws for one the gate rejects.
     *
     * <p>Both public conversions route through here, so a gate never reports valid for an
     * argument the conversion then refuses. The message names the function and the argument
     * length only. No character of the argument reaches it, including a card number converted at
     * {@code app/cbl/COTRN02C.cbl:L218}.</p>
     *
     * @param text             the argument to convert; may be {@code null}
     * @param currencyTolerant {@code true} to accept a currency sign and grouping separators
     * @param cobolFunction    the COBOL function name the message reports
     * @return the value the argument represents
     * @throws NumberFormatException when the matching gate rejects the argument
     */
    private static BigDecimal convert(String text, boolean currencyTolerant,
                                      String cobolFunction) {
        String canonical = canonicalNumberOrNull(text, currencyTolerant);
        if (canonical == null) {
            throw new NumberFormatException(
                    cobolFunction + " rejects this argument. " + describeArgument(text));
        }
        return new BigDecimal(canonical);
    }

    /**
     * Describes an argument by length alone.
     *
     * @param text the rejected argument; may be {@code null}
     * @return a sentence naming the length, holding no character of the argument
     */
    private static String describeArgument(String text) {
        if (text == null) {
            return "The argument is null.";
        }
        return "The argument holds " + text.length() + " characters.";
    }

    /**
     * Scans an argument against the COBOL grammar and returns a form {@link BigDecimal} parses.
     *
     * <p>The {@code currencyTolerant} flag switches between the two grammars
     * {@link #numval(String)} and {@link #numvalCurrency(String)} document. Both gates and both
     * conversions call this one method.</p>
     *
     * <p>The returned form carries an optional {@code -}, the integer digits with every separator
     * dropped, and the fraction digits behind a decimal point. A trailing {@code CR} or
     * {@code DB} arrives as the {@code -}. The form keeps every leading zero the argument
     * supplied, so the scale of the parsed value matches the count of fraction digits.</p>
     *
     * @param text             the argument to scan; may be {@code null}
     * @param currencyTolerant {@code true} to accept a currency sign and grouping separators
     * @return a form {@link BigDecimal} parses, or {@code null} when the argument is invalid
     */
    private static String canonicalNumberOrNull(String text, boolean currencyTolerant) {
        if (text == null) {
            return null;
        }

        int end = text.length();
        int at = skipSpaces(text, 0);

        boolean negative = false;
        boolean signBeforeDigits = false;
        if (at < end && (text.charAt(at) == PLUS_SIGN || text.charAt(at) == MINUS_SIGN)) {
            negative = text.charAt(at) == MINUS_SIGN;
            signBeforeDigits = true;
            at = skipSpaces(text, at + 1);
        }

        boolean currencyBeforeDigits = false;
        if (currencyTolerant && at < end && text.charAt(at) == CURRENCY_SIGN) {
            currencyBeforeDigits = true;
            at = skipSpaces(text, at + 1);
        }

        StringBuilder integerDigits = new StringBuilder(end);
        StringBuilder fractionDigits = new StringBuilder(end);

        if (at < end && text.charAt(at) == DECIMAL_POINT) {
            // Second alternative of the digit group: the decimal point then one or more digits.
            int afterFraction = readDigits(text, at + 1, fractionDigits);
            if (afterFraction == at + 1) {
                return null;
            }
            at = afterFraction;
        } else {
            // First alternative: one or more digits, further groups, then an optional fraction.
            int afterInteger = readDigits(text, at, integerDigits);
            if (afterInteger == at) {
                return null;
            }
            at = afterInteger;

            while (currencyTolerant && at < end && text.charAt(at) == DIGIT_SEPARATOR) {
                int afterGroup = readDigits(text, at + 1, integerDigits);
                if (afterGroup == at + 1) {
                    return null;
                }
                at = afterGroup;
            }

            if (at < end && text.charAt(at) == DECIMAL_POINT) {
                at = readDigits(text, at + 1, fractionDigits);
            }
        }

        at = skipSpaces(text, at);

        if (currencyTolerant && !currencyBeforeDigits && at < end
                && text.charAt(at) == CURRENCY_SIGN) {
            at = skipSpaces(text, at + 1);
        }

        // A leading sign and a trailing sign are alternatives, so only one of them may appear.
        if (!signBeforeDigits && at < end) {
            char trailing = text.charAt(at);
            if (trailing == PLUS_SIGN) {
                at = skipSpaces(text, at + 1);
            } else if (trailing == MINUS_SIGN) {
                negative = true;
                at = skipSpaces(text, at + 1);
            } else if (text.startsWith(CREDIT_MARKER, at)) {
                negative = true;
                at = skipSpaces(text, at + CREDIT_MARKER.length());
            } else if (text.startsWith(DEBIT_MARKER, at)) {
                negative = true;
                at = skipSpaces(text, at + DEBIT_MARKER.length());
            }
        }

        if (at != end) {
            return null;
        }

        int digitCount = integerDigits.length() + fractionDigits.length();
        if (digitCount > MAXIMUM_DIGITS) {
            return null;
        }

        StringBuilder canonical = new StringBuilder(digitCount + 2);
        if (negative) {
            canonical.append(MINUS_SIGN);
        }
        if (integerDigits.isEmpty()) {
            canonical.append(ZERO_DIGIT);
        } else {
            canonical.append(integerDigits);
        }
        if (!fractionDigits.isEmpty()) {
            canonical.append(DECIMAL_POINT).append(fractionDigits);
        }
        return canonical.toString();
    }

    /**
     * Copies the run of digits that starts at a position into a builder.
     *
     * @param text   the argument being scanned
     * @param from   the position to start reading
     * @param digits the builder that collects the run
     * @return the position after the run, equal to {@code from} when no digit sits there
     */
    private static int readDigits(String text, int from, StringBuilder digits) {
        int at = from;
        while (at < text.length() && isDigit(text.charAt(at))) {
            digits.append(text.charAt(at));
            at++;
        }
        return at;
    }

    /**
     * Skips the run of spaces that starts at a position.
     *
     * @param text the argument being scanned
     * @param from the position to start skipping
     * @return the position of the first character that is not a space
     */
    private static int skipSpaces(String text, int from) {
        int at = from;
        while (at < text.length() && text.charAt(at) == SPACE) {
            at++;
        }
        return at;
    }

    /**
     * Reports whether a character is one of the ten digits {@code 0} through {@code 9}. A digit
     * of any other script counts as an invalid character here.
     *
     * @param character the character to weigh
     * @return {@code true} for the ten characters {@code 0} through {@code 9}
     */
    private static boolean isDigit(char character) {
        return character >= ZERO_DIGIT && character <= NINE_DIGIT;
    }
}
