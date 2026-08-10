/**
 * PFKeyBar component tests.
 *
 * :purpose: Verify the shared BMS line-24 function-key bar
 *     (``app/cpy/CSSTRPFY.cpy``, AAP 0.3.5): one accessible button per supplied
 *     key inside a ``toolbar`` named ``Function keys``; a button click invokes its
 *     handler exactly once; the physical AID keys ``Enter``/``F3``/``F7``/``F8``
 *     each invoke the matching handler exactly once through the document keydown
 *     listener; unmapped keys (``F9``) and disabled keys are ignored; the disabled
 *     button is rendered ``disabled``; that the browser's own action is cancelled for
 *     EVERY AID the active screen declares, enabled or not, so a declared-but-disabled
 *     ``F5`` cannot reload the page while a request is in flight; that an AID the
 *     screen does not declare is left to the browser; that the latest published
 *     handler is the one a keystroke reaches; and that the ENTER double-invoke guard
 *     suppresses the document handler when the keydown target is a legend button.
 */
import { render, screen, fireEvent, createEvent } from '@testing-library/react';
// Jest's ESM runtime does not inject ``jest`` as a global (unlike describe/it/
// expect), so it is imported explicitly.
import { jest } from '@jest/globals';
import { PfKeyAction } from '../types';
import PFKeyBar from './PFKeyBar';
import type { PFKeyDef } from './PFKeyBar';

