/**
 * Encryption and Hashing Utilities
 * 
 * Provides secure cryptographic operations for the OCR Processing Application.
 * Implements security requirements from Section 0.7.1 including:
 * - Password hashing with bcrypt
 * - AES-256-CBC encryption for data at rest
 * - API key generation and hashing
 * - Secure token generation
 * - General-purpose SHA-256 hashing
 * 
 * SECURITY WARNING: This module handles sensitive cryptographic operations.
 * - NEVER log the output of these functions
 * - NEVER commit encryption keys to version control
 * - ALWAYS use environment variables for encryption keys
 * - NEVER use hardcoded secrets
 * 
 * @module encryption.utils
 */

import * as bcrypt from 'bcrypt';
import * as crypto from 'crypto';

/**
 * Default number of salt rounds for bcrypt password hashing.
 * Higher values increase security but also increase processing time.
 * Section 0.7.1: Configurable salt rounds with default 10.
 */
const DEFAULT_SALT_ROUNDS = 10;

/**
 * Encryption algorithm used for data at rest.
 * Section 0.7.1: ALL sensitive data MUST be encrypted at rest (AES-256).
 */
const ENCRYPTION_ALGORITHM = 'aes-256-cbc';

/**
 * Initialization vector length for AES-256-CBC encryption (16 bytes).
 */
const IV_LENGTH = 16;

/**
 * Key length for AES-256 encryption (32 bytes).
 */
const KEY_LENGTH = 32;

/**
 * Default length for generated API keys (32 bytes = 64 hex characters).
 */
const API_KEY_LENGTH = 32;

/**
 * Default length for generated random tokens (32 bytes = 64 hex characters).
 */
const TOKEN_LENGTH = 32;

/**
 * Hashes a password using bcrypt with configurable salt rounds.
 * 
 * Implements bcrypt password hashing as required by Section 0.7.1.
 * Uses automatic salt generation and secure one-way hashing.
 * 
 * SECURITY WARNING: NEVER log the plain password parameter.
 * 
 * @param password - The plain text password to hash
 * @param saltRounds - Number of salt rounds (default: 10). Higher = more secure but slower.
 * @returns Promise resolving to the hashed password string
 * @throws Error if hashing fails
 * 
 * @example
 * const hashedPassword = await hashPassword('userPassword123', 12);
 * // Store hashedPassword in database
 */
export async function hashPassword(
  password: string,
  saltRounds: number = DEFAULT_SALT_ROUNDS,
): Promise<string> {
  try {
    if (!password || password.trim().length === 0) {
      throw new Error('Password cannot be empty');
    }

    if (saltRounds < 4 || saltRounds > 31) {
      throw new Error('Salt rounds must be between 4 and 31');
    }

    const hashedPassword = await bcrypt.hash(password, saltRounds);
    return hashedPassword;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to hash password: ${error.message}`);
    }
    throw new Error('Failed to hash password: Unknown error');
  }
}

/**
 * Compares a plain text password with a bcrypt hashed password.
 * 
 * Uses timing-attack-safe comparison to verify passwords.
 * Implements secure password verification per Section 0.7.1.
 * 
 * SECURITY WARNING: NEVER log the plain password parameter.
 * 
 * @param password - The plain text password to verify
 * @param hashedPassword - The bcrypt hashed password to compare against
 * @returns Promise resolving to true if passwords match, false otherwise
 * @throws Error if comparison fails
 * 
 * @example
 * const isValid = await comparePassword('userPassword123', storedHash);
 * if (isValid) {
 *   // Authentication successful
 * }
 */
export async function comparePassword(
  password: string,
  hashedPassword: string,
): Promise<boolean> {
  try {
    if (!password || !hashedPassword) {
      return false;
    }

    const isMatch = await bcrypt.compare(password, hashedPassword);
    return isMatch;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to compare password: ${error.message}`);
    }
    throw new Error('Failed to compare password: Unknown error');
  }
}

/**
 * Generates a cryptographically secure random API key.
 * 
 * Creates a secure random API key suitable for API authentication.
 * The generated key should be hashed before storage using hashApiKey().
 * 
 * SECURITY WARNING: 
 * - Display this key to the user ONLY ONCE upon generation
 * - NEVER store the plain key in database (use hashApiKey() first)
 * - NEVER log this value
 * 
 * @param length - Length of the key in bytes (default: 32, resulting in 64 hex chars)
 * @returns Promise resolving to the generated API key as a hex string
 * @throws Error if key generation fails
 * 
 * @example
 * const apiKey = await generateApiKey();
 * // Display to user: apiKey
 * const hashedKey = await hashApiKey(apiKey);
 * // Store in database: hashedKey
 */
