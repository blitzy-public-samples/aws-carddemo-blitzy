package com.aws.carddemo.util.constants;

/**
 * Screen banner title constants for the AWS CardDemo application.
 *
 * <p>Java migration of the COBOL screen-title copybook {@code CCDA-SCREEN-TITLE}. These values are
 * the fixed banner lines rendered at the top of every 3270/BMS screen; in the target application
 * they are consumed by the Thymeleaf screen templates and screen-form DTOs.</p>
 *
 * <p>Each value is a byte-exact reproduction of a COBOL {@code PIC X(40)} field literal, preserving
 * every leading and trailing space so that the fixed {@value #TITLE_LENGTH}-column banner contract
 * is reproduced verbatim for 3270/BMS visual parity.</p>
 *
 * <p>Origin: legacy/cpy/COTTL01Y.cpy (01 CCDA-SCREEN-TITLE)</p>
 */
public final class ScreenTitles {

    /** Fixed banner width shared by every title; COBOL {@code PIC X(40)}. */
    public static final int TITLE_LENGTH = 40;

    /** CCDA-TITLE01, PIC X(40). */
    public static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /** CCDA-TITLE02, PIC X(40). */
    public static final String CCDA_TITLE02 = "              CardDemo                  ";

    /** CCDA-THANK-YOU, PIC X(40). */
    public static final String CCDA_THANK_YOU = "Thank you for using CCDA application... ";

    /**
     * Prevents instantiation of this constants holder.
     */
    private ScreenTitles() {
        throw new AssertionError("No instances");
    }
}
