/**
 * Redis Module for OCR Processing Application
 * 
 * Provides global Redis connection and caching capabilities for the application.
 * Supports caching, session storage, rate limiting, and job queue state management
 * with automatic reconnection and key prefix namespacing.
 * 
 * Key Prefixes:
 * - session:{userId} - User session data
 * - cache:{resource} - Cached application data
 * - rate_limit:{accountId}:{endpoint} - API rate limiting counters
 * - job:{jobId} - Processing job state and status
 * 
 * Usage Example:
 * ```typescript
 * // In any service
 * constructor(private readonly redisService: RedisService) {}
 * 
 * // Caching
 * await this.redisService.set(
 *   this.redisService.getCacheKey('documents:list'),
 *   JSON.stringify(documents),
 *   3600
 * );
 * 
 * // Session management
 * await this.redisService.setJson(
 *   this.redisService.getSessionKey(userId),
 *   sessionData,
 *   900
 * );
 * 
 * // Rate limiting
 * const count = await this.redisService.increment(
 *   this.redisService.getRateLimitKey(accountId, '/api/v1/documents')
 * );
 * ```
 * 
 * @module RedisModule
 * @see Section 0.4.3 Database Integration Points
 * @see Section 0.7.1 Security Requirements (rate limiting)
 */

import { Module, Global, Injectable, OnModuleInit, OnModuleDestroy, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createClient, RedisClientType } from 'redis';

/**
 * RedisService provides Redis operations for caching, session management,
 * rate limiting, and job state tracking across the application.
 * 
 * Features:
 * - Automatic connection management with retry logic
 * - Key prefix namespacing for organization
 * - JSON serialization/deserialization helpers
 * - TTL management for cache expiration
 * - Type-safe operations with TypeScript
 * - Comprehensive error handling and logging
 * 
 * @class RedisService
 * @implements {OnModuleInit}
 * @implements {OnModuleDestroy}
 */
