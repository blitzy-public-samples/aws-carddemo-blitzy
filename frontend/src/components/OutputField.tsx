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
 * :param row: The BMS row of the pair, as a row line of the enclosing 80-column grid.
 *     Supplying it places the caption and the value at their declared coordinates
 *     instead of letting them flow, which is what keeps a screen that declares two
 *     field columns on one terminal row from becoming two rows.
 * :param labelCol: The BMS column of the caption (``POS=(row,col)``), one-based.
 * :param labelWidth: The BMS ``LENGTH`` of the caption.
 * :param valueCol: The BMS column of the value, one-based.
 * :param valueRow: The BMS row of the value when it differs from the caption's, as on the
 *     three name fields of ``COACTVW``, whose captions sit on row 14 and whose values sit
 *     on row 15. Defaults to ``row``.
 * :param justifyRight: Right-justify the value inside its field, for a field the mapset
 *     declares ``JUSTIFY=(RIGHT)``. A right-justified numeric field is what puts every
 *     value's decimal point in the same column; left-justified, a shorter value carries its
 *     point left with it and the column reads ragged however well the boxes line up.
 * :param ariaLabel: Accessible name for the value, for a field the mapset paints no caption
 *     beside. It names the value without painting anything, so the rendered screen is
 *     unchanged; without it such a field has to borrow a neighbour's caption and two values
 *     end up sharing one name.
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
  row?: number;
  labelCol?: number;
  labelWidth?: number;
  valueCol?: number;
  valueRow?: number;
  justifyRight?: boolean;
  ariaLabel?: string;
}

/**
 * :purpose: Build the grid placement for one half of a placed pair.
 * :param row: the BMS row, used as the grid row line.
 * :param col: the BMS column, used as the grid column line (both are one-based, which
 *     is what CSS grid line numbering already is, so they transcribe directly).
 * :param span: the field's BMS ``LENGTH`` in character cells.
 * :returns: the style object, or ``undefined`` when the pair is not placed.
 */
function placement(
  row: number | undefined,
  col: number | undefined,
  span: number | undefined,
): { gridRow: string; gridColumn: string } | undefined {
  if (row === undefined || col === undefined) {
    return undefined;
  }
  const columns = span === undefined ? String(col) : `${String(col)} / span ${String(span)}`;
  return { gridRow: String(row), gridColumn: columns };
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
  row,
  labelCol,
  labelWidth,
  valueCol,
  valueRow,
  justifyRight = false,
  ariaLabel,
}: OutputFieldProps): ReactElement {
  const captionId = `${testId}-label`;
  const labelStyle = placement(row, labelCol, labelWidth);
  const placedValueStyle = placement(valueRow ?? row, valueCol, width);
  // Unplaced pairs keep reserving their field width, so a value occupies the same
  // columns whether or not it is populated; a placed pair gets that from its span.
  const baseValueStyle =
    placedValueStyle ??
    (width === undefined ? undefined : { minWidth: `${String(width)}ch` });
  const valueStyle = justifyRight
    ? { ...baseValueStyle, textAlign: 'right' as const }
    : baseValueStyle;
  return (
    <div className={className}>
      <dt className={labelClassName} id={captionId} style={labelStyle}>
        {label}
      </dt>
      <dd
        className={valueClassName}
        data-testid={testId}
        aria-label={ariaLabel}
        // `aria-labelledby` wins over `aria-label`, so an explicit name replaces the
        // reference rather than being shadowed by it.
        aria-labelledby={ariaLabel === undefined ? (labelledBy ?? captionId) : undefined}
        style={valueStyle}
      >
        {value}
      </dd>
    </div>
  );
}
