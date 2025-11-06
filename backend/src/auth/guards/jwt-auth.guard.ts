/**
 * JWT Authentication Guard
 * 
 * Validates JWT access tokens from Authorization header and protects API endpoints
 * from unauthenticated access. This guard extends Passport's AuthGuard('jwt') to
 * leverage JwtStrategy for token validation.
 * 
 * Features:
 * - Automatic token extraction from Authorization Bearer header
 * - JWT signature verification using JWT_ACCESS_SECRET
 * - Token expiration validation (15-minute expiry per Section 0.7.1)
 * - Comprehensive error handling for various token failure scenarios
 * - Support for public routes via @Public() decorator
 * - Automatic injection of authenticated user into request object
 * 
 * Token Format:
 * Authorization: Bearer <jwt-token>
 * 
 * User Object Structure (injected into request):
 * {
 *   id: string,          // User ID from token sub claim
 *   email: string,       // User email
 *   accountId: string,   // Multi-tenant account ID for data isolation
 *   roles: string[],     // User roles for RBAC authorization
 *   iat: number,         // Token issued at timestamp
 *   exp: number          // Token expiration timestamp
 * }
 * 
 * Usage Examples:
 * 
 * 1. Protect single endpoint:
 *    @Get('protected')
 *    @UseGuards(JwtAuthGuard)
 *    async protectedRoute(@Req() req) {
 *      const user = req.user;
 *      return this.service.getData(user.id);
 *    }
 * 
 * 2. Protect entire controller:
 *    @Controller('documents')
 *    @UseGuards(JwtAuthGuard)
 *    export class DocumentsController { ... }
 * 
 * 3. Combine with role-based guard:
 *    @Get('admin')
 *    @UseGuards(JwtAuthGuard, RolesGuard)
 *    @Roles('admin')
 *    async adminRoute() { ... }
 * 
 * 4. Allow public access (bypass authentication):
 *    @Public()
 *    @Get('public')
 *    async publicRoute() { ... }
 * 
 * Error Responses:
 * - 401 "No authentication token provided" - Missing Authorization header
 * - 401 "Token has expired" - Token exp claim exceeded
 * - 401 "Invalid token" - Malformed token or signature verification failed
 * - 401 "Authentication failed" - Generic authentication error
 * 
 * Security Considerations:
 * - Access tokens expire after 15 minutes (configurable)
 * - JWT_ACCESS_SECRET must be strong (min 32 characters)
 * - Always use HTTPS in production (tokens in clear text)
 * - Different secrets for access and refresh tokens
 * - Apply rate limiting to prevent brute force attacks
 * 
 * Requirements Satisfied:
 * - Section 0.5.3 Group 2A: JWT authentication guard implementation
 * - Section 0.7.1: Token expiration, authentication requirement
 * - Section 0.4.5: JWT token validation with Passport integration
 * - Section 0.7.2: NestJS guard pattern, extends AuthGuard
 * 
 * @module AuthModule
 * @see JwtStrategy for token validation logic
 * @see RolesGuard for authorization after authentication
 */

