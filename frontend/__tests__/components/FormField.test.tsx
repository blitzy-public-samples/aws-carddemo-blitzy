/**
 * FormField.test.tsx — React Testing Library spec for the shared MUI
 * `@/components/FormField` input component. It verifies control-by-type
 * rendering (text/password/number/date/select), the two-argument
 * `onChange(name, value)` contract, `maxLength`, validation display
 * (required/error/helperText), and password masking.
 *
 * Fields under test trace back to the legacy BMS symbolic-map copybooks:
 * app/cpy-bms/COSGN00.CPY (USERIDI/PASSWDI PIC X(8)), CORPT00.CPY (Monthly/
 * Yearly/Custom report-type flags collapsed into one Select), COACTUP.CPY
 * (ACCTSIDI PIC X(11)), and COUSR02.CPY (USRIDINI PIC X(8)).
 */

import { RenderWithProviders, screen, userEvent } from '../testUtils';
import { FormField } from '@/components/FormField';
import type { FieldOption } from '@/components/FormField';

// Report-type options mirror the CORPT00 Monthly/Yearly/Custom flags collapsed
// into a single MUI Select. Typed as FieldOption[] so the type import is used;
// ALL_UPPERCASE per the Ochs Rule since this is a fixed test constant.
const REPORT_OPTIONS: FieldOption[] = [
    { value: 'monthly', label: 'Monthly' },
    { value: 'yearly', label: 'Yearly' },
    { value: 'custom', label: 'Custom' },
];

