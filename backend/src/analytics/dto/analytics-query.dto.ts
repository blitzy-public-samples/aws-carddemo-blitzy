import { ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import {
  IsOptional,
  IsEnum,
  IsDateString,
  IsArray,
  IsString,
  IsInt,
  Min,
  Max,
  IsUUID,
} from 'class-validator';

/**
 * Enum defining available metrics for analytics queries
 * 
 * Each metric represents a specific business or operational KPI that can be
 * retrieved through the analytics API endpoints.
 */
export enum MetricType {
  /** Total number of documents processed over time period */
  PROCESSING_VOLUME = 'processing_volume',
  
  /** Percentage of OCR extractions with confidence score above threshold */
  ACCURACY_RATE = 'accuracy_rate',
  
  /** User engagement metrics including active users and session data */
  USER_ADOPTION = 'user_adoption',
  
  /** Average cost per document processed including OCR API costs */
  COST_PER_DOCUMENT = 'cost_per_document',
  
  /** Average time to process documents from upload to completion */
  PROCESSING_TIME = 'processing_time',
  
  /** Percentage of documents that failed processing or validation */
  ERROR_RATE = 'error_rate',
  
  /** Frequency and effectiveness of custom extraction templates */
  TEMPLATE_USAGE = 'template_usage',
  
  /** Activity and success rates for third-party integrations */
  INTEGRATION_ACTIVITY = 'integration_activity',
}

/**
 * Enum defining dimensions for grouping analytics results
 * 
 * Allows aggregation and segmentation of analytics data by various entities
 * and attributes for detailed insights.
 */
export enum GroupByDimension {
  /** Group by tenant account ID for multi-tenant isolation */
  ACCOUNT = 'account',
  
  /** Group by user ID to analyze per-user activity */
  USER = 'user',
  
  /** Group by document type (invoice, receipt, contract, form) */
  DOCUMENT_TYPE = 'document_type',
  
  /** Group by extraction template ID */
  TEMPLATE = 'template',
  
  /** Group by time period based on specified granularity */
  DATE = 'date',
}

/**
 * Enum defining time granularity options for trend analysis
 * 
 * Controls the time bucket size for aggregating time-series data
 * in analytics queries.
 */
export enum TimeGranularity {
  /** Hourly aggregation for real-time monitoring */
  HOUR = 'hour',
  
  /** Daily aggregation for standard reporting */
  DAY = 'day',
  
  /** Weekly aggregation for medium-term trends */
  WEEK = 'week',
  
  /** Monthly aggregation for business reporting */
  MONTH = 'month',
  
  /** Quarterly aggregation for executive summaries */
  QUARTER = 'quarter',
  
  /** Yearly aggregation for long-term analysis */
  YEAR = 'year',
}

/**
 * Data Transfer Object for analytics query parameters
 * 
 * Validates and types analytics API request parameters including:
 * - Date range filtering with ISO 8601 format validation
 * - Flexible metrics selection for targeted data retrieval
 * - Multi-dimensional grouping for aggregated insights
 * - Time granularity options for trend analysis
 * - Multi-tenant account isolation via accountId
 * - Entity-specific filtering (user, document type, template)
 * - Pagination support with configurable limits
 * 
 * All query parameters are optional, allowing flexible analytics queries
 * from simple aggregations to complex multi-dimensional analyses.
 * 
 * Security considerations:
 * - Account ID validation ensures multi-tenant data isolation
 * - UUID validation prevents SQL injection on identifier fields
 * - Date string validation prevents malformed date inputs
 * - Pagination limits prevent excessive resource consumption
 * 
 * @example
 * // Request processing volume and accuracy for invoices in January 2025
 * const query: AnalyticsQueryDto = {
 *   startDate: '2025-01-01T00:00:00Z',
 *   endDate: '2025-01-31T23:59:59Z',
 *   metrics: [MetricType.PROCESSING_VOLUME, MetricType.ACCURACY_RATE],
 *   groupBy: [GroupByDimension.DOCUMENT_TYPE],
 *   documentType: 'invoice',
 *   granularity: TimeGranularity.DAY,
 *   limit: 50
 * };
 * 
 * @example
 * // Request all metrics grouped by user with monthly granularity
 * const query: AnalyticsQueryDto = {
 *   startDate: '2025-01-01T00:00:00Z',
 *   endDate: '2025-12-31T23:59:59Z',
 *   metrics: Object.values(MetricType),
 *   groupBy: [GroupByDimension.USER, GroupByDimension.DATE],
 *   granularity: TimeGranularity.MONTH,
 *   accountId: '123e4567-e89b-12d3-a456-426614174000'
 * };
 */
export class AnalyticsQueryDto {
  /**
   * Start date for analytics query in ISO 8601 format
   * 
   * Defines the beginning of the time range for analytics data retrieval.
   * Must be in UTC timezone with ISO 8601 format (e.g., '2025-01-01T00:00:00Z').
   * If omitted, defaults to system-defined historical start date.
   */
  @ApiPropertyOptional({
    description: 'Start date for analytics query (ISO 8601)',
    example: '2025-01-01T00:00:00Z',
    type: String,
  })
  @IsOptional()
  @IsDateString()
  startDate?: string;

  /**
   * End date for analytics query in ISO 8601 format
   * 
   * Defines the end of the time range for analytics data retrieval.
   * Must be in UTC timezone with ISO 8601 format (e.g., '2025-01-31T23:59:59Z').
   * If omitted, defaults to current timestamp.
   */
  @ApiPropertyOptional({
    description: 'End date for analytics query (ISO 8601)',
    example: '2025-01-31T23:59:59Z',
    type: String,
  })
  @IsOptional()
  @IsDateString()
  endDate?: string;

  /**
   * Array of metrics to include in the analytics response
   * 
   * Allows selective retrieval of specific KPIs to optimize query performance
   * and reduce response payload size. If omitted, returns all available metrics.
   */
  @ApiPropertyOptional({
    description: 'Metrics to include in response',
    enum: MetricType,
    isArray: true,
    example: [MetricType.PROCESSING_VOLUME, MetricType.ACCURACY_RATE],
  })
  @IsOptional()
  @IsArray()
  @IsEnum(MetricType, { each: true })
  metrics?: MetricType[];

  /**
   * Array of dimensions to group analytics results by
   * 
   * Enables multi-dimensional aggregation for detailed segmentation analysis.
   * Results will be grouped hierarchically in the order specified.
   * If omitted, returns aggregate totals without grouping.
   */
  @ApiPropertyOptional({
    description: 'Dimensions to group results by',
    enum: GroupByDimension,
    isArray: true,
    example: [GroupByDimension.DOCUMENT_TYPE, GroupByDimension.USER],
  })
  @IsOptional()
  @IsArray()
  @IsEnum(GroupByDimension, { each: true })
  groupBy?: GroupByDimension[];

  /**
   * Time granularity for trend data aggregation
   * 
   * Specifies the time bucket size for time-series data when DATE grouping
   * is included. Affects aggregation resolution and response data volume.
   * If omitted with DATE grouping, defaults to DAY.
   */
  @ApiPropertyOptional({
    description: 'Time granularity for trend data',
    enum: TimeGranularity,
    example: TimeGranularity.DAY,
  })
  @IsOptional()
  @IsEnum(TimeGranularity)
  granularity?: TimeGranularity;

  /**
   * Filter results by specific account ID
   * 
   * Enforces multi-tenant data isolation by restricting analytics to a single
   * account. Required for tenant-level user roles. Admin roles can omit to
   * query across all accounts. Must be valid UUID v4 format.
   */
  @ApiPropertyOptional({
    description: 'Filter by account ID (multi-tenant isolation)',
    example: '123e4567-e89b-12d3-a456-426614174000',
    type: String,
  })
  @IsOptional()
  @IsUUID()
  accountId?: string;

  /**
   * Filter results by specific user ID
   * 
   * Restricts analytics to a single user's activity. Useful for personal
   * dashboards and per-user performance analysis. Must be valid UUID v4 format.
   */
  @ApiPropertyOptional({
    description: 'Filter by user ID',
    example: '123e4567-e89b-12d3-a456-426614174001',
    type: String,
  })
  @IsOptional()
  @IsUUID()
  userId?: string;

  /**
   * Filter results by document type
   * 
   * Restricts analytics to specific document classification (invoice, receipt,
   * contract, form, other). Enables document-type-specific performance analysis.
   */
  @ApiPropertyOptional({
    description: 'Filter by document type',
    enum: ['invoice', 'receipt', 'contract', 'form', 'other'],
    example: 'invoice',
    type: String,
  })
  @IsOptional()
  @IsString()
  documentType?: string;

  /**
   * Filter results by extraction template ID
   * 
   * Restricts analytics to documents processed using a specific custom template.
   * Enables template effectiveness analysis. Must be valid UUID v4 format.
   */
  @ApiPropertyOptional({
    description: 'Filter by template ID',
    example: '123e4567-e89b-12d3-a456-426614174002',
    type: String,
  })
  @IsOptional()
  @IsUUID()
  templateId?: string;

  /**
   * Maximum number of results to return
   * 
   * Implements pagination per Section 0.7.4 API Design Guidelines.
   * Must be between 1-100. Defaults to 25 if omitted.
   * Prevents excessive resource consumption and response payload sizes.
   */
  @ApiPropertyOptional({
    description: 'Number of results to return (pagination)',
    default: 25,
    minimum: 1,
    maximum: 100,
    type: Number,
  })
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(100)
  @Type(() => Number)
  limit?: number = 25;

  /**
   * Offset for pagination
   * 
   * Specifies the number of results to skip before returning data.
   * Must be non-negative. Defaults to 0 if omitted.
   * Used for offset-based pagination per Section 0.7.4 standards.
   */
  @ApiPropertyOptional({
    description: 'Offset for pagination',
    default: 0,
    minimum: 0,
    type: Number,
  })
  @IsOptional()
  @IsInt()
  @Min(0)
  @Type(() => Number)
  offset?: number = 0;
}
