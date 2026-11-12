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
import { useEffect, useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';

/**
 * Isomorphic layout effect — `useLayoutEffect` in the browser (so it runs in the
 * commit phase, BEFORE MUI's Modal applies its background `aria-hidden` in a
 * passive effect) and `useEffect` during SSR (avoids React's server-side
 * `useLayoutEffect` warning). The dialog only ever opens from a client
 * interaction, so the browser branch is what matters (QA Issue 7d).
 */
const useIsomorphicLayoutEffect =
    typeof window !== 'undefined' ? useLayoutEffect : useEffect;

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

    // --- Focus management across close (QA N-02) -----------------------------
    //
    // MUI's Modal drives two focus behaviours as a dialog closes that, together,
    // produce the browser warning "Blocked aria-hidden on an element because its
    // descendant retained focus":
    //   1. While the ~225ms exit transition plays, the background content behind
    //      the dialog still carries `aria-hidden="true"`.
    //   2. By default the Modal RESTORES focus to the element that opened it —
    //      here the destructive trigger button, which lives INSIDE that still
    //      `aria-hidden` background. A focused descendant of an aria-hidden
    //      subtree is exactly what the browser blocks and warns about.
    //
    // The fix has three cooperating parts:
    //   (a) `disableRestoreFocus` on the Dialog stops MUI from moving focus back
    //       to the trigger mid-transition (removes cause #2).
    //   (b) `BlurActiveDialogElement` parks focus on <body> BEFORE `open` flips
    //       to false, so no dialog descendant retains focus when the Modal also
    //       briefly marks the closing dialog container itself `aria-hidden`.
    //   (c) `HandleExited` returns focus to the opener AFTER the transition ends
    //       (once `aria-hidden` has been cleared from the background), preserving
    //       the accessible "focus returns to the control you came from" behaviour
    //       for the cancel/stay-on-page path.

    /**
     * The element that had focus when the dialog opened (its "trigger").
     *
     * Captured at the render where `open` transitions false → true — which runs
     * BEFORE MUI's focus-trap effect moves focus into the dialog — so it records
     * the opener (e.g. the page's DELETE button), never a dialog control.
     */
    const triggerRef = useRef<HTMLElement | null>(null);
    const wasOpenRef = useRef<boolean>(false);
    // Refs to the two action buttons so the initial focus can be applied AFTER
    // the open transition completes (see HandleDialogEntered) rather than via
    // `autoFocus`. Under MUI v9 + React 19, `autoFocus` focuses a button while
    // the Modal is still applying aria-hidden to the rest of the page, producing
    // a transient "Blocked aria-hidden … descendant retained focus" console
    // warning (QA Issue 7d).
    const cancelButtonRef = useRef<HTMLButtonElement>(null);
    const confirmButtonRef = useRef<HTMLButtonElement>(null);
    // N02 accessibility (chrome-verified): capture the opener's focused element
    // at the render where `open` flips false -> true, BEFORE MUI's focus-trap
    // layout effect (a CHILD effect, which React runs before THIS parent
    // component's own effects) moves focus into the dialog. Capturing this in a
    // useEffect/useLayoutEffect is impossible -- by the time any effect here
    // runs, the focus trap has already relocated focus, so document.activeElement
    // would read a dialog control instead of the trigger. The captured ref is
    // only READ later in event/transition callbacks (never during render) and
    // the writes are idempotent per open-transition, so react-hooks/refs is
    // deliberately suppressed for exactly this correct render-time capture.
    /* eslint-disable react-hooks/refs */
    if (props.open && !wasOpenRef.current && typeof document !== 'undefined') {
        const active = document.activeElement;
        triggerRef.current = active instanceof HTMLElement ? active : null;
    }
    wasOpenRef.current = props.open;
    /* eslint-enable react-hooks/refs */

    // QA Issue 7d — eliminate the OPEN-time half of the "Blocked aria-hidden …
    // descendant retained focus" warning at its ROOT CAUSE. When the dialog
    // opens, MUI's Modal marks the background page content `aria-hidden` inside a
    // PASSIVE effect. If the TRIGGER button that opened the dialog is still
    // focused at that moment, Chrome refuses to hide its focused ancestor and
    // logs the warning. Because this runs as a LAYOUT effect (client), it fires
    // in the commit phase BEFORE MUI's passive effect, so blurring the trigger
    // here moves focus to <body> (an ancestor, never a descendant of the hidden
    // wrapper) before the background is hidden. The trigger has already been
    // captured into `triggerRef` above (at render, before this blur), so the
    // cancel path can still restore focus to it via {@link HandleExited}. MUI's
    // focus trap and HandleDialogEntered then place focus correctly INSIDE the
    // dialog, so the net user-visible focus is unchanged — only the transient
    // race is removed. (The CLOSE-time half is handled by disableRestoreFocus +
    // BlurActiveDialogElement + HandleExited below.)
    useIsomorphicLayoutEffect(() => {
        if (!props.open) {
            return;
        }
        const active = document.activeElement;
        if (active instanceof HTMLElement) {
            active.blur();
        }
    }, [props.open]);

    /**
     * Moves focus off the currently-focused dialog descendant BEFORE the parent
     * flips `open` to false (QA N-02).
     *
     * Parking focus on `<body>` guarantees no dialog descendant retains focus
     * while the Modal is applying/removing `aria-hidden` during close, so the
     * "descendant retained focus" warning never fires. Final focus placement is
     * handled by {@link HandleExited} (cancel: back on the trigger) or by the
     * AppShell route announcer (confirm+navigate: the next page's heading).
     */
    function BlurActiveDialogElement(): void {
        if (typeof document === 'undefined') {
            return;
        }
        const active = document.activeElement;
        if (active instanceof HTMLElement) {
            active.blur();
        }
    }

    /**
     * Returns focus to the opener after the close transition completes.
     *
     * Runs on the transition `onExited` callback, by which point MUI has cleared
     * `aria-hidden` from the background. Focus is restored on the NEXT animation
     * frame (so any trailing aria-hidden bookkeeping has settled) and only when
     * the trigger is still present and enabled — after a CONFIRMED delete the
     * trigger is unmounted/disabled, so focus is intentionally left for the route
     * announcer to place on the destination page's heading instead.
     */
    function HandleExited(): void {
        const trigger = triggerRef.current;
        triggerRef.current = null;
        if (trigger === null || typeof window === 'undefined') {
            return;
        }
        window.requestAnimationFrame(() => {
            const isButton = trigger instanceof HTMLButtonElement;
            const isDisabled = isButton && trigger.disabled;
            if (trigger.isConnected && !isDisabled) {
                trigger.focus();
            }
        });
    }

    /** Confirms the action, unless a request is already in flight. */
    function HandleConfirm(): void {
        if (loading) {
            return;
        }
        BlurActiveDialogElement();
        props.onConfirm();
    }

    /** Cancels the action; also fired by the Escape key and backdrop click. */
    function HandleCancel(): void {
        if (loading) {
            return;
        }
        BlurActiveDialogElement();
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

    /**
     * Applies the initial focus once the open transition has fully entered, so
     * the page's aria-hidden bookkeeping has settled first (QA Issue 7d). Focus
     * still lands on the SAFE control — Cancel for a destructive dialog, the
     * confirm action otherwise (preserving QA M-29). MUI's focus trap and the
     * Escape-to-cancel behavior are unaffected.
     */
    function HandleDialogEntered(): void {
        const target = focusCancel
            ? cancelButtonRef.current
            : confirmButtonRef.current;
        if (target) {
            target.focus();
        }
    }

    return (
        <Dialog
            open={props.open}
            onClose={HandleCancel}
            aria-labelledby={TITLE_ID}
            aria-describedby={describedById}
            disableRestoreFocus
            slotProps={{
                transition: {
                    onEntered: HandleDialogEntered,
                    onExited: HandleExited,
                },
            }}
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
                    ref={cancelButtonRef}
                    variant="outlined"
                    onClick={HandleCancel}
                    disabled={loading}
                >
                    {cancelLabel}
                </Button>
                <Button
                    ref={confirmButtonRef}
                    variant="contained"
                    color={confirmColor}
                    onClick={HandleConfirm}
                    disabled={loading}
                >
                    {confirmLabel}
                </Button>
            </DialogActions>
        </Dialog>
    );
}
