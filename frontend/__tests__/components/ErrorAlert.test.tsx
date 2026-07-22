/**
 * ErrorAlert.test.tsx — React Testing Library spec that locks the public
 * behavior of the shared MUI feedback component `ErrorAlert`: the
 * transaction-posting reason-code message map (traceable verbatim to
 * app/cbl/CBTRN02C.cbl, codes 100-103 and 109), error-shape normalization,
 * the severity / title / auto-hide defaults, and the click-away-ignored
 * dismissal rule. ErrorAlert is the modern replacement for the single 3270
 * message line (`ERRMSGI PIC X(78)`, app/cpy-bms/COSGN00.CPY).
 */

import { RenderWithProviders, screen, userEvent, act } from '../testUtils';
import { ErrorAlert } from '@/components/ErrorAlert';
import type { ErrorResponse } from '@/types';

/**
 * Canonical posting reason codes paired with their descriptions, copied
 * verbatim from the batch posting validator `CBTRN02C` (app/cbl/CBTRN02C.cbl).
 * Codes 101 and 109 intentionally share the same description
 * ('ACCOUNT RECORD NOT FOUND'), matching the mainframe source exactly
 * (CBTRN02C.cbl L397-399 for 101 and L556-558 for 109).
 */
const POSTING_CODE_CASES: ReadonlyArray<[string, string]> = [
    ['100', 'INVALID CARD NUMBER FOUND'],
    ['101', 'ACCOUNT RECORD NOT FOUND'],
    ['102', 'OVERLIMIT TRANSACTION'],
    ['103', 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'],
    ['109', 'ACCOUNT RECORD NOT FOUND'],
];

/** Auto-hide delay (ms) the component applies when no override is supplied. */
const DEFAULT_AUTO_HIDE_DURATION = 6000;

/** Explicit auto-hide delay (ms) used to prove the prop override is honored. */
const CUSTOM_AUTO_HIDE_DURATION = 3000;

describe('ErrorAlert', () => {
    // ----------------------------------------------------------------------
    // Rendering visibility
    // ----------------------------------------------------------------------

    it('renders the alert with the message when open', () => {
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={jest.fn()}
                error={{ message: 'Something went wrong' } as ErrorResponse}
            />,
        );

        expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    });

    it('renders nothing when not open', () => {
        RenderWithProviders(
            <ErrorAlert
                open={false}
                onClose={jest.fn()}
                error={{ message: 'hidden message' } as ErrorResponse}
            />,
        );

        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        expect(screen.queryByText('hidden message')).not.toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // POSTING_CODE_MESSAGES mapping (data-driven, traceable to CBTRN02C)
    // ----------------------------------------------------------------------

    it.each(POSTING_CODE_CASES)(
        'maps posting code %s to its description',
        (code, message) => {
            // An empty message paired with a code exercises the component's
            // posting-code backfill, which resolves the description from its
            // internal POSTING_CODE_MESSAGES map.
            RenderWithProviders(
                <ErrorAlert open onClose={jest.fn()} error={{ message: '', code }} />,
            );

            const alert = screen.getByRole('alert');
            expect(alert).toHaveTextContent(message);
            expect(alert).toHaveTextContent(`[${code}]`);
        },
    );

    // ----------------------------------------------------------------------
    // NormalizeError shape handling
    // ----------------------------------------------------------------------

    it('renders an ErrorResponse object (message + code + detail)', () => {
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={jest.fn()}
                error={
                    {
                        message: 'Posting failed',
                        code: '102',
                        detail: 'balance exceeded',
                    } as ErrorResponse
                }
            />,
        );

        const alert = screen.getByRole('alert');
        expect(alert).toHaveTextContent('Posting failed');
        expect(alert).toHaveTextContent('[102]');
        expect(alert).toHaveTextContent('balance exceeded');
    });

    it('renders a plain string error', () => {
        RenderWithProviders(
            <ErrorAlert open onClose={jest.fn()} error={'Just a string error'} />,
        );

        expect(screen.getByRole('alert')).toHaveTextContent('Just a string error');
    });

    it('renders a fallback message for an unknown error shape', () => {
        // A number is an `unknown` shape with no `message`, so the component
        // falls back to its generic unexpected-error text.
        RenderWithProviders(<ErrorAlert open onClose={jest.fn()} error={42} />);

        expect(screen.getByRole('alert')).toHaveTextContent(
            'An unexpected error occurred.',
        );
    });

    it('handles a null error without crashing', () => {
        // `null` normalizes to an empty message; the component must still render
        // the alert region gracefully rather than throwing or early-returning.
        RenderWithProviders(<ErrorAlert open onClose={jest.fn()} error={null} />);

        expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // severity + title
    // ----------------------------------------------------------------------

    it('defaults severity to error', () => {
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={jest.fn()}
                error={{ message: 'Boom' } as ErrorResponse}
            />,
        );

        // MUI applies the `MuiAlert-filledError` class for the error severity.
        expect(screen.getByRole('alert').className).toMatch(/error/i);
    });

    it('honors a custom severity', () => {
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={jest.fn()}
                severity="warning"
                error={{ message: 'Heads up' } as ErrorResponse}
            />,
        );

        expect(screen.getByRole('alert').className).toMatch(/warning/i);
    });

    it('renders the title when provided', () => {
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={jest.fn()}
                title="Payment Error"
                error={{ message: 'Boom' } as ErrorResponse}
            />,
        );

        expect(screen.getByText('Payment Error')).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // Dismissal behavior (userEvent)
    // ----------------------------------------------------------------------

    it('calls onClose when the close button is clicked', async () => {
        const user = userEvent.setup();
        const onClose = jest.fn();
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={onClose}
                error={{ message: 'Dismiss me' } as ErrorResponse}
            />,
        );

        await user.click(screen.getByRole('button', { name: /close/i }));

        expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('does NOT call onClose on click-away', async () => {
        const user = userEvent.setup();
        const onClose = jest.fn();
        RenderWithProviders(
            <ErrorAlert
                open
                onClose={onClose}
                error={{ message: 'Read me first' } as ErrorResponse}
            />,
        );

        // Clicking outside the alert (a "clickaway") must be ignored so the
        // operator cannot accidentally dismiss an error they still need to read.
        await user.click(document.body);

        expect(onClose).not.toHaveBeenCalled();
        expect(screen.getByRole('alert')).toBeInTheDocument();
    });

    // ----------------------------------------------------------------------
    // autoHideDuration (fake timers — never mixed with userEvent)
    // ----------------------------------------------------------------------

    it('auto-hides after the default 6000ms', () => {
        jest.useFakeTimers();
        try {
            const onClose = jest.fn();
            RenderWithProviders(
                <ErrorAlert
                    open
                    onClose={onClose}
                    error={{ message: 'x' } as ErrorResponse}
                />,
            );

            act(() => {
                jest.advanceTimersByTime(DEFAULT_AUTO_HIDE_DURATION - 1);
            });
            expect(onClose).not.toHaveBeenCalled();

            act(() => {
                jest.advanceTimersByTime(1);
            });
            expect(onClose).toHaveBeenCalledTimes(1);
        } finally {
            act(() => {
                jest.runOnlyPendingTimers();
            });
            jest.useRealTimers();
        }
    });

    it('honors a custom autoHideDuration', () => {
        jest.useFakeTimers();
        try {
            const onClose = jest.fn();
            RenderWithProviders(
                <ErrorAlert
                    open
                    onClose={onClose}
                    autoHideDuration={CUSTOM_AUTO_HIDE_DURATION}
                    error={{ message: 'x' } as ErrorResponse}
                />,
            );

            act(() => {
                jest.advanceTimersByTime(CUSTOM_AUTO_HIDE_DURATION);
            });

            expect(onClose).toHaveBeenCalledTimes(1);
        } finally {
            act(() => {
                jest.runOnlyPendingTimers();
            });
            jest.useRealTimers();
        }
    });
});
