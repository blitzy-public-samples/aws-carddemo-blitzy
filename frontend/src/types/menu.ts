/**
 * Menu DTO types for the CardDemo React SPA.
 *
 * :module: ``frontend/src/types/menu.ts``
 *
 * Declares the request / response DTO contracts for the two menu screens of the
 * modernized CardDemo application:
 *
 * - ``MainMenuPage`` — the standard user menu, BMS mapset ``COMEN01`` driven by
 *   program ``COMEN01C`` under CICS transaction ``CM00``.
 * - ``AdminMenuPage`` — the administrator-only menu, BMS mapset ``COADM01``
 *   driven by program ``COADM01C`` under CICS transaction ``CA00``.
 *
 * The field contract is derived from the symbolic-map copybooks
 * ``app/cpy-bms/COMEN01.CPY`` and ``app/cpy-bms/COADM01.CPY`` (the ``AI`` input /
 * ``AO`` output two-view layouts; the ``L/F/A/C/P/H/V`` attribute bytes are
 * ignored and only the ``<field>I`` / ``<field>O`` value fields are modelled).
 * The two maps are field-identical, so a single :ts:type:`MenuResponseDto`
 * covers both, with the thin :ts:type:`MainMenuResponseDto` /
 * :ts:type:`AdminMenuResponseDto` aliases naming each screen's payload at its
 * call site. The selectable-option catalogue mirrors ``app/cpy/COMEN02Y.cpy``
 * (user options) and ``app/cpy/COADM02Y.cpy`` (admin options), which the backend
 * exposes through ``com.carddemo.common.constant.MenuOptions`` and the
 * api-gateway ``MenuController`` role-gated routing.
 *
 * Member names mirror the api-gateway ``MenuController`` JSON response contract
 * (camelCase properties) so REST responses bind without field remapping. The
 * shared :ts:type:`ErrMsg` primitive and the :ts:type:`Role` wire code are reused
 * from ``./common`` and ``./session`` respectively rather than redefined.
 *
 * :note: Types-only module with no runtime side effects (no ``import.meta``, no
 *   executable statements), so it is safe to import from Jest (jsdom) and from
 *   the Vite bundle alike.
 */
import type { ErrMsg } from './common';
import type { Role } from './session';

/**
 * :purpose: A single selectable menu entry rendered by the menu screens.
 * :field optionNumber: the one-based option number the user types into the
 *   ``OPTION`` field, mirroring ``CDEMO-MENU-OPT-NUM`` (``PIC 9(02)``, range
 *   1..12) from ``COMEN02Y`` / ``COADM02Y``.
 * :field label: the human-readable option description shown on the corresponding
 *   ``OPTN0nn`` line (``PIC X(40)``), sourced from ``CDEMO-MENU-OPT-NAME``
 *   (``PIC X(35)``).
 */
export interface MenuOption {
  optionNumber: number;
  label: string;
}

/**
 * :purpose: Request body for submitting a menu selection to the api-gateway
 *   ``MenuController``.
 * :field option: the raw two-character selection typed into the BMS ``OPTION``
 *   field (``PIC X(2)`` per ``COMEN01.CPY`` / ``COADM01.CPY``). Kept as a
 *   ``string`` to preserve the exact wire form — including a leading space or
 *   zero — rather than coercing to a number; the gateway parses and validates it.
 * :note: The current role / session context that gates which options are valid
 *   travels server-side in ``SessionContext`` (Spring Session / JWT), never in
 *   this request body, so no session fields are duplicated here.
 */
export interface MenuSelectionRequestDto {
  option: string;
}

/**
 * :purpose: Response body returned by the api-gateway ``MenuController`` for a
 *   menu screen, carrying the role-filtered option list plus the screen titles
 *   and any error message.
 * :field title01: first title line (``TITLE01`` ``PIC X(40)``).
 * :field title02: second title line (``TITLE02`` ``PIC X(40)``).
 * :field options: the role-filtered selectable options (the user list from
 *   ``COMEN02Y`` or the admin list from ``COADM02Y``); up to twelve entries,
 *   mirroring the ``OPTN001``..``OPTN012`` slots.
 * :field errMsg: line-23 error text (``ERRMSG`` ``PIC X(78)``); an empty string
 *   when there is no error. Reuses the shared :ts:type:`ErrMsg` alias from
 *   ``./common``.
 * :field role: the :ts:type:`Role` the menu was built for (``'A'`` admin /
 *   ``'U'`` user), echoing which catalogue was filtered; present only when the
 *   backend includes it. The gateway — not the client — enforces that the admin
 *   menu is ``ROLE_ADMIN``-only.
 * :field toProgram: resolved target program name after a selection (for example
 *   ``'COACTVWC'``), mirroring the legacy ``XCTL`` target
 *   (``CDEMO-MENU-OPT-PGMNAME`` ``PIC X(08)``); present only when the backend
 *   resolves it.
 * :field nextRoute: resolved SPA route path for the selected option; present only
 *   when the backend resolves it.
 */
export interface MenuResponseDto {
  title01: string;
  title02: string;
  options: MenuOption[];
  errMsg: ErrMsg;
  role?: Role;
  toProgram?: string;
  nextRoute?: string;
}

/**
 * :purpose: Payload for the standard user menu screen ``MainMenuPage`` (mapset
 *   ``COMEN01``, program ``COMEN01C``, transaction ``CM00``).
 * :note: A thin alias of :ts:type:`MenuResponseDto`; the user and admin maps are
 *   field-identical, so this exists only to name the payload semantically at its
 *   call site and to provide a divergence point should the contracts split later.
 */
export type MainMenuResponseDto = MenuResponseDto;

/**
 * :purpose: Payload for the administrator menu screen ``AdminMenuPage`` (mapset
 *   ``COADM01``, program ``COADM01C``, transaction ``CA00``).
 * :note: A thin alias of :ts:type:`MenuResponseDto`; see
 *   :ts:type:`MainMenuResponseDto`. The admin menu is ``ROLE_ADMIN``-only, a
 *   constraint enforced by the api-gateway rather than by this type.
 */
export type AdminMenuResponseDto = MenuResponseDto;
