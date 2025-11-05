-- PostgreSQL Initialization Script
-- Purpose: Enable required extensions for OCR Processing Application
-- This script runs automatically when the PostgreSQL container first initializes
-- Location: Mounted to /docker-entrypoint-initdb.d/ in the PostgreSQL container

-- Enable UUID generation extension (required for UUID primary keys)
-- This extension must be enabled before running TypeORM migrations
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Log successful initialization
DO $$
BEGIN
  RAISE NOTICE 'OCR Database initialized successfully';
  RAISE NOTICE 'uuid-ossp extension enabled';
END $$;
