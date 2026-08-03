package com.carddemo.cobol.reference;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The 56 United States state and territory codes that CardDemo accepts.
 *
 * <p>The copybook {@code app/cpy/CSLKPCDY.cpy} declares the host field
 * {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at line 1012 and the condition name
 * {@code VALID-US-STATE-CODE} at line 1013. A COBOL 88-level condition name is true when
 * its host field equals one of the literals that follow. Lines 1014 through 1069 hold
 * those 56 literals: the 50 states, the District of Columbia, and five territories.
 *
 * <p>{@code app/cbl/COACTUPC.cbl} tests the condition name once, at line 2495 in
 * paragraph {@code 1270-EDIT-US-STATE-CD}.
 *
 * <p>Decision rationale: {@code card-platform/docs/decision-log.md}.
 */
public final class UsStateCodes {

    /** Literal count the copybook declares at lines 1014 through 1069. */
    private static final int DECLARED_CODE_COUNT = 56;

    /** Width of the host field {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at line 1012. */
    private static final int CODE_LENGTH = 2;

    /** The 56 codes, in the order the copybook declares them. */
    private static final Set<String> VALID_STATE_CODES;

    static {
        Set<String> codes = new LinkedHashSet<>();
        Collections.addAll(codes,
                "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
                "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
                "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
                "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
                "DC", "AS", "GU", "MP", "PR", "VI");

        if (codes.size() != DECLARED_CODE_COUNT) {
            throw new IllegalStateException("CSLKPCDY.cpy lines 1014-1069 declare "
                    + DECLARED_CODE_COUNT + " codes; this class holds " + codes.size());
        }
        for (String code : codes) {
            if (code.length() != CODE_LENGTH) {
                throw new IllegalStateException(
                        "US-STATE-CODE-TO-EDIT is PIC X(2); this class holds \"" + code + "\"");
            }
        }

        VALID_STATE_CODES = Collections.unmodifiableSet(codes);
    }

    /** This class holds static members only. */
    private UsStateCodes() {
    }

    /**
     * Returns true when {@code code} is one of the 56 state and territory codes that
     * {@code app/cpy/CSLKPCDY.cpy} declares at lines 1014 through 1069 as
     * {@code VALID-US-STATE-CODE}.
     *
     * <p>{@code app/cbl/COACTUPC.cbl} line 2494 fills a {@code PIC X(2)} field with a plain
     * {@code MOVE}, and line 2495 tests the condition name. This method matches that test:
     * it compares the whole argument, keeps case significant, and trims nothing.
     * {@code "TX"} passes. {@code "tx"}, {@code "T"}, {@code ""}, and {@code null} return
     * false, as does any value padded with a space.
     *
     * @param code the two-character code to test, may be {@code null}
     * @return true when {@code code} is a valid state or territory code
     */
    public static boolean isValidUsStateCode(String code) {
        return code != null && VALID_STATE_CODES.contains(code);
    }

    /**
     * Returns an unmodifiable view of the 56 codes, in the order
     * {@code app/cpy/CSLKPCDY.cpy} declares them at lines 1014 through 1069. In that
     * declaration order the first code is {@code AL} and the last is {@code VI}. Sorted,
     * the lowest is {@code AK} and the highest is {@code WY}. Adding to the returned set
     * throws {@link UnsupportedOperationException}.
     *
     * @return the 56 state and territory codes in copybook declaration order
     */
    public static Set<String> stateAndTerritoryCodes() {
        return VALID_STATE_CODES;
    }
}
