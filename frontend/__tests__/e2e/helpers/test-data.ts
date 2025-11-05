/**
 * E2E Test Data Fixtures
 *
 * This file provides deterministic, reusable test data for E2E tests.
 * All data is predefined and consistent to ensure tests are deterministic
 * and do not produce random failures per Section 0.7.1 testing requirements.
 *
 * IMPORTANT: All data here is FAKE and for testing purposes only.
 * Do NOT use production data or real credentials.
 */

// ============================================================================
// Type Definitions
// ============================================================================

/**
 * Test user structure with authentication and profile information
 */
export interface TestUser {
  id: string;
  email: string;
  password: string;
  name: string;
  role: 'admin' | 'user' | 'viewer';
}

/**
 * Test API key structure with permissions and metadata
 */
export interface TestAPIKey {
  name: string;
  permissions: string[];
  expirationDate: string;
}

/**
 * Document metadata structure for test documents
 */
export interface TestDocument {
  id: string;
  filename: string;
  documentType: 'invoice' | 'receipt' | 'contract' | 'form';
  fileSize: number;
  mimeType: string;
  status: 'pending' | 'processing' | 'completed' | 'failed';
  uploadedAt: string;
  extractedFields: Record<string, any>;
  confidenceScore: number;
}

/**
 * Template zone definition for visual template builder
 */
export interface TemplateZone {
  id: string;
  fieldName: string;
  x: number;
  y: number;
  width: number;
  height: number;
  page: number;
}

/**
 * Template definition structure
 */
export interface TestTemplate {
  id: string;
  name: string;
  documentType: 'invoice' | 'receipt' | 'contract' | 'form';
  description: string;
  zones: TemplateZone[];
  validationRules: Record<string, any>;
  createdAt: string;
}

/**
 * Integration configuration structure
 */
export interface TestIntegration {
  quickbooks: {
    clientId: string;
    clientSecret: string;
    callbackUrl: string;
    realmId: string;
  };
  salesforce: {
    clientId: string;
    clientSecret: string;
    instanceUrl: string;
    username: string;
  };
  netsuite: {
    accountId: string;
    consumerKey: string;
    consumerSecret: string;
    tokenId: string;
    tokenSecret: string;
  };
}

/**
 * Webhook configuration structure
 */
export interface TestWebhook {
  webhookUrl: string;
  eventTypes: string[];
  secret: string;
  testEvent: {
    type: string;
    payload: Record<string, any>;
  };
}

/**
 * Search query test data structure
 */
export interface TestSearchQuery {
  query: string;
  filters?: Record<string, any>;
  expectedCount: number;
  description: string;
}

// ============================================================================
// Test Users - Predefined users with various roles
// ============================================================================

/**
 * Predefined test users with deterministic credentials
 * Use these for authentication testing across all E2E tests
 */
export const testUsers = {
  admin: {
    id: 'test-admin-001',
    email: 'admin.test@ocr-test.example.com',
    password: 'TestAdmin123!@#',
    name: 'Admin Test User',
    role: 'admin' as const,
  } as TestUser,

  user: {
    id: 'test-user-001',
    email: 'user.test@ocr-test.example.com',
    password: 'TestUser123!@#',
    name: 'Regular Test User',
    role: 'user' as const,
  } as TestUser,

  viewer: {
    id: 'test-viewer-001',
    email: 'viewer.test@ocr-test.example.com',
    password: 'TestViewer123!@#',
    name: 'Viewer Test User',
    role: 'viewer' as const,
  } as TestUser,
};

// ============================================================================
// Test API Keys - Predefined API keys with permissions
// ============================================================================

/**
 * Predefined API keys for testing API authentication
 */
export const testAPIKeys = {
  basicKey: {
    name: 'Basic Test API Key',
    permissions: ['document.read', 'document.create', 'template.read'],
    expirationDate: '2026-12-31T23:59:59Z',
  } as TestAPIKey,

  adminKey: {
    name: 'Admin Test API Key',
    permissions: [
      'document.read',
      'document.create',
      'document.update',
      'document.delete',
      'template.read',
      'template.create',
      'template.update',
      'template.delete',
      'user.read',
      'user.create',
      'user.update',
      'integration.manage',
    ],
    expirationDate: '2026-12-31T23:59:59Z',
  } as TestAPIKey,
};

// ============================================================================
// Test Documents - Sample documents with known metadata
// ============================================================================

/**
 * Predefined test documents with known content and extracted data
 * Use these for upload, processing, and review testing
 */
