/**
 * :purpose: Pin the browse-filter edits to the COBOL class tests they reproduce —
 *     ``COTRN00C`` L206-217 and ``COCRDLIC`` ``2210-EDIT-ACCOUNT`` / ``2220-EDIT-CARD``
 *     — so a value that differs from the one on display can never reach a browse.
 */

import { describe, expect, it } from '@jest/globals';
import {
  isFilterAllDigits,
  isFilterNotSupplied,
  limitToFieldWidth,
} from './screenFilters';

describe('isFilterNotSupplied (EQUAL LOW-VALUES OR EQUAL SPACES)', () => {
  it('treats an untouched field as not supplied', () => {
    expect(isFilterNotSupplied('')).toBe(true);
  });

  it('treats a blanked field as not supplied, at any width', () => {
    expect(isFilterNotSupplied(' ')).toBe(true);
    expect(isFilterNotSupplied('           ')).toBe(true);
  });

  it('does NOT treat a tab as not supplied', () => {
    // A tab is neither SPACES nor LOW-VALUES, so the source falls through to the
    // numeric edit. Trimming it away is what let a pasted tab browse the whole file
    // while the screen reported success.
    expect(isFilterNotSupplied('\t')).toBe(false);
    expect(isFilterNotSupplied(' \t ')).toBe(false);
  });

  it('does not treat other whitespace as not supplied', () => {
    expect(isFilterNotSupplied('\n')).toBe(false);
    expect(isFilterNotSupplied('\u00a0')).toBe(false);
  });

  it('does not treat a value as not supplied', () => {
    expect(isFilterNotSupplied('0')).toBe(false);
    expect(isFilterNotSupplied('15')).toBe(false);
  });
});

describe('isFilterAllDigits (COBOL IS NUMERIC on PIC X(n))', () => {
  it('accepts a value whose every character is an ASCII digit', () => {
    expect(isFilterAllDigits('0')).toBe(true);
    expect(isFilterAllDigits('0000001000683580')).toBe(true);
  });

  it('rejects an empty value', () => {
    expect(isFilterAllDigits('')).toBe(false);
  });

  it('rejects surrounding or embedded spaces', () => {
    // ``" 15 "`` was visibly retained while ``15`` went on the wire. The class test
    // examines the whole field, so a space anywhere in it fails.
    expect(isFilterAllDigits(' 15 ')).toBe(false);
    expect(isFilterAllDigits('1 5')).toBe(false);
    expect(isFilterAllDigits('15 ')).toBe(false);
  });

  it('rejects a tab, a sign and a decimal point', () => {
    expect(isFilterAllDigits('\t')).toBe(false);
    expect(isFilterAllDigits('15\t')).toBe(false);
    expect(isFilterAllDigits('+15')).toBe(false);
    expect(isFilterAllDigits('-15')).toBe(false);
    expect(isFilterAllDigits('1.5')).toBe(false);
  });

  it('rejects letters and non-ASCII digits', () => {
    expect(isFilterAllDigits('ABC')).toBe(false);
    // A COBOL numeric class test recognises no digit outside 0-9.
    expect(isFilterAllDigits('١٥')).toBe(false);
    expect(isFilterAllDigits('１５')).toBe(false);
  });
});

describe('limitToFieldWidth (the field cannot hold more than LENGTH=n)', () => {
  it('leaves a value the field can hold untouched', () => {
    expect(limitToFieldWidth('', 16)).toBe('');
    expect(limitToFieldWidth('15', 16)).toBe('15');
    expect(limitToFieldWidth('1111111111111111', 16)).toBe('1111111111111111');
  });

  it('refuses the characters past the declared width', () => {
    // Nineteen digits reached the browse and overflowed the key parse; the field
    // COTRN00.bms declares is sixteen characters wide and cannot hold a seventeenth.
    expect(limitToFieldWidth('1111111111111111111', 16)).toBe('1111111111111111');
    expect(limitToFieldWidth('1234567890123456789012345', 16)).toBe('1234567890123456');
    expect(limitToFieldWidth('1234567890123456789012345', 16)).toHaveLength(16);
  });
});