export async function generateApiKey(length: number = API_KEY_LENGTH): Promise<string> {
  try {
    if (length < 16 || length > 128) {
      throw new Error('API key length must be between 16 and 128 bytes');
    }

    return new Promise((resolve, reject) => {
      crypto.randomBytes(length, (err, buffer) => {
        if (err) {
          reject(new Error(`Failed to generate API key: ${err.message}`));
        } else {
          resolve(buffer.toString('hex'));
        }
      });
    });
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('Failed to generate API key: Unknown error');
  }
}

/**
 * Hashes an API key for secure storage in the database.
 * 
 * Implements Section 0.7.1 requirement: ALL API keys MUST be stored hashed (not plain text).
 * Uses SHA-256 hashing for API key storage. The plain API key is shown to user once,
 * and only the hash is stored in the database.
 * 
 * SECURITY WARNING: NEVER log the plain API key parameter.
 * 
 * @param apiKey - The plain text API key to hash
 * @returns Promise resolving to the SHA-256 hashed API key as hex string
 * @throws Error if hashing fails
 * 
 * @example
 * const apiKey = await generateApiKey();
 * const hashedKey = await hashApiKey(apiKey);
 * // Store hashedKey in api_keys table
 */
export async function hashApiKey(apiKey: string): Promise<string> {
  try {
    if (!apiKey || apiKey.trim().length === 0) {
      throw new Error('API key cannot be empty');
    }

    const hash = crypto.createHash('sha256').update(apiKey).digest('hex');
    return hash;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to hash API key: ${error.message}`);
    }
    throw new Error('Failed to hash API key: Unknown error');
  }
}

/**
 * Encrypts sensitive data using AES-256-CBC encryption.
 * 
 * Implements Section 0.7.1 requirement: ALL sensitive data MUST be encrypted at rest (AES-256).
 * Uses AES-256-CBC with randomly generated IV for each encryption operation.
 * The IV is prepended to the encrypted data for later decryption.
 * 
 * SECURITY WARNING:
 * - Encryption key MUST be provided via ENCRYPTION_KEY environment variable
 * - NEVER hardcode encryption keys
 * - NEVER log the plain text data or encryption key
 * 
 * @param plainText - The plain text data to encrypt
 * @param encryptionKey - The encryption key (32 bytes). Should come from environment variable.
 * @returns Promise resolving to encrypted data as hex string (IV prepended)
 * @throws Error if encryption fails or key is invalid
 * 
 * @example
 * const encryptionKey = process.env.ENCRYPTION_KEY;
 * const encrypted = await encrypt('sensitive data', encryptionKey);
 * // Store encrypted in database
 */
export async function encrypt(plainText: string, encryptionKey: string): Promise<string> {
  try {
    if (!plainText) {
      throw new Error('Plain text cannot be empty');
    }

    if (!encryptionKey) {
      throw new Error('Encryption key is required. Set ENCRYPTION_KEY environment variable.');
    }

    // Derive a proper 32-byte key from the provided key using scrypt
    const key = await new Promise<Buffer>((resolve, reject) => {
      crypto.scrypt(encryptionKey, 'salt', KEY_LENGTH, (err, derivedKey) => {
        if (err) {
          reject(err);
        } else {
          resolve(derivedKey);
        }
      });
    });

    // Generate random IV for this encryption operation
    const iv = crypto.randomBytes(IV_LENGTH);

    // Create cipher and encrypt
    const cipher = crypto.createCipheriv(ENCRYPTION_ALGORITHM, key, iv);
    let encrypted = cipher.update(plainText, 'utf8', 'hex');
    encrypted += cipher.final('hex');

    // Prepend IV to encrypted data (IV is not secret)
    const ivHex = iv.toString('hex');
    return ivHex + ':' + encrypted;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to encrypt data: ${error.message}`);
    }
    throw new Error('Failed to encrypt data: Unknown error');
  }
}

