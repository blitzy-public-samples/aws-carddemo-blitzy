'use client';

/**
 * BrandLogo — the Blitzy brand mark, rendered from the self-hosted
 * `public/blitzy-logo.svg` asset.
 *
 * One definition serves both placements: the `AppShell` header (to the left of
 * the `Blitzy` title) and the `/signon` page (above its heading). The asset is
 * served from the app's own origin, so the Content-Security-Policy
 * `img-src 'self'` directive admits it without any external host.
 *
 * The mark is rendered through an MUI `Box` with `component="img"` rather than a
 * raw `<img>` element, and its height resolves to a theme spacing token per
 * placement with an `auto` width, so the SVG keeps its intrinsic aspect ratio.
 *
 * @see AAP §0.3.4 — Material UI design-system rules (MUI components only,
 *      zero hardcoded CSS).
 */

import Box from '@mui/material/Box';

/** Same-origin path of the brand logo asset in `public/`. */
const BRAND_LOGO_SRC = '/blitzy-logo.svg';

/** Alternative text announced for the brand logo. */
const BRAND_LOGO_ALT = 'Blitzy logo';

/** Where the logo is rendered; each placement has its own height token. */
export type BrandLogoPlacement = 'header' | 'signon';

/**
 * Logo height per placement, as `theme.spacing` multipliers on the 8px base
 * unit: 3.5 → 28px in the header, 6 → 48px on the signon page.
 */
const BRAND_LOGO_HEIGHT_SPACING: Record<BrandLogoPlacement, number> = {
    header: 3.5,
    signon: 6,
};

/** Props for {@link BrandLogo}. */
export interface BrandLogoProps {
    /** The placement that selects the logo height. */
    placement: BrandLogoPlacement;
}

/**
 * Renders the Blitzy logo at the height of the given placement.
 *
 * Example:
 *
 * ```tsx
 * <BrandLogo placement="header" />
 * ```
 *
 * @param props - The {@link BrandLogoProps} selecting the placement.
 * @returns The logo image element.
 */
export function BrandLogo(props: BrandLogoProps) {
    const heightSpacing = BRAND_LOGO_HEIGHT_SPACING[props.placement];
    return (
        <Box
            component="img"
            src={BRAND_LOGO_SRC}
            alt={BRAND_LOGO_ALT}
            sx={(theme) => ({
                display: 'block',
                height: theme.spacing(heightSpacing),
                width: 'auto',
            })}
        />
    );
}
