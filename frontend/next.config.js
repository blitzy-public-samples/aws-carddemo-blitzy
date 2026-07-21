/** @type {import('next').NextConfig} */
const nextConfig = {
    // Enable React Strict Mode so potential problems (double-invoked effects,
    // deprecated API usage) surface during development. It has no effect on the
    // production runtime and is safe to keep enabled for builds.
    reactStrictMode: true,

    // Emit a self-contained production server under `.next/standalone`.
    // The frontend Dockerfile copies `.next/standalone` and `.next/static` into
    // the runtime image and launches the app with `node server.js`, so this
    // value MUST remain 'standalone' for the container image to work.
    output: 'standalone',

    eslint: {
        // This project intentionally ships no ESLint configuration, so ESLint is
        // skipped during `next build` to prevent a build-time prompt/failure.
        // TypeScript type-checking is deliberately left enabled (we do NOT set
        // `typescript.ignoreBuildErrors`), so `next build` still fails on type
        // errors - only lint is bypassed here.
        ignoreDuringBuilds: true,
    },
};

module.exports = nextConfig;
