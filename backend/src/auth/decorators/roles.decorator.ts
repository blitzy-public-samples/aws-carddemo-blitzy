import { SetMetadata } from '@nestjs/common';

/**
 * Metadata key used to store role requirements on route handlers
 * This key is used by RolesGuard to retrieve required roles via Reflector
 * 
 * @constant ROLES_KEY
 * @type {string}
 * @example
 * const requiredRoles = this.reflector.getAllAndOverride<string[]>(ROLES_KEY, [
 *   context.getHandler(),
 *   context.getClass(),
 * ]);
 */
export const ROLES_KEY = 'roles';

/**
 * Custom decorator that specifies role requirements for route handlers
 * Used in conjunction with RolesGuard to enforce role-based access control
 * 
 * @decorator Roles
 * @param {...string[]} roles - Variable number of role names required to access the endpoint
 * @returns {CustomDecorator} - NestJS custom decorator that sets metadata
 * 
 * @description
 * This decorator attaches role requirements as metadata to controller methods or classes.
 * The RolesGuard reads this metadata via Reflector and checks if the authenticated user
 * has any of the required roles. If the user has at least one matching role, access is granted.
 * 
 * IMPORTANT: This decorator should ALWAYS be used with an authentication guard:
 * - @UseGuards(JwtAuthGuard, RolesGuard) for JWT authentication
 * - @UseGuards(ApiKeyAuthGuard, RolesGuard) for API key authentication
 * 
 * The authentication guard must run BEFORE RolesGuard to inject user object into request.
 * 
 * @example
 * // Single role requirement
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin')
 * @Delete('users/:id')
 * async deleteUser() {
 *   // Only users with 'admin' role can access
 * }
 * 
 * @example
 * // Multiple roles (user needs ANY of these roles)
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin', 'manager', 'moderator')
 * @Get('reports')
 * async viewReports() {
 *   // Users with 'admin' OR 'manager' OR 'moderator' role can access
 * }
 * 
 * @example
 * // Apply to entire controller (all methods inherit role requirement)
 * @Controller('admin')
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin')
 * export class AdminController {
 *   // All methods require 'admin' role
 *   @Get('dashboard')
 *   getDashboard() { }
 * 
 *   // Override class-level roles with method-level roles
 *   @Roles('super-admin')
 *   @Delete('system')
 *   dangerousOperation() {
 *     // Requires 'super-admin' role (method-level overrides class-level)
 *   }
 * }
 * 
 * @example
 * // Common role combinations in OCR Processing Application
 * @Roles('admin') // Full system access
 * @Roles('user') // Standard user access
 * @Roles('manager') // Team management access
 * @Roles('api') // API-only access (for API keys)
 * 
 * @example
 * // Pattern 1: Single Role Requirement
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin')
 * @Post('templates/:id/publish')
 * async publishTemplate(@Param('id') id: string) {
 *   // Only administrators can publish templates
 * }
 * 
 * @example
 * // Pattern 2: Multiple Roles (OR Logic)
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin', 'manager')
 * @Get('users')
 * async listUsers() {
 *   // Admins OR managers can view user list
 * }
 * 
 * @example
 * // Pattern 3: Controller-Level Protection
 * @Controller('admin')
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('admin')
 * export class AdminController {
 *   // All methods require admin role by default
 *   @Get('settings')
 *   getSettings() { }
 *   
 *   @Put('settings')
 *   updateSettings() { }
 * }
 * 
 * @example
 * // Pattern 4: Method Override
 * @Controller('documents')
 * @UseGuards(JwtAuthGuard, RolesGuard)
 * @Roles('user') // Default: any user
 * export class DocumentsController {
 *   @Get() // Inherits 'user' role
 *   list() { }
 *   
 *   @Roles('admin') // Override: admin only
 *   @Delete(':id')
 *   delete(@Param('id') id: string) { }
 * }
 * 
 * @example
 * // Pattern 5: API Key Access
 * @UseGuards(ApiKeyAuthGuard, RolesGuard)
 * @Roles('api')
 * @Post('api/v1/documents')
 * async uploadViaApi() {
 *   // API keys get 'api' role
 * }
 * 
 * @remarks
 * AUTHORIZATION FLOW:
 * ```
 * Request → JwtAuthGuard (authenticate) → RolesGuard (authorize) → Route Handler
 * ```
 * - JwtAuthGuard validates token, injects user into request
 * - RolesGuard reads @Roles() metadata via ROLES_KEY
 * - RolesGuard checks user.roles against required roles
 * - Access granted if user has any required role
 * - HTTP 403 Forbidden if user lacks required roles
 * 
 * ROLE MATCHING LOGIC:
 * - User must have at least ONE of the specified roles (logical OR)
 * - Example: @Roles('admin', 'manager') - user with 'admin' OR 'manager' can access
 * - Empty roles array @Roles() = allow all authenticated users
 * 
 * INHERITANCE AND OVERRIDE:
 * - Class-level @Roles() applies to all methods
 * - Method-level @Roles() overrides class-level
 * - Use Reflector.getAllAndOverride() for proper precedence
 * 
 * COMMON ROLES:
 * - 'admin': Full system access, all permissions
 * - 'user': Standard user, basic document operations
 * - 'manager': Team management, user oversight
 * - 'api': Programmatic access via API keys
 * - Custom roles: Defined per application requirements
 * 
 * EXPECTED USER STRUCTURE:
 * ```typescript
 * interface AuthenticatedUser {
 *   id: string;
 *   email: string;
 *   accountId: string;
 *   roles: string[]; // <-- Required for RolesGuard
 * }
 * ```
 * 
 * ERROR HANDLING:
 * - No decorator = no role check (if only JwtAuthGuard used)
 * - No user object = RolesGuard returns false (403)
 * - No matching roles = RolesGuard returns false (403)
 * - Empty roles array @Roles() = allow all authenticated users
 * 
 * BEST PRACTICES:
 * - Always specify authentication guard before RolesGuard
 * - Use descriptive role names (avoid abbreviations)
 * - Document role requirements in API specification
 * - Keep role definitions centralized
 * - Use environment-specific roles if needed
 * - Prefer fewer, broader roles over many specific roles
 * - Implement role hierarchy if complex permissions needed
 * 
 * @see RolesGuard - Guard that enforces these role requirements
 * @see JwtAuthGuard - Authentication guard that must run before RolesGuard
 * @see ApiKeyAuthGuard - Alternative authentication guard for API access
 */
export const Roles = (...roles: string[]) => SetMetadata(ROLES_KEY, roles);
