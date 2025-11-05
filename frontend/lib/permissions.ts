/**
 * Role-Based Access Control (RBAC) Utility Module
 * 
 * This module provides a comprehensive permission system for the OCR Processing Application.
 * It defines permissions, roles, and helper functions for authorization checks throughout the UI.
 * 
 * @module permissions
 */

/**
 * Permission enum defining all application-level permissions.
 * Each permission represents a specific action a user can perform in the system.
 * 
 * Naming convention: RESOURCE_ACTION (e.g., DOCUMENT_READ, TEMPLATE_UPDATE)
 */
export enum Permission {
  // Document permissions
  DOCUMENT_READ = 'document.read',
  DOCUMENT_WRITE = 'document.write',
  DOCUMENT_DELETE = 'document.delete',
  DOCUMENT_APPROVE = 'document.approve',

  // Template permissions
  TEMPLATE_READ = 'template.read',
  TEMPLATE_CREATE = 'template.create',
  TEMPLATE_UPDATE = 'template.update',
  TEMPLATE_DELETE = 'template.delete',

  // User management permissions
  USER_READ = 'user.read',
  USER_WRITE = 'user.write',
  USER_DELETE = 'user.delete',

  // Integration permissions
  INTEGRATION_READ = 'integration.read',
  INTEGRATION_WRITE = 'integration.write',

  // API key management permission
  API_KEY_MANAGE = 'api_key.manage',

  // Audit log permission
  AUDIT_READ = 'audit.read',

  // Analytics permission
  ANALYTICS_READ = 'analytics.read',
}

/**
 * Role type defining all available user roles in the system.
 * 
 * - Admin: Full system access with all permissions
 * - User: Standard user with document processing and limited management capabilities
 * - Viewer: Read-only access to documents and analytics
 * - Processor: Document processing focused role without administrative functions
 */
export type Role = 'Admin' | 'User' | 'Viewer' | 'Processor';

/**
 * RolePermissions mapping defines which permissions each role has.
 * This is the authoritative source for role-based authorization decisions.
 * 
 * Role Definitions:
 * - Admin: Complete system access including user management, integrations, and audit logs
 * - User: Document processing, template viewing, analytics, own profile management
 * - Viewer: Read-only access to documents and analytics for monitoring purposes
 * - Processor: Document read/write and approval for data entry workers, no admin functions
 */
export const RolePermissions: Record<Role, Permission[]> = {
  Admin: [
    // Document permissions (full access)
    Permission.DOCUMENT_READ,
    Permission.DOCUMENT_WRITE,
    Permission.DOCUMENT_DELETE,
    Permission.DOCUMENT_APPROVE,

    // Template permissions (full access)
    Permission.TEMPLATE_READ,
    Permission.TEMPLATE_CREATE,
    Permission.TEMPLATE_UPDATE,
    Permission.TEMPLATE_DELETE,

    // User management permissions (full access)
    Permission.USER_READ,
    Permission.USER_WRITE,
    Permission.USER_DELETE,

    // Integration permissions (full access)
    Permission.INTEGRATION_READ,
    Permission.INTEGRATION_WRITE,

    // API key management (admin only)
    Permission.API_KEY_MANAGE,

    // Audit logs (admin only)
    Permission.AUDIT_READ,

    // Analytics access
    Permission.ANALYTICS_READ,
  ],

  User: [
    // Document permissions (read, write, approve)
    Permission.DOCUMENT_READ,
    Permission.DOCUMENT_WRITE,
    Permission.DOCUMENT_APPROVE,

    // Template permissions (read only)
    Permission.TEMPLATE_READ,

    // Analytics access
    Permission.ANALYTICS_READ,
  ],

  Viewer: [
    // Document permissions (read only)
    Permission.DOCUMENT_READ,

    // Analytics access (monitoring)
    Permission.ANALYTICS_READ,
  ],

  Processor: [
    // Document permissions (read, write, approve - focused on data entry)
    Permission.DOCUMENT_READ,
    Permission.DOCUMENT_WRITE,
    Permission.DOCUMENT_APPROVE,

    // Template permissions (read only - to use templates)
    Permission.TEMPLATE_READ,
  ],
};

/**
 * Checks if a user with given roles has a specific permission.
 * 
 * This function aggregates permissions from all user roles and checks if
 * the required permission is present in any of them.
 * 
 * @param userRoles - Array of roles assigned to the user (can be empty)
 * @param permission - The permission to check
 * @returns true if user has the permission through any of their roles, false otherwise
 * 
 * @example
 * ```typescript
 * const isAllowed = hasPermission(['User'], Permission.DOCUMENT_READ);
 * // Returns: true
 * 
 * const canDelete = hasPermission(['User'], Permission.DOCUMENT_DELETE);
 * // Returns: false (User role doesn't have delete permission)
 * 
 * const canManageKeys = hasPermission(['Admin'], Permission.API_KEY_MANAGE);
 * // Returns: true (Admin has all permissions)
 * ```
 */
