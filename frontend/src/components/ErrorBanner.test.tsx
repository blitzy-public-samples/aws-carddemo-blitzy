import { render, screen } from '@testing-library/react';
import ErrorBanner, {
  ERROR_LINE_WIDTH,
  isFieldInError,
  fieldErrorClass,
  fieldMarker,
  hasFieldErrors,
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
