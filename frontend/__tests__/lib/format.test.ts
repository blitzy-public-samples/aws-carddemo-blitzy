/**
 * format.test.ts -- Jest unit suite for the shared display-formatting helper
 * `@/lib/format` (frontend/src/lib/format.ts).
 *
 * WHAT IS VERIFIED (QA finding #10 — consistent currency formatting):
 *   - `FormatMoney` prefixes the currency symbol (`$`) to a decimal string;
 *   - the amount is rendered VERBATIM — no rounding, padding, thousands
 *     separators, or numeric coercion (AAP §0.7.1: exact decimal, never float);
 *   - negative and zero amounts are preserved exactly;
 *   - empty / whitespace-only input yields an empty string (no bare `$`).
 *
 * There are no collaborators to mock: `FormatMoney` is a pure function.
 */

import { FormatMoney, CURRENCY_PREFIX } from '@/lib/format';

describe('FormatMoney', () => {
    it('prefixes the currency symbol to a decimal string', () => {
        expect(FormatMoney('1000.00')).toBe('$1000.00');
        expect(CURRENCY_PREFIX).toBe('$');
    });

    it('renders the amount verbatim without rounding or padding', () => {
        // A one-decimal value must NOT be padded to two decimals, and a
        // long-fraction value must NOT be rounded — the string is untouched.
        expect(FormatMoney('3800.5')).toBe('$3800.5');
        expect(FormatMoney('0.005')).toBe('$0.005');
    });

    it('never inserts thousands separators (no locale reformatting)', () => {
        expect(FormatMoney('1234567.89')).toBe('$1234567.89');
    });

    it('preserves a leading minus sign for negative amounts', () => {
        expect(FormatMoney('-25.50')).toBe('$-25.50');
    });

    it('preserves an exact zero balance', () => {
        expect(FormatMoney('0.00')).toBe('$0.00');
    });

    it('returns an empty string for empty or whitespace-only input', () => {
        expect(FormatMoney('')).toBe('');
        expect(FormatMoney('   ')).toBe('');
    });
});
