/**
 * PFKeyBar
 * ========
 *
 * :purpose: Render the BMS line-24 function-key legend as accessible buttons and
 *     bind the physical 3270 AID keys to their per-screen handlers. Mirrors the
 *     AID-to-PFKey mapping of ``app/cpy/CSSTRPFY.cpy`` and the navigation
 *     conventions of AAP 0.3.5 (ENTER=submit, PF3=exit/back, PF7=page backward,
 *     PF8=page forward), plus any extra keys a page declares (F4/F5/F12).
 */
import { useEffect, useRef } from 'react';
import type { ReactElement } from 'react';
import { PfKeyAction } from '../types';

/**
 * :purpose: One entry in the line-24 function-key legend.
 * :param action: The AID / PF-key this entry represents.
 * :param label: Full legend text shown on the button (e.g. ``F3=Exit``).
 * :param onActivate: Callback invoked when the key or its button is activated.
 * :param enabled: When ``false`` the key renders but neither clicks nor keydown fire.
 * :param dark: When ``true`` the legend is not displayed, the effect of a BMS
 *     ``ATTRB=DRK`` legend field the program has not yet un-darkened. The AID stays
 *     live, so the key still reaches the screen's handler exactly as it reaches a
 *     3270 program whose legend text happens to be non-display.
 */
export interface PFKeyDef {
  action: PfKeyAction;
  label: string;
  onActivate: () => void;
  enabled?: boolean;
  dark?: boolean;
}

/**
 * :purpose: Props for :func:`PFKeyBar`.
 * :param keys: Enabled function keys and handlers supplied by the active page.
 */
export interface PFKeyBarProps {
  keys: PFKeyDef[];
}

/**
 * :purpose: Translate a physical ``KeyboardEvent.key`` into the matching
 *     :class:`PfKeyAction`, or ``null`` when the key is not a mapped AID.
 * :param key: The DOM keyboard key name.
 * :returns: The corresponding PF-key action, or ``null``.
 */
function keyToAction(key: string): PfKeyAction | null {
  switch (key) {
    case 'Enter':
      return PfKeyAction.Enter;
    case 'F3':
      return PfKeyAction.PF3;
    case 'F4':
      return PfKeyAction.PF4;
    case 'F5':
      return PfKeyAction.PF5;
    case 'F7':
      return PfKeyAction.PF7;
    case 'F8':
      return PfKeyAction.PF8;
    case 'F12':
      return PfKeyAction.PF12;
    default:
      return null;
  }
}

/**
 * :purpose: The shared line-24 PF-key bar.
 * :param keys: The enabled function keys and handlers for the active screen.
 * :returns: The rendered function-key toolbar.
 */
export default function PFKeyBar({ keys }: PFKeyBarProps): ReactElement {
  // The legend a page publishes is rebuilt whenever one of its handlers changes
  // identity. Holding it in a ref that every render refreshes lets the document
  // listener always read the latest handlers while being bound exactly once, so a
  // keystroke can never reach a handler from a superseded render and the listener is
  // not rebound on every publication.
  const keysRef = useRef<PFKeyDef[]>(keys);
  useEffect(() => {
    keysRef.current = keys;
  }, [keys]);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent): void {
      const action = keyToAction(event.key);
      if (action === null) {
        return;
      }
      // Avoid double-invoking ENTER when a legend button already holds focus:
      // its native click handler fires on its own.
      if (action === PfKeyAction.Enter && event.target instanceof HTMLButtonElement) {
        return;
      }
      const declared = keysRef.current.find((k) => k.action === action);
      if (declared === undefined) {
        return;
      }
      // The active screen claims this AID, so the browser's own action for the key
      // is suppressed whether or not the key is currently enabled: a disabled F5
      // must not reload the page out from under a request that is still in flight.
      event.preventDefault();
      if (declared.enabled === false) {
        return;
      }
      declared.onActivate();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return (
    <div className="pfKeyBar" role="toolbar" aria-label="Function keys">
      {keys
        .filter((k) => k.dark !== true)
        .map((k) => (
          <button
            key={k.action}
            type="button"
            className="pfKey"
            disabled={k.enabled === false}
            onClick={k.onActivate}
          >
            {k.label}
          </button>
        ))}
    </div>
  );
}
