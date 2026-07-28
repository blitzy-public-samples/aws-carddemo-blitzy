/**
 * next.config.js Cache-Control policy spec (QA finding I14).
 *
 * Guards the header policy for the standalone production build: hashed, content-
 * addressed assets under /_next/static must be cached immutably for a year,
 * while every OTHER (potentially per-user) response must stay `no-store`. The
 * two route sources must be MUTUALLY EXCLUSIVE so no response ever receives a
 * duplicate/conflicting Cache-Control header — the defect I14 reported, where a
 * single `/:path*` rule wrongly stamped `no-store` onto the immutable assets.
 *
 * `headers()` is exercised with NODE_ENV forced to 'production' because
 * IS_DEVELOPMENT collapses the policy to a single dev-only no-store rule (so HMR
 * chunks under /_next/static stay uncached during `next dev`).
 */

const IMMUTABLE_STATIC_SOURCE = '/_next/static/:path*';
const NO_STORE = 'no-store';
const IMMUTABLE = 'public, max-age=31536000, immutable';
const REQUIRED_HEADER_KEYS = [
    'Content-Security-Policy',
    'X-Content-Type-Options',
    'X-Frame-Options',
    'Referrer-Policy',
    'Permissions-Policy',
    'Cache-Control',
];

interface HeaderEntry {
    key: string;
    value: string;
}

interface HeaderRule {
    source: string;
    headers: HeaderEntry[];
}

/**
 * Loads next.config.js FRESH under a given NODE_ENV so its module-load-time
 * IS_DEVELOPMENT branch is re-evaluated, then returns the resolved header rules.
 * Restores the prior NODE_ENV and resets the module registry afterward so the
 * surrounding suite is unaffected.
 *
 * @param nodeEnv - The NODE_ENV to evaluate the config under.
 * @returns The array of header rules `headers()` produces.
 */
/**
 * Reassigns process.env.NODE_ENV. It is typed read-only (Next augments
 * ProcessEnv), so the write goes through a mutable-record cast — reassigning it
 * at runtime is precisely how this spec forces the config's module-load-time
 * IS_DEVELOPMENT branch.
 *
 * @param value - The NODE_ENV value to set (or restore).
 */
function SetNodeEnv(value: string | undefined): void {
    (process.env as Record<string, string | undefined>).NODE_ENV = value;
}

async function LoadHeaderRules(nodeEnv: string): Promise<HeaderRule[]> {
    const previousNodeEnv = process.env.NODE_ENV;
    SetNodeEnv(nodeEnv);
    jest.resetModules();
    try {
        const nextConfig = require('../../next.config.js');
        return (await nextConfig.headers()) as HeaderRule[];
    } finally {
        SetNodeEnv(previousNodeEnv);
        jest.resetModules();
    }
}

/**
 * Reads the Cache-Control value from a header rule.
 *
 * @param rule - The header rule to inspect.
 * @returns The Cache-Control value, or undefined when the header is absent.
 */
function CacheControlOf(rule: HeaderRule): string | undefined {
    const entry = rule.headers.find((header) => header.key === 'Cache-Control');
    return entry ? entry.value : undefined;
}

describe('next.config.js Cache-Control policy (QA I14)', () => {
    it('caches hashed /_next/static assets immutably in production', async () => {
        const rules = await LoadHeaderRules('production');

        const staticRule = rules.find(
            (rule) => rule.source === IMMUTABLE_STATIC_SOURCE,
        );
        expect(staticRule).toBeDefined();
        expect(CacheControlOf(staticRule as HeaderRule)).toBe(IMMUTABLE);
    });

    it('keeps non-static routes no-store in production via a source that excludes /_next/static', async () => {
        const rules = await LoadHeaderRules('production');

        const dynamicRule = rules.find(
            (rule) =>
                rule.source !== IMMUTABLE_STATIC_SOURCE &&
                CacheControlOf(rule) === NO_STORE,
        );
        expect(dynamicRule).toBeDefined();
        // The dynamic rule MUST exclude the hashed-asset prefix (negative
        // lookahead) so the two rules are mutually exclusive — no path can
        // receive both Cache-Control values.
        expect((dynamicRule as HeaderRule).source).toContain('?!_next/static');
    });

    it('never stamps no-store onto the immutable static assets in production', async () => {
        const rules = await LoadHeaderRules('production');

        const staticRule = rules.find(
            (rule) => rule.source === IMMUTABLE_STATIC_SOURCE,
        ) as HeaderRule;
        expect(CacheControlOf(staticRule)).not.toBe(NO_STORE);
    });

    it('preserves the hardened security headers on every production rule', async () => {
        const rules = await LoadHeaderRules('production');

        rules.forEach((rule) => {
            const keys = rule.headers.map((header) => header.key);
            expect(keys).toEqual(expect.arrayContaining(REQUIRED_HEADER_KEYS));
        });
    });

    it('collapses to a single no-store rule over all paths in development so HMR stays uncached', async () => {
        const rules = await LoadHeaderRules('development');

        expect(rules).toHaveLength(1);
        expect(rules[0].source).toBe('/:path*');
        expect(CacheControlOf(rules[0])).toBe(NO_STORE);
    });
});
