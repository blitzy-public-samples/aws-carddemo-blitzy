/**
 * NextAuth.js API Route Configuration
 *
 * Comprehensive authentication system implementing multiple authentication providers:
 * - Credentials (email/password) with backend API validation
 * - Google OAuth 2.0 for social login
 * - Microsoft Entra ID (formerly Azure AD) OAuth 2.0
 *
 * Features:
 * - JWT-based stateless sessions for scalability
 * - 15-minute access token expiry per security requirements (Section 0.7.1)
 * - 7-day refresh token expiry per security requirements (Section 0.7.1)
 * - Automatic token refresh mechanism
 * - OAuth account linking with backend
 * - Multi-tenant support via accountId
 * - Role-based access control (RBAC) data in session
 * - Comprehensive error handling without exposing sensitive details
 * - Rate limiting integration via backend API
 *
 * Security Considerations:
 * - All secrets stored in environment variables
 * - No sensitive data logged (tokens, passwords)
 * - HTTPS-only cookies in production
 * - CSRF protection via NextAuth built-in mechanisms
 * - Proper token rotation on refresh
 * - Input validation for all credentials
 *
 * @see Section 0.5.3 Phase 2: Authentication and User Management
 * @see Section 0.4.5 Authentication and Authorization Integrations
 * @see Section 0.7.1 Security Requirements
 */

import NextAuth, { type NextAuthOptions } from 'next-auth';
import { type JWT } from 'next-auth/jwt';
import CredentialsProvider from 'next-auth/providers/credentials';
import GoogleProvider from 'next-auth/providers/google';
import AzureADProvider from 'next-auth/providers/azure-ad';
import axios, { AxiosError, type AxiosResponse } from 'axios';

// ============================================================================
// CONSTANTS (Section 0.7.1 - No magic numbers)
// ============================================================================

/** Access token expiry time in milliseconds (15 minutes) */
// eslint-disable-next-line @typescript-eslint/no-magic-numbers
const ACCESS_TOKEN_EXPIRY_MS = 15 * 60 * 1000;

/** Access token expiry time in seconds (15 minutes) */
// eslint-disable-next-line @typescript-eslint/no-magic-numbers
const ACCESS_TOKEN_EXPIRY_SECONDS = 15 * 60;

/** Session expiry time in seconds (7 days) */
// eslint-disable-next-line @typescript-eslint/no-magic-numbers
const SESSION_EXPIRY_SECONDS = 7 * 24 * 60 * 60;

/** API request timeout in milliseconds (10 seconds) */
const API_TIMEOUT_MS = 10_000;

/** HTTP status codes */
const HTTP_STATUS = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  TOO_MANY_REQUESTS: 429,
} as const;

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

/**
 * Backend authentication API response
 */
interface AuthApiResponse {
  success: boolean;
  user: {
    id: string;
    email: string;
    name: string;
    roles: string[];
    accountId: string;
  };
  accessToken: string;
  refreshToken: string;
}

/**
 * Backend token refresh API response
 */
interface RefreshTokenApiResponse {
  success: boolean;
  accessToken: string;
  refreshToken?: string;
}

/**
 * Backend OAuth linking API response
 */
interface OAuthLinkApiResponse {
  success: boolean;
  user: {
    id: string;
    accountId: string;
    roles: string[];
  };
}

/**
 * Extended User interface for our application
 * Includes multi-tenant support and RBAC roles
 */
declare module 'next-auth' {
  interface Session {
    user: {
      id: string;
      email: string;
      name: string;
      roles: string[];
      accountId: string;
    };
    accessToken: string;
    error?: string;
  }

  interface User {
    id: string;
    email: string;
    name: string;
    roles: string[];
    accountId: string;
    accessToken?: string;
    refreshToken?: string;
  }
}

/**
 * Extended JWT interface with authentication tokens and metadata
 */
declare module 'next-auth/jwt' {
  interface JWT {
    id: string;
    email: string;
    name: string;
    roles: string[];
    accountId: string;
    accessToken: string;
    refreshToken: string;
    accessTokenExpires: number;
    error?: string;
  }
}

/**
 * Refresh Access Token Helper
 *
 * Attempts to refresh an expired access token using the refresh token.
 * Implements token rotation for enhanced security.
 *
 * @param token - Current JWT token with refresh token
 * @returns Updated JWT token with new access token or error flag
 */
async function refreshAccessToken(token: JWT): Promise<JWT> {
  try {
    const response: AxiosResponse<RefreshTokenApiResponse> = await axios.post(
      `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ''}/auth/refresh`,
      {
        refreshToken: token.refreshToken,
      },
      {
        headers: {
          'Content-Type': 'application/json',
        },
        timeout: API_TIMEOUT_MS,
      }
    );

    if (response.data.success) {
      return {
        ...token,
        accessToken: response.data.accessToken,
        refreshToken: response.data.refreshToken ?? token.refreshToken,
        accessTokenExpires: Date.now() + ACCESS_TOKEN_EXPIRY_MS,
      };
    }

    // Refresh failed, mark token with error
    return {
      ...token,
      error: 'RefreshAccessTokenError',
    };
  } catch (error) {
    console.error(
      'Token refresh failed:',
      error instanceof AxiosError ? error.response?.status : 'Unknown error'
    );

    return {
      ...token,
      error: 'RefreshAccessTokenError',
    };
  }
}