export const testDocuments = {
  invoice1: {
    id: 'test-doc-invoice-001',
    filename: 'test-invoice-001.pdf',
    documentType: 'invoice' as const,
    fileSize: 245678,
    mimeType: 'application/pdf',
    status: 'completed' as const,
    uploadedAt: '2025-10-15T10:30:00Z',
    confidenceScore: 0.95,
    extractedFields: {
      invoiceNumber: 'INV-2025-001',
      invoiceDate: '2025-10-15',
      dueDate: '2025-11-15',
      vendorName: 'Test Vendor Inc.',
      vendorAddress: '123 Test Street, Test City, TC 12345',
      totalAmount: 1250.5,
      currency: 'USD',
      taxAmount: 125.05,
      subtotal: 1125.45,
      lineItems: [
        {
          description: 'Professional Services',
          quantity: 10,
          unitPrice: 100.0,
          total: 1000.0,
        },
        {
          description: 'Consulting Fee',
          quantity: 1,
          unitPrice: 125.45,
          total: 125.45,
        },
      ],
    },
  } as TestDocument,

  receipt1: {
    id: 'test-doc-receipt-001',
    filename: 'test-receipt-001.jpg',
    documentType: 'receipt' as const,
    fileSize: 156789,
    mimeType: 'image/jpeg',
    status: 'completed' as const,
    uploadedAt: '2025-10-16T14:20:00Z',
    confidenceScore: 0.88,
    extractedFields: {
      merchantName: 'Test Coffee Shop',
      merchantAddress: '456 Main St, Test City, TC 12345',
      date: '2025-10-16',
      time: '14:15:00',
      total: 15.75,
      currency: 'USD',
      paymentMethod: 'Credit Card',
      lastFourDigits: '4242',
      items: [
        { name: 'Latte', price: 5.5, quantity: 1 },
        { name: 'Croissant', price: 3.25, quantity: 2 },
        { name: 'Tip', price: 3.75, quantity: 1 },
      ],
    },
  } as TestDocument,

  contract1: {
    id: 'test-doc-contract-001',
    filename: 'test-contract-001.pdf',
    documentType: 'contract' as const,
    fileSize: 789012,
    mimeType: 'application/pdf',
    status: 'completed' as const,
    uploadedAt: '2025-10-17T09:00:00Z',
    confidenceScore: 0.92,
    extractedFields: {
      contractTitle: 'Test Service Agreement',
      partyA: 'Test Company LLC',
      partyB: 'Test Client Inc.',
      effectiveDate: '2025-11-01',
      expirationDate: '2026-10-31',
      contractValue: 50000.0,
      currency: 'USD',
      termMonths: 12,
      signatureDate: '2025-10-17',
      signatories: ['John Doe', 'Jane Smith'],
    },
  } as TestDocument,

  // Low confidence document for testing validation workflows
  invoice2: {
    id: 'test-doc-invoice-002',
    filename: 'test-invoice-low-quality.pdf',
    documentType: 'invoice' as const,
    fileSize: 198765,
    mimeType: 'application/pdf',
    status: 'completed' as const,
    uploadedAt: '2025-10-18T11:45:00Z',
    confidenceScore: 0.72,
    extractedFields: {
      invoiceNumber: 'INV-2025-002',
      invoiceDate: '2025-10-18',
      vendorName: 'Low Quality Scan Vendor',
      totalAmount: 500.0,
      currency: 'USD',
    },
  } as TestDocument,
};

// ============================================================================
// Test Templates - Template definitions for common document types
// ============================================================================

/**
 * Predefined templates for testing template builder and matching
 */
