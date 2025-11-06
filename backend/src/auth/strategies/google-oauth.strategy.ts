/**
 * Google OAuth 2.0 Authentication Strategy
 * 
 * Implements Passport.js Google OAuth 2.0 strategy for NestJS to enable social login
 * via Google accounts. Handles the complete OAuth flow including:
 * - Redirect to Google login page
 * - Authorization callback processing
 * - Token exchange with Google
 * - User profile extraction
 * - User account creation/lookup
 * 
 * Authentication Flow:
 * 1. User clicks "Sign in with Google" -> Redirects to Google login
 * 2. User authenticates with Google -> Google redirects to callback URL
 * 3. Strategy validates OAuth tokens and extracts user profile
 * 4. validate() method returns user object for session creation
 * 
 * Configuration Requirements:
 * - GOOGLE_CLIENT_ID: OAuth 2.0 client ID from Google Cloud Console
 * - GOOGLE_CLIENT_SECRET: OAuth 2.0 client secret from Google Cloud Console
 * - BASE_URL: Application base URL for constructing callback URL
 * 
 * Security:
 * - Uses OAuth 2.0 authorization code flow
 * - Credentials managed via ConfigService (no hardcoded secrets)
 * - Validates email and profile scopes
 * - Returns sanitized user profile data
 * 
 * @see Section 0.4.5 - Authentication and Authorization Integrations
 * @see Section 0.5.3 - Phase 2: Authentication and User Management
 * @see Section 0.7.2 - Technology-Specific Guidelines (NestJS)
 */

import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PassportStrategy } from '@nestjs/passport';
import { Strategy, Profile, VerifyCallback } from 'passport-google-oauth20';

/**
 * User profile data extracted from Google OAuth
 * Represents the validated user information returned to auth service
 */
interface GoogleUser {
  email: string;
  firstName: string;
  lastName: string;
  provider: string;
  providerId: string;
  profilePhoto?: string;
  emailVerified: boolean;
}

/**
 * GoogleOAuthStrategy
 * 
 * NestJS authentication strategy for Google OAuth 2.0 social login.
 * Extends PassportStrategy with Google Strategy implementation to integrate
 * with NestJS guards and dependency injection system.
 * 
 * Usage:
 * - Register in AuthModule providers array
 * - Protect routes with @UseGuards(AuthGuard('google'))
 * - Access user data via @Request() decorator in controllers
 * 
 * @example
 * // In auth.controller.ts
 * @Get('google')
 * @UseGuards(AuthGuard('google'))
 * async googleAuth() {
 *   // Initiates Google OAuth flow
 * }
 * 
 * @Get('google/callback')
 * @UseGuards(AuthGuard('google'))
 * async googleAuthCallback(@Request() req) {
 *   // Receives validated user from this strategy
 *   return this.authService.loginWithOAuth(req.user);
 * }
 */
@Injectable()
export class GoogleOAuthStrategy extends PassportStrategy(Strategy, 'google') {
  /**
   * Initialize Google OAuth strategy with configuration
   * 
   * @param configService - NestJS ConfigService for environment variable access
   * @throws Error if required environment variables are missing
   */
  constructor(configService: ConfigService) {
    // Validate required environment variables
    const clientID = configService.get<string>('GOOGLE_CLIENT_ID');
    const clientSecret = configService.get<string>('GOOGLE_CLIENT_SECRET');
    const baseUrl = configService.get<string>('BASE_URL');

    if (!clientID || !clientSecret) {
      throw new Error(
        'Google OAuth configuration error: GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET must be set in environment variables'
      );
    }

    if (!baseUrl) {
      throw new Error(
        'Google OAuth configuration error: BASE_URL must be set in environment variables for callback URL construction'
      );
    }

    // Construct callback URL as per specification: {BASE_URL}/api/v1/auth/google/callback
    const callbackURL = `${baseUrl}/api/v1/auth/google/callback`;

    // Initialize Google OAuth 2.0 strategy
    super({
      clientID,
      clientSecret,
      callbackURL,
      scope: ['email', 'profile'], // Request email and profile information
      passReqToCallback: false, // Don't pass request object to validate method
    });
  }

  /**
   * Validate OAuth callback and extract user profile
   * 
   * Called by Passport.js after successful OAuth authentication with Google.
   * Extracts user profile information and returns a sanitized user object
   * for account creation or lookup by the auth service.
   * 
   * @param accessToken - OAuth 2.0 access token from Google
   * @param refreshToken - OAuth 2.0 refresh token from Google (optional)
   * @param profile - User profile information from Google
   * @param done - Passport callback function to complete authentication
   * @returns Promise resolving to GoogleUser object
   * 
   * @throws Error if email is not provided or not verified
   * @throws Error if required profile fields are missing
   */
  async validate(
    _accessToken: string,
    _refreshToken: string,
    profile: Profile,
    done: VerifyCallback
  ): Promise<any> {
    try {
      // Validate profile data presence
      if (!profile) {
        throw new Error('Google OAuth error: No profile data received from Google');
      }

      // Extract email from profile (emails array contains verified emails)
      const email = profile.emails && profile.emails.length > 0 && profile.emails[0]
        ? profile.emails[0].value 
        : null;

      if (!email) {
        throw new Error('Google OAuth error: No email provided by Google account');
      }

      // Validate email verification status for security
      const emailVerified = profile.emails && profile.emails[0] && profile.emails[0].verified !== undefined 
        ? profile.emails[0].verified 
        : false;

      if (!emailVerified) {
        throw new Error(
          'Google OAuth error: Email address not verified. Please verify your email with Google.'
        );
      }

      // Extract name components from profile
      const firstName = profile.name?.givenName || '';
      const lastName = profile.name?.familyName || '';

      // Validate that we have at least one name component
      if (!firstName && !lastName) {
        throw new Error('Google OAuth error: No name information provided by Google account');
      }

      // Extract profile photo if available
      const profilePhoto = profile.photos && profile.photos.length > 0 && profile.photos[0]
        ? profile.photos[0].value 
        : undefined;

      // Construct sanitized user object for auth service
      const user: GoogleUser = {
        email: email.toLowerCase().trim(), // Normalize email
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        provider: 'google',
        providerId: profile.id, // Google's unique user identifier
        profilePhoto,
        emailVerified,
      };

      // Return user object to Passport (will be attached to request)
      done(null, user);
    } catch (error) {
      // Handle validation errors
      const errorMessage = error instanceof Error 
        ? error.message 
        : 'Unknown error during Google OAuth validation';

      // Log error for monitoring (structured logging for DataDog/Sentry)
      console.error('[GoogleOAuthStrategy] Validation error:', {
        error: errorMessage,
        profileId: profile?.id,
        timestamp: new Date().toISOString(),
      });

      // Pass error to Passport (will trigger auth failure)
      done(error, false);
    }
  }
}
