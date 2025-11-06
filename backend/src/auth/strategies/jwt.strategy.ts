/**
 * JWT Authentication Strategy for NestJS
 * 
 * This file implements the Passport.js JWT authentication strategy for validating
 * JSON Web Tokens in API requests. It integrates with NestJS authentication guards
 * to secure protected endpoints throughout the application.
 * 
 * Key Features:
 * - Validates JWT signatures using JWT_ACCESS_SECRET from environment
 * - Extracts tokens from Authorization Bearer headers
 * - Provides user context (id, email, accountId, roles) to request handlers
 * - Handles token expiration and invalid token scenarios
 * - Implements stateless authentication per Agent Action Plan Section 0.4.5
 * 
 * Security Compliance:
 * - Section 0.7.1: No hardcoded secrets (uses ConfigService)
 * - Section 0.7.1: 15-minute access token expiration
 * - Section 0.7.2: Environment variable configuration
 * - Section 0.5.3: Phase 2 Authentication System implementation
 * 
 * @module auth/strategies
 */

import { Injectable, UnauthorizedException } from '@nestjs/common';
import { PassportStrategy } from '@nestjs/passport';
import { ConfigService } from '@nestjs/config';
import { Strategy, ExtractJwt } from 'passport-jwt';

/**
 * JWT Payload Interface
 * 
 * Defines the structure of the JWT token payload that will be validated
 * and extracted by this strategy. This payload is embedded in the JWT
 * during token generation and verified during authentication.
 * 
 * @interface JwtPayload
 */
export interface JwtPayload {
  /**
   * Unique identifier for the user
   * Used for user lookup and request context
   */
  sub: string;

  /**
   * User's email address
   * Used for identification and audit logging
   */
  email: string;

  /**
   * Account ID for multi-tenant isolation
   * Critical for ensuring users only access their account's data
   */
  accountId: string;

  /**
   * User's assigned roles for RBAC authorization
   * Used by RolesGuard for permission checking
   */
  roles: string[];

  /**
   * Token issued at timestamp (Unix timestamp)
   * Automatically added by JWT library
   */
  iat?: number;

  /**
   * Token expiration timestamp (Unix timestamp)
   * Set to 15 minutes per Section 0.7.1 requirements
   */
  exp?: number;
}

/**
 * JwtStrategy Class
 * 
 * Implements Passport JWT authentication strategy for NestJS application.
 * This strategy validates JWT tokens from Authorization headers and provides
 * authenticated user context to protected route handlers.
 * 
 * Usage:
 * - Registered as provider in AuthModule
 * - Automatically invoked by @UseGuards(AuthGuard('jwt')) decorator
 * - Attaches validated user object to request.user property
 * 
 * Token Flow:
 * 1. Client sends request with Authorization: Bearer <token>
 * 2. Strategy extracts token from header
 * 3. Strategy verifies token signature using JWT_ACCESS_SECRET
 * 4. Strategy checks token expiration (15-minute window)
 * 5. If valid, validate() method is called with decoded payload
 * 6. User object is attached to request for downstream handlers
 * 
 * Error Handling:
 * - Invalid signature: Passport automatically throws 401 Unauthorized
 * - Expired token: Passport automatically throws 401 Unauthorized
 * - Missing token: Passport automatically throws 401 Unauthorized
 * - Malformed token: Passport automatically throws 401 Unauthorized
 * 
 * @class JwtStrategy
 * @extends {PassportStrategy(Strategy, 'jwt')}
 */
@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy, 'jwt') {
  /**
   * Constructor for JwtStrategy
   * 
   * Initializes the JWT authentication strategy with configuration from
   * environment variables. Uses ConfigService for secure secret management
   * per Section 0.7.1 security requirements.
   * 
   * Configuration:
   * - jwtFromRequest: Extracts JWT from Authorization Bearer header
   * - ignoreExpiration: false (enforces 15-minute expiration)
   * - secretOrKey: JWT_ACCESS_SECRET from environment
   * 
   * @param {ConfigService} configService - NestJS configuration service for environment variables
   * @throws {Error} If JWT_ACCESS_SECRET is not configured in environment
   */
  constructor(private readonly configService: ConfigService) {
    super({
      // Extract JWT from Authorization header using Bearer scheme
      // Expects header format: "Authorization: Bearer <token>"
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),

      // Do NOT ignore token expiration
      // Enforces 15-minute access token lifetime per Section 0.7.1
      ignoreExpiration: false,

      // Load JWT secret from environment variable
      // Critical: NEVER hardcode secrets per Section 0.7.1
      secretOrKey: configService.get<string>('JWT_ACCESS_SECRET'),
    });

    // Validate that JWT_ACCESS_SECRET is configured
    // Fail fast if critical security configuration is missing
    if (!this.configService.get<string>('JWT_ACCESS_SECRET')) {
      throw new Error(
        'JWT_ACCESS_SECRET is not configured. Please set JWT_ACCESS_SECRET environment variable.'
      );
    }
  }

  /**
   * Validate Method
   * 
   * Called automatically by Passport after JWT signature and expiration are verified.
   * This method extracts user information from the validated token payload and returns
   * a user object that will be attached to the request (request.user).
   * 
   * The returned user object is used by:
   * - Route handlers to access authenticated user information
   * - RolesGuard for role-based authorization checks
   * - Audit logging for tracking user actions
   * - Multi-tenant data isolation (via accountId)
   * 
   * Token Validation Flow:
   * 1. Passport verifies JWT signature using JWT_ACCESS_SECRET
   * 2. Passport checks token expiration (rejects if > 15 minutes old)
   * 3. Passport decodes token payload
   * 4. This validate() method is called with decoded payload
   * 5. Method maps payload to user object format
   * 6. User object is attached to request.user
   * 
   * Security Notes:
   * - By the time this method is called, token signature is already verified
   * - Token expiration is already checked by Passport
   * - No additional validation needed for token authenticity
   * - Focus on mapping payload to application user format
   * 
   * @param {JwtPayload} payload - Decoded JWT payload with user information
   * @returns {Object} User object for request context with id, email, accountId, and roles
   * 
   * @example
   * // After authentication, route handlers can access:
   * // request.user = { id: '123', email: 'user@example.com', accountId: 'acc456', roles: ['user'] }
   */
  async validate(payload: JwtPayload) {
    // Map JWT payload to user object format expected by application
    // The 'sub' (subject) claim contains the user ID per JWT standards
    return {
      id: payload.sub,
      email: payload.email,
      accountId: payload.accountId,
      roles: payload.roles,
    };
  }
}
