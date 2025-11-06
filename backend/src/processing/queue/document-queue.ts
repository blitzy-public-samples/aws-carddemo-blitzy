/**
 * Document Queue Service - RabbitMQ/AWS SQS Message Queue Producer
 * 
 * This service implements a comprehensive message queue producer for the OCR Processing Application.
 * It handles publishing OCR processing jobs to RabbitMQ with support for:
 * - Automatic connection management with retry logic
 * - Message persistence and dead letter queue configuration
 * - Batch job queuing for high-volume processing
 * - Priority scheduling for critical documents
 * - Graceful shutdown and resource cleanup
 * - Comprehensive error handling and logging
 * 
 * @module ProcessingQueue
 * @see Section 0.4.2 Backend API ↔ Queue System
 * @see Section 0.5.5 Phase 4 Group 4B - Queue and Worker System
 */

import { Injectable, Logger, OnModuleInit, OnModuleDestroy } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { connect, Connection, Channel, Options } from 'amqplib';

/**
 * Queue configuration constants
 */
const QUEUE_NAME = 'ocr_processing_queue';
const EXCHANGE_NAME = 'document_processing_exchange';
const EXCHANGE_TYPE = 'topic';
const ROUTING_KEY = 'document.process';
const DLQ_NAME = 'ocr_processing_dlq';
const DLQ_EXCHANGE = 'document_processing_dlq_exchange';
const DLQ_ROUTING_KEY = 'document.dlq';
const MAX_RETRY_ATTEMPTS = 3;
const CONNECTION_RETRY_DELAY = 5000; // milliseconds
const PREFETCH_COUNT = 10;

/**
 * Job priority levels for document processing
 * Higher numeric values indicate higher priority
 */
export enum JobPriority {
  LOW = 0,
  NORMAL = 5,
  HIGH = 10,
  CRITICAL = 15
}

/**
 * Message interface for individual document processing jobs
 */
export interface JobMessage {
  /** Unique identifier for the processing job */
  jobId: string;
  /** Document identifier */
  documentId: string;
  /** Account identifier for multi-tenant isolation */
  accountId: string;
  /** User identifier who initiated the job */
  userId: string;
  /** Optional template ID for custom extraction */
  templateId?: string;
  /** Job priority level (defaults to NORMAL) */
  priority?: JobPriority;
  /** Job creation timestamp */
  createdAt: Date;
  /** Additional metadata for the job */
  metadata?: Record<string, any>;
}

/**
 * Message interface for batch document processing jobs
 */
export interface BatchJobMessage {
  /** Unique identifier for the batch */
  batchId: string;
  /** Array of job messages in the batch */
  jobMessages: JobMessage[];
  /** Batch creation timestamp */
  createdAt: Date;
  /** Additional metadata for the batch */
  metadata?: Record<string, any>;
}

/**
 * Custom error for queue connection failures
 */
export class QueueConnectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'QueueConnectionError';
    Object.setPrototypeOf(this, QueueConnectionError.prototype);
  }
}

/**
 * Custom error for message publishing failures
 */
export class QueuePublishError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'QueuePublishError';
    Object.setPrototypeOf(this, QueuePublishError.prototype);
  }
}

/**
 * DocumentQueue Service
 * 
 * Manages RabbitMQ/SQS connection and message publishing for OCR processing jobs.
 * Implements automatic reconnection, batch processing, and comprehensive error handling.
 * 
 * @implements {OnModuleInit}
 * @implements {OnModuleDestroy}
 */
@Injectable()
export class DocumentQueue implements OnModuleInit, OnModuleDestroy {
  private readonly logger: Logger;
  private connection: Connection | null = null;
  private channel: Channel | null = null;
  private readonly queueType: string;
  private readonly rabbitmqUrl: string;
  private reconnectAttempts = 0;
  private isShuttingDown = false;

  // Metrics tracking
  private metrics = {
    totalPublished: 0,
    totalFailed: 0,
    connectionFailures: 0,
    lastPublishTime: null as Date | null,
  };

  /**
   * Constructs the DocumentQueue service
   * 
   * @param {ConfigService} configService - NestJS configuration service for environment variables
   */
  constructor(private readonly configService: ConfigService) {
    this.logger = new Logger('DocumentQueue');
    
    // Retrieve configuration from environment variables
    this.queueType = this.configService.get<string>('QUEUE_TYPE', 'rabbitmq');
    
    const username = this.configService.get<string>('RABBITMQ_USERNAME', 'guest');
    const password = this.configService.get<string>('RABBITMQ_PASSWORD', 'guest');
    const host = this.configService.get<string>('RABBITMQ_HOST', 'localhost');
    const port = this.configService.get<string>('RABBITMQ_PORT', '5672');
    
    // Build RabbitMQ connection URL
    this.rabbitmqUrl = `amqp://${username}:${password}@${host}:${port}`;
  }

