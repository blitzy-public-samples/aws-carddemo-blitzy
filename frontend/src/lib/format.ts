/**
 * Shared display-formatting helpers for the CardDemo frontend.
 *
 * This module centralizes presentation-only formatting so that every screen
 * renders the same values identically. It was extracted to resolve the
 * inconsistency where the account-view screen prefixed monetary amounts with a
 * currency symbol while the bill-payment screen did not (QA finding #10).
 *
 * Compliance (AAP §0.7.1 — exact decimal numerics):
 *   Monetary amounts arrive from the API as Decimal STRINGS (for example
 *   `"1000.00"`). They are rendered VERBATIM and are NEVER parsed into a
 *   JavaScript `number` — floating-point rounding of a currency value is a
 *   regulatory compliance failure. Consequently {@link FormatMoney} only
 *   prepends a currency symbol; it performs no arithmetic, rounding, grouping,
 *   or locale conversion that would require numeric coercion.
 *
 * Ochs Test Rule (AAP §0.8.2): the file name is snake_case (`format.ts`),
 * the exported function is PascalCase (`FormatMoney`), and module constants are
 * ALL_UPPERCASE with underscores (`CURRENCY_PREFIX`).
 *
 * @packageDocumentation
 */

/**
 * Currency symbol prepended to monetary display strings. Kept as a named
 * constant (Ochs "no magic literals") and exported so tests and callers can
 * reference the exact prefix rather than duplicating the glyph.
 */
export const CURRENCY_PREFIX = '$';

/**
 * Formats a monetary amount for display by prefixing {@link CURRENCY_PREFIX}.
 *
 * The input is an exact Decimal amount as a string and is returned verbatim
 * behind the currency symbol; it is never coerced to a number (AAP §0.7.1).
 * An empty or whitespace-only input yields an empty string so that absent
 * amounts render as nothing rather than a bare, valueless currency symbol.
 *
 * @param value - The raw Decimal amount as a string (for example `"1000.00"`).
 * @returns The prefixed display string (for example `"$1000.00"`), or `''`
 *   when {@link value} is empty/whitespace.
 *
 * @example
 * ```ts
 * FormatMoney('1000.00'); // "$1000.00"
 * FormatMoney('-25.50');  // "$-25.50"
 * FormatMoney('');        // ""
 * ```
 */
export function FormatMoney(value: string): string {
    if (value === undefined || value === null || value.trim() === '') {
        return '';
    }
    return `${CURRENCY_PREFIX}${value}`;
}
