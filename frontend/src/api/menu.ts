/**
 * :module: ``frontend/src/api/menu.ts``
 * :purpose: Domain API module for the two CardDemo menu screens — ``MainMenuPage``
 *   (the role-filtered user menu) and ``AdminMenuPage`` (the administrator-only
 *   menu). It wraps the api-gateway ``MenuController`` routes, re-expressing the
 *   legacy CICS pseudo-conversational menu programs as stateless REST calls:
 *   ``COMEN01C`` / transaction ``CM00`` (user menu) and ``COADM01C`` /
 *   transaction ``CA00`` (admin menu). Behavior references
 *   ``app/cbl/COMEN01C.cbl`` and ``app/cbl/COADM01C.cbl``.
 * :output: The named async functions ``getMainMenu`` and ``getAdminMenu`` (the
 *   role-scoped menu listings) plus ``selectMenuOption`` and
 *   ``selectAdminMenuOption`` (the option-dispatch calls that mirror the legacy
 *   ``PROCESS-ENTER-KEY`` / ``XCTL`` navigation).
 * :note: This module only fetches and posts menu data through the shared
 *   ``apiClient``. Role gating — the legacy ``COADM01`` versus ``COMEN01``
 *   ``XCTL`` split — is enforced by the ``App.tsx`` route guards and the
 *   api-gateway (which requires ``ROLE_ADMIN`` for the admin routes); it is
 *   deliberately not duplicated here. Rationale is recorded in
 *   ``docs/decision-log.md``.
 */

import apiClient from './client';
import type {
  MenuResponseDto,
  MenuSelectionRequestDto,
  MenuSelectionResponseDto,
} from '../types';

/**
 * :purpose: Fetch the role-filtered main (user) menu — legacy program
 *   ``COMEN01C``, CICS transaction ``CM00``.
 * :returns: A :ts:type:`MenuResponseDto` listing the visible main-menu options
 *   and their resolved downstream routes.
 */
export async function getMainMenu(): Promise<MenuResponseDto> {
  const response = await apiClient.get<MenuResponseDto>('/menu');
  return response.data;
}

/**
 * :purpose: Fetch the administrator menu — legacy program ``COADM01C``, CICS
 *   transaction ``CA00``. Admin-only: the api-gateway enforces ``ROLE_ADMIN``,
 *   so a non-admin request is rejected upstream and this module performs no
 *   gating of its own.
 * :returns: A :ts:type:`MenuResponseDto` listing the admin-menu options and
 *   their resolved downstream routes.
 */
export async function getAdminMenu(): Promise<MenuResponseDto> {
  const response = await apiClient.get<MenuResponseDto>('/admin/menu');
  return response.data;
}

/**
 * :purpose: Submit a main-menu option selection — legacy ``COMEN01C``
 *   ``PROCESS-ENTER-KEY`` under CICS transaction ``CM00`` — mirroring the legacy
 *   ``XCTL`` transfer to the chosen program.
 * :param request: the selection payload; ``option`` is the entered
 *   two-character option text preserved verbatim (matching COBOL ``OPTIONI``
 *   ``PIC X(2)``), and ``aid`` is the optional action key (``'ENTER'`` to
 *   select, ``'PF3'`` to exit).
 * :returns: A :ts:type:`MenuSelectionResponseDto` describing the resolved
 *   navigation target (dispatch route), the PF3 back navigation, or an
 *   informational coming-soon message.
 */
export async function selectMenuOption(
  request: MenuSelectionRequestDto,
): Promise<MenuSelectionResponseDto> {
  const response = await apiClient.post<MenuSelectionResponseDto>(
    '/menu/select',
    request,
  );
  return response.data;
}

/**
 * :purpose: Submit an administrator-menu option selection — legacy ``COADM01C``
 *   ``PROCESS-ENTER-KEY`` under CICS transaction ``CA00`` — mirroring the legacy
 *   ``XCTL`` transfer to the chosen program. Admin-only: the api-gateway
 *   enforces ``ROLE_ADMIN`` upstream.
 * :param request: the selection payload; ``option`` is the entered
 *   two-character option text preserved verbatim (matching COBOL ``OPTIONI``
 *   ``PIC X(2)``), and ``aid`` is the optional action key (``'ENTER'`` to
 *   select, ``'PF3'`` to exit).
 * :returns: A :ts:type:`MenuSelectionResponseDto` describing the resolved
 *   navigation target (dispatch route), the PF3 back navigation, or an
 *   informational coming-soon message.
 */
export async function selectAdminMenuOption(
  request: MenuSelectionRequestDto,
): Promise<MenuSelectionResponseDto> {
  const response = await apiClient.post<MenuSelectionResponseDto>(
    '/admin/menu/select',
    request,
  );
  return response.data;
}
