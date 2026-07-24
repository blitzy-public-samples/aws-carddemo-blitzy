'use client';

/**
 * FormField — a generic, presentational form-field wrapper built on Material UI.
 *
 * It renders one of two shapes, selected by the `type` prop:
 *   - `text` | `password` | `number` | `date`  -> a single MUI `TextField`
 *     (`variant="outlined"`), where `password` masks input and `date` yields the
 *     native ISO (YYYY-MM-DD) picker with a permanently-shrunk label.
 *   - `select`                                  -> an MUI `FormControl` wrapping
 *     `InputLabel` + `Select` + one `MenuItem` per supplied option, with an
 *     optional `FormHelperText`.
 *
 * The component is intentionally generic: it does NOT hardcode any per-screen
 * field. Callers pass field length, requiredness, type, options, and validation
 * state as props so every data-entry screen (`/signon`, `/accounts/update`,
 * `/cards/update`, `/transactions/add`, `/reports`, `/users/add`,
 * `/users/update`) renders consistent, length-bounded inputs.
 *
 * Traceability — field-length source (BMS symbolic-map copybooks,
 * `app/cpy-bms/*.CPY`): every symbolic-map input field is declared as
 * `<name>I PIC X(n)`, where `n` is the on-screen buffer width and therefore the
 * `maxLength` a caller passes here. Verified examples:
 *   - COSGN00.CPY : USERIDI X(8), PASSWDI X(8)                 -> maxLength 8 (password masked)
 *   - COUSR02.CPY : FNAMEI/LNAMEI X(20), USRTYPEI X(1) (A/U),  -> names maxLength 20; user-type -> select
 *                   PASSWDI X(8)
 *   - CORPT00.CPY : MONTHLYI/YEARLYI/CUSTOMI X(1)              -> one Select (Monthly/Yearly/Custom);
 *                   SDT../EDT.. (MM X(2)+DD X(2)+YYYY X(4))    -> one ISO type="date"; CONFIRMI X(1) (Y/N)
 *   - COACTUP.CPY : ACRDLIMI/ACSHLIMI/ACURBALI/ACRCYCRI/       -> money, maxLength 15, signed 2-decimal
 *                   ACRCYDBI X(15); OPN/EXP/RIS YEAR X(4)+         format hint `-99999999.99`; split date
 *                   MON X(2)+DAY X(2); names X(25); addr X(50)     parts -> one ISO type="date"
 *
 * Ochs Test Rule: component & handler names are PascalCase, variables camelCase,
 * constants ALL_UPPERCASE; a single props object keeps the parameter count at 1.
 * Input length is bounded at the edge (`maxLength`) as an input-sanitization
 * affordance; business validation stays with the caller (via `error`/`helperText`).
 *
 * @packageDocumentation
 */

import {
    TextField,
    FormControl,
    InputLabel,
    Select,
    MenuItem,
    FormHelperText,
} from '@mui/material';
import type { SelectChangeEvent } from '@mui/material/Select';
import type { ChangeEvent } from 'react';

/**
 * A single option rendered as a `MenuItem` when `type === 'select'`.
 */
export interface FieldOption {
    /** The value submitted to `onChange` when this option is chosen. */
    value: string;
    /** The human-readable text shown in the dropdown. */
    label: string;
}

/**
 * The supported field kinds. `select` renders a dropdown; every other kind
 * renders an outlined `TextField` whose native input `type` matches the value.
 */
export type FieldType = 'text' | 'password' | 'number' | 'date' | 'select';

/**
 * Default placeholder for monetary fields (signed, 2-decimal DISPLAY format),
 * mirroring the legacy `PIC S9(nn)V99` money edit (e.g. COACTUP amount fields).
 */
const AMOUNT_FORMAT_HINT = '-99999999.99';

/**
 * Default placeholder for ISO date fields (the redesign collapses the legacy
 * split YEAR/MONTH/DAY symbolic-map fields into one `type="date"`).
 */
const DATE_FORMAT_HINT = 'YYYY-MM-DD';

/**
 * Props for {@link FormField}. Grouping every input into one object satisfies the
 * Ochs "max 4 parameters" rule (the component takes a single props argument).
 *
 * `value` is ALWAYS a string: account/card ids carry significant leading zeros
 * and monetary amounts are exact decimals-as-strings, so the value must never be
 * coerced to a JavaScript `number`.
 */
