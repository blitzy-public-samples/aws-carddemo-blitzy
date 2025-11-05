import { Table, TableForeignKey, TableIndex } from 'typeorm';

import type { MigrationInterface, QueryRunner } from 'typeorm';

/**
 * Migration: CreateWebhooksTable010
 * 
 * Creates the webhooks table for storing webhook subscription configurations.
 * This table supports the webhook system that delivers event notifications to external endpoints
 * for events like document.uploaded, document.processed, document.approved, batch.completed, and template.created.
 * 
 * Features:
 * - UUID primary key for webhook subscriptions
 * - Foreign key relationship to accounts table with CASCADE delete
 * - Event type enumeration for supported webhook events
 * - Target URL and secret for secure webhook delivery with HMAC signatures
 * - Retry tracking with count and delivery status
 * - Timestamps in UTC for audit trail
 * - Indexes on account_id and event_type for query optimization
 * 
 * This migration is fully reversible via the down() method.
 * 
 * @implements {MigrationInterface}
 */
export class CreateWebhooksTable010 implements MigrationInterface {
  /**
   * Executes the forward migration to create the webhooks table.
   * 
   * Creates:
   * - webhooks table with all required columns
   * - Foreign key constraint to accounts table
   * - Indexes on account_id and event_type for efficient queries
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing SQL commands
   * @returns {Promise<void>}
   */
  public async up(queryRunner: QueryRunner): Promise<void> {
    // Create the webhooks table
    await queryRunner.createTable(
      new Table({
        name: 'webhooks',
        columns: [
          {
            name: 'id',
            type: 'uuid',
            isPrimary: true,
            isGenerated: true,
            generationStrategy: 'uuid',
            comment: 'Unique identifier for the webhook subscription',
          },
          {
            name: 'account_id',
            type: 'uuid',
            isNullable: false,
            comment: 'Foreign key reference to the accounts table for multi-tenant isolation',
          },
          {
            name: 'event_type',
            type: 'varchar',
            length: '100',
            isNullable: false,
            comment: 'Type of event that triggers this webhook (e.g., document.uploaded, document.processed, document.approved, batch.completed, template.created)',
          },
          {
            name: 'target_url',
            type: 'varchar',
            length: '2048',
            isNullable: false,
            comment: 'The HTTP endpoint URL where webhook events will be delivered via POST request',
          },
          {
            name: 'secret',
            type: 'varchar',
            length: '255',
            isNullable: false,
            comment: 'Secret key used for generating HMAC-SHA256 signature in X-Webhook-Signature header for webhook verification',
          },
          {
            name: 'is_active',
            type: 'boolean',
            default: true,
            isNullable: false,
            comment: 'Flag indicating whether this webhook subscription is active and should receive event notifications',
          },
          {
            name: 'retry_count',
            type: 'integer',
            default: 0,
            isNullable: false,
            comment: 'Number of retry attempts for failed webhook deliveries (max 3 attempts with exponential backoff)',
          },
          {
            name: 'last_delivery_at',
            type: 'timestamp',
            isNullable: true,
            comment: 'Timestamp of the last webhook delivery attempt in UTC',
          },
          {
            name: 'last_delivery_status',
            type: 'varchar',
            length: '50',
            isNullable: true,
            comment: 'Status of the last delivery attempt (success, failed, pending)',
          },
          {
            name: 'created_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Timestamp when the webhook subscription was created in UTC',
          },
          {
            name: 'updated_at',
            type: 'timestamp',
            default: 'CURRENT_TIMESTAMP',
            onUpdate: 'CURRENT_TIMESTAMP',
            isNullable: false,
            comment: 'Timestamp when the webhook subscription was last updated in UTC',
          },
        ],
      }),
      true, // ifNotExists - creates table only if it doesn't exist
    );

    // Create foreign key constraint to accounts table with CASCADE delete
    // When an account is deleted, all associated webhook subscriptions are automatically removed
    await queryRunner.createForeignKey(
      'webhooks',
      new TableForeignKey({
        name: 'FK_webhooks_account_id',
        columnNames: ['account_id'],
        referencedTableName: 'accounts',
        referencedColumnNames: ['id'],
        onDelete: 'CASCADE',
        onUpdate: 'CASCADE',
      }),
    );

    // Create index on account_id for efficient querying of webhooks by account
    // This optimizes queries that filter webhooks for a specific tenant account
    await queryRunner.createIndex(
      'webhooks',
      new TableIndex({
        name: 'IDX_webhooks_account_id',
        columnNames: ['account_id'],
      }),
    );

    // Create index on event_type for efficient querying of webhooks by event type
    // This optimizes queries that need to find all webhooks subscribed to a specific event
    await queryRunner.createIndex(
      'webhooks',
      new TableIndex({
        name: 'IDX_webhooks_event_type',
        columnNames: ['event_type'],
      }),
    );

    // Create composite index on (account_id, event_type) for efficient querying by both fields
    // This optimizes the common query pattern of finding webhooks for a specific account and event type
    await queryRunner.createIndex(
      'webhooks',
      new TableIndex({
        name: 'IDX_webhooks_account_id_event_type',
        columnNames: ['account_id', 'event_type'],
      }),
    );

    // Create index on is_active to optimize queries that filter for active webhooks only
    await queryRunner.createIndex(
      'webhooks',
      new TableIndex({
        name: 'IDX_webhooks_is_active',
        columnNames: ['is_active'],
      }),
    );
  }

  /**
   * Executes the rollback migration to drop the webhooks table.
   * 
   * Drops:
   * - All indexes on the webhooks table
   * - Foreign key constraint to accounts table
   * - webhooks table itself
   * 
   * This ensures the database can be reverted to its previous state if needed.
   * 
   * @param {QueryRunner} queryRunner - TypeORM query runner for executing SQL commands
   * @returns {Promise<void>}
   */
  public async down(queryRunner: QueryRunner): Promise<void> {
    // Drop indexes first (in reverse order of creation)
    await queryRunner.dropIndex('webhooks', 'IDX_webhooks_is_active');
    await queryRunner.dropIndex('webhooks', 'IDX_webhooks_account_id_event_type');
    await queryRunner.dropIndex('webhooks', 'IDX_webhooks_event_type');
    await queryRunner.dropIndex('webhooks', 'IDX_webhooks_account_id');

    // Drop foreign key constraint
    await queryRunner.dropForeignKey('webhooks', 'FK_webhooks_account_id');

    // Drop the webhooks table
    await queryRunner.dropTable('webhooks', true); // ifExists - drops table only if it exists
  }
}
