/**
 * :module: ``frontend/src/hooks/useScreenFocus.test.tsx``
 * :purpose: Cover the BMS cursor-placement rules reproduced by ``useScreenFocus`` — the
 *     insert-cursor field of a mapset, the re-send that places the cursor on a rejected
 *     field, and the placement that must survive the keyboard-locked interval during
 *     which a screen's entry fields are disabled while its read is in flight.
 * :output: Jest test suite; no exports.
 */

import { render, act } from '@testing-library/react';
import type { ReactElement } from 'react';
import { useInitialFocus, useFocusOnChange, useFocusOnSettled } from './useScreenFocus';

/**
 * :purpose: Stand in for a screen whose single ``ATTRB=IC`` entry field is disabled
 *     while the screen's read is outstanding, exactly as the card list disables its
 *     browse filters via ``disabled={loading}``.
 * :param inFlight: Whether the read is outstanding.
 * :param message: The current line-23 message, or the empty string for none.
 * :returns: The rendered probe.
 */
function IcFieldScreen({
  inFlight,
  message,
}: {
  inFlight: boolean;
  message: string;
}): ReactElement {
  const ref = useInitialFocus<HTMLInputElement>();
  useFocusOnSettled(inFlight, ref);
  useFocusOnChange(message === '' ? null : message, ref);
  return <input data-testid="icField" disabled={inFlight} ref={ref} />;
}

describe('useInitialFocus', () => {
  it('places the cursor on the insert-cursor field when the screen is first shown', () => {
    const { getByTestId } = render(<IcFieldScreen inFlight={false} message="" />);
    expect(document.activeElement).toBe(getByTestId('icField'));
  });
});

describe('useFocusOnSettled', () => {
  it('places the cursor once the read settles, when mount-time focus could not stick', () => {
    // The field is disabled for the whole in-flight interval, so the mount-time
    // .focus() is a no-op -- this is the regression: the cursor ended up on <body>.
    const { getByTestId, rerender } = render(<IcFieldScreen inFlight message="" />);
    const field = getByTestId('icField');
    expect(field.hasAttribute('disabled')).toBe(true);
    expect(document.activeElement).not.toBe(field);

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="" />);
    });

    expect(field.hasAttribute('disabled')).toBe(false);
    expect(document.activeElement).toBe(field);
  });

  it('re-places the cursor on every subsequent settle, as CICS honours IC on every SEND MAP', () => {
    const { getByTestId, rerender } = render(<IcFieldScreen inFlight message="" />);
    const field = getByTestId('icField');

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="" />);
    });
    expect(document.activeElement).toBe(field);

    // A PF7 / PF8 browse: the field is disabled again. A real browser blurs a field the
    // moment it becomes disabled -- jsdom does not, so the blur is applied explicitly
    // here to reproduce the condition the hook exists to recover from.
    act(() => {
      rerender(<IcFieldScreen inFlight message="" />);
      field.blur();
    });
    expect(document.activeElement).not.toBe(field);

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="" />);
    });
    expect(document.activeElement).toBe(field);
  });

  it('does not move the cursor while the read stays settled', () => {
    const { getByTestId, rerender } = render(<IcFieldScreen inFlight={false} message="" />);
    const field = getByTestId('icField');
    act(() => {
      field.blur();
    });
    expect(document.activeElement).not.toBe(field);

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="" />);
    });

    expect(document.activeElement).not.toBe(field);
  });
});

describe('useFocusOnChange', () => {
  it('returns the cursor to the field when the screen reports a new outcome', () => {
    const { getByTestId, rerender } = render(<IcFieldScreen inFlight={false} message="" />);
    const field = getByTestId('icField');
    act(() => {
      field.blur();
    });

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="INVALID ACTION CODE" />);
    });

    expect(document.activeElement).toBe(field);
  });

  it('does not move the cursor when the same outcome is reported again', () => {
    const { getByTestId, rerender } = render(
      <IcFieldScreen inFlight={false} message="INVALID ACTION CODE" />,
    );
    const field = getByTestId('icField');
    act(() => {
      field.blur();
    });

    act(() => {
      rerender(<IcFieldScreen inFlight={false} message="INVALID ACTION CODE" />);
    });

    expect(document.activeElement).not.toBe(field);
  });
});
