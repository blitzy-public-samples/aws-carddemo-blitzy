/**
 * :module: ``frontend/src/hooks/useScreenFocus.ts``
 * :purpose: Reproduce the BMS cursor placement rules as focus management, in one place so
 *     every screen behaves the same way. A mapset marks exactly one field ``ATTRB=IC`` so the
 *     cursor lands there when the map is sent, and a program that detects a bad field re-sends
 *     the map with the cursor on that field; the two hooks here are those two rules.
 * :output: The named ``placeCursor`` function and the ``useInitialFocus``,
 *     ``useFocusOnChange`` and ``useFocusOnSettled`` hooks.
 * :note: Logic only — no UI and no I/O. Every browser global is guarded, so the module
 *     imports cleanly under Jest (jsdom).
 * :note: Every rule is a LAYOUT effect, so the cursor is placed in the same commit that
 *     rendered the field. Under a passive effect the field is enabled but unfocused for a
 *     frame, which is the interval in which focus measurably rests on the document body
 *     between two attempts on the same screen.
 */

import { useLayoutEffect, useRef } from 'react';
import type { RefObject } from 'react';
import { useSendCount } from '../components/Layout';

/**
 * :purpose: Place the cursor on a field the way a program does, WITHOUT moving what the
 *     operator is looking at. A 3270 device shows all 24 rows and all 80 columns at once, so
 *     ``MOVE -1 TO <field>L`` moves the cursor and nothing else; the frame never travels to
 *     bring a field into sight because every field is already in sight. A browser does travel:
 *     the default ``focus()`` scrolls the focused element into the viewport, and on a viewport
 *     narrower than the 80 columns that scrolled the whole frame sideways the instant a screen
 *     placed its cursor on a wide field. Arriving on `Update Credit Card Details` put the
 *     cursor on the 50-character name field and the operator was left looking at the middle of
 *     the frame, reading the title as ``tails`` with every caption off to the left, having
 *     done nothing at all. Suppressing the scroll keeps the cursor placement exact and leaves
 *     the view where the operator put it; panning stays available and stays theirs to command.
 * :param target: the field to place the cursor on; ``null`` or ``undefined`` when the
 *     field is not rendered, in which case nothing happens.
 * :returns: nothing.
 */
export function placeCursor(target: HTMLElement | null | undefined): void {
  target?.focus({ preventScroll: true });
}

/**
 * :purpose: Place the cursor on a screen's insert-cursor field when the screen is
 *     first shown, mirroring the single BMS ``ATTRB=IC`` field of its mapset.
 * :param target: An existing ref for that field, so one field can be driven by both
 *     rules; omitted creates a ref.
 * :returns: The ref to attach to that field; it is focused once, after the first
 *     paint.
 */
export function useInitialFocus<T extends HTMLElement>(
  target?: RefObject<T | null>,
): RefObject<T | null> {
  const own = useRef<T | null>(null);
  const ref = target ?? own;
  useLayoutEffect(() => {
    placeCursor(ref.current);
  }, [ref]);
  return ref;
}

/**
 * :purpose: Move the cursor to a field whenever a screen reports a new outcome for it,
 *     mirroring a program that re-sends its map with the cursor on the field it rejected.
 * :param token: The value identifying the current outcome — typically the line-23 message
 *     or the name of the first field that failed its edits. Focus moves when the token changes
 *     to a new non-empty value, and never for an empty or absent token.
 * :param target: An existing ref for the field, so the same field can also carry the
 *     insert-cursor rule; omitted creates a ref.
 * :param ready: ``false`` while the screen's call is outstanding -- the keyboard-locked
 *     interval, during which the entry fields are disabled. Two things go wrong without it. A
 *     screen that publishes its outcome in the same commit that still has the fields disabled
 *     would spend the token on a ``focus()`` that a disabled control ignores, and the effect
 *     would never run again because the token had not changed. And a program performs ``MOVE
 *     -1 TO <field>L`` on EVERY failing pass, so submitting the same wrong value twice must
 *     place the cursor twice, which a changed-token test alone cannot do. Defaults to
 *     ``true``, which reproduces the plain changed-token behaviour for a screen that publishes
 *     only once settled.
 * :returns: The ref to attach to the field that should receive the cursor.
 */
export function useFocusOnChange<T extends HTMLElement>(
  token: string | null,
  target?: RefObject<T | null>,
  ready = true,
): RefObject<T | null> {
  const own = useRef<T | null>(null);
  const ref = target ?? own;
  const sendCount = useSendCount();
  const lastToken = useRef<string>('');
  const lastSend = useRef<number>(sendCount);
  const settling = useRef<boolean>(false);
  useLayoutEffect(() => {
    if (!ready) {
      // The fields are disabled; remember that an answer is owed so the outcome it
      // publishes is treated as newly published even when its text repeats.
      settling.current = true;
      return;
    }
    const justSettled = settling.current;
    settling.current = false;
    const newSend = sendCount !== lastSend.current;
    lastSend.current = sendCount;
    if (token === null || token === '') {
      return;
    }
    // A program that rejects a field re-sends the map with the cursor on it EVERY time, so
    // an identical rejection repeated on a later send, or answered by a call that has just
    // settled, places the cursor again. An unchanged token on a plain re-render is the same
    // outcome still on display, so the cursor is left where the operator put it.
    if (token === lastToken.current && !newSend && !justSettled) {
      return;
    }
    lastToken.current = token;
    placeCursor(ref.current);
  }, [ready, ref, token, sendCount]);
  return ref;
}

/**
 * :purpose: Re-place the cursor on a screen's insert-cursor field each time the screen
 *     finishes sending its map, mirroring the fact that CICS honours ``ATTRB=IC`` on every
 *     ``SEND MAP`` rather than only the first. A screen that reads its rows before painting
 *     them therefore keeps the cursor on its ``IC`` field once the rows arrive, and keeps it
 *     there after each PF7 / PF8 browse.
 * :param inFlight: ``true`` while the screen's read is outstanding — the keyboard-locked
 *     interval, during which the entry fields are disabled. The cursor is placed when this
 *     settles back to ``false``.
 * :param target: An existing ref for that field, so one field can be driven by this rule
 *     and by the two above; omitted creates a ref.
 * :returns: The ref to attach to that field.
 * :note: Focusing a disabled element is a no-op, so this rule — not
 * :func: `useInitialFocus` — is what places the cursor on a screen whose entry fields are
 *     disabled while its read is in flight.
 */
export function useFocusOnSettled<T extends HTMLElement>(
  inFlight: boolean,
  target?: RefObject<T | null>,
): RefObject<T | null> {
  const own = useRef<T | null>(null);
  const ref = target ?? own;
  const wasInFlight = useRef<boolean>(false);
  // A layout effect, so the cursor is placed in the SAME commit that re-enables the
  // entry field. A passive effect leaves the field enabled but unfocused for a frame,
  // which is the measurable interval in which focus rests on the document body.
  useLayoutEffect(() => {
    if (wasInFlight.current && !inFlight) {
      placeCursor(ref.current);
    }
    wasInFlight.current = inFlight;
  }, [inFlight, ref]);
  return ref;
}
