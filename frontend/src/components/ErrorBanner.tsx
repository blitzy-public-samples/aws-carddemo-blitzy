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
 * :purpose: DOM id of the line-23 message region, so a screen can associate the
 *     message with the field whose edit produced it through ``aria-describedby``.
 */
export const ERROR_LINE_ID = 'screenMessageLine';

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
 * :purpose: Accessibility bindings emitted on the one control a screen faults for
 *     the current line-23 message, mirroring the program's ``MOVE -1 TO <field>L``
 *     cursor placement.
 * :param invalid: ``aria-invalid``, present only when the control is faulted.
 * :param describedBy: Points at :data:`ERROR_LINE_ID`, present only when faulted.
 */
export interface InvalidFieldProps {
  'aria-invalid'?: true;
  'aria-describedby'?: string;
}

/**
 * :purpose: Build the accessibility bindings for the control a screen faults,
 *     associating it with the line-23 message region and keeping any static hint
 *     the field already describes itself with.
 * :param invalid: ``true`` when this control is the one the screen faults.
 * :param hintId: Optional id of the field's own hint text, retained ahead of the
 *     message region so both are announced.
 * :returns: ``aria-invalid`` plus the merged description when faulted, the hint
 *     alone when not faulted, or an empty object when neither applies. The message
 *     region is referenced only when faulted because :data:`ERROR_LINE_ID` exists
 *     only on the error variant, so an unconditional reference would dangle.
 */
export function invalidFieldProps(invalid: boolean, hintId?: string): InvalidFieldProps {
  const described = [hintId, invalid ? ERROR_LINE_ID : undefined].filter(
    (part): part is string => part !== undefined && part !== '',
  );
  const props: InvalidFieldProps = {};
  if (invalid) {
    props['aria-invalid'] = true;
  }
  if (described.length > 0) {
    props['aria-describedby'] = described.join(' ');
  }
  return props;
}

/**
 * :purpose: Build the accessible invalid state for a control rejected by its own
 *     value, with no line-23 message describing it — the row-action columns, which
 *     the legacy screens fault only on the next send.
 * :param invalid: ``true`` when the control's value is not an accepted code.
 * :returns: ``aria-invalid`` alone when invalid, otherwise an empty object. No
 *     description is emitted: with no message published, the error variant is not
 *     mounted and :data:`ERROR_LINE_ID` is absent from the document, so a reference
 *     would dangle. Use :func:`invalidFieldProps` whenever a message is on line 23.
 */
export function invalidValueProps(invalid: boolean): InvalidFieldProps {
  return invalid ? { 'aria-invalid': true } : {};
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
      <div className="errorBanner" role="alert" id={ERROR_LINE_ID}>
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