export interface FormFieldProps {
    /** Field name; also used as the `onChange` key and to derive element ids. */
    name: string;
    /** Visible label text (outlined-notch label / dropdown label). */
    label: string;
    /** Controlled value as a string (never a number — preserves leading zeros / exact decimals). */
    value: string;
    /** Change handler invoked as `onChange(name, value)`. */
    onChange: (name: string, value: string) => void;
    /** Field kind; defaults to `'text'`. */
    type?: FieldType;
    /** Maximum accepted length, taken from the copybook `<name>I PIC X(n)`. */
    maxLength?: number;
    /** Marks the field required; defaults to `false`. */
    required?: boolean;
    /** Options for `type === 'select'` (ignored otherwise). */
    options?: FieldOption[];
    /** Validation error state (renders the field in its error color). */
    error?: boolean;
    /** Validation or format helper message shown beneath the field. */
    helperText?: string;
    /** Placeholder text; falls back to a type-derived format hint when omitted. */
    placeholder?: string;
    /** Disables the control. */
    disabled?: boolean;
    /** Renders the input read-only (value visible, not editable). */
    readOnly?: boolean;
    /** Stretches the control to its container width; defaults to `true`. */
    fullWidth?: boolean;
    /** Focuses the control on mount (e.g. the sign-on user-id field). */
    autoFocus?: boolean;
    /**
     * Browser autofill hint forwarded to the native `<input autocomplete>`
     * attribute (QA N-01). Identity screens pass semantic tokens so password
     * managers behave correctly (`"username"` / `"current-password"` on
     * `/signon`); transient business-key lookups pass `"off"` so the browser
     * does not offer to autofill an account/card/transaction key with the
     * operator's own saved data. Applies to the text/password/number/date
     * shapes only (a `select` has no autofill semantics). Defaults to
     * `undefined` (the browser heuristic default) so existing fields are
     * unchanged unless a caller opts in.
     */
    autoComplete?: string;
}

/**
 * Renders a length-bounded, design-system-consistent form field.
 *
 * @param props - The {@link FormFieldProps} describing the field to render.
 * @returns The MUI element for the requested field kind.
 *
 * @example
 * ```tsx
 * // Text input, 8 chars (COSGN00 USERIDI PIC X(8)):
 * <FormField name="userId" label="User ID" value={userId}
 *     onChange={handleChange} maxLength={8} required autoFocus />
 *
 * // Dropdown (CORPT00 Monthly/Yearly/Custom flags collapsed to one Select):
 * <FormField name="reportType" label="Report Type" type="select"
 *     value={reportType} onChange={handleChange}
 *     options={[{ value: 'monthly', label: 'Monthly' }]} />
 * ```
 */
export function FormField(props: FormFieldProps) {
    const { type = 'text', required = false, fullWidth = true } = props;

    // Accessible ids derived from the field name (associates label <-> control).
    const labelId = `${props.name}-label`;
    const selectId = `${props.name}-select`;

    /**
     * Relays native `TextField` edits to the caller as `onChange(name, value)`.
     */
    function HandleTextChange(
        event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
    ): void {
        props.onChange(props.name, event.target.value);
    }

    /**
     * Relays `Select` changes to the caller as `onChange(name, value)`.
     */
    function HandleSelectChange(event: SelectChangeEvent): void {
        props.onChange(props.name, event.target.value);
    }

    // Dropdown mode: FormControl + InputLabel + Select + MenuItem[] (+ helper).
    // `label` on BOTH InputLabel and Select drives the MD3 outlined-notch behavior.
    if (type === 'select') {
        return (
            <FormControl
                fullWidth={fullWidth}
                required={required}
                error={props.error}
                disabled={props.disabled}
            >
                <InputLabel id={labelId}>{props.label}</InputLabel>
                <Select
                    labelId={labelId}
                    id={selectId}
                    name={props.name}
                    value={props.value}
                    label={props.label}
                    onChange={HandleSelectChange}
                >
                    {(props.options ?? []).map((option) => (
                        <MenuItem key={option.value} value={option.value}>
                            {option.label}
                        </MenuItem>
                    ))}
                </Select>
                {props.helperText ? (
                    <FormHelperText>{props.helperText}</FormHelperText>
                ) : null}
            </FormControl>
        );
    }

    // Input mode (text/password/number/date). Provide a type-derived placeholder
    // format hint only when the caller does not supply its own placeholder.
    let effectivePlaceholder: string | undefined = props.placeholder;
    if (effectivePlaceholder === undefined) {
        if (type === 'number') {
            effectivePlaceholder = AMOUNT_FORMAT_HINT;
        } else if (type === 'date') {
            effectivePlaceholder = DATE_FORMAT_HINT;
        }
    }

    return (
        <TextField
            name={props.name}
            label={props.label}
            value={props.value}
            onChange={HandleTextChange}
            variant="outlined"
            type={type}
            required={required}
            error={props.error}
            helperText={props.helperText}
            fullWidth={fullWidth}
            placeholder={effectivePlaceholder}
            disabled={props.disabled}
            autoFocus={props.autoFocus}
            autoComplete={props.autoComplete}
            slotProps={{
                htmlInput: {
                    maxLength: props.maxLength,
                    readOnly: props.readOnly,
                },
                inputLabel: type === 'date' ? { shrink: true } : undefined,
            }}
        />
    );
}
