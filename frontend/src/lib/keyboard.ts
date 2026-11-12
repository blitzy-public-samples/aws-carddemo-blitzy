/**
 * keyboard.ts — Shared keyboard-shortcut arbitration helpers.
 *
 * TRACEABILITY: the legacy 3270 screens responded to PF/function keys (Enter,
 * F3, F5, PF7/PF8, ...) at the SCREEN level — the whole panel reacted, not an
 * individual field. In the SPA those screen shortcuts are wired to a container
 * `onKeyDown` (or, for a couple of pages, a `window` keydown listener). Without
 * scoping, an Enter (or Space) pressed while a button, link, or ARIA widget is
 * focused would fire BOTH the control's own activation AND the screen-level
 * shortcut, duplicating a fetch/submit (QA M-28). It could also let a page and
 * the AppShell both act on Escape (double navigation).
 *
 * This module centralizes the arbitration so every handler applies the same
 * rule: a screen-level activation shortcut (Enter/Space) is suppressed when the
 * event originates from a control that natively owns that key. Free-text inputs
 * are deliberately NOT suppressed, preserving the legacy "type an id, press
 * Enter to load" behavior. Escape ownership is handled at the call sites (a page
 * that owns Escape stops propagation / prevents default; otherwise the AppShell
 * is the single global fallback owner).
 *
 * Greenfield test infrastructure consumer; no direct legacy COBOL origin.
 *
 * @see AAP §0.4.4 — PF-key actions map to buttons plus keyboard shortcuts.
 */

/**
 * Keys that natively activate an interactive control (a button, link, or ARIA
 * widget). Both the modern `' '` and the legacy `'Spacebar'` spellings are
 * included so the guard is robust across browsers.
 */
const ACTIVATION_KEYS: ReadonlySet<string> = new Set(['Enter', ' ', 'Spacebar']);

/**
 * ARIA roles whose elements are activated by Enter/Space and therefore own
 * those keys. A screen-level activation shortcut must not double-fire when one
 * of these is focused.
 */
const INTERACTIVE_ROLES: ReadonlySet<string> = new Set([
    'button',
    'link',
    'menuitem',
    'menuitemcheckbox',
    'menuitemradio',
    'tab',
    'switch',
    'checkbox',
    'radio',
    'option',
]);

/**
 * CSS selector matching the native/ARIA interactive ancestors that own
 * activation keys. Used with `closest()` so focus landing on an inner element
 * of a composite control (for example the `<span>` inside a MUI `Button`) is
 * still recognized as interactive.
 */
const INTERACTIVE_SELECTOR =
    'button, a[href], select, textarea, [role="button"], [role="link"], ' +
    '[role="menuitem"], [role="tab"], [role="switch"], [role="option"]';

/**
 * Reports whether an event target is a control that natively activates on
 * Enter/Space (a button, link, `<select>`, `<textarea>`, or ARIA widget), or is
 * nested inside one.
 *
 * A free-text `<input>` (for example a MUI `TextField`) is intentionally NOT
 * treated as interactive here: on the legacy screens Enter in a data-entry
 * field triggered the screen action, and that behavior is preserved.
 *
 * @param target - The event target to classify (typically `event.target`).
 * @returns `true` when the target owns activation keys and a screen-level
 *     shortcut should stand down; otherwise `false`.
 */
export function IsInteractiveActivationTarget(
    target: EventTarget | null,
): boolean {
    if (target === null || !(target instanceof HTMLElement)) {
        return false;
    }

    const tagName = target.tagName;
    if (
        tagName === 'BUTTON' ||
        tagName === 'SELECT' ||
        tagName === 'TEXTAREA'
    ) {
        return true;
    }
    if (tagName === 'A' && target.hasAttribute('href')) {
        return true;
    }

    const explicitRole = target.getAttribute('role');
    if (explicitRole !== null && INTERACTIVE_ROLES.has(explicitRole)) {
        return true;
    }

    // Focus may land on an inner node of a composite control; walk up to catch
    // the interactive ancestor (for example a MUI Button's inner label span).
    return target.closest(INTERACTIVE_SELECTOR) !== null;
}

