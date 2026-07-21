'use client';

/**
 * ConfirmDialog — a reusable confirmation dialog built on MUI `Dialog`.
 *
 * Traceability: this component replaces the legacy 3270 `COUSR03` "Delete User"
 * confirmation interaction (`app/bms/COUSR03.bms`, `app/cpy-bms/COUSR03.CPY`),
 * where the operator pressed F5 to delete and F3 to go back. In the modern SPA
 * its primary consumer is the `/users/delete` page, which confirms a
 * `DELETE /admin/users/{userId}` request. The component is intentionally
 * GENERIC: it hardcodes no user fields and renders whatever the calling page
 * supplies through `title` / `message` / `children`.
 *
 * PF-key mapping (AAP §0.4.4): the native Escape key and backdrop click both
 * invoke `onCancel`, preserving the legacy PF3 = Back behavior. Escape/backdrop
 * close is deliberately left enabled.
 *
 * Security (Ochs Test Rule): callers MUST pass only non-sensitive display
 * fields into `children` (for example user_id, first name, last name, and
 * user_type). Never pass passwords or secrets; any `card_num` / `ssn` must
 * already be masked upstream. This component merely renders the supplied
 * `children`.
 *
 * @see AAP §0.3.2 — Delete confirmation → Dialog + DialogTitle/Content/Actions.
 * @see AAP §0.3.4 — Material UI design-system rules (MUI components only,
 *      zero hardcoded CSS).
 */

import {
    Dialog,
    DialogTitle,
    DialogContent,
    DialogContentText,
    DialogActions,
    Button,
} from '@mui/material';
import type { ReactNode } from 'react';

/** Default label for the confirm (primary) action button. */
const DEFAULT_CONFIRM_LABEL = 'Confirm';

/** Default label for the cancel (secondary) action button. */
const DEFAULT_CANCEL_LABEL = 'Cancel';

/**
 * Props for {@link ConfirmDialog}.
 *
 * All caller-specific content is supplied through this single typed object
 * (Ochs ≤4-parameter rule → one interface). Only `open`, `title`, `onConfirm`,
 * and `onCancel` are required; every other field has a sensible default.
 */
export interface ConfirmDialogProps {
    /** Whether the dialog is currently visible. */
    open: boolean;
    /** Dialog heading text (for example, 'Delete User'). */
    title: string;
    /** Optional descriptive prompt rendered above `children`. */
    message?: string;
    /**
     * Optional detail block. The calling page supplies the confirmation
     * summary here (for example the COUSR03 user detail: user_id, first name,
     * last name, and user_type). Never pass passwords or unmasked sensitive
     * data into this slot.
     */
    children?: ReactNode;
    /**
     * Confirm button label. Defaults to `DEFAULT_CONFIRM_LABEL`; the delete
     * flow overrides this with 'Delete'.
     */
    confirmLabel?: string;
    /** Cancel button label. Defaults to `DEFAULT_CANCEL_LABEL`. */
    cancelLabel?: string;
    /**
     * Confirm button color. Defaults to `'error'` because the primary consumer
     * is a destructive delete; callers may override to `'primary'` (or
     * `'warning'`) for non-destructive confirmations.
     */
    confirmColor?: 'error' | 'primary' | 'warning';
    /** When true, disables both actions while a request is in flight. */
    loading?: boolean;
    /** Invoked when the user confirms the action. */
    onConfirm: () => void;
    /** Invoked when the user cancels (button, Escape key, or backdrop click). */
    onCancel: () => void;
}

/**
 * Renders a modal confirmation dialog with a cancel and a confirm action.
 *
 * @param props - The {@link ConfirmDialogProps} controlling the dialog.
 * @returns The rendered MUI confirmation dialog.
 *
 * @example
 * ```tsx
 * <ConfirmDialog
 *     open={isDialogOpen}
 *     title="Delete User"
 *     message="This action cannot be undone."
 *     confirmLabel="Delete"
 *     loading={isDeleting}
 *     onConfirm={HandleDeleteConfirmed}
 *     onCancel={HandleDeleteCancelled}
 * >
 *     <Typography variant="body2">User ID: {userId}</Typography>
 * </ConfirmDialog>
 * ```
 */
export function ConfirmDialog(props: ConfirmDialogProps) {
    const {
        confirmLabel = DEFAULT_CONFIRM_LABEL,
        cancelLabel = DEFAULT_CANCEL_LABEL,
        confirmColor = 'error',
        loading = false,
    } = props;

    /** Confirms the action, unless a request is already in flight. */
    function HandleConfirm(): void {
        if (loading) {
            return;
        }
        props.onConfirm();
    }

    /** Cancels the action; also fired by the Escape key and backdrop click. */
    function HandleCancel(): void {
        if (loading) {
            return;
        }
        props.onCancel();
    }

    return (
        <Dialog
            open={props.open}
            onClose={HandleCancel}
            aria-labelledby="confirm-dialog-title"
            aria-describedby="confirm-dialog-description"
        >
            <DialogTitle id="confirm-dialog-title">{props.title}</DialogTitle>
            <DialogContent>
                {props.message ? (
                    <DialogContentText id="confirm-dialog-description">
                        {props.message}
                    </DialogContentText>
                ) : null}
                {props.children}
            </DialogContent>
            <DialogActions>
                <Button
                    variant="outlined"
                    onClick={HandleCancel}
                    disabled={loading}
                >
                    {cancelLabel}
                </Button>
                <Button
                    variant="contained"
                    color={confirmColor}
                    onClick={HandleConfirm}
                    disabled={loading}
                    autoFocus
                >
                    {confirmLabel}
                </Button>
            </DialogActions>
        </Dialog>
    );
}
