/**
 * :module: messages
 * :purpose: The shared screen-message literals of ``app/cpy/CSMSG01Y.cpy``
 *   (``CCDA-COMMON-MESSAGES``), the copybook every online program includes so the
 *   same two texts appear on every screen. Each literal is published twice: the
 *   fixed-width ``PIC X(50)`` form exactly as the copybook declares it, and the
 *   trimmed form a proportional browser renders.
 * :output: :ts:type:`CCDA_MSG_THANK_YOU_PIC_X50`,
 *   :ts:type:`CCDA_MSG_THANK_YOU`, :ts:type:`CCDA_MSG_INVALID_KEY_PIC_X50`, and
 *   :ts:type:`CCDA_MSG_INVALID_KEY`.
 * :note: The trailing blanks of each ``PIC X(50)`` value are part of the frozen
 *   literal and are preserved verbatim in the ``_PIC_X50`` constants.
 */

/**
 * :purpose: ``CCDA-MSG-THANK-YOU`` verbatim — the text ``COSGN00C`` sends with
 *   ``EXEC CICS SEND TEXT ... ERASE`` when ``DFHPF3`` ends the session (L88-L89).
 */
export const CCDA_MSG_THANK_YOU_PIC_X50 =
  'Thank you for using CardDemo application...       ';

/**
 * :purpose: ``CCDA-MSG-INVALID-KEY`` verbatim — the line-23 text a program moves to
 *   ``WS-MESSAGE`` for an attention identifier it does not handle.
 */
export const CCDA_MSG_INVALID_KEY_PIC_X50 =
  'Invalid key pressed. Please see below...          ';

/** :purpose: Display form of :ts:type:`CCDA_MSG_THANK_YOU_PIC_X50`. */
export const CCDA_MSG_THANK_YOU = CCDA_MSG_THANK_YOU_PIC_X50.trim();

/** :purpose: Display form of :ts:type:`CCDA_MSG_INVALID_KEY_PIC_X50`. */
export const CCDA_MSG_INVALID_KEY = CCDA_MSG_INVALID_KEY_PIC_X50.trim();
