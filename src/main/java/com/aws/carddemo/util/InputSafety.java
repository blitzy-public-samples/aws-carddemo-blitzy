package com.aws.carddemo.util;

/**
 * Boundary input-safety guards for keyed lookups in the migrated AWS CardDemo online tier.
 *
 * <p>This tiny utility centralizes the single check the online screens need before a submitted
 * identifier is used as a key against a PostgreSQL-backed Spring Data repository: rejection of an
 * <em>embedded</em> NUL (U+0000) byte.</p>
 *
 * <h2>Why NUL is special</h2>
 * <p>PostgreSQL text types ({@code CHAR}, {@code VARCHAR}, {@code TEXT}) cannot store the NUL
 * character. Binding a parameter that contains one aborts the statement with SQLSTATE {@code 22021}
 * (<em>character&nbsp;not&nbsp;in&nbsp;repertoire</em>) and poisons the surrounding transaction. On
 * the mainframe a 3270/BMS input field can never deliver an embedded {@code x'00'} <em>among</em>
 * data bytes &mdash; NUL is COBOL {@code LOW-VALUES}, used only for unentered/absent field positions.
 * An identifier that carries an embedded NUL is therefore malformed input that no legacy path could
 * ever have produced, and it must be rejected at the web boundary <strong>before</strong> any
 * repository access.</p>
 *
 * <h2>Why rejection, not normalization</h2>
 * <p>NUL bytes must never be silently stripped or folded to spaces: a trailing-NUL fold followed by
 * the usual fixed-width trailing-space trim would let {@code "admin\u0000x"} collapse to
 * {@code "admin"} and match a different record &mdash; a classic NUL-truncation bypass (CWE-158). The
 * only safe treatment is to reject the value so the caller can surface its ordinary, controlled
 * &ldquo;not found&rdquo;/validation outcome (no HTTP 500, no misclassified duplicate) exactly as it
 * would for any other unmatched key.</p>
 *
 * <p>The all-NUL / all-spaces &ldquo;blank&rdquo; case (COBOL {@code = SPACES OR LOW-VALUES}) is a
 * separate, screen-specific concern handled by each service's own blank test; this guard is only
 * concerned with the presence of a NUL byte and is applied <em>after</em> the blank test so an empty
 * field keeps its existing &ldquo;can NOT be empty&rdquo; behaviour.</p>
 */
public final class InputSafety {

    /**
     * The NUL character (U+0000), COBOL {@code LOW-VALUES}. It is the one character PostgreSQL text
     * types cannot store; a bound parameter containing it fails with SQLSTATE {@code 22021}.
     */
    public static final char NUL = '\u0000';

    /**
     * Non-instantiable utility holder.
     */
    private InputSafety() {
        throw new AssertionError("No com.aws.carddemo.util.InputSafety instances.");
    }

    /**
     * Reports whether the supplied value contains at least one embedded NUL (U+0000) byte.
     *
     * <p>Intended to be called at a keyed-lookup boundary, after the screen's own blank test, to
     * reject an identifier that PostgreSQL could not store (SQLSTATE {@code 22021}) before it reaches
     * the repository. A {@code null} value contains no NUL and returns {@code false}; the {@code null}
     * / blank case is the caller's separate concern.</p>
     *
     * @param value the submitted field value to inspect; may be {@code null}
     * @return {@code true} when {@code value} is non-{@code null} and contains one or more NUL
     *         characters; {@code false} otherwise
     */
    public static boolean containsNul(String value) {
        return value != null && value.indexOf(NUL) >= 0;
    }
}