import { Injectable, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { AuthGuard } from '@nestjs/passport';

/**
 * JWT Authentication Guard
 * 
 * Extends Passport's AuthGuard to implement JWT token validation for API endpoints.
 * Automatically extracts tokens from Authorization header, validates signature and
 * expiration, and injects authenticated user into request object.
 */
@Injectable()
export class JwtAuthGuard extends AuthGuard('jwt') {
  /**
   * Creates an instance of JwtAuthGuard
   * 
   * @param reflector - NestJS Reflector for reading route metadata
   */
  constructor(private reflector: Reflector) {
    super();
  }

  /**
   * Determines if the current request should be allowed to proceed
   * 
   * This method is called before the route handler is executed. It checks for
   * public route metadata and delegates JWT validation to Passport's AuthGuard.
   * 
   * Execution Flow:
   * 1. Check if route has @Public() decorator metadata
   * 2. If public, allow access without authentication
   * 3. Otherwise, delegate to parent AuthGuard('jwt').canActivate()
   * 4. Parent extracts token from Authorization header
   * 5. Parent validates token signature using JWT_ACCESS_SECRET
   * 6. Parent checks token expiration
   * 7. Parent calls JwtStrategy.validate() with token payload
   * 8. JwtStrategy returns user object to inject into request
   * 
   * @param context - Execution context containing request information
   * @returns Promise<boolean> or boolean indicating if request can proceed
   * 
   * @example
   * // Route with public access
   * @Public()
   * @Get('health')
   * async healthCheck() { return { status: 'ok' }; }
   * 
   * @example
   * // Protected route (requires authentication)
   * @Get('profile')
   * @UseGuards(JwtAuthGuard)
   * async getProfile(@Req() req) {
   *   return this.usersService.findOne(req.user.id);
   * }
   */
  canActivate(context: ExecutionContext) {
    // Check if route is marked as public using @Public() decorator
    // getAllAndOverride checks both method and class-level decorators
    // Method-level decorator takes precedence over class-level
    const isPublic = this.reflector.getAllAndOverride<boolean>('isPublic', [
      context.getHandler(), // Method decorator
      context.getClass(),   // Class decorator
    ]);

    // Allow public routes to bypass authentication
    if (isPublic) {
      return true;
    }

    // Delegate to parent AuthGuard('jwt') for JWT validation
    // This triggers the JWT strategy configured in Passport
    // Returns Promise<boolean> or boolean
    return super.canActivate(context);
  }

  /**
   * Processes the result of Passport authentication and handles errors
   * 
   * This method is called by Passport after JWT validation. It receives the
   * validation result and any errors, then performs custom error handling
   * and user injection into the request object.
   * 
   * Token Validation Process:
   * 1. Passport extracts token from "Authorization: Bearer <token>" header
   * 2. Passport verifies token signature using JWT_ACCESS_SECRET
   * 3. Passport checks token expiration (exp claim)
   * 4. JwtStrategy.validate() transforms payload into user object
   * 5. This method receives validation result and handles errors
   * 6. User object is injected into request for route handlers
   * 
   * Error Scenarios:
   * - TokenExpiredError: Token exp claim exceeded (15-minute expiry)
   * - JsonWebTokenError: Invalid token format or signature mismatch
   * - No auth token: Missing Authorization header
   * - Generic error: Unexpected validation failure
   * 
   * @param err - Error from Passport or strategy (if any)
   * @param user - User object from JwtStrategy.validate() (if successful)
   * @param info - Additional information about validation (e.g., error details)
   * @param context - Execution context for accessing request
   * @returns User object to be injected into request
   * @throws UnauthorizedException for any authentication failure
   * 
   * @example
   * // Successful authentication - user object injected
   * {
   *   id: '550e8400-e29b-41d4-a716-446655440000',
   *   email: 'user@example.com',
   *   accountId: '123e4567-e89b-12d3-a456-426614174000',
   *   roles: ['user']
   * }
   * 
   * @example
   * // Token expired error
   * throw new UnauthorizedException('Token has expired');
   * 
   * @example
   * // Invalid token error
   * throw new UnauthorizedException('Invalid token');
   */
  handleRequest(err: any, user: any, info: any, context: ExecutionContext) {
    // Handle errors from Passport authentication or strategy validation
    // User is falsy if authentication failed
    if (err || !user) {
      // Token expired error - JWT exp claim exceeded
      // This occurs when current time > token expiration timestamp
      if (info?.name === 'TokenExpiredError') {
        throw new UnauthorizedException('Token has expired');
      }

      // Invalid token error - malformed JWT or signature verification failed
      // This occurs when:
      // - Token format is invalid (not proper JWT structure)
      // - Signature doesn't match (wrong secret or tampered token)
      // - Token encoding is corrupted
      if (info?.name === 'JsonWebTokenError') {
        throw new UnauthorizedException('Invalid token');
      }

      // Missing token error - no Authorization header provided
      // This occurs when client doesn't send Authorization header
      if (info?.message === 'No auth token') {
        throw new UnauthorizedException('No authentication token provided');
      }

      // Generic authentication failure
      // Throw original error if available, otherwise generic message
      // This catches any unexpected validation failures
      throw err || new UnauthorizedException('Authentication failed');
    }

    // Authentication successful - inject user into request
    // Get HTTP request object from execution context
    const request = context.switchToHttp().getRequest();
    
    // Attach authenticated user to request object
    // User object structure:
    // {
    //   id: string - User UUID from token sub claim
    //   email: string - User email for identification
    //   accountId: string - Multi-tenant account ID for data isolation
    //   roles: string[] - User roles for RBAC (e.g., ['admin', 'user'])
    //   iat: number - Token issued at timestamp (for audit)
    //   exp: number - Token expiration timestamp (for reference)
    // }
    request.user = user;

    // Return user object (Passport also injects it into request)
    // Route handlers can access via @Req() req decorator
    return user;
  }
}
