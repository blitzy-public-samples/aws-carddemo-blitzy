import { ApiProperty } from '@nestjs/swagger';
import {
  IsEmail,
  IsString,
  IsNotEmpty,
  MinLength,
  MaxLength,
  Matches,
  IsUUID,
  IsOptional,
} from 'class-validator';

/**
 * Data Transfer Object for creating a new user account.
 * 
 * Used by POST /users endpoint for user registration.
 * All fields are validated using class-validator decorators.
 * Password must meet complexity requirements per security policy.
 * Account ID enforces multi-tenant data isolation.
 * 
 * Validation Rules:
 * - Email: Must be valid email format
 * - Password: Min 8 characters with uppercase, lowercase, number, and special character
 * - First/Last Name: 2-50 characters
 * - Account ID: Valid UUID v4 format for multi-tenant isolation
 * - Roles: Optional array of role names (defaults to ["User"] if not provided)
 * 
 * @see UsersController.create()
 * @see UsersService.create()
 * 
 * @example
 * ```typescript
 * const createUserDto: CreateUserDto = {
 *   email: 'john.doe@company.com',
 *   password: 'SecurePass123!',
 *   firstName: 'John',
 *   lastName: 'Doe',
 *   account_id: '550e8400-e29b-41d4-a716-446655440000',
 *   roles: ['User']
 * };
 * ```
 */
export class CreateUserDto {
  /**
   * User email address (must be unique within account).
   * Validated for proper email format.
   */
  @ApiProperty({
    description: 'User email address (must be unique within account)',
    example: 'john.doe@company.com',
    format: 'email',
  })
  @IsEmail({}, { message: 'Invalid email format' })
  @IsNotEmpty({ message: 'Email is required' })
  email!: string;

  /**
   * User password with complexity requirements.
   * Must contain at least 8 characters including:
   * - One uppercase letter
   * - One lowercase letter
   * - One number
   * - One special character (@$!%*?&#)
   */
  @ApiProperty({
    description: 'User password (min 8 characters, must contain uppercase, lowercase, number, and special character)',
    example: 'SecurePass123!',
    minLength: 8,
  })
  @IsString()
  @IsNotEmpty({ message: 'Password is required' })
  @MinLength(8, { message: 'Password must be at least 8 characters long' })
  @Matches(
    /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&#])[A-Za-z\d@$!%*?&#]/,
    {
      message: 'Password must contain uppercase, lowercase, number, and special character',
    },
  )
  password!: string;

  /**
   * User first name.
   * Must be between 2 and 50 characters.
   */
  @ApiProperty({
    description: 'User first name',
    example: 'John',
    minLength: 2,
    maxLength: 50,
  })
  @IsString()
  @IsNotEmpty({ message: 'First name is required' })
  @MinLength(2, { message: 'First name must be at least 2 characters' })
  @MaxLength(50, { message: 'First name must not exceed 50 characters' })
  firstName!: string;

  /**
   * User last name.
   * Must be between 2 and 50 characters.
   */
  @ApiProperty({
    description: 'User last name',
    example: 'Doe',
    minLength: 2,
    maxLength: 50,
  })
  @IsString()
  @IsNotEmpty({ message: 'Last name is required' })
  @MinLength(2, { message: 'Last name must be at least 2 characters' })
  @MaxLength(50, { message: 'Last name must not exceed 50 characters' })
  lastName!: string;

  /**
   * Account ID for multi-tenant isolation.
   * Must be a valid UUID v4 format.
   * Ensures all user data is properly associated with their account.
   */
  @ApiProperty({
    description: 'Account ID for multi-tenant isolation (UUID v4 format)',
    example: '550e8400-e29b-41d4-a716-446655440000',
    format: 'uuid',
  })
  @IsUUID(4, { message: 'Account ID must be a valid UUID v4' })
  @IsNotEmpty({ message: 'Account ID is required' })
  account_id!: string;

  /**
   * Array of role names to assign to user.
   * Optional field that defaults to ["User"] if not provided.
   * Each role name must be a valid string.
   */
  @ApiProperty({
    description: 'Array of role names to assign to user (optional, defaults to ["User"])',
    example: ['User'],
    required: false,
    type: [String],
  })
  @IsOptional()
  @IsString({ each: true })
  roles?: string[];
}
