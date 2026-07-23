/**
 * ConfirmDialog.test.tsx — verifies the shared MUI `ConfirmDialog` modal:
 * open/closed visibility, default and custom action labels, confirm/cancel
 * callbacks, the Escape-key → cancel rule, the `loading` disabled state, and the
 * `confirmColor='error'` default. Traceable to the legacy `COUSR03` "Delete
 * User" delete-confirm screen (app/bms/COUSR03.bms, app/cpy-bms/COUSR03.CPY).
 */

import {
    RenderWithProviders,
    screen,
    userEvent,
    act,
    waitFor,
} from '../testUtils';
import { axe } from 'jest-axe';
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

        // Ensure focus is inside the dialog so the Escape keydown is dispatched
        // within the modal's keydown scope. The destructive dialog now autofocus
        // Cancel (QA M-29), so target it here. The programmatic focus is wrapped
        // in `act` because MUI ButtonBase updates focus-visible state on focus;
        // wrapping keeps that update inside React's act() scope (QA N-07).
        act(() => {
            screen.getByRole('button', { name: 'Cancel' }).focus();
        });
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

    // ----------------------------------------------------------------------
    // Accessible description association (QA M-29) — `aria-describedby` must
    // always resolve to a rendered element, including the children-only
    // destructive-warning path used by /users/delete.
    // ----------------------------------------------------------------------
    describe('accessible description (M-29)', () => {
        it('associates aria-describedby with the message when one is given', () => {
            renderDialog({ message: SAMPLE_MESSAGE });

            const dialog = screen.getByRole('dialog');
            const describedById = dialog.getAttribute('aria-describedby');
            expect(describedById).toBeTruthy();
            // The referenced element exists and carries the message text.
            const target = document.getElementById(describedById as string);
            expect(target).not.toBeNull();
            expect(target).toHaveTextContent(SAMPLE_MESSAGE);
        });

        it('associates aria-describedby with children when there is no message', () => {
            renderDialog({
                message: undefined,
                children: 'You are about to delete USER0001.',
            });

            const dialog = screen.getByRole('dialog');
            const describedById = dialog.getAttribute('aria-describedby');
            // The reference must resolve to a REAL element (never a dangling id).
            expect(describedById).toBeTruthy();
            const target = document.getElementById(describedById as string);
            expect(target).not.toBeNull();
            expect(target).toHaveTextContent(
                'You are about to delete USER0001.',
            );
        });

        it('omits aria-describedby when neither message nor children exist', () => {
            renderDialog({ message: undefined });

            const dialog = screen.getByRole('dialog');
            expect(dialog).not.toHaveAttribute('aria-describedby');
        });
    });

    // ----------------------------------------------------------------------
    // Destructive default focus (QA M-29) — a destructive dialog must place
    // initial focus on Cancel so a stray Enter does not immediately confirm.
    // ----------------------------------------------------------------------
    describe('destructive default focus (M-29)', () => {
        it('focuses Cancel (not Delete) on open for the error color', async () => {
            renderDialog({ confirmColor: 'error', confirmLabel: 'Delete' });

            await waitFor(() => {
                expect(
                    screen.getByRole('button', { name: 'Cancel' }),
                ).toHaveFocus();
            });
            expect(
                screen.getByRole('button', { name: 'Delete' }),
            ).not.toHaveFocus();
        });

        it('focuses the confirm action on open for a non-destructive color', async () => {
            renderDialog({ confirmColor: 'primary', confirmLabel: 'Save' });

            await waitFor(() => {
                expect(
                    screen.getByRole('button', { name: 'Save' }),
                ).toHaveFocus();
            });
        });
    });

    // ----------------------------------------------------------------------
    // Automated axe gate (QA M-30) on both description paths.
    // ----------------------------------------------------------------------
    describe('accessibility axe gate (M-30)', () => {
        it('has no violations on the message path', async () => {
            const { baseElement } = RenderWithProviders(
                <ConfirmDialog
                    open
                    title={DEFAULT_TITLE}
                    message={SAMPLE_MESSAGE}
                    onConfirm={jest.fn()}
                    onCancel={jest.fn()}
                />,
            );

            expect(await axe(baseElement)).toHaveNoViolations();
        });

        it('has no violations on the children-only destructive path', async () => {
            const { baseElement } = RenderWithProviders(
                <ConfirmDialog
                    open
                    title="Delete User"
                    confirmLabel="Delete"
                    confirmColor="error"
                    onConfirm={jest.fn()}
                    onCancel={jest.fn()}
                >
                    You are about to delete USER0001. This cannot be undone.
                </ConfirmDialog>,
            );

            expect(await axe(baseElement)).toHaveNoViolations();
        });
    });
});