export const testTemplates = {
  invoiceTemplate: {
    id: 'test-template-invoice-001',
    name: 'Standard Invoice Template',
    documentType: 'invoice' as const,
    description: 'Template for standard business invoices with header and line items',
    createdAt: '2025-10-01T00:00:00Z',
    zones: [
      {
        id: 'zone-invoice-number',
        fieldName: 'invoiceNumber',
        x: 450,
        y: 50,
        width: 150,
        height: 30,
        page: 1,
      },
      {
        id: 'zone-invoice-date',
        fieldName: 'invoiceDate',
        x: 450,
        y: 90,
        width: 150,
        height: 30,
        page: 1,
      },
      {
        id: 'zone-vendor-name',
        fieldName: 'vendorName',
        x: 50,
        y: 50,
        width: 300,
        height: 40,
        page: 1,
      },
      {
        id: 'zone-total-amount',
        fieldName: 'totalAmount',
        x: 450,
        y: 600,
        width: 150,
        height: 40,
        page: 1,
      },
    ],
    validationRules: {
      invoiceNumber: { required: true, pattern: '^INV-\\d{4}-\\d{3}$' },
      invoiceDate: { required: true, type: 'date' },
      totalAmount: { required: true, type: 'number', min: 0 },
    },
  } as TestTemplate,

  receiptTemplate: {
    id: 'test-template-receipt-001',
    name: 'Standard Receipt Template',
    documentType: 'receipt' as const,
    description: 'Template for retail receipts',
    createdAt: '2025-10-01T00:00:00Z',
    zones: [
      {
        id: 'zone-merchant-name',
        fieldName: 'merchantName',
        x: 100,
        y: 50,
        width: 400,
        height: 50,
        page: 1,
      },
      {
        id: 'zone-date',
        fieldName: 'date',
        x: 50,
        y: 120,
        width: 200,
        height: 30,
        page: 1,
      },
      {
        id: 'zone-total',
        fieldName: 'total',
        x: 400,
        y: 500,
        width: 150,
        height: 40,
        page: 1,
      },
    ],
    validationRules: {
      merchantName: { required: true },
      date: { required: true, type: 'date' },
      total: { required: true, type: 'number', min: 0 },
    },
  } as TestTemplate,

  contractTemplate: {
    id: 'test-template-contract-001',
    name: 'Standard Contract Template',
    documentType: 'contract' as const,
    description: 'Template for service agreements',
    createdAt: '2025-10-01T00:00:00Z',
    zones: [
      {
        id: 'zone-contract-title',
        fieldName: 'contractTitle',
        x: 100,
        y: 100,
        width: 400,
        height: 50,
        page: 1,
      },
      {
        id: 'zone-effective-date',
        fieldName: 'effectiveDate',
        x: 100,
        y: 200,
        width: 200,
        height: 30,
        page: 1,
      },
      {
        id: 'zone-contract-value',
        fieldName: 'contractValue',
        x: 350,
        y: 250,
        width: 150,
        height: 30,
        page: 1,
      },
    ],
    validationRules: {
      contractTitle: { required: true },
      effectiveDate: { required: true, type: 'date' },
      contractValue: { required: false, type: 'number', min: 0 },
    },
  } as TestTemplate,
};

// ============================================================================
// Test Integrations - Integration configuration data
// ============================================================================

/**
 * Mock integration credentials for testing third-party connections
 * These are FAKE credentials for testing only
 */
export const testIntegrations: TestIntegration = {
  quickbooks: {
    clientId: 'test-qb-client-id-123456',
    clientSecret: 'test-qb-client-secret-abcdef',
    callbackUrl: 'http://localhost:3000/api/v1/integrations/quickbooks/callback',
    realmId: 'test-realm-123456',
  },

  salesforce: {
    clientId: 'test-sf-client-id-789012',
    clientSecret: 'test-sf-client-secret-ghijkl',
    instanceUrl: 'https://test.salesforce.com',
    username: 'test@ocr-test.example.com.sandbox',
  },

  netsuite: {
    accountId: 'test-ns-account-123',
    consumerKey: 'test-ns-consumer-key-mnopqr',
    consumerSecret: 'test-ns-consumer-secret-stuvwx',
    tokenId: 'test-ns-token-id-yzabcd',
    tokenSecret: 'test-ns-token-secret-efghij',
  },
};

// ============================================================================
// Test Webhooks - Webhook configuration and test events
// ============================================================================

/**
 * Webhook test data for testing webhook subscriptions and delivery
 */
export const testWebhooks: TestWebhook = {
  webhookUrl: 'https://test-webhook.example.com/ocr-events',
  eventTypes: [
    'document.uploaded',
    'document.processing',
    'document.processed',
    'document.approved',
    'batch.completed',
    'template.created',
  ],
  secret: 'test-webhook-secret-key-12345',
  testEvent: {
    type: 'document.processed',
    payload: {
      documentId: 'test-doc-invoice-001',
      status: 'completed',
      confidenceScore: 0.95,
      extractedFields: {
        invoiceNumber: 'INV-2025-001',
        totalAmount: 1250.5,
      },
      timestamp: '2025-10-15T10:35:00Z',
    },
  },
};

// ============================================================================
// Test Search Queries - Search test data with expected results
// ============================================================================

/**
 * Predefined search queries for testing search functionality
 * Each query includes expected result count for validation
 */