/**
 * Reports whether a screen-level Enter/Space activation shortcut should be
 * suppressed for this event because a focused interactive control already owns
 * the key.
 *
 * Handlers call this first: when it returns `true` they return early and let the
 * focused control handle the key, preventing the duplicate fetch/submit that
 * QA M-28 identified.
 *
 * @param key - The pressed key (`event.key`).
 * @param target - The event target (`event.target`).
 * @returns `true` when the shortcut must stand down; otherwise `false`.
 */
export function ShouldSuppressActivationShortcut(
    key: string,
    target: EventTarget | null,
): boolean {
    if (!ACTIVATION_KEYS.has(key)) {
        return false;
    }
    return IsInteractiveActivationTarget(target);
}

/**
 * Resolves the actual focusable element for a control located by `name`.
 *
 * A MUI `TextField` renders a directly focusable `<input name>`; a MUI `Select`
 * renders its value-bearing `<input name>` as a hidden native input
 * (`aria-hidden`, `tabindex="-1"`) paired with a focusable `role="combobox"`
 * trigger inside the same `.MuiInputBase-root` wrapper. This returns the trigger
 * for the Select shape and the element itself for a plain input.
 *
 * @param element - The element found by `name`.
 * @returns The element that should receive focus, or `null` when a Select's
 *     trigger cannot be located.
 */
function ResolveFocusableControl(element: HTMLElement): HTMLElement | null {
    const isNonFocusableNativeInput =
        element.getAttribute('aria-hidden') === 'true' ||
        element.getAttribute('tabindex') === '-1' ||
        (element instanceof HTMLInputElement && element.type === 'hidden');
    if (isNonFocusableNativeInput) {
        const wrapper = element.closest('.MuiInputBase-root');
        return wrapper?.querySelector<HTMLElement>('[role="combobox"]') ?? null;
    }
    return element;
}

/**
 * Moves focus to a form control identified by its `name` attribute.
 *
 * Data-entry screens call this to park the cursor on a specific field after a
 * failed submit (QA Issue 5), reproducing the legacy 3270 behavior of placing
 * the cursor on the field that needs attention. It resolves the correct
 * focusable element for both a `TextField` and a `Select` (see
 * {@link ResolveFocusableControl}).
 *
 * Because the control elements exist in the DOM regardless of validation state,
 * this is safe to call synchronously right after setting the error state — it
 * never depends on an error-driven React re-render having flushed first.
 *
 * @param fieldName - The control's `name` (the FormField `name` prop).
 * @returns `true` when a matching control was found and focused; else `false`.
 */
export function FocusFieldByName(fieldName: string): boolean {
    if (typeof document === 'undefined' || fieldName === '') {
        return false;
    }
    const named = document.getElementsByName(fieldName);
    if (named.length === 0) {
        return false;
    }
    const focusTarget = ResolveFocusableControl(named[0] as HTMLElement);
    if (focusTarget === null) {
        return false;
    }
    focusTarget.focus();
    return true;
}

/**
 * Focuses the first invalid field, honoring the caller's on-screen field order.
 *
 * The caller supplies its fields in display order plus the authoritative set of
 * field names it just marked invalid, so this never reads validation state back
 * out of the DOM (which would race React's asynchronous re-render). Focus moves
 * to the first ordered field present in the invalid set — matching the legacy
 * behavior of parking the cursor on the first field in error (QA Issue 5).
 *
 * @param orderedFieldNames - Field `name`s in the order they appear on screen.
 * @param invalidFieldNames - The set of field `name`s currently in error.
 * @returns `true` when a field was focused; otherwise `false`.
 */
export function FocusFirstInvalidField(
    orderedFieldNames: readonly string[],
    invalidFieldNames: ReadonlySet<string>,
): boolean {
    for (const fieldName of orderedFieldNames) {
        if (invalidFieldNames.has(fieldName) && FocusFieldByName(fieldName)) {
            return true;
        }
    }
    return false;
}
