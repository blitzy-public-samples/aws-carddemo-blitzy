/**
 * Authentication & identity DTOs.
 * Mirrors `backend/app/schemas/auth.py`.
 * References: app/cpy-bms/COSGN00.CPY (signon screen), app/cpy/COCOM01Y.cpy
 * (legacy CARDDEMO-COMMAREA identity/role), app/cpy/CSUSR01Y.cpy (user record).
 * SECURITY: `password` is inbound only (LoginRequest); never echoed in any response.
 * Session-based auth is the baseline; JWT fields are optional (alternative mode).
 */

/** Signon request (COSGN00). user_id & password max length 8. */
export interface LoginRequest {
    user_id: string;
    password: string;
}

/** JWT token envelope (used only in JWT auth mode). */
export interface Token {
    access_token: string;
    token_type: string;
}

/** Signon response - identity + role; token fields present only in JWT mode. */
export interface LoginResponse {
    user_id: string;
    first_name: string;
    last_name: string;
    user_type: 'A' | 'U';
    access_token?: string;
    token_type?: string;
}

/** The authenticated principal resolved from the session / JWT claims. */
export interface CurrentUser {
    user_id: string;
    user_type: 'A' | 'U';
    first_name?: string;
    last_name?: string;
}