/**
 * NextAuth Configuration Options
 *
 * Configures authentication providers, session strategy, JWT settings,
 * and callback functions for comprehensive authentication flow.
 */
export const authOptions: NextAuthOptions = {
  /**
   * Authentication Providers
   *
   * 1. Credentials: Email/password authentication with backend validation
   * 2. Google: OAuth 2.0 social login
   * 3. Microsoft: OAuth 2.0 enterprise SSO
   */
  providers: [
    /**
     * Credentials Provider
     *
     * Validates email and password against backend API.
     * Backend handles password hashing, rate limiting, and account lockout.
     */
    CredentialsProvider({
      id: 'credentials',
      name: 'Email and Password',
      credentials: {
        email: {
          label: 'Email',
          type: 'email',
          placeholder: 'your@email.com',
        },
        password: {
          label: 'Password',
          type: 'password',
        },
      },
      async authorize(credentials) {
        // Validate required fields
        if (!credentials?.email || !credentials?.password) {
          throw new Error('Email and password required');
        }

        try {
          // Call backend authentication API
          const response: AxiosResponse<AuthApiResponse> = await axios.post(
            `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ''}/auth/login`,
            {
              email: credentials.email,
              password: credentials.password,
            },
            {
              headers: {
                'Content-Type': 'application/json',
              },
              timeout: API_TIMEOUT_MS,
            }
          );

          // Successful authentication
          if (response.data.success && response.data.user) {
            return {
              id: response.data.user.id,
              email: response.data.user.email,
              name: response.data.user.name,
              roles: response.data.user.roles ?? [],
              accountId: response.data.user.accountId,
              accessToken: response.data.accessToken,
              refreshToken: response.data.refreshToken,
            };
          }

          // Authentication failed
          return null;
        } catch (error) {
          // Log error without sensitive details
          if (error instanceof AxiosError) {
            console.error('Authentication error:', error.response?.status);

            // Handle specific error cases with user-friendly messages
            if (error.response?.status === HTTP_STATUS.UNAUTHORIZED) {
              throw new Error('Invalid credentials');
            }
            if (error.response?.status === HTTP_STATUS.TOO_MANY_REQUESTS) {
              throw new Error('Too many login attempts. Please try again later.');
            }
            if (error.response?.status === HTTP_STATUS.FORBIDDEN) {
              throw new Error('Account is locked. Contact support.');
            }
          }

          // Generic error message
          throw new Error('Authentication failed. Please try again.');
        }
      },
    }),

    /**
     * Google OAuth Provider
     *
     * Enables Google social login with offline access for token refresh.
     * Requests consent on first login to obtain refresh token.
     */
    GoogleProvider({
      clientId: process.env.GOOGLE_CLIENT_ID!,
      clientSecret: process.env.GOOGLE_CLIENT_SECRET!,
      authorization: {
        params: {
          prompt: 'consent',
          access_type: 'offline',
          response_type: 'code',
          scope: 'openid email profile',
        },
      },
    }),

    /**
     * Azure AD Provider (Microsoft Entra ID)
     *
     * Enables Microsoft OAuth for enterprise SSO.
     * Supports multi-tenant authentication with 'common' tenant ID.
     */
    AzureADProvider({
      clientId: process.env.MICROSOFT_CLIENT_ID!,
      clientSecret: process.env.MICROSOFT_CLIENT_SECRET!,
      tenantId: process.env.MICROSOFT_TENANT_ID ?? 'common', // 'common' for multi-tenant support
      authorization: {
        params: {
          scope: 'openid email profile User.Read',
        },
      },
    }),
  ],

  /**
   * Session Configuration
   *
   * Uses JWT strategy for stateless sessions (no database session storage).
   * Session expires after 7 days per security requirements.
   */
  session: {
    strategy: 'jwt',
    maxAge: SESSION_EXPIRY_SECONDS,
  },

  /**
   * JWT Configuration
   *
   * Access tokens expire after 15 minutes per security requirements.
   * Uses NEXTAUTH_SECRET for signing tokens.
   */
  jwt: {
    maxAge: ACCESS_TOKEN_EXPIRY_SECONDS,
    secret: process.env.NEXTAUTH_SECRET!,
  },

  /**
   * Custom Pages
   *
   * Redirects authentication flows to custom pages for consistent UX.
   */
  pages: {
    signIn: '/auth/login',
    signOut: '/auth/login',
    error: '/auth/login',
    verifyRequest: '/auth/verify',
  },

  /**
   * Callback Functions
   *
   * Customize authentication flow behavior and session management.
   */
  callbacks: {
    /**
     * Sign In Callback
     *
     * Called on successful sign in. Handles OAuth account linking with backend.
     * For OAuth providers, creates or links account in backend database.
     *
     * @param user - User object from provider
     * @param account - Account object with provider details
     * @returns true to allow sign in, false to deny, or redirect URL on error
     */
    async signIn({ user, account }) {
      // Handle OAuth provider sign in
      if (account?.provider === 'google' || account?.provider === 'azure-ad') {
        try {
          // Link OAuth account with backend user account
          const response: AxiosResponse<OAuthLinkApiResponse> = await axios.post(
            `${process.env.NEXT_PUBLIC_API_BASE_URL ?? ''}/auth/oauth/link`,
            {
              provider: account.provider,
              providerId: account.providerAccountId,
              email: user.email,
              name: user.name,
              accessToken: account.access_token,
            },
            {
              timeout: API_TIMEOUT_MS,
            }
          );

          if (!response.data.success) {
            console.error('OAuth account linking failed');
            return '/auth/login?error=OAuthAccountNotLinked';
          }

          // Update user object with backend data
          user.id = response.data.user.id;
          user.accountId = response.data.user.accountId;
          user.roles = response.data.user.roles;

          return true;
        } catch (error) {
          console.error(
            'OAuth linking error:',
            error instanceof AxiosError ? error.response?.status : 'Unknown'
          );
          return '/auth/login?error=OAuthCallback';
        }
      }

      // Credentials provider - already validated in authorize callback
      return true;
    },

    /**
     * JWT Callback
     *
     * Called whenever a JWT is created or updated.
     * Adds user data to token on sign in.
     * Handles token refresh when expired.
     *
     * @param token - Current JWT token
     * @param user - User object (only present on sign in)
     * @param trigger - What triggered the callback
     * @param session - Session data (for update trigger)
     * @returns Updated JWT token
     */
    async jwt({ token, user, trigger, session }) {
      // Initial sign in - populate token with user data
      if (user) {
        token.id = user.id;
        token.email = user.email;
        token.name = user.name;
        token.roles = user.roles;
        token.accountId = user.accountId;
        token.accessToken = user.accessToken ?? '';
        token.refreshToken = user.refreshToken ?? '';
        token.accessTokenExpires = Date.now() + ACCESS_TOKEN_EXPIRY_MS;
      }

      // Handle session update trigger (user profile changes)
      if (trigger === 'update' && session && typeof session === 'object') {
        const sessionData = session as { name?: string; email?: string };
        if (sessionData.name) {
          token.name = sessionData.name;
        }
        if (sessionData.email) {
          token.email = sessionData.email;
        }
      }

      // Token is still valid, return as-is
      if (Date.now() < token.accessTokenExpires) {
        return token;
      }

      // Token expired, attempt to refresh
      return await refreshAccessToken(token);
    },

    /**
     * Session Callback
     *
     * Called whenever a session is checked or created.
     * Exposes user data and access token to client-side session.
     *
     * @param session - Session object to be returned to client
     * @param token - JWT token with user data
     * @returns Updated session object
     */
    session({ session, token }) {
      if (token) {
        session.user = {
          id: String(token.id),
          email: String(token.email),
          name: String(token.name),
          roles: Array.isArray(token.roles) ? token.roles : [],
          accountId: String(token.accountId),
        };
        session.accessToken = String(token.accessToken);
        if (token.error) {
          session.error = String(token.error);
        }
      }

      return session;
    },

    /**
     * Redirect Callback
     *
     * Controls where users are redirected after authentication.
     * Prevents open redirect vulnerabilities.
     *
     * @param url - URL to redirect to
     * @param baseUrl - Base URL of the application
     * @returns Safe redirect URL
     */
    redirect({ url, baseUrl }) {
      // Allow relative URLs
      if (url.startsWith('/')) {
        return `${baseUrl}${url}`;
      }

      // Allow URLs from same origin
      if (new URL(url).origin === baseUrl) {
        return url;
      }

      // Default to base URL for security (prevents open redirects)
      return baseUrl;
    },
  },

  /**
   * Secret for signing tokens
   * Must be set in environment variables
   */
  secret: process.env.NEXTAUTH_SECRET!,

  /**
   * Debug mode
   * Enabled in development for detailed authentication logs
   * Disabled in production to avoid exposing sensitive information
   */
  debug: process.env.NODE_ENV === 'development',
};

/**
 * NextAuth Handler
 *
 * Handles all NextAuth API routes:
 * - /api/auth/signin - Sign in page
 * - /api/auth/signout - Sign out
 * - /api/auth/callback/:provider - OAuth callbacks
 * - /api/auth/session - Get session
 * - /api/auth/csrf - CSRF token
 * - /api/auth/providers - List providers
 *
 * This catch-all route handles all authentication-related requests.
 */
export default NextAuth(authOptions);
