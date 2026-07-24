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
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import { PfKeyAction } from '../types';

/**
 * :purpose: One entry in the line-24 function-key legend.
 * :param action: The AID / PF-key this entry represents.
 * :param label: Full legend text shown on the button (e.g. ``F3=Exit``).
 * :param onActivate: Callback invoked when the key or its button is activated.
 * :param enabled: When ``false`` the key renders but neither clicks nor keydown fire.
 */
export interface PFKeyDef {
  action: PfKeyAction;
  label: string;
  onActivate: () => void;
  enabled?: boolean;
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
      const match = keys.find((k) => k.action === action && k.enabled !== false);
      if (match) {
        event.preventDefault();
        match.onActivate();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [keys]);

  return (
    <div className="pfKeyBar" role="toolbar" aria-label="Function keys">
      {keys.map((k) => (
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
