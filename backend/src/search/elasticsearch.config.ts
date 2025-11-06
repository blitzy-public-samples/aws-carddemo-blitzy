/**
 * ElasticSearch Configuration Module
 * 
 * Provides comprehensive ElasticSearch configuration for the OCR Processing Application's
 * document search functionality. Includes index mappings, custom analyzers, search settings,
 * and aggregation configurations per Phase 6 requirements (Section 0.5.7).
 * 
 * Key Features:
 * - Custom text analyzers with stemming and stopword removal
 * - Optimized field mappings for full-text and structured search
 * - Search result highlighting configuration
 * - Faceted search aggregations (document type, status, confidence, date)
 * - 5-second refresh interval for near real-time search
 * - Multi-tenant isolation support via account_id filtering
 * 
 * Performance Target: <1 second search across 100,000 documents (Section 0.7.1)
 */

import { ConfigService } from '@nestjs/config';

/**
 * ElasticSearch index configuration interface
 * Defines the structure for creating an ElasticSearch index with settings and mappings
 */
export interface ElasticsearchIndexConfig {
  index: string;
  body: {
    settings: {
      number_of_shards: number;
      number_of_replicas: number;
      refresh_interval: string;
      analysis: {
        analyzer: {
          [key: string]: {
            type: string;
            tokenizer: string;
            filter: string[];
          };
        };
        filter: {
          [key: string]: any;
        };
      };
    };
    mappings: {
      properties: {
        [key: string]: any;
      };
    };
  };
}

/**
 * ElasticSearch client configuration interface
 * Defines connection settings for the ElasticSearch client
 */
export interface ElasticsearchConfig {
  node: string;
  auth?: {
    username: string;
    password: string;
  };
  maxRetries: number;
  requestTimeout: number;
  sniffOnStart: boolean;
}

/**
 * Generates ElasticSearch client configuration from environment variables
 * 
 * Retrieves connection settings from ConfigService with sensible defaults:
 * - ELASTICSEARCH_URL (default: http://elasticsearch:9200)
 * - ELASTICSEARCH_USERNAME (optional, enables auth if provided)
 * - ELASTICSEARCH_PASSWORD (required if username is provided)
 * 
 * @param configService - NestJS ConfigService for environment variable access
 * @returns ElasticsearchConfig object with connection settings
 * 
 * @example
 * const config = getElasticsearchConfig(configService);
 * const client = new Client(config);
 */
export const getElasticsearchConfig = (
  configService: ConfigService,
): ElasticsearchConfig => {
  const username = configService.get<string>('ELASTICSEARCH_USERNAME');
  const password = configService.get<string>('ELASTICSEARCH_PASSWORD');

  return {
    node: configService.get<string>('ELASTICSEARCH_URL') || 'http://elasticsearch:9200',
    auth: username
      ? {
          username,
          password: password || '',
        }
      : undefined,
    maxRetries: 3,
    requestTimeout: 30000, // 30 seconds - allows for complex queries
    sniffOnStart: true, // Auto-discover cluster nodes for load balancing
  };
};

/**
 * Documents index configuration with optimized mappings and analyzers
 * 
 * Defines the complete index structure for the documents_index including:
 * - 3 shards for horizontal scaling and query parallelization
 * - 1 replica for high availability and read performance
 * - 5-second refresh interval for near real-time search (Section 0.4.3)
 * - Custom analyzers for optimal text search with stemming and stopwords
 * - Comprehensive field mappings for document metadata and content
 * 
 * Field Categories:
 * 1. Identification: document_id, account_id (multi-tenant isolation)
 * 2. Content: document_text (full-text search)
 * 3. Structured Data: extracted_fields (nested objects for field-level search)
 * 4. Metadata: file_name, file_type, file_size, page_count
 * 5. Classification: document_type, processing_status
 * 6. Quality: confidence_score
 * 7. Categorization: tags
 * 8. Timestamps: created_at, updated_at, processed_at
 * 9. User Tracking: uploaded_by, approved_by
 */
