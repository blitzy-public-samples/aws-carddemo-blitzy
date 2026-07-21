/**
 * ConfirmDialog.test.tsx — verifies the shared MUI `ConfirmDialog` modal:
 * open/closed visibility, default and custom action labels, confirm/cancel
 * callbacks, the Escape-key → cancel rule, the `loading` disabled state, and the
 * `confirmColor='error'` default. Traceable to the legacy `COUSR03` "Delete
 * User" delete-confirm screen (app/bms/COUSR03.bms, app/cpy-bms/COUSR03.CPY).
 */

import { RenderWithProviders, screen, userEvent } from '../testUtils';
import { ConfirmDialog } from '@/components/ConfirmDialog';

/** Benign, non-sensitive dialog heading reused across the specs. */
const DEFAULT_TITLE = 'Delete User';

/** Sample prompt text referencing only the benign README seed user id. */
const SAMPLE_MESSAGE = 'Are you sure you want to delete USER0001?';

describe('ConfirmDialog', () => {
    /**
     * Render `ConfirmDialog` with the required props merged with `overrides`,
     * so every spec stays small (Ochs ≤ 20 lines). Fresh `jest.fn()` mocks back
     * `onConfirm` / `onCancel`; the resolved props are returned so callers can
     * assert on those mocks.
     *
     * @param overrides - Partial props that replace any default.
     * @returns The resolved props passed to the rendered dialog.
     */
    function renderDialog(overrides?: Partial<Parameters<typeof ConfirmDialog>[0]>) {
        const props = {
            open: true,
            title: DEFAULT_TITLE,
            onConfirm: jest.fn(),
            onCancel: jest.fn(),
            ...overrides,
        };

        RenderWithProviders(<ConfirmDialog {...props} />);

        return { ...props };
    }

    // ----------------------------------------------------------------------
    // Visibility
    // ----------------------------------------------------------------------

    it('renders title and message when open', () => {
        renderDialog({ message: SAMPLE_MESSAGE });

        expect(screen.getByRole('dialog')).toBeInTheDocument();
        expect(screen.getByText(DEFAULT_TITLE)).toBeInTheDocument();
        expect(screen.getByText(SAMPLE_MESSAGE)).toBeInTheDocument();
    });

    it('does not render when closed', () => {
        renderDialog({ open: false });

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('renders children when provided', () => {
        renderDialog({ message: undefined, children: 'Custom body content' });

        expect(screen.getByText('Custom body content')).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // Labels
    // ----------------------------------------------------------------------

    it('shows default Confirm and Cancel labels', () => {
        renderDialog();

        expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    });

    it('shows custom labels', () => {
        renderDialog({ confirmLabel: 'Delete', cancelLabel: 'Keep' });

        expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Keep' })).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Confirm' })).toBeNull();
        expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
    });

    // ----------------------------------------------------------------------
    // Callbacks
    // ----------------------------------------------------------------------

    it('calls onConfirm when the confirm button is clicked', async () => {
        const user = userEvent.setup();
        const { onConfirm } = renderDialog();

        await user.click(screen.getByRole('button', { name: 'Confirm' }));

        expect(onConfirm).toHaveBeenCalledTimes(1);
    });

    it('calls onCancel when the cancel button is clicked', async () => {
        const user = userEvent.setup();
        const { onCancel } = renderDialog();

        await user.click(screen.getByRole('button', { name: 'Cancel' }));

        expect(onCancel).toHaveBeenCalledTimes(1);
    });

    // ----------------------------------------------------------------------
    // Escape key → cancel
    // ----------------------------------------------------------------------

    it('calls onCancel when Escape is pressed', async () => {
        const user = userEvent.setup();
        const { onCancel } = renderDialog();

        // The confirm button has `autoFocus`; ensure focus is inside the
        // dialog so the Escape keydown is dispatched within it.
        screen.getByRole('button', { name: 'Confirm' }).focus();
        await user.keyboard('{Escape}');

        expect(onCancel).toHaveBeenCalledTimes(1);
    });

    // ----------------------------------------------------------------------
    // Loading state
    // ----------------------------------------------------------------------

    it('disables both buttons while loading', () => {
        renderDialog({ loading: true });

        expect(screen.getByRole('button', { name: 'Confirm' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    });

    it('does not call onConfirm when clicked while loading', async () => {
        // Disable user-event's pointer-events guard: a disabled MUI Button sets
        // `pointer-events: none`, which otherwise makes `click` throw instead of
        // proceeding. With the guard off the click is dispatched but the real
        // `disabled` attribute still swallows `onClick`; the HandleConfirm
        // `loading` guard is a second layer of protection.
        const user = userEvent.setup({ pointerEventsCheck: 0 });
        const { onConfirm } = renderDialog({ loading: true });

        await user.click(screen.getByRole('button', { name: 'Confirm' }));

        expect(onConfirm).not.toHaveBeenCalled();
    });

    // ----------------------------------------------------------------------
    // confirmColor default & override
    // ----------------------------------------------------------------------

    it('defaults the confirm button color to error', () => {
        renderDialog();

        const confirmButton = screen.getByRole('button', { name: 'Confirm' });
        expect(confirmButton.className).toMatch(/colorError|MuiButton-containedError/i);
    });

    it('honors a custom confirmColor', () => {
        renderDialog({ confirmColor: 'primary', confirmLabel: 'Save' });

        const confirmButton = screen.getByRole('button', { name: 'Save' });
        expect(confirmButton.className).toMatch(/colorPrimary|containedPrimary/i);
    });
});
