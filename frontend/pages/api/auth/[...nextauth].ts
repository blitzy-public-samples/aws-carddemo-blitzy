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

import NextAuth, { NextAuthOptions, Session, User as NextAuthUser, Account, Profile } from 'next-auth';
import { JWT } from 'next-auth/jwt';
import CredentialsProvider from 'next-auth/providers/credentials';
import GoogleProvider from 'next-auth/providers/google';
import MicrosoftEntraIDProvider from 'next-auth/providers/microsoft-entra-id';
import axios, { AxiosError, AxiosResponse } from 'axios';

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
    const response: AxiosResponse = await axios.post(
      `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/refresh`,
      {
        refreshToken: token.refreshToken,
      },
      {
        headers: {
          'Content-Type': 'application/json',
        },
        timeout: 10000, // 10 second timeout
      }
    );

    if (response.data.success) {
      return {
        ...token,
        accessToken: response.data.accessToken,
        refreshToken: response.data.refreshToken ?? token.refreshToken, // Use new refresh token if provided
        accessTokenExpires: Date.now() + 15 * 60 * 1000, // 15 minutes from now
      };
    }

    // Refresh failed, mark token with error
    return {
      ...token,
      error: 'RefreshAccessTokenError',
    };
  } catch (error) {
    console.error('Token refresh failed:', error instanceof AxiosError ? error.response?.status : 'Unknown error');
    
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
      async authorize(credentials, req) {
        // Validate required fields
        if (!credentials?.email || !credentials?.password) {
          throw new Error('Email and password required');
        }

        try {
          // Call backend authentication API
          const response: AxiosResponse = await axios.post(
            `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/login`,
            {
              email: credentials.email,
              password: credentials.password,
            },
            {
              headers: {
                'Content-Type': 'application/json',
              },
              timeout: 10000, // 10 second timeout
            }
          );

          // Successful authentication
          if (response.data.success && response.data.user) {
            return {
              id: response.data.user.id,
              email: response.data.user.email,
              name: response.data.user.name,
              roles: response.data.user.roles || [],
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
            if (error.response?.status === 401) {
              throw new Error('Invalid credentials');
            }
            if (error.response?.status === 429) {
              throw new Error('Too many login attempts. Please try again later.');
            }
            if (error.response?.status === 403) {
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
     * Microsoft Entra ID Provider (formerly Azure AD)
     * 
     * Enables Microsoft OAuth for enterprise SSO.
     * Supports multi-tenant authentication with 'common' tenant ID.
     */
    MicrosoftEntraIDProvider({
      clientId: process.env.MICROSOFT_CLIENT_ID!,
      clientSecret: process.env.MICROSOFT_CLIENT_SECRET!,
      tenantId: process.env.MICROSOFT_TENANT_ID || 'common', // 'common' for multi-tenant support
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
    maxAge: 7 * 24 * 60 * 60, // 7 days in seconds (Section 0.7.1)
  },

  /**
   * JWT Configuration
   * 
   * Access tokens expire after 15 minutes per security requirements.
   * Uses NEXTAUTH_SECRET for signing tokens.
   */
  jwt: {
    maxAge: 15 * 60, // 15 minutes in seconds (Section 0.7.1)
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
     * @param profile - Profile object from OAuth provider
     * @returns true to allow sign in, false to deny, or redirect URL on error
     */
    async signIn({ user, account, profile }) {
      // Handle OAuth provider sign in
      if (account?.provider === 'google' || account?.provider === 'microsoft-entra-id') {
        try {
          // Link OAuth account with backend user account
          const response: AxiosResponse = await axios.post(
            `${process.env.NEXT_PUBLIC_API_BASE_URL}/auth/oauth/link`,
            {
              provider: account.provider,
              providerId: account.providerAccountId,
              email: user.email,
              name: user.name,
              accessToken: account.access_token,
            },
            {
              timeout: 10000,
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
          console.error('OAuth linking error:', error instanceof AxiosError ? error.response?.status : 'Unknown');
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
     * @param account - Account object (only present on sign in)
     * @param trigger - What triggered the callback
     * @param session - Session data (for update trigger)
     * @returns Updated JWT token
     */
    async jwt({ token, user, account, trigger, session }) {
      // Initial sign in - populate token with user data
      if (user) {
        token.id = user.id;
        token.email = user.email;
        token.name = user.name;
        token.roles = user.roles;
        token.accountId = user.accountId;
        token.accessToken = user.accessToken || '';
        token.refreshToken = user.refreshToken || '';
        token.accessTokenExpires = Date.now() + 15 * 60 * 1000; // 15 minutes from now
      }

      // Handle session update trigger (user profile changes)
      if (trigger === 'update' && session) {
        token.name = session.name;
        token.email = session.email;
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
    async session({ session, token }) {
      if (token) {
        session.user = {
          id: token.id as string,
          email: token.email as string,
          name: token.name as string,
          roles: token.roles as string[],
          accountId: token.accountId as string,
        };
        session.accessToken = token.accessToken as string;
        session.error = token.error as string | undefined;
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
    async redirect({ url, baseUrl }) {
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