describe('PFKeyBar', () => {
  it('renders a labeled button per key inside a toolbar named "Function keys"', () => {
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Sign-on', onActivate: jest.fn() },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} />);

    const toolbar = screen.getByRole('group', { name: 'Function keys' });
    expect(toolbar).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ENTER=Sign-on' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('invokes the handler exactly once when its button is clicked', () => {
    const onEnter = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Sign-on', onActivate: onEnter },
    ];
    render(<PFKeyBar keys={keys} />);

    fireEvent.click(screen.getByRole('button', { name: 'ENTER=Sign-on' }));
    expect(onEnter).toHaveBeenCalledTimes(1);
  });

  it('invokes the matching handler exactly once for Enter/F3/F7/F8 keydown', () => {
    const onEnter = jest.fn();
    const onExit = jest.fn();
    const onBackward = jest.fn();
    const onForward = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Continue', onActivate: onEnter },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: onExit },
      { action: PfKeyAction.PF7, label: 'F7=Backward', onActivate: onBackward },
      { action: PfKeyAction.PF8, label: 'F8=Forward', onActivate: onForward },
    ];
    render(<PFKeyBar keys={keys} />);

    // Fire on ``document`` so the keydown target is the document (not a button):
    // this exercises the physical-key path without any native button click.
    fireEvent.keyDown(document, { key: 'Enter' });
    fireEvent.keyDown(document, { key: 'F3' });
    fireEvent.keyDown(document, { key: 'F7' });
    fireEvent.keyDown(document, { key: 'F8' });

    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(onBackward).toHaveBeenCalledTimes(1);
    expect(onForward).toHaveBeenCalledTimes(1);
  });

  it('ignores an unmapped key', () => {
    const onEnter = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Continue', onActivate: onEnter },
    ];
    render(<PFKeyBar keys={keys} />);

    fireEvent.keyDown(document, { key: 'F9' });
    expect(onEnter).not.toHaveBeenCalled();
  });

  it('renders a disabled key as disabled and never invokes it via click or keydown', () => {
    const onForward = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF8, label: 'F8=Forward', onActivate: onForward, enabled: false },
    ];
    render(<PFKeyBar keys={keys} />);

    const button = screen.getByRole('button', { name: 'F8=Forward' });
    expect(button).toBeDisabled();

    fireEvent.click(button);
    fireEvent.keyDown(document, { key: 'F8' });
    expect(onForward).not.toHaveBeenCalled();
  });

  it('cancels the browser default for a declared key that is currently disabled', () => {
    const onSave = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: onSave, enabled: false },
    ];
    render(<PFKeyBar keys={keys} />);

    // The screen claims F5, so the browser's reload must be cancelled even though the
    // key cannot currently act -- a reload during a pending request is exactly what a
    // disabled key has to prevent.
    const cancelled = !fireEvent.keyDown(document, { key: 'F5', cancelable: true });
    expect(cancelled).toBe(true);
    expect(onSave).not.toHaveBeenCalled();
  });

  it('cancels the browser default for a declared key that is enabled', () => {
    const onSave = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: onSave },
    ];
    render(<PFKeyBar keys={keys} />);

    const cancelled = !fireEvent.keyDown(document, { key: 'F5', cancelable: true });
    expect(cancelled).toBe(true);
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('leaves the browser default alone for an undeclared AID when no policy is published', () => {
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} />);

    // F5 belongs to the browser on a screen that neither declares it nor publishes an
    // unhandled-key policy: with no ``WHEN OTHER`` arm to reach, claiming the key would
    // only take it away from the operator.
    const cancelled = !fireEvent.keyDown(document, { key: 'F5', cancelable: true });
    expect(cancelled).toBe(false);
  });

  it('claims an undeclared AID and hands it to the unhandled-key policy', () => {
    const onUnhandled = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} onUnhandledAid={onUnhandled} />);

    // A 3270 transmits every AID and the program answers it -- the ``WHEN OTHER`` arm of
    // its ``EVALUATE EIBAID``. Claiming the key is also what stops F5 reloading the
    // screen and F12 opening the inspector in the middle of a transaction. The keys
    // swept here are the whole recognised AID set (``PfKeyAction``, from the
    // ``CSSTRPFY`` mapping) less the one this screen declares.
    for (const key of ['Enter', 'F4', 'F5', 'F7', 'F8', 'F12']) {
      const event = createEvent.keyDown(document, { key, cancelable: true });
      fireEvent(document, event);
      expect(event.defaultPrevented).toBe(true);
    }
    expect(onUnhandled).toHaveBeenCalledTimes(6);
  });

  it('names the struck AID when it hands an undeclared key to the policy', () => {
    const onUnhandled = jest.fn();
    render(
      <PFKeyBar
        keys={[{ action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() }]}
        onUnhandledAid={onUnhandled}
      />,
    );

    fireEvent.keyDown(document, { key: 'F7' });
    expect(onUnhandled).toHaveBeenCalledWith(PfKeyAction.PF7);
  });

  it('discards an undeclared AID struck while the keyboard is locked', () => {
    const onUnhandled = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} inputInhibited onUnhandledAid={onUnhandled} />);

    // The keyboard is locked from the moment an AID is transmitted until the map
    // arrives, so the key is still CLAIMED -- the browser must not act on it either --
    // but it is discarded rather than queued, exactly as a declared key is.
    const event = createEvent.keyDown(document, { key: 'F5', cancelable: true });
    fireEvent(document, event);

    expect(event.defaultPrevented).toBe(true);
    expect(onUnhandled).not.toHaveBeenCalled();
  });

  it('counts an undeclared AID as a send, so a repeated key re-announces the reply', () => {
    const onDispatched = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(
      <PFKeyBar keys={keys} onAidDispatched={onDispatched} onUnhandledAid={jest.fn()} />,
    );

    // The program answers by re-sending its map, so the send counts exactly as a
    // declared key's does: that is what re-announces the message region and re-places
    // the cursor when the SAME key is struck twice.
    fireEvent.keyDown(document, { key: 'F7' });
    fireEvent.keyDown(document, { key: 'F7' });
    expect(onDispatched).toHaveBeenCalledTimes(2);
  });

  it('routes an undeclared AID to the latest published policy', () => {
    const first = jest.fn();
    const second = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    const { rerender } = render(<PFKeyBar keys={keys} onUnhandledAid={first} />);
    rerender(<PFKeyBar keys={keys} onUnhandledAid={second} />);

    fireEvent.keyDown(document, { key: 'F5' });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('prefers a declared key over the unhandled-key policy', () => {
    const onExit = jest.fn();
    const onUnhandled = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: onExit },
    ];
    render(<PFKeyBar keys={keys} onUnhandledAid={onUnhandled} />);

    fireEvent.keyDown(document, { key: 'F3' });
    expect(onExit).toHaveBeenCalledTimes(1);
    expect(onUnhandled).not.toHaveBeenCalled();
  });

  it('prefers a dark declared key over the unhandled-key policy', () => {
    const onEnter = jest.fn();
    const onUnhandled = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Fetch', onActivate: onEnter, dark: true },
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} onUnhandledAid={onUnhandled} />);

    // A key the legend does not advertise is still DECLARED: COACTVW.bms names only
    // 'F3=Exit' on line 24, yet ENTER is in COACTVWC's valid AID set.
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onUnhandled).not.toHaveBeenCalled();
  });

  it('does not reach the unhandled policy for a declared key that is disabled', () => {
    const onSave = jest.fn();
    const onUnhandled = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: onSave, enabled: false },
    ];
    render(<PFKeyBar keys={keys} onUnhandledAid={onUnhandled} />);

    // The screen claims the key either way, so the browser cannot act on it; a key the
    // screen declares is never re-routed to the WHEN OTHER arm.
    const event = createEvent.keyDown(document, { key: 'F5', cancelable: true });
    fireEvent(document, event);

    expect(event.defaultPrevented).toBe(true);
    expect(onSave).not.toHaveBeenCalled();
    expect(onUnhandled).not.toHaveBeenCalled();
  });

  it('routes a keystroke to the latest published handler', () => {
    const first = jest.fn();
    const second = jest.fn();
    const { rerender } = render(
      <PFKeyBar
        keys={[{ action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: first }]}
      />,
    );

    // A page republishes its legend whenever one of its handlers changes identity;
    // the keystroke must reach the newest one, not the one bound at mount.
    rerender(
      <PFKeyBar
        keys={[{ action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: second }]}
      />,
    );
    fireEvent.keyDown(document, { key: 'F3' });

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('renders no legend for a dark key but still routes its AID to the handler', () => {
    const onSave = jest.fn();
    const onExit = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: onExit },
      { action: PfKeyAction.PF5, label: 'F5=Save', onActivate: onSave, dark: true },
    ];
    render(<PFKeyBar keys={keys} />);

    // A BMS ``ATTRB=DRK`` legend field displays nothing, yet the key still reaches the
    // program: the caption is absent while the AID stays live.
    expect(screen.queryByRole('button', { name: 'F5=Save' })).toBeNull();
    expect(screen.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();

    const event = fireEvent.keyDown(document, { key: 'F5', cancelable: true });
    expect(onSave).toHaveBeenCalledTimes(1);
    // Declared AIDs never reach the browser, darkened legend or not.
    expect(event).toBe(false);
  });

  it('keeps a dark key out of the legend without disabling the keys beside it', () => {
    const onCancel = jest.fn();
    render(
      <PFKeyBar
        keys={[
          { action: PfKeyAction.Enter, label: 'ENTER=Process', onActivate: jest.fn() },
          { action: PfKeyAction.PF12, label: 'F12=Cancel', onActivate: onCancel, dark: true },
        ]}
      />,
    );

    const rendered = screen.getAllByRole('button');
    expect(rendered).toHaveLength(1);
    expect(rendered[0]).toHaveTextContent('ENTER=Process');
    expect(rendered[0]).not.toBeDisabled();
  });

  it('suppresses the document ENTER handler when the keydown target is a legend button', () => {
    const onEnter = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Continue', onActivate: onEnter },
    ];
    render(<PFKeyBar keys={keys} />);

    // Target is a button: the double-invoke guard returns early so the document
    // listener does not fire (the button's own native click already handles it).
    fireEvent.keyDown(screen.getByRole('button', { name: 'ENTER=Continue' }), { key: 'Enter' });
    expect(onEnter).not.toHaveBeenCalled();
  });

  it('leaves ENTER to the skip link, so the link is operable without a pointer', () => {
    const onEnter = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Fetch', onActivate: onEnter },
    ];
    render(
      <>
        <a className="screen__skipLink" href="#screenStatusRegion">
          Skip to message line and function keys
        </a>
        <PFKeyBar keys={keys} />
      </>,
    );

    // The shell's skip link is the one anchor the application renders, and ENTER is the
    // only key that activates a link. Claiming the key for the screen called
    // `preventDefault` on that activation, so the link worked by pointer alone -- for
    // the one operator who has no pointer.
    const link = screen.getByRole('link', {
      name: 'Skip to message line and function keys',
    });
    const event = createEvent.keyDown(link, { key: 'Enter' });
    fireEvent(link, event);

    expect(onEnter).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
  });

  it('still claims ENTER for the screen when a field holds focus', () => {
    const onEnter = jest.fn();
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.Enter, label: 'ENTER=Fetch', onActivate: onEnter },
    ];
    render(
      <>
        <input aria-label="Enter User ID:" />
        <PFKeyBar keys={keys} />
      </>,
    );

    // The exemption must not reach a screen field: ENTER in an entry field is the AID.
    fireEvent.keyDown(screen.getByLabelText('Enter User ID:'), { key: 'Enter' });
    expect(onEnter).toHaveBeenCalledTimes(1);
  });
});
