package com.carddemo.common.constant;

/**
 * Verbatim CardDemo screen-title constants transformed from COBOL copybook
 * ``COTTL01Y`` (group ``01 CCDA-SCREEN-TITLE``).
 *
 * :purpose: Expose the fixed CardDemo screen-title display strings shared by
 *           the modernized services and the React page headers.
 * :output: Three 40-character ``String`` constants that reproduce the original
 *          ``PIC X(40)`` literals byte-for-byte, including every leading,
 *          interior, and trailing padding space.
 */
public final class Titles {

    /**
     * Primary application banner title.
     *
     * :output: Source ``CCDA-TITLE01`` ``PIC X(40)`` literal (40 characters).
     */
    public static final String CCDA_TITLE01 = "      AWS Mainframe Modernization       ";

    /**
     * Secondary application title.
     *
     * :output: Source ``CCDA-TITLE02`` ``PIC X(40)`` literal (40 characters).
     */
    public static final String CCDA_TITLE02 = "              CardDemo                  ";

    /**
     * Sign-off thank-you line.
     *
     * :output: Source ``CCDA-THANK-YOU`` ``PIC X(40)`` literal (40 characters).
     */
    public static final String CCDA_THANK_YOU = "Thank you for using CCDA application... ";

    /**
     * Non-instantiable constant holder.
     *
     * :purpose: Prevent instantiation of this utility class.
     */
    private Titles() {
    }
}