describe('FormField', () => {
    describe('rendering by type', () => {
        it('renders a text input by default', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    onChange={jest.fn()}
                />,
            );

            expect(screen.getByLabelText('User ID')).toBeInTheDocument();
            expect(screen.getByRole('textbox')).toBeInTheDocument();
        });

        it('renders a password input that masks input', () => {
            RenderWithProviders(
                <FormField
                    name="password"
                    label="Password"
                    type="password"
                    value=""
                    onChange={jest.fn()}
                />,
            );

            // Masked inputs expose a `type="password"` element and NO textbox role.
            const passwordInput = screen.getByLabelText('Password');
            expect(passwordInput).toHaveAttribute('type', 'password');
            expect(screen.queryByRole('textbox')).toBeNull();
        });

        it('renders a number input', () => {
            RenderWithProviders(
                <FormField
                    name="amount"
                    label="Amount"
                    type="number"
                    value=""
                    onChange={jest.fn()}
                />,
            );

            const amountInput = screen.getByRole('spinbutton');
            expect(amountInput).toBeInTheDocument();
            expect(amountInput).toHaveAttribute('type', 'number');
        });

        it('renders a date input', () => {
            RenderWithProviders(
                <FormField
                    name="expirationDate"
                    label="Expiration Date"
                    type="date"
                    value=""
                    onChange={jest.fn()}
                />,
            );

            // Date inputs have no textbox role; query by label and assert type.
            expect(screen.getByLabelText('Expiration Date')).toHaveAttribute(
                'type',
                'date',
            );
        });

        it('renders a select with options', async () => {
            const user = userEvent.setup();
            RenderWithProviders(
                <FormField
                    name="reportType"
                    label="Report Type"
                    type="select"
                    value="monthly"
                    options={REPORT_OPTIONS}
                    onChange={jest.fn()}
                />,
            );

            // MUI Select renders a combobox; a regex tolerates the selected-value
            // suffix MUI folds into the combobox accessible name.
            const combobox = screen.getByRole('combobox', {
                name: /Report Type/i,
            });
            expect(combobox).toBeInTheDocument();

            // Options are portal-rendered only after the combobox is opened.
            await user.click(combobox);
            expect(
                screen.getByRole('option', { name: 'Yearly' }),
            ).toBeInTheDocument();
        });
    });

    describe('onChange contract', () => {
        it('calls onChange with (name, value) when typing', async () => {
            const user = userEvent.setup();
            const onChange = jest.fn();
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    onChange={onChange}
                />,
            );

            // Controlled value="" never updates, so one keystroke fires exactly
            // one deterministic onChange('userId', 'A').
            await user.type(screen.getByLabelText('User ID'), 'A');

            expect(onChange).toHaveBeenCalledWith('userId', 'A');

            // Prove the two-arg (name, value) signature — NOT the raw event.
            const firstCall = onChange.mock.calls[0];
            expect(firstCall).toHaveLength(2);
            expect(firstCall[0]).toBe('userId');
            expect(firstCall[1]).toBe('A');
        });

        it('calls onChange with (name, value) on select change', async () => {
            const user = userEvent.setup();
            const onChange = jest.fn();
            RenderWithProviders(
                <FormField
                    name="reportType"
                    label="Report Type"
                    type="select"
                    value="monthly"
                    options={REPORT_OPTIONS}
                    onChange={onChange}
                />,
            );

            await user.click(
                screen.getByRole('combobox', { name: /Report Type/i }),
            );
            await user.click(screen.getByRole('option', { name: 'Yearly' }));

            expect(onChange).toHaveBeenCalledWith('reportType', 'yearly');
            expect(onChange.mock.calls[0]).toHaveLength(2);
        });
    });

    describe('constraints', () => {
        it('applies maxLength to the underlying input', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    maxLength={8}
                    onChange={jest.fn()}
                />,
            );

            // COSGN00 USERIDI PIC X(8) -> maxLength 8 forwarded as DOM maxlength.
            expect(screen.getByLabelText('User ID')).toHaveAttribute(
                'maxlength',
                '8',
            );
        });

        it('respects readOnly', () => {
            RenderWithProviders(
                <FormField
                    name="acctId"
                    label="Account ID"
                    value="0000000001"
                    readOnly
                    onChange={jest.fn()}
                />,
            );

            // IDs are strings (leading zeros preserved); readOnly -> DOM readonly.
            expect(screen.getByLabelText('Account ID')).toHaveAttribute(
                'readonly',
            );
        });

        it('respects disabled', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    disabled
                    onChange={jest.fn()}
                />,
            );

            expect(screen.getByLabelText('User ID')).toBeDisabled();
        });
    });

    describe('validation', () => {
        it('marks the field required', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    required
                    onChange={jest.fn()}
                />,
            );

            // `required` may append `*` to the label, so match with a regex.
            expect(screen.getByLabelText(/User ID/)).toBeRequired();
        });

        it('renders helperText', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    helperText="User ID is required"
                    onChange={jest.fn()}
                />,
            );

            expect(
                screen.getByText('User ID is required'),
            ).toBeInTheDocument();
        });

        it('renders error state with helperText', () => {
            RenderWithProviders(
                <FormField
                    name="userId"
                    label="User ID"
                    value=""
                    error
                    helperText="Invalid User ID"
                    onChange={jest.fn()}
                />,
            );

            // The helper node carries the MUI error class when `error` is set.
            const helperNode = screen.getByText('Invalid User ID');
            expect(helperNode).toBeInTheDocument();
            expect(helperNode.className).toMatch(/Mui-error|error/i);
        });
    });

    describe('onKeyDown passthrough (QA I23 / I25)', () => {
        it('forwards key events from the native input to the onKeyDown handler', async () => {
            const user = userEvent.setup();
            const handleKeyDown = jest.fn();
            RenderWithProviders(
                <FormField
                    name="acctId"
                    label="Account ID"
                    value=""
                    onChange={jest.fn()}
                    onKeyDown={handleKeyDown}
                />,
            );

            const input = screen.getByRole('textbox');
            // Focus through userEvent (not a raw input.focus()) so the resulting
            // React state update is wrapped in act(...), keeping the test free of
            // "not wrapped in act" warnings while still focusing the field.
            await user.click(input);
            // Pressing Enter must invoke the caller's handler (Enter-submits),
            // with the key reported so the caller can gate on 'Enter'.
            await user.keyboard('{Enter}');

            expect(handleKeyDown).toHaveBeenCalled();
            expect(handleKeyDown.mock.calls[0][0].key).toBe('Enter');
        });
    });
});
