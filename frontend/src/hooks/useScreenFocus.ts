/**
 * :module: ``frontend/src/hooks/useScreenFocus.ts``
 * :purpose: Reproduce the BMS cursor placement rules as focus management, in one place
 *     so every screen behaves the same way. A mapset marks exactly one field
 *     ``ATTRB=IC`` so the cursor lands there when the map is sent, and a program that
 *     detects a bad field re-sends the map with the cursor on that field; the two
 *     hooks here are those two rules.
 * :output: The named ``useInitialFocus`` and ``useFocusOnChange`` hooks.
 * :note: Logic only — no UI and no I/O. Every browser global is guarded, so the module
 *     imports cleanly under Jest (jsdom).
 */

import { useEffect, useRef } from 'react';
import type { RefObject } from 'react';

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
  useEffect(() => {
    ref.current?.focus();
  }, [ref]);
  return ref;
}

/**
 * :purpose: Move the cursor to a field whenever a screen reports a new outcome for it,
 *     mirroring a program that re-sends its map with the cursor on the field it
 *     rejected.
 * :param token: The value identifying the current outcome — typically the line-23
 *     message or the name of the first field that failed its edits. Focus moves when
 *     the token changes to a new non-empty value, and never for an empty or absent
 *     token.
 * :param target: An existing ref for the field, so the same field can also carry the
 *     insert-cursor rule; omitted creates a ref.
 * :returns: The ref to attach to the field that should receive the cursor.
 */
export function useFocusOnChange<T extends HTMLElement>(
  token: string | null,
  target?: RefObject<T | null>,
): RefObject<T | null> {
  const own = useRef<T | null>(null);
  const ref = target ?? own;
  const lastToken = useRef<string>('');
  useEffect(() => {
    if (token === null || token === '' || token === lastToken.current) {
      return;
    }
    lastToken.current = token;
    ref.current?.focus();
  }, [ref, token]);
  return ref;
}

/**
 * :purpose: Re-place the cursor on a screen's insert-cursor field each time the screen
 *     finishes sending its map, mirroring the fact that CICS honours ``ATTRB=IC`` on
 *     every ``SEND MAP`` rather than only the first. A screen that reads its rows
 *     before painting them therefore keeps the cursor on its ``IC`` field once the
 *     rows arrive, and keeps it there after each PF7 / PF8 browse.
 * :param inFlight: ``true`` while the screen's read is outstanding — the keyboard-locked
 *     interval, during which the entry fields are disabled. The cursor is placed when
 *     this settles back to ``false``.
 * :param target: An existing ref for that field, so one field can be driven by this
 *     rule and by the two above; omitted creates a ref.
 * :returns: The ref to attach to that field.
 * :note: Focusing a disabled element is a no-op, so this rule — not
 *     :func:`useInitialFocus` — is what places the cursor on a screen whose entry
 *     fields are disabled while its read is in flight.
 */
export function useFocusOnSettled<T extends HTMLElement>(
  inFlight: boolean,
  target?: RefObject<T | null>,
): RefObject<T | null> {
  const own = useRef<T | null>(null);
  const ref = target ?? own;
  const wasInFlight = useRef<boolean>(false);
  useEffect(() => {
    if (wasInFlight.current && !inFlight) {
      ref.current?.focus();
    }
    wasInFlight.current = inFlight;
  }, [inFlight, ref]);
  return ref;
}
