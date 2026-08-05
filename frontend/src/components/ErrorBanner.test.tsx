import { render, screen } from '@testing-library/react';
import ErrorBanner, {
  ERROR_LINE_ID,
  ERROR_LINE_WIDTH,
  isFieldInError,
  fieldErrorClass,
  fieldMarker,
  hasFieldErrors,
  invalidFieldProps,
  invalidValueProps,
} from './ErrorBanner';
import type { FieldErrorMap } from '../types';

describe('ErrorBanner', () => {
  it('renders the error message with an alert role', () => {
    render(<ErrorBanner message="Record changed by some one else. Please review" />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Record changed by some one else. Please review');
    expect(alert).toHaveClass('errorBanner');
  });

  it('falls back to the informational message with a status role', () => {
    render(<ErrorBanner infoMessage="Type your User ID and Password" />);
    expect(screen.getByRole('status')).toHaveTextContent('Type your User ID and Password');
  });

  it('renders an empty placeholder when there is no message', () => {
    const { container } = render(<ErrorBanner />);
    const banner = container.querySelector('.errorBanner');
    expect(banner).not.toBeNull();
    expect(banner).toHaveClass('errorBanner--empty');
  });

  it('exposes the line-23 width of 78 columns', () => {
    expect(ERROR_LINE_WIDTH).toBe(78);
  });

  it('flags invalid or blank fields as in error (CSSETATY semantics)', () => {
    expect(isFieldInError({ invalid: true, blank: false })).toBe(true);
    expect(isFieldInError({ invalid: false, blank: true })).toBe(true);
    expect(isFieldInError({ invalid: false, blank: false })).toBe(false);
    expect(isFieldInError(undefined)).toBe(false);
  });

  it('returns the RED field class and a leading * marker for blank fields', () => {
    expect(fieldErrorClass({ invalid: true, blank: false })).toBe('fieldError');
    expect(fieldErrorClass({ invalid: false, blank: false })).toBe('');
    expect(fieldMarker({ invalid: false, blank: true })).toBe('*');
    expect(fieldMarker({ invalid: true, blank: false })).toBe('');
  });

  it('binds aria-invalid and the message region only on the faulted control', () => {
    expect(invalidFieldProps(true)).toEqual({
      'aria-invalid': true,
      'aria-describedby': ERROR_LINE_ID,
    });
    // A control that is not faulted emits nothing, so no dangling IDREF is left
    // behind on a screen whose banner is showing the info or blank variant.
    expect(invalidFieldProps(false)).toEqual({});
  });

  it('keeps a field hint ahead of the message region when faulted', () => {
    expect(invalidFieldProps(true, 'tranAmtHint')).toEqual({
      'aria-invalid': true,
      'aria-describedby': `tranAmtHint ${ERROR_LINE_ID}`,
    });
    expect(invalidFieldProps(false, 'tranAmtHint')).toEqual({
      'aria-describedby': 'tranAmtHint',
    });
    expect(invalidFieldProps(false, '')).toEqual({});
  });

  it('emits an id the error variant actually renders', () => {
    render(<ErrorBanner message="Tran ID can NOT be empty..." />);
    expect(screen.getByRole('alert')).toHaveAttribute('id', ERROR_LINE_ID);
    const bound = invalidFieldProps(true)['aria-describedby'];
    expect(document.getElementById(bound ?? '')).not.toBeNull();
  });

  it('omits the message reference for a value-evident fault', () => {
    // A row-action column faults from its own value without publishing a line-23
    // message, so the error variant is never mounted and the id does not exist.
    expect(invalidValueProps(true)).toEqual({ 'aria-invalid': true });
    expect(invalidValueProps(false)).toEqual({});
    expect(invalidValueProps(true)).not.toHaveProperty('aria-describedby');
  });

  it('never leaves a dangling message reference on a screen with no error', () => {
    // The blank and informational variants carry no id, so any control that
    // referenced the message region while one of them is showing would point at
    // nothing. Only the error variant renders it.
    const { unmount } = render(<ErrorBanner infoMessage="Press PF5 key to delete this user ..." />);
    expect(document.getElementById(ERROR_LINE_ID)).toBeNull();
    expect(invalidValueProps(true)['aria-describedby']).toBeUndefined();
    unmount();

    render(<ErrorBanner />);
    expect(document.getElementById(ERROR_LINE_ID)).toBeNull();
    expect(invalidValueProps(true)['aria-describedby']).toBeUndefined();
  });

  it('detects whether any field in a map is in error', () => {
    const map: FieldErrorMap = {
      acctId: { invalid: false, blank: false },
      amount: { invalid: true, blank: false },
    };
    expect(hasFieldErrors(map)).toBe(true);
    expect(hasFieldErrors({ x: { invalid: false, blank: false } })).toBe(false);
    expect(hasFieldErrors(undefined)).toBe(false);
  });
});
