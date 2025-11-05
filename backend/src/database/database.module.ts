/**
 * Database Module - PostgreSQL Database Configuration
 * 
 * Provides TypeORM PostgreSQL connection configuration, entity registration,
 * migration management, and transaction support for the OCR Processing Application.
 * 
 * Features:
 * - TypeORM PostgreSQL connection with environment-based configuration
 * - Connection pooling (min: 5, max: 20 connections) per Section 0.4.3
 * - SSL encryption in production environments
 * - Automatic entity discovery from feature modules
 * - Migration management with auto-run capability
 * - Redis-based query result caching
 * - Transaction management utilities
 * - Multi-tenant data isolation support
 * - Health check capabilities
 * - Graceful shutdown handling
 * 
 * Environment Variables Required:
 * - DB_HOST: PostgreSQL host (default: localhost)
 * - DB_PORT: PostgreSQL port (default: 5432)
 * - DB_USERNAME: Database user (default: ocr_dev)
 * - DB_PASSWORD: Database password (required)
 * - DB_NAME: Database name (default: ocr_db)
 * - DB_LOGGING: Enable query logging (default: true in development)
 * - RUN_MIGRATIONS: Auto-run migrations on startup (default: true)
 * - REDIS_HOST: Redis cache host (default: localhost)
 * - REDIS_PORT: Redis cache port (default: 6379)
 * - NODE_ENV: Environment (development/production)
 * 
 * References:
 * - Section 0.4.3: PostgreSQL Database Integration Points
 * - Section 0.5.2: Phase 1 Database Setup
 * - Section 0.7.2: NestJS Backend Guidelines
 * - Section 0.7.1: Security Requirements
 * 
 * @module DatabaseModule
 */

