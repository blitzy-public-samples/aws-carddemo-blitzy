/**
 * OutputField
 * ===========
 *
 * :purpose: Render one protected BMS field — a caption and the value beside it — as a
 *     named term/description pair rather than as a read-only entry control. A BMS
 *     field defined ``ATTRB=ASKIP`` (or ``PROT``) cannot receive the cursor, so the
 *     migrated screen must not make its value a keyboard stop either; the value keeps
 *     its accessible name through the caption it is associated with.
 * :output: The rendered caption/value pair.
 * :note: Must be rendered inside a ``<dl>``: the pair is a term and its description.
 *     The shared stylesheet lays each pair out on one character row.
 */
import type { ReactElement, ReactNode } from 'react';

/**
 * :purpose: Props for :func:`OutputField`.
 * :param label: The BMS caption literal, rendered verbatim including the column
 *     padding its spaces carry.
 * :param value: The protected value to display.
 * :param testId: Test id placed on the value element; also the base of the caption's
 *     generated DOM id.
 * :param width: Field width in character cells (the BMS ``LENGTH``), reserved so the
 *     value occupies the same columns whether or not it is populated.
 * :param className: Class of the pair's row container; defaults to the shared
 *     one-character-row treatment.
 * :param labelClassName: Class of the caption; defaults to the TURQUOISE prompt tone.
 * :param valueClassName: Class of the value; defaults to the BLUE field tone.
 * :param labelledBy: DOM id of an existing caption to name the value with, for a
 *     screen whose caption is rendered outside this pair.
 */
export interface OutputFieldProps {
  label: ReactNode;
  value: ReactNode;
  testId: string;
  width?: number;
  className?: string;
  labelClassName?: string;
  valueClassName?: string;
  labelledBy?: string;
}

/**
 * :purpose: Render a protected caption/value pair that carries an accessible name and
 *     no tab stop.
 * :param props: See :class:`OutputFieldProps`.
 * :returns: The rendered pair.
 */
export default function OutputField({
  label,
  value,
  testId,
  width,
  className = 'detailField',
  labelClassName = 'prompt',
  valueClassName = 'label',
  labelledBy,
}: OutputFieldProps): ReactElement {
  const captionId = `${testId}-label`;
  return (
    <div className={className}>
      <dt className={labelClassName} id={captionId}>
        {label}
      </dt>
      <dd
        className={valueClassName}
        data-testid={testId}
        aria-labelledby={labelledBy ?? captionId}
        style={width === undefined ? undefined : { minWidth: `${String(width)}ch` }}
      >
        {value}
      </dd>
    </div>
  );
}
