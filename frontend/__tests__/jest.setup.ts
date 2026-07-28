/**
 * Global Jest setup — referenced by `package.json > jest.setupFilesAfterEnv`
 * and run once per test file before its suites execute. The mocks below back
 * the browser APIs that jsdom omits but Material UI calls at render time
 * (`matchMedia`, `ResizeObserver`, `IntersectionObserver`, `scrollTo`).
 * Greenfield test infrastructure; there is no legacy COBOL origin.
 */

// Register the @testing-library/jest-dom custom matchers (toBeInTheDocument,
// toHaveValue, toBeDisabled, toHaveAttribute, toHaveTextContent, ...).
import '@testing-library/jest-dom';

// Register the jest-axe accessibility matcher (`toHaveNoViolations`) globally so
// every component/page spec can assert `expect(await axe(container))
// .toHaveNoViolations()` as an automated WCAG gate (QA M-30). axe-core runs in
// jsdom; the layout-dependent color-contrast check cannot compute here and is
// covered instead by the browser-level Lighthouse accessibility audit.
import { toHaveNoViolations } from 'jest-axe';
expect.extend(toHaveNoViolations);

// ---------------------------------------------------------------------------
// window.matchMedia
// ---------------------------------------------------------------------------
// jsdom omits matchMedia, but MUI's useMediaQuery and its responsive
// components (AppBar/Drawer behavior, Grid) call it during render. Default to
// `matches: false` so the smallest breakpoint is assumed, keeping layout
// deterministic across tests. Object.defineProperty conforms to the browser
// API surface without triggering a MediaQueryList structural type mismatch.
Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: jest.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: jest.fn(),
        removeListener: jest.fn(),
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        dispatchEvent: jest.fn(),
    })),
});

// ---------------------------------------------------------------------------
// ResizeObserver
// ---------------------------------------------------------------------------
// jsdom lacks ResizeObserver; MUI layout-measuring components (Table, Popper,
// Menu/Select positioning, Tooltip) reference it at runtime.
class ResizeObserverMock {
    observe = jest.fn();
    unobserve = jest.fn();
    disconnect = jest.fn();
}

global.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver;

// ---------------------------------------------------------------------------
// IntersectionObserver
// ---------------------------------------------------------------------------
// jsdom lacks IntersectionObserver; provided defensively for any lazy or
// visibility-driven MUI behavior.
class IntersectionObserverMock {
    readonly root = null;
    readonly rootMargin = '';
    readonly thresholds = [];
    observe = jest.fn();
    unobserve = jest.fn();
    disconnect = jest.fn();
    takeRecords = jest.fn(() => []);
}

global.IntersectionObserver = IntersectionObserverMock as unknown as typeof IntersectionObserver;

// ---------------------------------------------------------------------------
// window.scrollTo
// ---------------------------------------------------------------------------
// jsdom does not implement scrollTo and logs "Not implemented" errors when
// MUI focus management invokes it; stub it so specs stay quiet.
window.scrollTo = jest.fn() as unknown as typeof window.scrollTo;