export const documentsIndexConfig: ElasticsearchIndexConfig = {
  index: 'documents_index',
  body: {
    settings: {
      number_of_shards: 3, // Distributed across 3 shards for performance
      number_of_replicas: 1, // One replica for HA and read scaling
      refresh_interval: '5s', // Per Section 0.4.3 - balance between real-time and performance
      analysis: {
        analyzer: {
          // Custom analyzer for document text with stemming and stopwords
          document_text_analyzer: {
            type: 'custom',
            tokenizer: 'standard', // Standard word boundary tokenization
            filter: [
              'lowercase', // Case-insensitive search
              'asciifolding', // Remove accents (café -> cafe)
              'document_stop_filter', // Remove common English stopwords
              'document_stemmer', // Reduce words to root form (running -> run)
            ],
          },
          // Keyword analyzer for exact matching on certain fields
          keyword_analyzer: {
            type: 'custom',
            tokenizer: 'keyword', // No tokenization, treat as single term
            filter: ['lowercase'], // Case-insensitive exact matching
          },
        },
        filter: {
          // English stopwords filter (the, a, an, is, etc.)
          document_stop_filter: {
            type: 'stop',
            stopwords: '_english_',
          },
          // English stemmer (reduces words to root form)
          document_stemmer: {
            type: 'stemmer',
            language: 'english',
          },
        },
      },
    },
    mappings: {
      properties: {
        // ===== IDENTIFICATION FIELDS =====
        
        /**
         * Unique document identifier (UUID)
         * Type: keyword - exact matching, no analysis
         */
        document_id: {
          type: 'keyword',
        },
        
        /**
         * Account identifier for multi-tenant isolation
         * Type: keyword - CRITICAL for tenant boundary enforcement
         */
        account_id: {
          type: 'keyword',
        },
        
        // ===== CONTENT FIELDS =====
        
        /**
         * Full document text content from OCR extraction
         * Primary field for full-text search with custom analyzer
         * Includes keyword subfield for sorting and aggregations
         */
        document_text: {
          type: 'text',
          analyzer: 'document_text_analyzer', // Custom analyzer with stemming
          search_analyzer: 'document_text_analyzer',
          fields: {
            keyword: {
              type: 'keyword',
              ignore_above: 256, // Limit keyword field to 256 chars
            },
          },
        },
        
        // ===== STRUCTURED DATA FIELDS =====
        
        /**
         * Extracted fields from document (invoice numbers, dates, amounts, etc.)
         * Type: nested - allows independent querying of each field object
         * Structure: Array of {field_name, field_value, field_type, confidence}
         */
        extracted_fields: {
          type: 'nested',
          properties: {
            field_name: {
              type: 'keyword', // Exact field name (e.g., "invoice_number")
            },
            field_value: {
              type: 'text',
              analyzer: 'document_text_analyzer',
              fields: {
                keyword: {
                  type: 'keyword',
                  ignore_above: 256,
                },
              },
            },
            field_type: {
              type: 'keyword', // Data type (string, number, date, etc.)
            },
            confidence: {
              type: 'float', // OCR confidence score (0.0 - 1.0)
            },
          },
        },
        
        // ===== METADATA FIELDS =====
        
        /**
         * Document file metadata
         * Type: object - grouped metadata fields
         */
        metadata: {
          type: 'object',
          properties: {
            file_name: {
              type: 'text',
              fields: {
                keyword: {
                  type: 'keyword',
                  ignore_above: 256,
                },
              },
            },
            file_type: {
              type: 'keyword', // pdf, jpg, png, etc.
            },
            file_size: {
              type: 'long', // File size in bytes
            },
            page_count: {
              type: 'integer', // Number of pages in document
            },
          },
        },
        
        // ===== CLASSIFICATION FIELDS =====
        
        /**
         * Document type classification (invoice, receipt, contract, form, etc.)
         * Type: keyword - used for faceted search and filtering
         */
        document_type: {
          type: 'keyword',
        },
        
        /**
         * Processing status (uploaded, processing, processed, approved, failed)
         * Type: keyword - used for status filtering and aggregations
         */
        processing_status: {
          type: 'keyword',
        },
        
        // ===== CATEGORIZATION FIELDS =====
        
        /**
         * User-defined tags for document categorization
         * Type: keyword array - allows multiple tags per document
         */
        tags: {
          type: 'keyword',
        },
        
        // ===== QUALITY FIELDS =====
        
        /**
         * Overall document confidence score from OCR processing
         * Type: float - range 0.0 to 1.0
         * Used for quality filtering and aggregations
         */
        confidence_score: {
          type: 'float',
        },
        
        // ===== TIMESTAMP FIELDS =====
        
        /**
         * Document creation timestamp (upload time)
         * Format: ISO 8601 or epoch milliseconds
         */
        created_at: {
          type: 'date',
          format: 'strict_date_optional_time||epoch_millis',
        },
        
        /**
         * Last update timestamp (field corrections, status changes)
         * Format: ISO 8601 or epoch milliseconds
         */
        updated_at: {
          type: 'date',
          format: 'strict_date_optional_time||epoch_millis',
        },
        
        /**
         * OCR processing completion timestamp
         * Format: ISO 8601 or epoch milliseconds
         */
        processed_at: {
          type: 'date',
          format: 'strict_date_optional_time||epoch_millis',
        },
        
        // ===== USER TRACKING FIELDS =====
        
        /**
         * User ID who uploaded the document
         * Type: keyword - for user activity tracking
         */
        uploaded_by: {
          type: 'keyword',
        },
        
        /**
         * User ID who approved the document
         * Type: keyword - for approval workflow tracking
         */
        approved_by: {
          type: 'keyword',
        },
      },
    },
  },
};