  /**
   * Lifecycle hook called when the module is initialized
   * Establishes connection to RabbitMQ and sets up queues
   * 
   * @throws {QueueConnectionError} If connection cannot be established after retries
   */
  async onModuleInit(): Promise<void> {
    try {
      this.logger.log('Initializing DocumentQueue service...');
      
      if (this.queueType === 'rabbitmq') {
        await this.connectToRabbitMQ();
        this.logger.log('DocumentQueue service initialized successfully');
      } else if (this.queueType === 'sqs') {
        this.logger.warn('SQS queue type detected - feature not yet implemented');
        // TODO: Implement AWS SQS connection in future enhancement
      } else {
        throw new QueueConnectionError(`Unsupported queue type: ${this.queueType}`);
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error('Failed to initialize DocumentQueue service', errorStack);
      throw new QueueConnectionError(`Module initialization failed: ${errorMessage}`);
    }
  }

  /**
   * Lifecycle hook called when the module is being destroyed
   * Performs graceful shutdown of RabbitMQ connection
   */
  async onModuleDestroy(): Promise<void> {
    try {
      this.logger.log('Shutting down DocumentQueue service...');
      this.isShuttingDown = true;

      // Close channel gracefully
      if (this.channel) {
        await this.channel.close();
        this.logger.log('RabbitMQ channel closed');
        this.channel = null;
      }

      // Close connection gracefully
      if (this.connection) {
        await this.connection.close();
        this.logger.log('RabbitMQ connection closed');
        this.connection = null;
      }

      this.logger.log('DocumentQueue service shutdown complete');
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error('Error during DocumentQueue shutdown', errorStack);
      // Force cleanup even on error
      this.channel = null;
      this.connection = null;
    }
  }

  /**
   * Establishes connection to RabbitMQ and sets up exchange and queues
   * Implements retry logic with exponential backoff
   * 
   * @private
   * @throws {QueueConnectionError} If connection fails after max retry attempts
   */
  private async connectToRabbitMQ(): Promise<void> {
    try {
      this.logger.log(`Connecting to RabbitMQ at ${this.rabbitmqUrl.replace(/:[^:@]+@/, ':****@')}...`);

      // Create connection with error handling
      this.connection = await connect(this.rabbitmqUrl);
      
      // Set up connection error handlers
      this.connection.on('error', (err) => {
        if (!this.isShuttingDown) {
          this.logger.error('RabbitMQ connection error', err);
          this.metrics.connectionFailures++;
          this.reconnect().catch(e => this.logger.error('Reconnection failed', e));
        }
      });

      this.connection.on('close', () => {
        if (!this.isShuttingDown) {
          this.logger.warn('RabbitMQ connection closed unexpectedly');
          this.reconnect().catch(e => this.logger.error('Reconnection failed', e));
        }
      });

      // Create channel with prefetch configuration
      this.channel = await this.connection.createChannel();
      await this.channel.prefetch(PREFETCH_COUNT);

      // Assert exchange (topic, durable)
      await this.channel.assertExchange(EXCHANGE_NAME, EXCHANGE_TYPE, {
        durable: true,
      });

      // Assert main processing queue with DLQ configuration
      await this.channel.assertQueue(QUEUE_NAME, {
        durable: true,
        arguments: {
          'x-dead-letter-exchange': DLQ_EXCHANGE,
          'x-dead-letter-routing-key': DLQ_ROUTING_KEY,
        },
      });

      // Bind main queue to exchange
      await this.channel.bindQueue(QUEUE_NAME, EXCHANGE_NAME, ROUTING_KEY);

      // Assert Dead Letter Queue exchange and queue
      await this.channel.assertExchange(DLQ_EXCHANGE, 'direct', {
        durable: true,
      });

      await this.channel.assertQueue(DLQ_NAME, {
        durable: true,
      });

      await this.channel.bindQueue(DLQ_NAME, DLQ_EXCHANGE, DLQ_ROUTING_KEY);

      this.logger.log('RabbitMQ connection established successfully');
      this.logger.log(`Queue: ${QUEUE_NAME}, Exchange: ${EXCHANGE_NAME}, DLQ: ${DLQ_NAME}`);
      
      // Reset reconnect attempts on successful connection
      this.reconnectAttempts = 0;
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      this.logger.error('Failed to connect to RabbitMQ', errorStack);
      this.metrics.connectionFailures++;
      
      // Implement exponential backoff retry
      if (this.reconnectAttempts < MAX_RETRY_ATTEMPTS) {
        const delay = CONNECTION_RETRY_DELAY * Math.pow(2, this.reconnectAttempts);
        this.logger.log(`Retrying connection in ${delay}ms (attempt ${this.reconnectAttempts + 1}/${MAX_RETRY_ATTEMPTS})...`);
        this.reconnectAttempts++;
        
        await new Promise(resolve => setTimeout(resolve, delay));
        return this.connectToRabbitMQ();
      } else {
        throw new QueueConnectionError(`Failed to connect after ${MAX_RETRY_ATTEMPTS} attempts: ${errorMessage}`);
      }
    }
  }

  /**
   * Ensures connection and channel are valid, reconnects if needed
   * 
   * @private
   * @throws {QueueConnectionError} If reconnection fails
   */
  private async ensureConnection(): Promise<void> {
    if (!this.connection || !this.channel) {
      this.logger.warn('Connection or channel is null, attempting to reconnect...');
      await this.reconnect();
    }

    if (!this.connection || !this.channel) {
      throw new QueueConnectionError('Failed to establish connection');
    }
  }

  /**
   * Reconnects to RabbitMQ after connection loss
   * 
   * @private
   * @throws {QueueConnectionError} If reconnection fails after max attempts
   */
  private async reconnect(): Promise<void> {
    try {
      this.logger.log('Attempting to reconnect to RabbitMQ...');
      
      // Close existing connections
      try {
        if (this.channel) await this.channel.close();
      } catch (e) {
        // Ignore errors on close
      }
      
      try {
        if (this.connection) await this.connection.close();
      } catch (e) {
        // Ignore errors on close
      }

      this.channel = null;
      this.connection = null;

      // Wait before reconnecting
      await new Promise(resolve => setTimeout(resolve, CONNECTION_RETRY_DELAY));

      // Attempt to reconnect
      await this.connectToRabbitMQ();
      
      this.logger.log('Reconnection successful');
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      this.logger.error('Reconnection failed', errorStack);
      throw new QueueConnectionError(`Reconnection failed: ${errorMessage}`);
    }
  }

  /**
   * Publishes a single job message to the processing queue
   * 
   * @param {JobMessage} jobMessage - The job message to publish
   * @returns {Promise<boolean>} True if published successfully, false otherwise
   * @throws {QueuePublishError} If message validation fails
   * 
   * @example
   * ```typescript
   * const success = await documentQueue.publishJob({
   *   jobId: 'job-123',
   *   documentId: 'doc-456',
   *   accountId: 'acc-789',
   *   userId: 'user-001',
   *   priority: JobPriority.HIGH,
   *   createdAt: new Date(),
   * });
   * ```
   */
  async publishJob(jobMessage: JobMessage): Promise<boolean> {
    try {
      // Validate required fields
      if (!jobMessage.jobId || !jobMessage.documentId || !jobMessage.accountId) {
        throw new QueuePublishError('Missing required fields: jobId, documentId, or accountId');
      }

      // Ensure connection is valid
      await this.ensureConnection();

      // Set default priority if not specified
      const priority = jobMessage.priority ?? JobPriority.NORMAL;
      
      // Serialize message to JSON
      const messageContent = JSON.stringify({
        ...jobMessage,
        priority,
        publishedAt: new Date().toISOString(),
      });

      const messageBuffer = Buffer.from(messageContent);

      // Publish to channel with persistence and priority
      const published = this.channel!.publish(
        EXCHANGE_NAME,
        ROUTING_KEY,
        messageBuffer,
        {
          persistent: true,
          priority: priority,
          timestamp: Date.now(),
          contentType: 'application/json',
          messageId: jobMessage.jobId,
        } as Options.Publish
      );

      if (published) {
        this.metrics.totalPublished++;
        this.metrics.lastPublishTime = new Date();
        this.logger.log(`Published job ${jobMessage.jobId} with priority ${priority} for document ${jobMessage.documentId}`);
        return true;
      } else {
        this.logger.warn(`Failed to publish job ${jobMessage.jobId} - channel buffer full`);
        this.metrics.totalFailed++;
        return false;
      }
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error(`Error publishing job ${jobMessage.jobId}:`, errorStack);
      this.metrics.totalFailed++;
      
      // Attempt retry with exponential backoff
      if (error instanceof QueueConnectionError) {
        await this.reconnect();
        // Retry once after reconnection
        try {
          return await this.publishJob(jobMessage);
        } catch (retryError) {
          const retryErrorStack = retryError instanceof Error ? retryError.stack : undefined;
          this.logger.error('Retry failed after reconnection', retryErrorStack);
          return false;
        }
      }
      
      return false;
    }
  }

  /**
   * Publishes multiple job messages as a batch
   * 
   * @param {JobMessage[]} jobMessages - Array of job messages to publish
   * @returns {Promise<{success: number, failed: number}>} Count of successful and failed publishes
   * @throws {QueuePublishError} If jobMessages array is empty
   * 
   * @example
   * ```typescript
   * const result = await documentQueue.publishBatchJobs([
   *   { jobId: '1', documentId: 'd1', accountId: 'a1', userId: 'u1', createdAt: new Date() },
   *   { jobId: '2', documentId: 'd2', accountId: 'a1', userId: 'u1', createdAt: new Date() },
   * ]);
   * console.log(`Success: ${result.success}, Failed: ${result.failed}`);
   * ```
   */
  async publishBatchJobs(jobMessages: JobMessage[]): Promise<{ success: number; failed: number }> {
    try {
      // Validate array is not empty
      if (!jobMessages || jobMessages.length === 0) {
        throw new QueuePublishError('jobMessages array cannot be empty');
      }

      this.logger.log(`Publishing batch of ${jobMessages.length} jobs...`);

      // Ensure connection is valid
      await this.ensureConnection();

      let success = 0;
      let failed = 0;

      // Publish each message individually
      for (const jobMessage of jobMessages) {
        const published = await this.publishJob(jobMessage);
        if (published) {
          success++;
        } else {
          failed++;
        }
      }

      this.logger.log(`Batch publish complete: ${success} successful, ${failed} failed out of ${jobMessages.length} total`);

      return { success, failed };
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      const errorMessage = error instanceof Error ? error.message : 'Unknown error';
      this.logger.error('Error publishing batch jobs:', errorStack);
      throw new QueuePublishError(`Batch publish failed: ${errorMessage}`);
    }
  }

  /**
   * Gets the current depth (message count) of the processing queue
   * 
   * @returns {Promise<number>} Number of messages currently in the queue
   * 
   * @example
   * ```typescript
   * const depth = await documentQueue.getQueueDepth();
   * console.log(`Current queue depth: ${depth}`);
   * ```
   */
  async getQueueDepth(): Promise<number> {
    try {
      await this.ensureConnection();

      const queueInfo = await this.channel!.checkQueue(QUEUE_NAME);
      return queueInfo.messageCount;
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error('Error getting queue depth:', errorStack);
      return 0;
    }
  }

  /**
   * Purges all messages from the processing queue
   * WARNING: This operation is destructive and cannot be undone
   * 
   * @returns {Promise<boolean>} True if purge was successful, false otherwise
   * 
   * @example
   * ```typescript
   * const purged = await documentQueue.purgeQueue();
   * if (purged) console.log('Queue purged successfully');
   * ```
   */
  async purgeQueue(): Promise<boolean> {
    try {
      await this.ensureConnection();

      await this.channel!.purgeQueue(QUEUE_NAME);
      this.logger.warn(`Queue ${QUEUE_NAME} has been purged`);
      return true;
    } catch (error) {
      const errorStack = error instanceof Error ? error.stack : undefined;
      this.logger.error('Error purging queue:', errorStack);
      return false;
    }
  }

  /**
   * Gets current metrics for monitoring and observability
   * 
   * @returns {Object} Metrics object containing publish statistics
   * 
   * @example
   * ```typescript
   * const metrics = await documentQueue.getMetrics();
   * console.log(`Total published: ${metrics.totalPublished}`);
   * ```
   */
  async getMetrics(): Promise<{
    totalPublished: number;
    totalFailed: number;
    connectionFailures: number;
    lastPublishTime: Date | null;
    currentQueueDepth: number;
  }> {
    const currentQueueDepth = await this.getQueueDepth();

    return {
      ...this.metrics,
      currentQueueDepth,
    };
  }
}

