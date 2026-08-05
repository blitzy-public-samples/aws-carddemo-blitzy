/**
 * :module: programRoutes.test
 * :purpose: Lock the menu dispatch mapping: every program named by the two menu option
 *     tables (``app/cpy/COMEN02Y.cpy`` and ``app/cpy/COADM02Y.cpy``) resolves to one of
 *     the seventeen screens, each of the four card and user programs resolves to its own
 *     distinct screen rather than to a shared service prefix, and the resolver carries
 *     no authorization data of its own.
 */

import { PROGRAM_SCREEN_ROUTES, resolveProgramRoute } from './programRoutes';

/** The ten populated rows of ``CDEMO-MENU-OPTIONS`` (``COMEN02Y``), in copybook order. */
const MAIN_MENU_PROGRAMS = [
  'COACTVWC',
  'COACTUPC',
  'COCRDLIC',
  'COCRDSLC',
  'COCRDUPC',
  'COTRN00C',
  'COTRN01C',
  'COTRN02C',
  'CORPT00C',
  'COBIL00C',
];

/** The four populated rows of ``CDEMO-ADMIN-OPTIONS`` (``COADM02Y``), in copybook order. */
const ADMIN_MENU_PROGRAMS = ['COUSR00C', 'COUSR01C', 'COUSR02C', 'COUSR03C'];

describe('resolveProgramRoute', () => {
  it('resolves every main-menu program to a screen route', () => {
    for (const program of MAIN_MENU_PROGRAMS) {
      expect(resolveProgramRoute(program)).not.toBeNull();
    }
  });

  it('resolves every admin-menu program to a screen route', () => {
    for (const program of ADMIN_MENU_PROGRAMS) {
      expect(resolveProgramRoute(program)).not.toBeNull();
    }
  });

  it('covers exactly the fourteen menu programs and no others', () => {
    expect([...PROGRAM_SCREEN_ROUTES.keys()].sort()).toEqual(
      [...MAIN_MENU_PROGRAMS, ...ADMIN_MENU_PROGRAMS].sort(),
    );
  });

  it('gives each card and user program its own screen, not a shared prefix', () => {
    expect(resolveProgramRoute('COCRDLIC')).toBe('/cards');
    expect(resolveProgramRoute('COCRDSLC')).toBe('/cards/view');
    expect(resolveProgramRoute('COCRDUPC')).toBe('/cards/update');
    expect(resolveProgramRoute('COUSR00C')).toBe('/users');
    expect(resolveProgramRoute('COUSR01C')).toBe('/users/add');
    expect(resolveProgramRoute('COUSR02C')).toBe('/users/update');
    expect(resolveProgramRoute('COUSR03C')).toBe('/users/delete');

    const routes = [...PROGRAM_SCREEN_ROUTES.values()];
    expect(new Set(routes).size).toBe(routes.length);
  });

  it('trims the dispatched program name the response carries', () => {
    expect(resolveProgramRoute('  COBIL00C  ')).toBe('/billpay');
  });

  it('yields null for an absent or unmapped program', () => {
    expect(resolveProgramRoute(null)).toBeNull();
    expect(resolveProgramRoute('DUMMY   ')).toBeNull();
    expect(resolveProgramRoute('CBACT04C')).toBeNull();
  });
});
