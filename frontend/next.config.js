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
