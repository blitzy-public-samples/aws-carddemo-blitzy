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
    Box,
} from '@mui/material';
import type { ReactNode } from 'react';

/** Default label for the confirm (primary) action button. */
const DEFAULT_CONFIRM_LABEL = 'Confirm';

/** Default label for the cancel (secondary) action button. */
const DEFAULT_CANCEL_LABEL = 'Cancel';

/** DOM id of the dialog title, referenced by `aria-labelledby`. */
const TITLE_ID = 'confirm-dialog-title';

/** DOM id of the optional `message` prompt, used as the description target. */
const MESSAGE_ID = 'confirm-dialog-description';

/**
 * DOM id of the `children` detail block. When no `message` is supplied, the
 * children ARE the description, so `aria-describedby` points here instead —
 * never at a missing element (QA M-29).
 */
const DETAIL_ID = 'confirm-dialog-detail';

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

    // Resolve which element actually describes the dialog so `aria-describedby`
    // never dangles (QA M-29). Priority: the `message` prompt when present,
    // otherwise the `children` detail block (the children-only destructive-
    // warning path used by /users/delete). When neither is supplied the
    // attribute is omitted entirely rather than pointing at a missing id.
    const hasMessage = Boolean(props.message);
    const hasChildren = props.children !== undefined && props.children !== null;
    let describedById: string | undefined;
    if (hasMessage) {
        describedById = MESSAGE_ID;
    } else if (hasChildren) {
        describedById = DETAIL_ID;
    } else {
        describedById = undefined;
    }

    // Destructive dialogs default initial focus to Cancel (the SAFE choice) so a
    // stray Enter does not immediately confirm a delete (QA M-29). Non-
    // destructive confirmations keep initial focus on the confirm action.
    const focusCancel = confirmColor === 'error';

    return (
        <Dialog
            open={props.open}
            onClose={HandleCancel}
            aria-labelledby={TITLE_ID}
            aria-describedby={describedById}
        >
            <DialogTitle id={TITLE_ID}>{props.title}</DialogTitle>
            <DialogContent>
                {hasMessage ? (
                    <DialogContentText id={MESSAGE_ID}>
                        {props.message}
                    </DialogContentText>
                ) : null}
                {hasChildren ? (
                    <Box id={DETAIL_ID}>{props.children}</Box>
                ) : null}
            </DialogContent>
            <DialogActions>
                <Button
                    variant="outlined"
                    onClick={HandleCancel}
                    disabled={loading}
                    autoFocus={focusCancel}
                >
                    {cancelLabel}
                </Button>
                <Button
                    variant="contained"
                    color={confirmColor}
                    onClick={HandleConfirm}
                    disabled={loading}
                    autoFocus={!focusCancel}
                >
                    {confirmLabel}
                </Button>
            </DialogActions>
        </Dialog>
    );
}
