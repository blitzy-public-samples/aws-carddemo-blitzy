import { Injectable } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { ConfigService } from '@nestjs/config';
import { Strategy, VerifyCallback } from 'passport-oauth2';
import axios from 'axios';

/**
 * Microsoft OAuth 2.0 Authentication Strategy
 * 
 * Implements Passport.js strategy for Microsoft Identity Platform (Azure AD) authentication.
 * Supports both personal Microsoft accounts and Azure AD organizational accounts.
 * Enables enterprise SSO integration as defined in Agent Action Plan Section 0.4.5.
 * 
 * OAuth Flow:
 * 1. User initiates login via /api/v1/auth/microsoft
 * 2. Redirects to Microsoft login page (login.microsoftonline.com)
 * 3. User authenticates with Microsoft credentials
 * 4. Microsoft redirects to callback URL with authorization code
 * 5. Strategy exchanges code for access token
 * 6. Strategy fetches user profile from Microsoft Graph API
 * 7. validate() method processes profile and returns user data
 * 
 * Configuration Requirements (Environment Variables):
 * - MICROSOFT_CLIENT_ID: Azure AD application (client) ID
 * - MICROSOFT_CLIENT_SECRET: Azure AD application secret
 * - BASE_URL: Application base URL for callback construction
 * 
 * Scopes Requested:
 * - User.Read: Access user's basic profile from Microsoft Graph
 * - email: Access user's email address
 * - profile: Access user's basic profile information (name, etc.)
 * 
 * @see Section 0.4.5 OAuth 2.0 Providers
 * @see Section 0.5.3 Phase 2 - Authentication System
 * @see Section 0.7.2 NestJS Backend Guidelines
 */
@Injectable()
export class MicrosoftOAuthStrategy extends PassportStrategy(Strategy, 'microsoft') {
  constructor(private readonly configService: ConfigService) {
    super({
      authorizationURL: 'https://login.microsoftonline.com/common/oauth2/v2.0/authorize',
      tokenURL: 'https://login.microsoftonline.com/common/oauth2/v2.0/token',
      clientID: configService.get<string>('MICROSOFT_CLIENT_ID'),
      clientSecret: configService.get<string>('MICROSOFT_CLIENT_SECRET'),
      callbackURL: `${configService.get<string>('BASE_URL')}/api/v1/auth/microsoft/callback`,
      scope: ['User.Read', 'email', 'profile'],
      // Pass access token to validate method for profile fetching
      passReqToCallback: false,
    });

    // Validate required configuration at instantiation
    if (!configService.get<string>('MICROSOFT_CLIENT_ID')) {
      throw new Error('MICROSOFT_CLIENT_ID environment variable is required for Microsoft OAuth strategy');
    }
    if (!configService.get<string>('MICROSOFT_CLIENT_SECRET')) {
      throw new Error('MICROSOFT_CLIENT_SECRET environment variable is required for Microsoft OAuth strategy');
    }
    if (!configService.get<string>('BASE_URL')) {
      throw new Error('BASE_URL environment variable is required for Microsoft OAuth callback URL');
    }
  }

