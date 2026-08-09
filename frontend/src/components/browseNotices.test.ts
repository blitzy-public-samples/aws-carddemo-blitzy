/**
 * :module: ``frontend/src/components/browseNotices.test.ts``
 * :purpose: Verify the browse-notice classifier: the verbatim boundary and empty-result
 *     literals the three browse programs publish are recognised as non-failures, and
 *     anything else -- a genuine edit failure included -- is not, so softening the
 *     announcement can never swallow a real refusal.
 * :output: Jest test suite; no exports.
 */

import { BROWSE_NOTICE_MESSAGES, isBrowseNotice } from './browseNotices';

describe('isBrowseNotice', () => {
  it.each([
    'You are already at the bottom of the page...',
    'You are already at the top of the page...',
    'You are at the top of the page...',
    'You have reached the bottom of the page...',
    'You have reached the top of the page...',
    'NO MORE RECORDS TO SHOW',
    'NO RECORDS TO SHOW',
    'NO RECORDS FOUND FOR THIS SEARCH CONDITION.',
  ])('classifies the verbatim boundary literal %p as a notice', (literal) => {
    expect(isBrowseNotice(literal)).toBe(true);
  });

  it('tolerates the padding a fixed-width message field carries', () => {
    expect(isBrowseNotice('  NO RECORDS TO SHOW   ')).toBe(true);
  });

  it.each([
    'User ID NOT found...',
    'Record changed by some one else. Please review',
    'Unable to lookup User...',
    'User Type must be A or U',
    '',
  ])('does not soften a genuine failure such as %p', (message) => {
    expect(isBrowseNotice(message)).toBe(false);
  });

  it('holds exactly the eight literals the browse programs publish', () => {
    expect(BROWSE_NOTICE_MESSAGES.size).toBe(8);
  });
});
