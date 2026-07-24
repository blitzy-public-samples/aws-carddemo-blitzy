/**
 * :module: menu
 * :purpose: Request/response DTO types for the two menu screens ``MainMenuPage``
 *   (mapset ``COMEN01``, program ``COMEN01C``, transaction ``CM00``) and
 *   ``AdminMenuPage`` (mapset ``COADM01``, program ``COADM01C``, transaction
 *   ``CA00``).
 * :output: :ts:type:`MenuOption`, :ts:type:`MenuResponseDto`,
 *   :ts:type:`MenuSelectionRequestDto`, :ts:type:`MenuSelectionResponseDto`, and
 *   the :ts:type:`MainMenuResponseDto` / :ts:type:`AdminMenuResponseDto` aliases.
 * :note: Member names mirror the api-gateway ``MenuController`` JSON records
 *   (``MenuResponse``, ``MenuOptionView``, ``MenuSelectionRequest``,
 *   ``MenuSelectionResponse``) so REST payloads bind without field remapping.
 */

/**
 * :purpose: A single selectable menu entry (``MenuController.MenuOptionView``).
 * :field optionNumber: one-based option number the user types.
 * :field optionName: display label for the option.
 * :field programName: legacy target program name.
 * :field targetRoute: resolved gateway route, or ``null`` when unmapped.
 */
export interface MenuOption {
  optionNumber: number;
  optionName: string;
  programName: string;
  targetRoute: string | null;
}

/**
 * :purpose: Request body for the ``/menu/select`` and ``/admin/menu/select``
 *   endpoints (``MenuController.MenuSelectionRequest``).
 * :field option: the entered option text, kept as ``string`` to preserve the
 *   exact wire form.
 * :field aid: action key (``'ENTER'`` to select, ``'PF3'`` to exit); a ``null``
 *   or blank value is treated as ENTER, so it is optional.
 */
export interface MenuSelectionRequestDto {
  option: string;
  aid?: string;
}

/**
 * :purpose: Response body for ``GET /menu`` and ``GET /admin/menu``
 *   (``MenuController.MenuResponse``).
 * :field tranId: originating transaction id (``CM00`` main / ``CA00`` admin).
 * :field programName: originating legacy program name.
 * :field options: the role-filtered visible options.
 * :field message: informational message, or ``null`` when none applies.
 */
export interface MenuResponseDto {
  tranId: string;
  programName: string;
  options: MenuOption[];
  message: string | null;
}

/**
 * :purpose: Response body for the two ``/select`` endpoints
 *   (``MenuController.MenuSelectionResponse``).
 * :field dispatched: ``true`` when the client should navigate to ``targetRoute``.
 * :field programName: resolved target program name, or ``null`` for a PF3 back.
 * :field targetRoute: downstream route to navigate to, or ``null`` for a
 *   coming-soon result.
 * :field message: the coming-soon message, or ``null`` when a dispatch occurred.
 */
export interface MenuSelectionResponseDto {
  dispatched: boolean;
  programName: string | null;
  targetRoute: string | null;
  message: string | null;
}

/**
 * :purpose: Payload for the standard user menu ``MainMenuPage``; a thin alias of
 *   :ts:type:`MenuResponseDto`.
 */
export type MainMenuResponseDto = MenuResponseDto;

/**
 * :purpose: Payload for the administrator menu ``AdminMenuPage``; a thin alias of
 *   :ts:type:`MenuResponseDto`. The admin menu is ``ROLE_ADMIN``-only, enforced by
 *   the api-gateway.
 */
export type AdminMenuResponseDto = MenuResponseDto;
