/**
 * :purpose: Resolve the CardDemo api-gateway base URL for the axios REST client
 *   from the ``VITE_API_BASE_URL`` build-time environment variable, falling back
 *   to the local api-gateway origin when the variable is absent or empty.
 * :returns: The resolved api-gateway base URL.
 */
export function getApiBaseUrl(): string {
  const baseUrl = import.meta.env?.VITE_API_BASE_URL;
  return baseUrl && baseUrl.length > 0 ? baseUrl : 'http://localhost:8080';
}

/**
 * :purpose: Resolve the application id shown after the sign-on screen's ``AppID:``
 *   caption. The legacy screen filled the field with ``EXEC CICS ASSIGN APPLID``
 *   (``COSGN00C`` L198-199) — the identity of the region serving the screen — so the
 *   migrated screen takes it from the deployment through ``VITE_APP_ID``.
 * :returns: The configured application id, or the empty string, which is what the
 *   mapset's own ``APPLID`` field (no ``INITIAL``) shows when the region supplies none.
 */
export function getAppId(): string {
  return import.meta.env?.VITE_APP_ID ?? '';
}

/**
 * :purpose: Resolve the system id shown after the sign-on screen's ``SysID:`` caption,
 *   filled by ``EXEC CICS ASSIGN SYSID`` on the legacy screen (``COSGN00C`` L202-203).
 * :returns: The configured system id, or the empty string — the mapset declares
 *   ``SYSID`` as ``INITIAL='        '``, so blank is its own default.
 */
export function getSysId(): string {
  return import.meta.env?.VITE_SYS_ID ?? '';
}
