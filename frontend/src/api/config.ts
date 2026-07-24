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