  /**
   * Validates OAuth callback and fetches user profile
   * 
   * Called by Passport after successful OAuth token exchange.
   * Fetches user profile from Microsoft Graph API and extracts relevant user information.
   * 
   * Microsoft Graph Profile Structure:
   * - id: Unique Microsoft account identifier
   * - userPrincipalName: User's principal name (often same as email)
   * - mail: User's email address (primary)
   * - givenName: User's first name
   * - surname: User's last name
   * - displayName: Full display name
   * 
   * @param accessToken - OAuth 2.0 access token for Microsoft Graph API
   * @param refreshToken - OAuth 2.0 refresh token (optional, for token refresh)
   * @param profile - Initial profile data from token response (may be incomplete)
   * @param done - Passport callback function to complete authentication
   * 
   * @returns User object with normalized profile data for account creation/lookup
   * 
   * Error Handling:
   * - Network errors fetching profile (done with error)
   * - Invalid/expired access token (done with error)
   * - Missing required profile fields (done with error)
   * - Microsoft Graph API errors (done with error)
   * 
   * @see Section 0.4.5 Authentication and Authorization Integrations
   * @see Section 0.7.1 Security Requirements (all inputs validated)
   */
  async validate(
    accessToken: string,
    refreshToken: string,
    profile: any,
    done: VerifyCallback,
  ): Promise<any> {
    try {
      // Fetch complete user profile from Microsoft Graph API
      // The profile parameter from OAuth2 strategy may be incomplete,
      // so we fetch full profile using the access token
      const graphApiUrl = 'https://graph.microsoft.com/v1.0/me';
      
      let microsoftProfile;
      try {
        const response = await axios.get(graphApiUrl, {
          headers: {
            Authorization: `Bearer ${accessToken}`,
            'Content-Type': 'application/json',
          },
          timeout: 10000, // 10 second timeout per Section 0.7.1 performance requirements
        });
        microsoftProfile = response.data;
      } catch (error) {
        // Handle Microsoft Graph API errors
        if (axios.isAxiosError(error)) {
          if (error.response) {
            // Microsoft Graph returned an error response
            const status = error.response.status;
            const errorData = error.response.data;
            
            if (status === 401) {
              // Invalid or expired access token
              return done(new Error('Microsoft OAuth access token is invalid or expired'), null);
            } else if (status === 403) {
              // Insufficient permissions
              return done(new Error('Insufficient permissions to access Microsoft profile. Required scopes: User.Read, email, profile'), null);
            } else {
              // Other Microsoft Graph API errors
              return done(new Error(`Microsoft Graph API error: ${errorData?.error?.message || 'Unknown error'}`), null);
            }
          } else if (error.request) {
            // Network error - no response received
            return done(new Error('Failed to connect to Microsoft Graph API. Please check network connectivity.'), null);
          } else {
            // Request setup error
            return done(new Error(`Failed to fetch Microsoft profile: ${error.message}`), null);
          }
        }
        // Non-Axios error
        throw error;
      }

      // Extract and validate required fields from Microsoft profile
      const email = microsoftProfile.mail || microsoftProfile.userPrincipalName;
      const firstName = microsoftProfile.givenName;
      const lastName = microsoftProfile.surname;
      const microsoftId = microsoftProfile.id;
      const displayName = microsoftProfile.displayName;

      // Validate required fields per Section 0.7.1 security requirements
      if (!email) {
        return done(new Error('Microsoft profile missing required email address'), null);
      }
      
      if (!microsoftId) {
        return done(new Error('Microsoft profile missing required user ID'), null);
      }

      // Construct normalized user object for auth service
      // This object will be used for account lookup or creation
      const user = {
        // Microsoft-specific identifier
        microsoftId,
        
        // User identification fields
        email: email.toLowerCase(), // Normalize email to lowercase for consistency
        firstName: firstName || '', // Default to empty string if not provided
        lastName: lastName || '', // Default to empty string if not provided
        displayName: displayName || email, // Fallback to email if no display name
        
        // OAuth tokens for potential future use
        accessToken, // Can be used for accessing Microsoft services on user's behalf
        refreshToken: refreshToken || null, // May not be provided depending on scope
        
        // Provider identification
        provider: 'microsoft',
        
        // Additional metadata
        profilePictureUrl: null, // Microsoft Graph can provide photo via /me/photo/$value if needed
        
        // Enterprise tenant information (if available)
        tenantId: microsoftProfile.tenantId || null, // Azure AD tenant ID for organizational accounts
        jobTitle: microsoftProfile.jobTitle || null, // Job title for organizational accounts
        officeLocation: microsoftProfile.officeLocation || null, // Office location for organizational accounts
        
        // Account type identification
        accountType: microsoftProfile.userPrincipalName?.includes('#EXT#') 
          ? 'external' // External/guest users in Azure AD
          : microsoftProfile.tenantId 
            ? 'organizational' // Azure AD organizational account
            : 'personal', // Personal Microsoft account
      };

      // Successfully validated - pass user object to auth service
      // The auth service will handle account lookup/creation and session establishment
      return done(null, user);
      
    } catch (error) {
      // Catch any unexpected errors and pass to Passport
      // This ensures errors are properly logged and handled by NestJS exception filters
      const errorMessage = error instanceof Error ? error.message : 'Unknown error during Microsoft OAuth validation';
      return done(new Error(`Microsoft OAuth validation failed: ${errorMessage}`), null);
    }
  }
}
