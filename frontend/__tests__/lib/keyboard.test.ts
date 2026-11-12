/**
 * keyboard.test.ts — Jest unit suite for the shared keyboard-shortcut
 * arbitration helper `@/lib/keyboard` (frontend/src/lib/keyboard.ts).
 *
 * WHAT IS VERIFIED (QA M-28 — screen shortcuts must not hijack focused
 * controls or duplicate a fetch/submit):
 *   - `ShouldSuppressActivationShortcut` returns TRUE for Enter/Space when the
 *     event target is a control that natively activates on those keys (button,
 *     link, select, textarea, or an ARIA widget role), including a nested inner
 *     node of a composite control;
 *   - it returns FALSE for a free-text `<input>` so the legacy "type an id,
 *     press Enter to load" screen behavior is preserved;
 *   - it returns FALSE for non-activation keys (F3, Escape, PageDown, ...)
 *     regardless of target, so PF-key handlers are never suppressed;
 *   - it tolerates a null target.
 *
 * These are pure DOM-predicate functions; the only collaborator is jsdom, which
 * supplies real `HTMLElement` targets. Greenfield test infra — no legacy origin.
 */

import {
    ShouldSuppressActivationShortcut,
    IsInteractiveActivationTarget,
} from '@/lib/keyboard';

/**
 * Create a detached element (optionally with attributes) to stand in for an
 * `event.target`. Detached is sufficient: the helper inspects tag name, the
 * `href`/`role` attributes, and `closest()`, none of which require attachment.
 *
 * @param tag - The element tag to create (for example `'button'`).
 * @param attributes - Optional attribute name/value pairs to set.
 * @returns The configured element.
 */
function makeElement(
    tag: string,
    attributes: Record<string, string> = {},
): HTMLElement {
    const element = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes)) {
        element.setAttribute(name, value);
    }
    return element;
}

describe('IsInteractiveActivationTarget', () => {
    it('recognizes native activating controls', () => {
        expect(IsInteractiveActivationTarget(makeElement('button'))).toBe(true);
        expect(IsInteractiveActivationTarget(makeElement('select'))).toBe(true);
        expect(IsInteractiveActivationTarget(makeElement('textarea'))).toBe(
            true,
        );
        expect(
            IsInteractiveActivationTarget(makeElement('a', { href: '/x' })),
        ).toBe(true);
    });

    it('recognizes ARIA widget roles', () => {
        expect(
            IsInteractiveActivationTarget(
                makeElement('div', { role: 'button' }),
            ),
        ).toBe(true);
        expect(
            IsInteractiveActivationTarget(makeElement('div', { role: 'link' })),
        ).toBe(true);
    });

    it('walks up to an interactive ancestor (composite control inner node)', () => {
        // A MUI Button renders a <span> label inside the <button>; focus/target
        // may be that span. closest() must still classify it as interactive.
        const button = makeElement('button');
        const innerLabel = makeElement('span');
        button.appendChild(innerLabel);

        expect(IsInteractiveActivationTarget(innerLabel)).toBe(true);
    });

    it('does NOT treat a free-text input or plain text as interactive', () => {
        expect(
            IsInteractiveActivationTarget(makeElement('input', { type: 'text' })),
        ).toBe(false);
        expect(IsInteractiveActivationTarget(makeElement('span'))).toBe(false);
        expect(IsInteractiveActivationTarget(makeElement('div'))).toBe(false);
    });

    it('does NOT treat a bare anchor without href as interactive', () => {
        expect(IsInteractiveActivationTarget(makeElement('a'))).toBe(false);
    });

    it('tolerates a null target', () => {
        expect(IsInteractiveActivationTarget(null)).toBe(false);
    });
});

describe('ShouldSuppressActivationShortcut', () => {
    it('suppresses Enter on an interactive control (no duplicate submit)', () => {
        expect(
            ShouldSuppressActivationShortcut('Enter', makeElement('button')),
        ).toBe(true);
    });

    it('suppresses Space on an interactive control', () => {
        expect(
            ShouldSuppressActivationShortcut(
                ' ',
                makeElement('div', { role: 'button' }),
            ),
        ).toBe(true);
        // Legacy 'Spacebar' spelling is handled too.
        expect(
            ShouldSuppressActivationShortcut('Spacebar', makeElement('button')),
        ).toBe(true);
    });

    it('does NOT suppress Enter on a free-text input (legacy screen behavior)', () => {
        expect(
            ShouldSuppressActivationShortcut(
                'Enter',
                makeElement('input', { type: 'text' }),
            ),
        ).toBe(false);
    });

    it('never suppresses non-activation keys, regardless of target', () => {
        const button = makeElement('button');
        for (const key of ['F3', 'F5', 'Escape', 'PageDown', 'PageUp', 'a']) {
            expect(ShouldSuppressActivationShortcut(key, button)).toBe(false);
        }
    });

    it('tolerates a null target', () => {
        expect(ShouldSuppressActivationShortcut('Enter', null)).toBe(false);
    });
});
