/**
 * browseNotices
 * =============
 *
 * :purpose: Classify the line-23 text a browse screen publishes as either a failure or
 *     a non-failure NOTICE. ``COUSR00.bms``, ``COCRDLI.bms`` and ``COTRN00.bms`` each
 *     declare their ``ERRMSG`` field ``COLOR=RED`` statically and their programs never
 *     move ``DFHGREEN``, so reaching the end of a browse is displayed in RED on a real
 *     3270 and that colour is preserved verbatim. What the terminal had no way to
 *     express is announcement urgency: routing an ordinary end-of-list condition
 *     through ``role="alert"`` interrupts a screen-reader user to tell them a list
 *     ended. These literals are therefore published as notices -- same RED, polite
 *     announcement.
 * :output: The verbatim :data:`BROWSE_NOTICE_MESSAGES` set and the
 *     :func:`isBrowseNotice` predicate.
 * :note: The literals are reproduced character-for-character from the COBOL sources,
 *     including their trailing ellipses and full stop, so a message that is NOT one of
 *     these keeps the assertive alert role it deserves.
 */

/**
 * :purpose: Every boundary and empty-result literal the three browse programs publish.
 *     ``COUSR00C`` L273 / L637, ``COCRDLIC`` and ``COTRN00C`` boundary and
 *     no-records branches.
 */
export const BROWSE_NOTICE_MESSAGES: ReadonlySet<string> = new Set([
  'You are already at the bottom of the page...',
  'You are already at the top of the page...',
  'You are at the top of the page...',
  'You have reached the bottom of the page...',
  'You have reached the top of the page...',
  'NO MORE RECORDS TO SHOW',
  'NO RECORDS TO SHOW',
  'NO RECORDS FOUND FOR THIS SEARCH CONDITION.',
]);

/**
 * :purpose: Report whether a line-23 message states a browse boundary or an empty
 *     result rather than a failure.
 * :param message: the line-23 text a screen is about to publish.
 * :returns: ``true`` when the message is one of the verbatim notice literals.
 */
export function isBrowseNotice(message: string): boolean {
  return BROWSE_NOTICE_MESSAGES.has(message.trim());
}