export const testSearchQueries = {
  basicQuery: {
    query: 'invoice',
    expectedCount: 2,
    description: 'Basic full-text search for "invoice"',
  } as TestSearchQuery,

  filterByType: {
    query: '',
    filters: {
      documentType: 'invoice',
    },
    expectedCount: 2,
    description: 'Filter documents by type: invoice',
  } as TestSearchQuery,

  filterByDateRange: {
    query: '',
    filters: {
      uploadedFrom: '2025-10-15T00:00:00Z',
      uploadedTo: '2025-10-17T23:59:59Z',
    },
    expectedCount: 3,
    description: 'Filter documents by upload date range',
  } as TestSearchQuery,

  complexQuery: {
    query: 'Test Vendor',
    filters: {
      documentType: 'invoice',
      confidenceScoreMin: 0.9,
      uploadedFrom: '2025-10-01T00:00:00Z',
    },
    expectedCount: 1,
    description: 'Complex search with text query and multiple filters',
  } as TestSearchQuery,

  noResults: {
    query: 'nonexistent-document-xyz-123',
    expectedCount: 0,
    description: 'Search query that should return no results',
  } as TestSearchQuery,
};

// ============================================================================
// Test Notification Settings - Notification preferences
// ============================================================================

/**
 * Test notification settings for user preferences testing
 */
export const testNotificationSettings = {
  processingComplete: {
    email: true,
    inApp: true,
    webhook: false,
  },

  weeklyDigest: {
    enabled: true,
    dayOfWeek: 'Monday',
    time: '09:00',
  },

  accountActivity: {
    newUserAdded: true,
    integrationConnected: true,
    apiKeyCreated: true,
  },
};

// ============================================================================
// Test Batch Processing Data - Batch upload test datasets
// ============================================================================

/**
 * Batch processing test data with multiple document sets
 */
export const testBatchProcessing = {
  smallBatch: {
    id: 'test-batch-small-001',
    name: 'Small Batch Test (5 documents)',
    documentCount: 5,
    documents: [
      { filename: 'batch-invoice-001.pdf', type: 'invoice' },
      { filename: 'batch-invoice-002.pdf', type: 'invoice' },
      { filename: 'batch-receipt-001.jpg', type: 'receipt' },
      { filename: 'batch-receipt-002.jpg', type: 'receipt' },
      { filename: 'batch-contract-001.pdf', type: 'contract' },
    ],
    expectedProcessingTime: 150, // seconds
  },

  mediumBatch: {
    id: 'test-batch-medium-001',
    name: 'Medium Batch Test (25 documents)',
    documentCount: 25,
    expectedProcessingTime: 600, // seconds
  },

  largeBatch: {
    id: 'test-batch-large-001',
    name: 'Large Batch Test (100 documents)',
    documentCount: 100,
    expectedProcessingTime: 900, // seconds (15 minutes per requirement)
  },
};

// ============================================================================
// Test Export Configurations - Export format and field selections
// ============================================================================

/**
 * Export configuration test data for testing data export functionality
 */
export const testExportConfigs = {
  jsonExport: {
    format: 'json',
    fields: ['invoiceNumber', 'invoiceDate', 'totalAmount', 'vendorName'],
    includeMetadata: true,
    includeConfidenceScores: true,
  },

  csvExport: {
    format: 'csv',
    fields: ['invoiceNumber', 'invoiceDate', 'totalAmount', 'vendorName'],
    includeHeaders: true,
    delimiter: ',',
  },

  xmlExport: {
    format: 'xml',
    fields: ['invoiceNumber', 'invoiceDate', 'totalAmount', 'vendorName'],
    rootElement: 'documents',
    itemElement: 'document',
  },

  excelExport: {
    format: 'excel',
    fields: ['invoiceNumber', 'invoiceDate', 'totalAmount', 'vendorName'],
    sheetName: 'Extracted Documents',
    includeFormatting: true,
  },

  fullExport: {
    format: 'json',
    fields: 'all', // Export all fields
    includeMetadata: true,
    includeConfidenceScores: true,
    includeAuditTrail: true,
  },
};

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Generate a deterministic timestamp-based ID
 * Uses a fixed seed to ensure determinism across test runs
 *
 * @param prefix - Prefix for the ID
 * @param seed - Seed number for determinism
 * @returns Deterministic ID string
 */