@Injectable()
export class RedisService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(RedisService.name);
  private client: RedisClientType;
  private isConnected = false;

  constructor(private readonly configService: ConfigService) {}

  /**
   * Initialize Redis connection on module initialization.
   * Configures connection parameters from environment variables and
   * establishes connection with retry logic.
   * 
   * @throws {Error} If connection fails after all retry attempts
   */
  async onModuleInit(): Promise<void> {
    const host = this.configService.get<string>('REDIS_HOST', 'localhost');
    const port = this.configService.get<number>('REDIS_PORT', 6379);
    const password = this.configService.get<string>('REDIS_PASSWORD');
    const database = this.configService.get<number>('REDIS_DB', 0);

    // Validate configuration
    const nodeEnv = this.configService.get<string>('NODE_ENV', 'development');
    if (nodeEnv === 'production' && !password) {
      this.logger.warn('REDIS_PASSWORD is not set in production environment. This is insecure!');
    }

    this.logger.log(`Initializing Redis connection to ${host}:${port}, database: ${database}`);

    // Create Redis client with configuration
    this.client = createClient({
      socket: {
        host,
        port,
        reconnectStrategy: (retries: number) => {
          if (retries > 10) {
            this.logger.error('Redis connection failed after 10 retry attempts');
            return new Error('Redis connection retry limit exceeded');
          }
          // Exponential backoff: 2^retries * 100ms, max 3000ms
          const delay = Math.min(Math.pow(2, retries) * 100, 3000);
          this.logger.warn(`Redis connection retry attempt ${retries}, waiting ${delay}ms`);
          return delay;
        },
      },
      password: password || undefined,
      database,
    }) as RedisClientType;

    // Set up event listeners for connection monitoring
    this.client.on('error', (error: Error) => {
      this.logger.error(`Redis connection error: ${error.message}`, error.stack);
      this.isConnected = false;
    });

    this.client.on('connect', () => {
      this.logger.log('Redis client connecting...');
    });

    this.client.on('ready', () => {
      this.logger.log(`Redis connection established successfully to ${host}:${port}`);
      this.isConnected = true;
    });

    this.client.on('reconnecting', () => {
      this.logger.warn('Redis client reconnecting...');
      this.isConnected = false;
    });

    this.client.on('end', () => {
      this.logger.log('Redis connection closed');
      this.isConnected = false;
    });

    try {
      // Establish connection
      await this.client.connect();
      this.logger.log('Redis connection initialization complete');
    } catch (error) {
      this.logger.error(`Failed to connect to Redis: ${error.message}`, error.stack);
      throw error;
    }
  }

  /**
   * Gracefully disconnect Redis client on module destruction.
   * Ensures all pending commands are completed before closing.
   */
  async onModuleDestroy(): Promise<void> {
    if (this.client && this.isConnected) {
      try {
        await this.client.quit();
        this.logger.log('Redis connection closed gracefully');
      } catch (error) {
        this.logger.error(`Error closing Redis connection: ${error.message}`);
        // Force disconnect if graceful quit fails
        await this.client.disconnect();
      }
    }
  }

  /**
   * Retrieve a value from Redis by key.
   * 
   * @param {string} key - The Redis key to retrieve
   * @returns {Promise<string | null>} The value if exists, null otherwise
   * 
   * @example
   * const value = await redisService.get('cache:user:123');
   */
  async get(key: string): Promise<string | null> {
    try {
      return await this.client.get(key);
    } catch (error) {
      this.logger.error(`Error getting key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Set a value in Redis with optional TTL (Time To Live).
   * 
   * @param {string} key - The Redis key to set
   * @param {string} value - The value to store
   * @param {number} [ttl] - Optional expiration time in seconds
   * 
   * @example
   * await redisService.set('cache:data', 'value', 3600); // Expires in 1 hour
   */
  async set(key: string, value: string, ttl?: number): Promise<void> {
    try {
      if (ttl) {
        await this.client.setEx(key, ttl, value);
      } else {
        await this.client.set(key, value);
      }
    } catch (error) {
      this.logger.error(`Error setting key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Delete a key from Redis.
   * 
   * @param {string} key - The Redis key to delete
   * 
   * @example
   * await redisService.del('session:user:123');
   */
  async del(key: string): Promise<void> {
    try {
      await this.client.del(key);
    } catch (error) {
      this.logger.error(`Error deleting key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Check if a key exists in Redis.
   * 
   * @param {string} key - The Redis key to check
   * @returns {Promise<boolean>} True if key exists, false otherwise
   * 
   * @example
   * const exists = await redisService.exists('cache:user:123');
   */
  async exists(key: string): Promise<boolean> {
    try {
      const result = await this.client.exists(key);
      return result === 1;
    } catch (error) {
      this.logger.error(`Error checking existence of key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Set expiration time for a key in seconds.
   * 
   * @param {string} key - The Redis key to set expiration for
   * @param {number} seconds - Expiration time in seconds
   * 
   * @example
   * await redisService.expire('cache:data', 3600); // Expire in 1 hour
   */
  async expire(key: string, seconds: number): Promise<void> {
    try {
      await this.client.expire(key, seconds);
    } catch (error) {
      this.logger.error(`Error setting expiration for key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Get the remaining time to live for a key in seconds.
   * 
   * @param {string} key - The Redis key to check
   * @returns {Promise<number>} Remaining TTL in seconds, -1 if no expiration, -2 if key doesn't exist
   * 
   * @example
   * const remainingTime = await redisService.ttl('cache:data');
   */
  async ttl(key: string): Promise<number> {
    try {
      return await this.client.ttl(key);
    } catch (error) {
      this.logger.error(`Error getting TTL for key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Find keys matching a pattern.
   * WARNING: Use sparingly in production as this can be expensive on large datasets.
   * 
   * @param {string} pattern - Pattern to match (e.g., 'cache:*')
   * @returns {Promise<string[]>} Array of matching keys
   * 
   * @example
   * const sessionKeys = await redisService.keys('session:*');
   */
  async keys(pattern: string): Promise<string[]> {
    try {
      this.logger.debug(`Scanning keys with pattern: ${pattern}`);
      return await this.client.keys(pattern);
    } catch (error) {
      this.logger.error(`Error scanning keys with pattern ${pattern}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Clear all keys in the current database.
   * WARNING: Use only in development/testing environments.
   * 
   * @example
   * await redisService.flushdb(); // Clear all keys
   */
  async flushdb(): Promise<void> {
    try {
      const nodeEnv = this.configService.get<string>('NODE_ENV', 'development');
      if (nodeEnv === 'production') {
        throw new Error('flushdb() is not allowed in production environment');
      }
      await this.client.flushDb();
      this.logger.warn('Redis database flushed');
    } catch (error) {
      this.logger.error(`Error flushing database: ${error.message}`);
      throw error;
    }
  }

  /**
   * Build a prefixed key for organized namespace management.
   * 
   * @param {string} prefix - The prefix namespace
   * @param {string} key - The key within the namespace
   * @returns {string} Formatted key with prefix
   * 
   * @example
   * const key = redisService.buildKey('cache', 'user:123'); // 'cache:user:123'
   */
  buildKey(prefix: string, key: string): string {
    return `${prefix}:${key}`;
  }

  /**
   * Get a session key for user session storage.
   * 
   * @param {string} userId - The user ID
   * @returns {string} Formatted session key
   * 
   * @example
   * const key = redisService.getSessionKey('user-123'); // 'session:user-123'
   */
  getSessionKey(userId: string): string {
    return this.buildKey('session', userId);
  }

  /**
   * Get a cache key for application data caching.
   * 
   * @param {string} resource - The resource identifier
   * @returns {string} Formatted cache key
   * 
   * @example
   * const key = redisService.getCacheKey('documents:list'); // 'cache:documents:list'
   */
  getCacheKey(resource: string): string {
    return this.buildKey('cache', resource);
  }

  /**
   * Get a rate limit key for API rate limiting.
   * 
   * @param {string} accountId - The account ID
   * @param {string} endpoint - The API endpoint
   * @returns {string} Formatted rate limit key
   * 
   * @example
   * const key = redisService.getRateLimitKey('acc-123', '/api/v1/documents');
   * // 'rate_limit:acc-123:/api/v1/documents'
   */
  getRateLimitKey(accountId: string, endpoint: string): string {
    return `rate_limit:${accountId}:${endpoint}`;
  }

  /**
   * Get a job key for processing job state tracking.
   * 
   * @param {string} jobId - The job ID
   * @returns {string} Formatted job key
   * 
   * @example
   * const key = redisService.getJobKey('job-123'); // 'job:job-123'
   */
  getJobKey(jobId: string): string {
    return this.buildKey('job', jobId);
  }

  /**
   * Atomically increment a counter. Useful for rate limiting.
   * 
   * @param {string} key - The Redis key to increment
   * @returns {Promise<number>} The new value after increment
   * 
   * @example
   * const count = await redisService.increment('rate_limit:user:123:/api/documents');
   */
  async increment(key: string): Promise<number> {
    try {
      return await this.client.incr(key);
    } catch (error) {
      this.logger.error(`Error incrementing key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Set a value with expiration time in seconds.
   * 
   * @param {string} key - The Redis key to set
   * @param {number} seconds - Expiration time in seconds
   * @param {string} value - The value to store
   * 
   * @example
   * await redisService.setex('temp:data', 300, 'value'); // Expires in 5 minutes
   */
  async setex(key: string, seconds: number, value: string): Promise<void> {
    try {
      await this.client.setEx(key, seconds, value);
    } catch (error) {
      this.logger.error(`Error setting key ${key} with expiration: ${error.message}`);
      throw error;
    }
  }

  /**
   * Store a JSON object in Redis with automatic serialization.
   * 
   * @param {string} key - The Redis key to set
   * @param {any} value - The object to serialize and store
   * @param {number} [ttl] - Optional expiration time in seconds
   * 
   * @example
   * await redisService.setJson('cache:user:123', { name: 'John', role: 'admin' }, 3600);
   */
  async setJson(key: string, value: any, ttl?: number): Promise<void> {
    try {
      const serialized = JSON.stringify(value);
      await this.set(key, serialized, ttl);
    } catch (error) {
      this.logger.error(`Error setting JSON for key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Retrieve and deserialize a JSON object from Redis.
   * 
   * @param {string} key - The Redis key to retrieve
   * @returns {Promise<T | null>} The deserialized object or null if not found
   * 
   * @example
   * const user = await redisService.getJson<User>('cache:user:123');
   */
  async getJson<T>(key: string): Promise<T | null> {
    try {
      const value = await this.get(key);
      if (!value) {
        return null;
      }
      return JSON.parse(value) as T;
    } catch (error) {
      this.logger.error(`Error getting JSON for key ${key}: ${error.message}`);
      throw error;
    }
  }

  /**
   * Get the raw Redis client for advanced operations not covered by helper methods.
   * Use with caution and prefer helper methods when available.
   * 
   * @returns {RedisClientType} The Redis client instance
   * 
   * @example
   * const client = redisService.getClient();
   * await client.hSet('hash:key', 'field', 'value');
   */
  getClient(): RedisClientType {
    return this.client;
  }

  /**
   * Health check method to verify Redis connection.
   * 
   * @returns {Promise<boolean>} True if connection is healthy, false otherwise
   * 
   * @example
   * const isHealthy = await redisService.ping();
   */
  async ping(): Promise<boolean> {
    try {
      const result = await this.client.ping();
      return result === 'PONG';
    } catch (error) {
      this.logger.error(`Redis ping failed: ${error.message}`);
      return false;
    }
  }
}

/**
 * Redis Module providing global Redis connectivity across the application.
 * 
 * This module is marked as @Global() to make RedisService available
 * application-wide without requiring imports in every module.
 * 
 * Environment Variables Required:
 * - REDIS_HOST: Redis server host (default: 'localhost')
 * - REDIS_PORT: Redis server port (default: 6379)
 * - REDIS_PASSWORD: Redis authentication password (optional, required in production)
 * - REDIS_DB: Redis database number (default: 0)
 * 
 * @module RedisModule
 */
@Global()
@Module({
  providers: [RedisService],
  exports: [RedisService],
})
export class RedisModule {}
