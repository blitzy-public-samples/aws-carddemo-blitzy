/**
 * :module: ``frontend/src/pages/tranAddFormat.test.ts``
 * :purpose: Cover the two field edits ``COTRN02C COPY-LAST-TRAN-DATA`` applies to the
 *     values it copies onto the add screen, including the property that matters most:
 *     an edited amount satisfies the screen's own ``+99999999.99`` check, so a copy
 *     followed by ENTER can actually post.
 * :output: Jest test suite; no exports.
 */

import { toAmountPicture, toMapDate, DATE_FIELD_WIDTH } from './tranAddFormat';

/** The shape ``COTRN02C`` requires of ``TRNAMTI`` before it will accept an add. */
const AMOUNT_SHAPE = /^[-+][0-9]{8}\.[0-9]{2}$/;

describe('toAmountPicture', () => {
  it('edits a positive amount into the PIC +99999999.99 picture', () => {
    expect(toAmountPicture('603.22')).toBe('+00000603.22');
  });

  it('keeps the sign of a negative amount', () => {
    expect(toAmountPicture('-919.00')).toBe('-00000919.00');
  });

  it('honours an explicit plus sign', () => {
    expect(toAmountPicture('+56.77')).toBe('+00000056.77');
  });

  it('pads a whole amount to two decimal digits', () => {
    expect(toAmountPicture('100')).toBe('+00000100.00');
  });

  it('pads a single decimal digit', () => {
    expect(toAmountPicture('1.5')).toBe('+00000001.50');
  });

  it('edits zero', () => {
    expect(toAmountPicture('0.00')).toBe('+00000000.00');
  });

  it('fills all eight integer digits', () => {
    expect(toAmountPicture('99999999.99')).toBe('+99999999.99');
  });

  it('ignores surrounding whitespace', () => {
    expect(toAmountPicture('  603.22  ')).toBe('+00000603.22');
  });

  it('produces a value the screen amount check accepts, for every shape it edits', () => {
    for (const raw of ['603.22', '-919.00', '+56.77', '100', '1.5', '0.00', '99999999.99']) {
      expect(toAmountPicture(raw)).toMatch(AMOUNT_SHAPE);
    }
  });

  it('always produces exactly twelve characters', () => {
    for (const raw of ['603.22', '-919.00', '0', '99999999.99']) {
      expect(toAmountPicture(raw)).toHaveLength(12);
    }
  });
});

describe('toMapDate', () => {
  it('truncates a 26-character timestamp to the date the map field holds', () => {
    expect(toMapDate('2022-06-10 19:27:53.000000')).toBe('2022-06-10');
  });

  it('truncates the hyphenated COBOL timestamp form just the same', () => {
    expect(toMapDate('2022-06-10-19.27.53.000000')).toBe('2022-06-10');
  });

  it('leaves a value already of field width unchanged', () => {
    expect(toMapDate('2022-06-10')).toBe('2022-06-10');
  });

  it('never returns more than the field width', () => {
    expect(toMapDate('2022-06-10 19:27:53.000000')).toHaveLength(DATE_FIELD_WIDTH);
  });

  it('returns the empty string for an empty value', () => {
    expect(toMapDate('   ')).toBe('');
  });
});
