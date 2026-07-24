/**
 * PFKeyBar component tests.
 *
 * :purpose: Verify the shared BMS line-24 function-key bar
 *     (``app/cpy/CSSTRPFY.cpy``, AAP 0.3.5): one accessible button per supplied
 *     key inside a ``toolbar`` named ``Function keys``; a button click invokes its
 *     handler exactly once; the physical AID keys ``Enter``/``F3``/``F7``/``F8``
 *     each invoke the matching handler exactly once through the document keydown
 *     listener; unmapped keys (``F9``) and disabled keys are ignored; the disabled
 *     button is rendered ``disabled``; and the ENTER double-invoke guard suppresses
 *     the document handler when the keydown target is a legend button.
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

  it('ignores an unmapped key (F9)', () => {
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
