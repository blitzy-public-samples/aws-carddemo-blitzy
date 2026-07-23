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
