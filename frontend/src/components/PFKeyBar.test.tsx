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
import { render, screen, fireEvent } from '@testing-library/react';
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

    const toolbar = screen.getByRole('toolbar', { name: 'Function keys' });
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

  it('leaves the browser default alone for an AID the screen does not declare', () => {
    const keys: PFKeyDef[] = [
      { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() },
    ];
    render(<PFKeyBar keys={keys} />);

    // F5 belongs to the browser on a screen whose legend does not claim it.
    const cancelled = !fireEvent.keyDown(document, { key: 'F5', cancelable: true });
    expect(cancelled).toBe(false);
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
});
