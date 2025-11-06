"""
OCR Processing Service Application Package.

This package contains the core application logic for the FastAPI-based OCR processing
microservice. It provides comprehensive document digitization capabilities including:

- OCR text extraction using Tesseract and cloud APIs (Google Vision, AWS Textract)
- Document type classification and intelligent routing
- Named entity recognition (NER) for data extraction
- Confidence scoring and validation
- Image preprocessing and optimization

The package is organized into the following modules:

    - routers: FastAPI route handlers for HTTP endpoints
    - services: Business logic for OCR, NLP, and document processing
    - models: Pydantic models for request/response validation
    - utils: Utility functions for image processing and document parsing

Architecture:
    This service follows a layered architecture pattern:
    - API Layer (routers): Handles HTTP requests and responses
    - Service Layer (services): Implements business logic and orchestration
    - Model Layer (models): Defines data structures and validation rules
    - Utility Layer (utils): Provides helper functions and utilities

Integration:
    The OCR service integrates with:
    - Backend API Service: Receives processing jobs via HTTP
    - Cloud OCR APIs: Google Cloud Vision, AWS Textract
    - Message Queue: RabbitMQ for asynchronous job processing
    - Object Storage: AWS S3/Google Cloud Storage for document retrieval

Performance:
    - Single-page document processing: <30 seconds
    - Multi-page document (10 pages): <2 minutes
    - Batch processing: Parallel worker execution
    - Auto-scaling based on queue depth

Security:
    - All endpoints require authentication (JWT validation)
    - Input validation using Pydantic models
    - File type validation and virus scanning integration
    - Error handling without exposing internal details

For detailed API documentation, see the automatically generated OpenAPI/Swagger docs
at /docs when the service is running.

Example:
    To import and use components from this package:
    
    >>> from app.routers import ocr
    >>> from app.services.ocr_service import OCRService
    >>> from app.models.ocr_request import OCRRequest

Note:
    This __init__.py file intentionally keeps imports minimal to avoid circular
    dependency issues. Import specific modules directly where needed rather than
    through this package initialization file.

Version History:
    1.0.0 (2025-10-31): Initial release with core OCR functionality
"""

# Package metadata
__version__: str = "1.0.0"
__author__: str = "OCR Processing Team"
__description__: str = "FastAPI-based OCR processing microservice for document digitization"

# Package information tuple for programmatic access
__package_info__: tuple = (
    "ocr-service",
    __version__,
    __author__,
    __description__,
)

# Expose package metadata in __all__ for explicit public API
__all__: list[str] = [
    "__version__",
    "__author__",
    "__description__",
    "__package_info__",
]