export function generateDeterministicId(prefix: string, seed: number): string {
  // Use fixed epoch timestamp + seed for determinism
  const baseTimestamp = 1729000000000; // Fixed timestamp: Oct 15, 2024
  const deterministicValue = baseTimestamp + seed;
  return `${prefix}-${deterministicValue.toString(36)}`;
}

/**
 * Generate a deterministic email address for testing
 *
 * @param username - Username part of email
 * @param seed - Seed number for uniqueness
 * @returns Deterministic email address
 */
export function generateTestEmail(username: string, seed: number): string {
  return `${username}.${seed}@ocr-test.example.com`;
}

/**
 * Generate deterministic file data for upload testing
 *
 * @param filename - Name of the file
 * @param sizeInKb - Size in kilobytes
 * @returns Mock file data object
 */
export function generateTestFileData(filename: string, sizeInKb: number) {
  const mimeTypes: Record<string, string> = {
    pdf: 'application/pdf',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
  };

  const extension = filename.split('.').pop()?.toLowerCase() || 'pdf';
  const mimeType = mimeTypes[extension] || 'application/octet-stream';

  return {
    filename,
    size: sizeInKb * 1024,
    mimeType,
    lastModified: 1729000000000, // Fixed timestamp for determinism
  };
}

/**
 * Create a test document with deterministic data
 *
 * @param overrides - Fields to override in default document
 * @returns Test document object
 */
export function createTestDocument(overrides: Partial<TestDocument> = {}): TestDocument {
  return {
    id: generateDeterministicId('test-doc', Date.now()),
    filename: 'test-document.pdf',
    documentType: 'invoice',
    fileSize: 250000,
    mimeType: 'application/pdf',
    status: 'completed',
    uploadedAt: new Date().toISOString(),
    confidenceScore: 0.9,
    extractedFields: {},
    ...overrides,
  };
}

/**
 * Wait helper for E2E tests - ensures deterministic timing
 *
 * @param ms - Milliseconds to wait
 * @returns Promise that resolves after specified time
 */
export function waitFor(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Get test data for a specific test scenario
 * Useful for organizing test data by scenario
 */
export const testScenarios = {
  // Scenario: New user signup and first document upload
  newUserOnboarding: {
    user: testUsers.user,
    document: testDocuments.invoice1,
    expectedSteps: ['signup', 'verify-email', 'upload', 'review', 'approve'],
  },

  // Scenario: Admin managing users and permissions
  adminUserManagement: {
    admin: testUsers.admin,
    newUser: {
      email: generateTestEmail('new.user', 999),
      name: 'New Test User',
      role: 'user' as const,
    },
  },

  // Scenario: Batch processing workflow
  batchProcessingWorkflow: {
    user: testUsers.user,
    batch: testBatchProcessing.smallBatch,
    expectedOutcomes: {
      successful: 5,
      failed: 0,
      avgConfidence: 0.9,
    },
  },

  // Scenario: Integration setup and data export
  integrationSetup: {
    user: testUsers.admin,
    integration: testIntegrations.quickbooks,
    exportConfig: testExportConfigs.jsonExport,
  },

  // Scenario: Template creation and document matching
  templateCreation: {
    user: testUsers.admin,
    template: testTemplates.invoiceTemplate,
    testDocument: testDocuments.invoice1,
    expectedMatch: true,
  },
};

// ============================================================================
// Cleanup Utilities
// ============================================================================

/**
 * Test data that should be cleaned up after each test
 * Track created resources to ensure proper cleanup
 */
export class TestDataTracker {
  private createdDocuments: string[] = [];
  private createdTemplates: string[] = [];
  private createdUsers: string[] = [];
  private createdAPIKeys: string[] = [];

  trackDocument(documentId: string): void {
    this.createdDocuments.push(documentId);
  }

  trackTemplate(templateId: string): void {
    this.createdTemplates.push(templateId);
  }

  trackUser(userId: string): void {
    this.createdUsers.push(userId);
  }

  trackAPIKey(keyId: string): void {
    this.createdAPIKeys.push(keyId);
  }

  getCreatedDocuments(): string[] {
    return [...this.createdDocuments];
  }

  getCreatedTemplates(): string[] {
    return [...this.createdTemplates];
  }

  getCreatedUsers(): string[] {
    return [...this.createdUsers];
  }

  getCreatedAPIKeys(): string[] {
    return [...this.createdAPIKeys];
  }

  reset(): void {
    this.createdDocuments = [];
    this.createdTemplates = [];
    this.createdUsers = [];
    this.createdAPIKeys = [];
  }
}