/**
 * Decrypts data that was encrypted with the encrypt() function.
 * 
 * Decrypts AES-256-CBC encrypted data. Expects the IV to be prepended to the encrypted data
 * (as produced by the encrypt() function).
 * 
 * SECURITY WARNING:
 * - Decryption key MUST match the encryption key
 * - NEVER log decrypted sensitive data
 * - NEVER log the encryption key
 * 
 * @param encryptedData - The encrypted data as hex string (with IV prepended)
 * @param encryptionKey - The decryption key (must match encryption key)
 * @returns Promise resolving to the decrypted plain text string
 * @throws Error if decryption fails, data is malformed, or key is invalid
 * 
 * @example
 * const encryptionKey = process.env.ENCRYPTION_KEY;
 * const decrypted = await decrypt(encryptedData, encryptionKey);
 * // Use decrypted data (don't log it)
 */
export async function decrypt(encryptedData: string, encryptionKey: string): Promise<string> {
  try {
    if (!encryptedData) {
      throw new Error('Encrypted data cannot be empty');
    }

    if (!encryptionKey) {
      throw new Error('Encryption key is required. Set ENCRYPTION_KEY environment variable.');
    }

    // Split IV and encrypted data
    const parts = encryptedData.split(':');
    if (parts.length !== 2 || !parts[0] || !parts[1]) {
      throw new Error('Invalid encrypted data format. Expected IV:encryptedData');
    }

    const ivHex: string = parts[0];
    const encryptedHex: string = parts[1];

    // Derive the same key used for encryption
    const key = await new Promise<Buffer>((resolve, reject) => {
      crypto.scrypt(encryptionKey, 'salt', KEY_LENGTH, (err, derivedKey) => {
        if (err) {
          reject(err);
        } else {
          resolve(derivedKey);
        }
      });
    });

    // Extract IV
    const iv = Buffer.from(ivHex, 'hex');

    // Create decipher and decrypt
    const decipher = crypto.createDecipheriv(ENCRYPTION_ALGORITHM, key, iv);
    let decrypted = decipher.update(encryptedHex, 'hex', 'utf8');
    decrypted += decipher.final('utf8');

    return decrypted;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to decrypt data: ${error.message}`);
    }
    throw new Error('Failed to decrypt data: Unknown error');
  }
}

/**
 * Generates a cryptographically secure random token.
 * 
 * Creates a secure random token suitable for session tokens, CSRF tokens,
 * password reset tokens, or other security-sensitive random values.
 * 
 * SECURITY WARNING: NEVER log generated tokens.
 * 
 * @param length - Length of the token in bytes (default: 32, resulting in 64 hex chars)
 * @returns Promise resolving to the generated token as a hex string
 * @throws Error if token generation fails
 * 
 * @example
 * const resetToken = await generateRandomToken(48);
 * // Store hash of resetToken in database
 * // Send plain resetToken to user via secure channel (email)
 */
export async function generateRandomToken(length: number = TOKEN_LENGTH): Promise<string> {
  try {
    if (length < 16 || length > 128) {
      throw new Error('Token length must be between 16 and 128 bytes');
    }

    return new Promise((resolve, reject) => {
      crypto.randomBytes(length, (err, buffer) => {
        if (err) {
          reject(new Error(`Failed to generate random token: ${err.message}`));
        } else {
          resolve(buffer.toString('hex'));
        }
      });
    });
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('Failed to generate random token: Unknown error');
  }
}

/**
 * Generates a SHA-256 hash of the provided data.
 * 
 * General-purpose hashing function for non-password data.
 * Use this for data integrity checks, generating deterministic IDs, or hashing tokens.
 * 
 * NOTE: For password hashing, use hashPassword() instead (bcrypt with salt).
 * NOTE: For API key hashing, use hashApiKey() instead.
 * 
 * @param data - The data to hash (string)
 * @returns Promise resolving to the SHA-256 hash as hex string
 * @throws Error if hashing fails
 * 
 * @example
 * const hash = await generateHash('data-to-hash');
 * // Use hash for integrity check or deterministic ID
 */
export async function generateHash(data: string): Promise<string> {
  try {
    if (!data) {
      throw new Error('Data to hash cannot be empty');
    }

    const hash = crypto.createHash('sha256').update(data).digest('hex');
    return hash;
  } catch (error) {
    if (error instanceof Error) {
      throw new Error(`Failed to generate hash: ${error.message}`);
    }
    throw new Error('Failed to generate hash: Unknown error');
  }
}