export function hasPermission(
  userRoles: Role[] = [],
  permission: Permission
): boolean {
  // Handle empty or null roles gracefully
  if (!userRoles || userRoles.length === 0) {
    return false;
  }

  // Check if any of the user's roles grant the required permission
  return userRoles.some((role) => {
    const rolePermissions = RolePermissions[role];
    return rolePermissions && rolePermissions.includes(permission);
  });
}

/**
 * Checks if a user has a specific role.
 * 
 * @param userRoles - Array of roles assigned to the user (can be empty)
 * @param requiredRole - The role to check for
 * @returns true if user has the specified role, false otherwise
 * 
 * @example
 * ```typescript
 * const isAdmin = hasRole(['Admin', 'User'], 'Admin');
 * // Returns: true
 * 
 * const isViewer = hasRole(['User'], 'Viewer');
 * // Returns: false
 * ```
 */
export function hasRole(userRoles: Role[] = [], requiredRole: Role): boolean {
  // Handle empty or null roles gracefully
  if (!userRoles || userRoles.length === 0) {
    return false;
  }

  return userRoles.includes(requiredRole);
}

/**
 * Checks if a user has at least one of the specified roles.
 * 
 * Useful for scenarios where multiple roles are acceptable for an action.
 * 
 * @param userRoles - Array of roles assigned to the user (can be empty)
 * @param requiredRoles - Array of roles to check against (at least one must match)
 * @returns true if user has any of the required roles, false otherwise
 * 
 * @example
 * ```typescript
 * const canProcess = hasAnyRole(['Processor'], ['Admin', 'Processor']);
 * // Returns: true (Processor is in required roles)
 * 
 * const canManage = hasAnyRole(['Viewer'], ['Admin', 'User']);
 * // Returns: false (Viewer is not in required roles)
 * ```
 */
export function hasAnyRole(
  userRoles: Role[] = [],
  requiredRoles: Role[] = []
): boolean {
  // Handle empty or null roles gracefully
  if (!userRoles || userRoles.length === 0) {
    return false;
  }

  if (!requiredRoles || requiredRoles.length === 0) {
    return false;
  }

  return userRoles.some((role) => requiredRoles.includes(role));
}

/**
 * Checks if a user has all specified permissions.
 * 
 * Useful for features that require multiple permissions simultaneously.
 * 
 * @param userRoles - Array of roles assigned to the user (can be empty)
 * @param permissions - Array of permissions that must all be present
 * @returns true if user has all specified permissions, false otherwise
 * 
 * @example
 * ```typescript
 * const canManageTemplates = hasAllPermissions(
 *   ['Admin'],
 *   [Permission.TEMPLATE_READ, Permission.TEMPLATE_WRITE, Permission.TEMPLATE_DELETE]
 * );
 * // Returns: true (Admin has all permissions)
 * 
 * const canManageDocuments = hasAllPermissions(
 *   ['User'],
 *   [Permission.DOCUMENT_READ, Permission.DOCUMENT_DELETE]
 * );
 * // Returns: false (User doesn't have DOCUMENT_DELETE)
 * ```
 */
export function hasAllPermissions(
  userRoles: Role[] = [],
  permissions: Permission[] = []
): boolean {
  // Handle empty or null roles gracefully
  if (!userRoles || userRoles.length === 0) {
    return false;
  }

  // If no permissions specified, return true (vacuous truth)
  if (!permissions || permissions.length === 0) {
    return true;
  }

  // Check that user has every required permission
  return permissions.every((permission) =>
    hasPermission(userRoles, permission)
  );
}

/**
 * Checks if a user can perform an action on a resource type.
 * 
 * This is a convenience function that combines resource type and action
 * into a permission string and checks if the user has that permission.
 * 
 * @param userRoles - Array of roles assigned to the user (can be empty)
 * @param resource - The resource type (e.g., 'document', 'template', 'user')
 * @param action - The action to perform (e.g., 'read', 'write', 'delete')
 * @returns true if user can perform the action on the resource, false otherwise
 * 
 * @example
 * ```typescript
 * const canReadDocs = canAccessResource(['User'], 'document', 'read');
 * // Returns: true (checks Permission.DOCUMENT_READ)
 * 
 * const canDeleteUsers = canAccessResource(['User'], 'user', 'delete');
 * // Returns: false (checks Permission.USER_DELETE)
 * 
 * const canManageKeys = canAccessResource(['Admin'], 'api_key', 'manage');
 * // Returns: true (checks Permission.API_KEY_MANAGE)
 * ```
 */
export function canAccessResource(
  userRoles: Role[] = [],
  resource: string,
  action: string
): boolean {
  // Handle empty or null roles gracefully
  if (!userRoles || userRoles.length === 0) {
    return false;
  }

  // Handle empty or null resource/action
  if (!resource || !action) {
    return false;
  }

  // Construct permission string from resource and action
  const permissionString = `${resource.toLowerCase()}.${action.toLowerCase()}`;

  // Check if this permission exists in the Permission enum
  const permissionExists = Object.values(Permission).includes(
    permissionString as Permission
  );

  if (!permissionExists) {
    // Permission doesn't exist in system - deny by default
    return false;
  }

  // Check if user has this permission
  return hasPermission(userRoles, permissionString as Permission);
}
