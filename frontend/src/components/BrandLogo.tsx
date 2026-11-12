'use client';

/** Same-origin Blitzy mark shared by the header and sign-on page. */

import Box from '@mui/material/Box';

const BRAND_LOGO_SRC = '/blitzy-logo.svg';
const BRAND_LOGO_ALT = 'Blitzy logo';

export type BrandLogoPlacement = 'header' | 'signon';

/** Heights use the 8px theme spacing unit: header 28px; sign-on 48px. */
const BRAND_LOGO_HEIGHT_SPACING: Record<BrandLogoPlacement, number> = {
    header: 3.5,
    signon: 6,
};

export interface BrandLogoProps {
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
                // Auto inline margins center the mark in a flex column without stretching it.
                mx: 'auto',
                height: theme.spacing(heightSpacing),
                width: 'auto',
            })}
        />
    );
}
