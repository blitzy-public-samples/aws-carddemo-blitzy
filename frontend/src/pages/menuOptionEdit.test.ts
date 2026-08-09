/**
 * :module: ``frontend/src/pages/menuOptionEdit.test.ts``
 * :purpose: Pin the menu option edit to ``PROCESS-ENTER-KEY`` — that the entry reaching
 *     the edit is the raw field content, that a non-digit is refused rather than rewritten
 *     into a valid option, and that the right-justify-and-zero-fill of
 *     ``JUSTIFY=(RIGHT,ZERO)`` is reproduced.
 * :output: Jest assertions only.
 */

import {
  CDEMO_ADMIN_OPT_COUNT,
  CDEMO_MENU_OPT_COUNT,
  MSG_INVALID_OPTION,
  OPTION_FIELD_WIDTH,
  isOptionRefused,
  toProgramOption,
} from './menuOptionEdit';

describe('toProgramOption', () => {
  it('right-justifies and zero-fills a single digit, as JUSTIFY=(RIGHT,ZERO) does', () => {
    expect(toProgramOption('1')).toBe('01');
    expect(toProgramOption('9')).toBe('09');
  });

  it('treats a trailing blank the way the blank scan does', () => {
    expect(toProgramOption('1 ')).toBe('01');
  });

  it('keeps a leading blank as the zero it becomes', () => {
    expect(toProgramOption(' 1')).toBe('01');
  });

  it('passes a two-digit entry through unchanged', () => {
    expect(toProgramOption('10')).toBe('10');
  });

  it('turns an untouched or blanked field into ZEROS, not into nothing', () => {
    expect(toProgramOption('')).toBe('00');
    expect(toProgramOption(' ')).toBe('00');
    expect(toProgramOption('  ')).toBe('00');
  });

  it('carries a non-digit through unaltered for the class test to refuse', () => {
    expect(toProgramOption('-1')).toBe('-1');
    expect(toProgramOption('1-')).toBe('1-');
    expect(toProgramOption('a')).toBe('0a');
  });

  it('never returns more than the declared field width', () => {
    expect(toProgramOption('123')).toHaveLength(OPTION_FIELD_WIDTH);
    expect(toProgramOption('1')).toHaveLength(OPTION_FIELD_WIDTH);
    expect(toProgramOption('')).toHaveLength(OPTION_FIELD_WIDTH);
  });
});

describe('isOptionRefused', () => {
  it('accepts every option the main table declares', () => {
    for (let option = 1; option <= CDEMO_MENU_OPT_COUNT; option += 1) {
      expect(isOptionRefused(String(option), CDEMO_MENU_OPT_COUNT)).toBe(false);
    }
  });

  it('accepts every option the admin table declares', () => {
    for (let option = 1; option <= CDEMO_ADMIN_OPT_COUNT; option += 1) {
      expect(isOptionRefused(String(option), CDEMO_ADMIN_OPT_COUNT)).toBe(false);
    }
  });

  it('refuses a signed entry instead of dispatching the digit inside it', () => {
    // The defect this pins: stripping the sign turned ``-1`` into option 1 and opened
    // Account View, an option the operator never asked for.
    expect(isOptionRefused('-1', CDEMO_MENU_OPT_COUNT)).toBe(true);
  });

  it('refuses any other non-digit content', () => {
    expect(isOptionRefused('a', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('1a', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('.5', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('\t', CDEMO_MENU_OPT_COUNT)).toBe(true);
  });

  it('refuses ZEROS, whether entered or left blank', () => {
    expect(isOptionRefused('0', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('00', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('  ', CDEMO_MENU_OPT_COUNT)).toBe(true);
  });

  it('refuses an option above the declared count of each table', () => {
    expect(isOptionRefused('11', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('99', CDEMO_MENU_OPT_COUNT)).toBe(true);
    expect(isOptionRefused('5', CDEMO_ADMIN_OPT_COUNT)).toBe(true);
  });

  it('refuses an entry whose significant digits sit past the field width', () => {
    // ``maxlength`` and the entry handler both clamp to the field width, so only the
    // first two positions can ever reach the edit; ``'001'`` cannot mean option 1.
    expect(isOptionRefused('001', CDEMO_MENU_OPT_COUNT)).toBe(true);
  });
});

describe('MSG_INVALID_OPTION', () => {
  it('is the refusal literal verbatim, with its three trailing periods', () => {
    expect(MSG_INVALID_OPTION).toBe('Please enter a valid option number...');
  });
});
