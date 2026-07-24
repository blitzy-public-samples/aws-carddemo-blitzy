// -----------------------------------------------------------------------------
// Browser-security header policy (QA finding M-10).
//
// Next.js serves the SPA's HTML/JS from this frontend, so the hardened response
// headers for those documents must be set HERE (the backend's
// SecurityHeadersMiddleware only decorates API responses). Every value below is
// a token, not a magic literal, and is assembled into the Content-Security-Policy
// and the flat header list returned by `headers()`.
// -----------------------------------------------------------------------------

// True during `next dev`; production builds/standalone run with NODE_ENV
// 'production'. Used ONLY to relax script-src for the dev HMR runtime (which
// needs 'unsafe-eval'); the production policy never allows eval.
const IS_DEVELOPMENT = process.env.NODE_ENV !== 'production';

// Backend origin the browser calls (axios in src/lib/apiClient.ts). Derived from
// the same NEXT_PUBLIC_API_URL that the client bundle uses, so connect-src allows
// exactly that origin and nothing wider. Falls back to 'self' only when unset.
function ResolveApiConnectSource() {
    const apiUrl = process.env.NEXT_PUBLIC_API_URL;
    if (!apiUrl) {
        return '';
    }
    try {
        return new URL(apiUrl).origin;
    } catch {
        // A malformed NEXT_PUBLIC_API_URL must not break the build; omit it and
        // fall back to the same-origin 'self' already present in connect-src.
        return '';
    }
}

// Assemble the Content-Security-Policy. script-src/style-src include
// 'unsafe-inline' because Next.js injects inline hydration/bootstrap scripts and
// Emotion (MUI's engine) injects inline <style> tags without a nonce; this is the
// documented policy for a non-nonce Next + MUI app. 'unsafe-eval' is added ONLY
// in development for the HMR runtime. frame-ancestors 'none' + X-Frame-Options
// DENY block clickjacking; object-src 'none' and base-uri 'self' close common
// injection vectors.
function BuildContentSecurityPolicy() {
    const apiConnectSource = ResolveApiConnectSource();
    const connectSources = ["'self'", apiConnectSource].filter(Boolean).join(' ');
    const scriptSource = IS_DEVELOPMENT
        ? "'self' 'unsafe-inline' 'unsafe-eval'"
        : "'self' 'unsafe-inline'";
    const directives = [
        "default-src 'self'",
        `script-src ${scriptSource}`,
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "font-src 'self' data:",
        `connect-src ${connectSources}`,
        "frame-ancestors 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "object-src 'none'",
    ];
    return directives.join('; ');
}

// Flat security-header list applied to EVERY route by `headers()` below. Mirrors
// the backend's API header posture for the documents Next.js serves: MIME-sniff
// protection, clickjacking defense, referrer minimization, a locked-down
// Permissions-Policy, and no-store caching so authenticated HTML is never cached.
const SECURITY_HEADERS = [
    { key: 'Content-Security-Policy', value: BuildContentSecurityPolicy() },
    { key: 'X-Content-Type-Options', value: 'nosniff' },
    { key: 'X-Frame-Options', value: 'DENY' },
    { key: 'Referrer-Policy', value: 'no-referrer' },
    {
        key: 'Permissions-Policy',
        value: 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
    },
    { key: 'Cache-Control', value: 'no-store' },
];

/** @type {import('next').NextConfig} */
const nextConfig = {
    // Enable React Strict Mode so potential problems (double-invoked effects,
    // deprecated API usage) surface during development. It has no effect on the
    // production runtime and is safe to keep enabled for builds.
    reactStrictMode: true,

    // Drop the `X-Powered-By: Next.js` banner (QA finding M-10): it discloses the
    // framework/implementation and offers no functional value.
    poweredByHeader: false,

    // Emit a self-contained production server under `.next/standalone`.
    // The frontend Dockerfile copies `.next/standalone` and `.next/static` into
    // the runtime image and launches the app with `node server.js`, so this
    // value MUST remain 'standalone' for the container image to work.
    output: 'standalone',

    // Attach the browser-security headers (QA finding M-10) to every response
    // Next.js serves. The `source` glob matches all routes.
    async headers() {
        return [
            {
                source: '/:path*',
                headers: SECURITY_HEADERS,
            },
        ];
    },

    // NOTE (QA #6): The `eslint` config key is intentionally omitted. Next.js 16
    // removed the built-in ESLint-during-build integration, so an `eslint` key
    // here is an unrecognized option and logs a deprecation warning on every
    // build/start ("`eslint` configuration in next.config.js is no longer
    // supported"). Because Next 16 no longer runs ESLint during `next build`,
    // dropping the key does NOT re-enable a lint step or introduce a build
    // prompt/failure. Linting, when desired, is run separately via the project's
    // lint script / `.eslintrc`. TypeScript type-checking remains fully enabled
    // (we do NOT set `typescript.ignoreBuildErrors`), so `next build` still fails
    // on type errors.
};

module.exports = nextConfig;
