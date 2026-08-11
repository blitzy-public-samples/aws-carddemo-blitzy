/**
 * :module: ``frontend/src/hooks/useScreenAction.test.tsx``
 * :purpose: Cover the guarantee ``useScreenAction`` exists to provide — that an AID
 *     activated in the same task as the keystroke, paste or autofill that changed the
 *     screen acts on the value now displayed, never on the value it replaced — and that
 *     the published activator keeps one identity so a screen's line-24 legend is not
 *     rebuilt on every keystroke.
 * :output: Jest test suite; no exports.
 */

import { render, fireEvent } from '@testing-library/react';
import { useState } from 'react';
import type { ReactElement } from 'react';
import { useScreenAction } from './useScreenAction';

/**
 * :purpose: Stand in for a screen whose entry field feeds a handler published to the
 *     shared frame. ``onPublish`` receives the activator on every render, so a test can
 *     hold the one captured at mount and invoke it later — exactly what ``PFKeyBar``
 *     does with the ``onActivate`` a page published for it.
 * :param onSend: Receives the value the activator dispatched.
 * :param onPublish: Receives every activator identity the screen publishes.
 * :returns: The rendered probe.
 */
function EntryScreen({
  onSend,
  onPublish,
}: {
  onSend: (value: string) => void;
  onPublish: (activator: () => void) => void;
}): ReactElement {
  const [value, setValue] = useState('');
  const activate = useScreenAction((): void => {
    onSend(value);
  });
  onPublish(activate);
  return (
    <input
      data-testid="entry"
      value={value}
      onChange={(event) => {
        setValue(event.target.value);
      }}
    />
  );
}

/**
 * :purpose: Render the probe and return the entry field together with the activator
 *     captured on the very first render.
 * :param sent: Collects the values the activator dispatches.
 * :param published: Collects every activator identity published.
 * :returns: The entry field and the activator captured at mount.
 */
function mountEntryScreen(
  sent: string[],
  published: Array<() => void>,
): { entry: HTMLInputElement; capturedAtMount: () => void } {
  render(
    <EntryScreen
      onSend={(value) => sent.push(value)}
      onPublish={(activator) => published.push(activator)}
    />,
  );
  const entry = document.querySelector('[data-testid="entry"]');
  if (!(entry instanceof HTMLInputElement)) {
    throw new Error('entry field not rendered');
  }
  const [capturedAtMount] = published;
  return { entry, capturedAtMount };
}

describe('useScreenAction', () => {
  it('dispatches the value now on screen through an activator captured earlier', () => {
    const sent: string[] = [];
    const published: Array<() => void> = [];
    const { entry, capturedAtMount } = mountEntryScreen(sent, published);

    fireEvent.change(entry, { target: { value: '4' } });
    capturedAtMount();
    fireEvent.change(entry, { target: { value: '47' } });
    capturedAtMount();

    // The pre-fix behaviour resolved the handler through a passive effect, so an
    // activator captured before the keystroke dispatched the value it replaced.
    expect(sent).toEqual(['4', '47']);
  });

  it('publishes one activator identity for the lifetime of the screen', () => {
    const sent: string[] = [];
    const published: Array<() => void> = [];
    const { entry } = mountEntryScreen(sent, published);

    fireEvent.change(entry, { target: { value: '4' } });
    fireEvent.change(entry, { target: { value: '47' } });

    expect(published.length).toBeGreaterThan(1);
    expect(new Set(published).size).toBe(1);
  });

  it('forwards arguments and the handler result', () => {
    const results: number[] = [];
    function Probe(): ReactElement {
      const [factor] = useState(2);
      const activate = useScreenAction((n: number): number => n * factor);
      return (
        <button
          type="button"
          data-testid="double"
          onClick={() => {
            results.push(activate(21));
          }}
        />
      );
    }
    render(<Probe />);
    const button = document.querySelector('[data-testid="double"]');
    if (!(button instanceof HTMLButtonElement)) {
      throw new Error('probe not rendered');
    }
    fireEvent.click(button);
    expect(results).toEqual([42]);
  });
});