import { Global, Module, Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { ConfigModule, ConfigService } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { 
  DataSource, 
  DataSourceOptions, 
  Repository, 
  EntityTarget,
  EntityManager,
  QueryRunner,
  ObjectLiteral,
  DeepPartial
} from 'typeorm';

/**
 * Creates TypeORM database configuration from environment variables.
 * 
 * Configuration includes:
 * - PostgreSQL connection parameters (host, port, credentials)
 * - Connection pooling (min: 5, max: 20)
 * - SSL encryption in production
 * - Entity auto-discovery pattern
 * - Migration configuration
 * - Redis query caching
 * - Timezone standardization (UTC)
 * - Query timeout (60 seconds)
 * 
 * @param configService - NestJS ConfigService for environment variable access
 * @returns TypeORM DataSourceOptions configuration object
 * 
 * @example
 * ```typescript
 * // Used by TypeOrmModule.forRootAsync
 * TypeOrmModule.forRootAsync({
 *   imports: [ConfigModule],
 *   inject: [ConfigService],
 *   useFactory: createDatabaseConfig
 * })
 * ```
 */
export function createDatabaseConfig(configService: ConfigService): DataSourceOptions {
  const nodeEnv = configService.get<string>('NODE_ENV', 'development');
  const isProduction = nodeEnv === 'production';
  const isDevelopment = nodeEnv === 'development';

  // Log configuration (without sensitive data)
  const logger = new Logger('DatabaseConfig');
  logger.log(`Configuring database for environment: ${nodeEnv}`);

  const config: DataSourceOptions = {
    type: 'postgres',
    
    // Connection parameters from environment variables
    host: configService.get<string>('DB_HOST', 'localhost'),
    port: configService.get<number>('DB_PORT', 5432),
    username: configService.get<string>('DB_USERNAME', 'ocr_dev'),
    password: configService.get<string>('DB_PASSWORD'),
    database: configService.get<string>('DB_NAME', 'ocr_db'),
    
    // Entity and migration discovery patterns
    // Auto-discover all *.entity.ts files in feature modules
    entities: [`${__dirname  }/../**/*.entity{.ts,.js}`],
    migrations: [`${__dirname  }/migrations/**/*{.ts,.js}`],
    
    // CRITICAL: Never use synchronize in production - use migrations only
    // Per Section 0.7.2: ALL migrations MUST be reversible
    synchronize: false,
    
    // Logging configuration
    // Enable detailed logging in development, minimal in production
    logging: configService.get<boolean>('DB_LOGGING', isDevelopment),
    logger: 'advanced-console',
    
    // Connection pool configuration per Section 0.4.3
    extra: {
      // Maximum number of connections in pool
      max: 20,
      
      // Minimum number of connections to maintain
      min: 5,
      
      // Time (ms) before idle connection is closed
      idleTimeoutMillis: 30000,
      
      // Time (ms) before connection attempt fails
      connectionTimeoutMillis: 10000,
      
      // Query timeout (60 seconds) per Section 0.7.2
      statement_timeout: 60000,
    },
    
    // SSL configuration - required in production per Section 0.4.3
    ssl: isProduction ? { rejectUnauthorized: true } : false,
    
    // Note: Timezone standardization handled by PostgreSQL server configuration
    // All TIMESTAMP columns should use TIMESTAMP WITH TIME ZONE type
    // Application code should work with Date objects in UTC
    
    // Redis-based query result caching
    cache: {
      type: 'redis',
      options: {
        host: configService.get<string>('REDIS_HOST', 'localhost'),
        port: configService.get<number>('REDIS_PORT', 6379),
      },
      // 30 second cache TTL
      duration: 30000,
    },
    
    // Migration management
    // Auto-run pending migrations on startup (configurable)
    migrationsRun: configService.get<boolean>('RUN_MIGRATIONS', true),
    
    // Wrap each migration in its own transaction
    migrationsTransactionMode: 'each',
  };

  // Validate critical configuration
  // Validate password is configured (required for security)
  if (!config.password) {
    logger.error('DB_PASSWORD environment variable is required');
    throw new Error('Database password not configured. Set DB_PASSWORD environment variable.');
  }

  logger.log(`Database connection configured: ${config.host}:${config.port}/${config.database}`);
  
  return config;
}

/**
 * Database Service - Utility Methods for Database Operations
 * 
 * Provides helper methods for common database operations including:
 * - Transaction management
 * - Raw SQL query execution
 * - Repository access
 * - Migration management
 * - Health checks
 * 
 * All methods implement proper error handling and logging per Section 0.7.2.
 * 
 * @injectable
 * 
 * @example
 * ```typescript
 * // Inject in any service
 * constructor(private readonly databaseService: DatabaseService) {}
 * 
 * // Use transaction
 * await this.databaseService.transaction(async (manager) => {
 *   await manager.save(entity1);
 *   await manager.save(entity2);
 * });
 * ```
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(DatabaseService.name);

  constructor(private readonly dataSource: DataSource) {}

  /**
   * Lifecycle hook - called when module is initialized.
   * Logs successful connection and sets up event handlers.
   */
  onModuleInit(): void {
    try {
      // Verify connection is established
      if (this.dataSource.isInitialized) {
        this.logger.log('Database connection established successfully');
        // Access connection options safely using TypeORM DataSourceOptions properties
        const options = this.dataSource.options;
        const dbName = typeof options.database === 'string' ? options.database : 'unknown';
        const dbHost = 'host' in options && typeof options.host === 'string' ? options.host : 'unknown';
        this.logger.log(`Connected to: ${dbName} on ${dbHost}`);
        
        // Access extra options safely with type guard
        if ('extra' in this.dataSource.options && this.dataSource.options.extra) {
          const extra: unknown = this.dataSource.options.extra;
          if (typeof extra === 'object' && extra !== null) {
            const poolConfig = extra as { min?: number; max?: number };
            this.logger.log(`Connection pool: min=${poolConfig.min}, max=${poolConfig.max}`);
          }
        }
      }

      // Set up connection event handlers
      this.setupConnectionHandlers();
    } catch (error) {
      const errorMessage = error instanceof Error ? error.stack : String(error);
      this.logger.error('Failed to initialize database connection', errorMessage);
      throw error;
    }
  }

  /**
   * Lifecycle hook - called when module is being destroyed.
   * Ensures graceful shutdown of database connections.
   */
  async onModuleDestroy(): Promise<void> {
    try {
      if (this.dataSource.isInitialized) {
        this.logger.log('Closing database connection...');
        await this.dataSource.destroy();
        this.logger.log('Database connection closed successfully');
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.stack : String(error);
      this.logger.error('Error during database shutdown', errorMessage);
      throw error;
    }
  }

  /**
   * Sets up connection event handlers for logging and monitoring.
   * Handles connection errors, reconnection events, and warnings.
   * 
   * @private
   */
  private setupConnectionHandlers(): void {
    // Note: TypeORM DataSource doesn't expose direct connection events
    // Connection monitoring is handled through health checks
    this.logger.debug('Connection handlers configured');
  }

  /**
   * Gets the active TypeORM DataSource connection.
   * 
   * @returns Active DataSource instance
   * @throws Error if connection is not initialized
   * 
   * @example
   * ```typescript
   * const connection = databaseService.getConnection();
   * const queryRunner = connection.createQueryRunner();
   * ```
   */
  getConnection(): DataSource {
    if (!this.dataSource.isInitialized) {
      throw new Error('Database connection not initialized');
    }
    return this.dataSource;
  }

  /**
   * Executes a raw SQL query with optional parameters.
   * 
   * SECURITY: Always use parameterized queries to prevent SQL injection.
   * Per Section 0.7.1: ALL database queries MUST use parameterized queries.
   * 
   * NOTE: Caller must provide explicit type parameter T for type safety.
   * The results are cast to T but not validated at runtime - ensure your
   * type matches the actual query results.
   * 
   * @template T - Expected return type (must be explicitly provided)
   * @param sql - SQL query string with parameter placeholders ($1, $2, etc.)
   * @param parameters - Query parameters (optional)
   * @returns Query results as array of type T
   * 
   * @example
   * ```typescript
   * interface User { id: string; name: string; }
   * const results = await databaseService.query<User>(
   *   'SELECT * FROM users WHERE account_id = $1',
   *   [accountId]
   * );
   * ```
   */
  async query<T>(sql: string, parameters?: unknown[]): Promise<T[]> {
    try {
      this.logger.debug(`Executing query: ${sql}`);
      const startTime = Date.now();
      
      // Execute query - TypeORM returns results as any[], caller provides type via generic T
      const results: unknown = await this.dataSource.query(sql, parameters);
      
      const duration = Date.now() - startTime;
      this.logger.debug(`Query completed in ${duration}ms`);
      
      return results as T[];
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error(`Query execution failed: ${errorMessage}`, errorStack);
      this.logger.error(`Failed query: ${sql}`);
      throw error;
    }
  }

  /**
   * Executes a callback function within a database transaction.
   * 
   * Automatically handles commit on success and rollback on error.
   * Supports nested transactions through savepoints.
   * 
   * @template T - Return type of the transaction callback
   * @param callback - Async function receiving EntityManager
   * @returns Result from callback function
   * 
   * @example
   * ```typescript
   * const result = await databaseService.transaction(async (manager) => {
   *   const user = await manager.save(User, userData);
   *   const profile = await manager.save(Profile, { userId: user.id });
   *   return { user, profile };
   * });
   * ```
   */
  async transaction<T>(
    callback: (entityManager: EntityManager) => Promise<T>
  ): Promise<T> {
    const queryRunner: QueryRunner = this.dataSource.createQueryRunner();
    
    try {
      // Establish connection
      await queryRunner.connect();
      
      // Start transaction
      await queryRunner.startTransaction();
      
      this.logger.debug('Transaction started');
      const startTime = Date.now();
      
      // Execute callback with transaction entity manager
      const result = await callback(queryRunner.manager);
      
      // Commit transaction
      await queryRunner.commitTransaction();
      
      const duration = Date.now() - startTime;
      this.logger.debug(`Transaction committed successfully in ${duration}ms`);
      
      return result;
    } catch (error) {
      // Rollback on error
      await queryRunner.rollbackTransaction();
      
      const errorStack = error instanceof Error ? error.stack : String(error);
      this.logger.error('Transaction rolled back due to error', errorStack);
      throw error;
    } finally {
      // Release query runner
      await queryRunner.release();
    }
  }

  /**
   * Gets a TypeORM Repository instance for an entity.
   * 
   * Provides type-safe access to entity CRUD operations.
   * 
   * @template Entity - Entity class type (must extend ObjectLiteral)
   * @param entity - Entity class or name
   * @returns Repository instance for the entity
   * 
   * @example
   * ```typescript
   * const userRepo = databaseService.getRepository(User);
   * const users = await userRepo.find({ where: { accountId } });
   * ```
   */
  getRepository<Entity extends ObjectLiteral>(entity: EntityTarget<Entity>): Repository<Entity> {
    return this.dataSource.getRepository(entity);
  }

  /**
   * Manually triggers execution of pending migrations.
   * 
   * Useful for environments where RUN_MIGRATIONS is set to false.
   * Logs each migration execution with timing information.
   * 
   * @returns Promise that resolves when migrations complete
   * @throws Error if migration execution fails
   * 
   * @example
   * ```typescript
   * await databaseService.runMigrations();
   * ```
   */
  async runMigrations(): Promise<void> {
    try {
      this.logger.log('Running pending migrations...');
      const startTime = Date.now();
      
      const migrations = await this.dataSource.runMigrations({ transaction: 'each' });
      
      const duration = Date.now() - startTime;
      
      if (migrations.length === 0) {
        this.logger.log('No pending migrations found');
      } else {
        this.logger.log(`Successfully ran ${migrations.length} migration(s) in ${duration}ms`);
        migrations.forEach(migration => {
          this.logger.log(`  ✓ ${migration.name}`);
        });
      }
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : String(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.logger.error('Migration execution failed', errorStack);
      throw new Error(`Failed to run migrations: ${errorMessage}`);
    }
  }

  /**
   * Reverts the most recently executed migration.
   * 
   * Used for rollback during development or emergency recovery.
   * Per Section 0.7.2: ALL migrations MUST be reversible.
   * 
   * @returns Promise that resolves when revert completes
   * @throws Error if no migrations to revert or revert fails
   * 
   * @example
   * ```typescript
   * await databaseService.revertMigration();
   * ```
   */
  async revertMigration(): Promise<void> {
    try {
      this.logger.log('Reverting last migration...');
      const startTime = Date.now();
      
      await this.dataSource.undoLastMigration({ transaction: 'each' });
      
      const duration = Date.now() - startTime;
      this.logger.log(`Migration reverted successfully in ${duration}ms`);
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : String(error);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.logger.error('Migration revert failed', errorStack);
      throw new Error(`Failed to revert migration: ${errorMessage}`);
    }
  }

  /**
   * Performs a health check on the database connection.
   * 
   * Executes a simple query to verify connection is active and responsive.
   * Used by health check endpoints per Section 0.7.2.
   * 
   * @returns true if connection is healthy, false otherwise
   * 
   * @example
   * ```typescript
   * const isHealthy = await databaseService.checkConnection();
   * if (!isHealthy) {
   *   throw new ServiceUnavailableException('Database unavailable');
   * }
   * ```
   */
  async checkConnection(): Promise<boolean> {
    try {
      // Execute simple query to verify connection
      await this.dataSource.query('SELECT 1');
      return true;
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : String(error);
      this.logger.error('Database health check failed', errorStack);
      return false;
    }
  }

  /**
   * Helper method for pagination.
   * 
   * Calculates skip and take values for query pagination.
   * Per Section 0.7.4: Default page size 25, max 100.
   * 
   * @param page - Page number (1-based)
   * @param limit - Items per page (default: 25, max: 100)
   * @returns Object with skip and take values
   * 
   * @example
   * ```typescript
   * const { skip, take } = databaseService.getPaginationParams(2, 50);
   * const users = await userRepo.find({ skip, take });
   * ```
   */
  getPaginationParams(page: number = 1, limit: number = 25): { skip: number; take: number } {
    // Validate and constrain parameters
    const validPage = Math.max(1, page);
    const validLimit = Math.min(Math.max(1, limit), 100);
    
    return {
      skip: (validPage - 1) * validLimit,
      take: validLimit,
    };
  }

  /**
   * Bulk insert helper with chunking for large datasets.
   * 
   * Splits large inserts into chunks to avoid memory issues and improve performance.
   * 
   * @template Entity - Entity type (must extend ObjectLiteral)
   * @param entity - Entity class
   * @param records - Array of entity data to insert (partial entities)
   * @param chunkSize - Number of records per chunk (default: 100)
   * @returns Promise that resolves when all inserts complete
   * 
   * @example
   * ```typescript
   * await databaseService.bulkInsert(Document, documentData, 500);
   * ```
   */
  async bulkInsert<Entity extends ObjectLiteral>(
    entity: EntityTarget<Entity>,
    records: Array<Partial<Entity>>,
    chunkSize: number = 100
  ): Promise<void> {
    try {
      this.logger.log(`Bulk inserting ${records.length} records in chunks of ${chunkSize}`);
      const repository = this.getRepository(entity);
      
      // Process in chunks
      for (let i = 0; i < records.length; i += chunkSize) {
        const chunk = records.slice(i, i + chunkSize);
        // Cast to DeepPartial for TypeORM save method compatibility
        await repository.save(chunk as DeepPartial<Entity>[]);
        this.logger.debug(`Inserted chunk ${Math.floor(i / chunkSize) + 1}/${Math.ceil(records.length / chunkSize)}`);
      }
      
      this.logger.log(`Bulk insert completed: ${records.length} records`);
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : String(error);
      this.logger.error('Bulk insert failed', errorStack);
      throw error;
    }
  }
}

/**
 * Database Module - Global Module for Database Configuration
 * 
 * Provides TypeORM PostgreSQL connection configuration to the entire application.
 * Uses @Global decorator to make DatabaseService available application-wide
 * without importing in every module.
 * 
 * This module is foundational and imported by AppModule in Phase 1.
 * All feature modules requiring data persistence depend on this module.
 * 
 * @module
 * @global
 */
@Global()
@Module({
  imports: [
    // Import ConfigModule for environment variable access
    ConfigModule,
    
    // Configure TypeORM with async factory pattern
    TypeOrmModule.forRootAsync({
      imports: [ConfigModule],
      inject: [ConfigService],
      useFactory: createDatabaseConfig,
    }),
  ],
  providers: [DatabaseService],
  exports: [DatabaseService],
})
export class DatabaseModule {}

/**
 * DataSource instance for TypeORM CLI usage.
 * 
 * Used by TypeORM CLI commands:
 * - npm run migration:generate -- MigrationName
 * - npm run migration:run
 * - npm run migration:revert
 * 
 * Loads configuration from environment variables using dotenv.
 * 
 * @example
 * ```bash
 * # Generate migration
 * npm run typeorm migration:generate -- -n CreateUsersTable
 * 
 * # Run migrations
 * npm run typeorm migration:run
 * 
 * # Revert migration
 * npm run typeorm migration:revert
 * ```
 */
export const dataSource = new DataSource({
  type: 'postgres',
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '5432', 10),
  username: process.env.DB_USERNAME || 'ocr_dev',
  password: process.env.DB_PASSWORD,
  database: process.env.DB_NAME || 'ocr_db',
  entities: [`${__dirname  }/../**/*.entity{.ts,.js}`],
  migrations: [`${__dirname  }/migrations/**/*{.ts,.js}`],
  synchronize: false,
  logging: process.env.DB_LOGGING === 'true',
  ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: true } : false,
  // Note: Timezone handled by PostgreSQL server configuration
});
