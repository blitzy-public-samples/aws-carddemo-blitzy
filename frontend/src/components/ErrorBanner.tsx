/**
 * ErrorBanner
 * ===========
 *
 * :purpose: Render the BMS line-23 message region (``X(78)``, RED) that shows the
 *     current screen error or informational message, and provide the per-field
 *     highlight helpers derived from ``app/cpy/CSSETATY.cpy``: on re-entry an
 *     invalid or blank field renders RED (``.fieldError``) and a blank required
 *     field additionally receives a leading ``*`` marker (``.blankMarker``).
 */
import type { ReactElement } from 'react';
import type { FieldErrorMap, FieldErrorState } from '../types';

/** Maximum width of the line-23 message region (BMS ``X(78)``). */
export const ERROR_LINE_WIDTH = 78;

/**
 * :purpose: Props for :func:`ErrorBanner`.
 * :param message: Error message, rendered RED with ``role="alert"``.
 * :param infoMessage: Fallback informational message, rendered ``role="status"``.
 */
export interface ErrorBannerProps {
  message?: string;
  infoMessage?: string;
}

/**
 * :purpose: Whether a field should render in error (RED). True when the field is
 *     flagged invalid or blank, mirroring ``FLG-x-NOT-OK OR FLG-x-BLANK``.
 * :param state: The field's error state, if any.
 * :returns: ``true`` when the field is in error.
 */
export function isFieldInError(state: FieldErrorState | undefined): boolean {
  return state !== undefined && (state.invalid || state.blank);
}

/**
 * :purpose: The CSS class for a field given its error state.
 * :param state: The field's error state, if any.
 * :returns: ``fieldError`` when invalid/blank, otherwise an empty string.
 */
export function fieldErrorClass(state: FieldErrorState | undefined): string {
  return isFieldInError(state) ? 'fieldError' : '';
}

/**
 * :purpose: The leading marker for a field: ``*`` for a blank required field
 *     (``FLG-x-BLANK`` -> ``MOVE '*'``), otherwise an empty string.
 * :param state: The field's error state, if any.
 * :returns: ``*`` when blank, otherwise an empty string.
 */
export function fieldMarker(state: FieldErrorState | undefined): string {
  return state !== undefined && state.blank ? '*' : '';
}

/**
 * :purpose: Whether any field in the map is currently flagged in error.
 * :param map: The field-error map, if any.
 * :returns: ``true`` when at least one field is in error.
 */
export function hasFieldErrors(map: FieldErrorMap | undefined): boolean {
  if (!map) {
    return false;
  }
  return Object.values(map).some((state) => isFieldInError(state));
}

/**
 * :purpose: The shared line-23 message banner.
 * :param message: Error message (RED, ``role="alert"``).
 * :param infoMessage: Informational fallback (``role="status"``).
 * :returns: The rendered message region.
 */
export default function ErrorBanner({ message, infoMessage }: ErrorBannerProps): ReactElement {
  if (message) {
    return (
      <div className="errorBanner" role="alert">
        {message}
      </div>
    );
  }
  if (infoMessage) {
    return (
      <div className="errorBanner" role="status">
        {infoMessage}
      </div>
    );
  }
  return <div className="errorBanner errorBanner--empty" data-testid="error-banner-empty" />;
}