/**
 * Default search result highlighting configuration
 * 
 * Highlights matched terms in search results for better user experience.
 * Uses HTML <mark> tags to wrap matched text fragments.
 * 
 * Configuration:
 * - document_text: 3 fragments of 150 characters each
 * - extracted_fields.field_value: 2 fragments of 100 characters each
 * 
 * @example
 * const searchResults = await client.search({
 *   index: 'documents_index',
 *   body: {
 *     query: { match: { document_text: 'invoice' } },
 *     highlight: defaultSearchHighlight,
 *   },
 * });
 */
export const defaultSearchHighlight = {
  fields: {
    document_text: {
      pre_tags: ['<mark>'], // Opening tag for highlighted text
      post_tags: ['</mark>'], // Closing tag for highlighted text
      fragment_size: 150, // Characters per fragment
      number_of_fragments: 3, // Max fragments to return
    },
    'extracted_fields.field_value': {
      pre_tags: ['<mark>'],
      post_tags: ['</mark>'],
      fragment_size: 100,
      number_of_fragments: 2,
    },
  },
};

/**
 * Default aggregations for faceted search
 * 
 * Provides pre-configured aggregations for common search filters:
 * 1. Document Type Distribution - Count by document type (invoice, receipt, etc.)
 * 2. Processing Status - Count by status (processed, pending, failed)
 * 3. Confidence Score Ranges - Group by low/medium/high confidence
 * 4. Date Histogram - Daily document count distribution
 * 
 * These aggregations enable:
 * - Faceted search UI with filter counts
 * - Analytics on document distribution
 * - Quality metrics tracking
 * 
 * @example
 * const searchResults = await client.search({
 *   index: 'documents_index',
 *   body: {
 *     query: { match_all: {} },
 *     aggs: defaultAggregations,
 *   },
 * });
 * 
 * // Access aggregation results:
 * const docTypeBreakdown = searchResults.aggregations.by_document_type.buckets;
 * const confidenceRanges = searchResults.aggregations.by_confidence.buckets;
 */
export const defaultAggregations = {
  // Aggregation 1: Document type distribution
  by_document_type: {
    terms: {
      field: 'document_type',
      size: 20, // Return top 20 document types
    },
  },
  
  // Aggregation 2: Processing status distribution
  by_status: {
    terms: {
      field: 'processing_status',
      size: 10, // Return all status values (typically <10)
    },
  },
  
  // Aggregation 3: Confidence score ranges
  by_confidence: {
    range: {
      field: 'confidence_score',
      ranges: [
        { from: 0.0, to: 0.7, key: 'low' }, // Low confidence: 0-70%
        { from: 0.7, to: 0.85, key: 'medium' }, // Medium confidence: 70-85%
        { from: 0.85, to: 1.0, key: 'high' }, // High confidence: 85-100%
      ],
    },
  },
  
  // Aggregation 4: Date histogram (daily distribution)
  by_date: {
    date_histogram: {
      field: 'created_at',
      calendar_interval: 'day', // Group by calendar day
    },
  },
};
