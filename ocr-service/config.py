"""
OCR Processing Service Configuration Module

This module provides centralized configuration management using Pydantic settings.
All configuration values are validated at startup and can be overridden via environment
variables or .env file.

The Settings class manages:
- Service metadata (name, version, environment)
- Server configuration (host, port, workers)
- OCR engine credentials (Google Vision, AWS Textract, Tesseract)
- Database connections (Redis, RabbitMQ)
- Image processing parameters
- NLP model settings
- Performance tuning parameters
- Monitoring and observability integrations

Usage:
    from config import settings
    
    print(settings.app_name)
    print(settings.rabbitmq_url)
"""

from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import Optional
from pathlib import Path


class Settings(BaseSettings):
    """
    Application configuration with environment variable support.
    
    All settings can be overridden via environment variables with the same name
    (case-insensitive). For example, APP_NAME or app_name will override the
    app_name setting.
    
    Configuration is automatically loaded from .env file if present in the
    working directory.
    """
    
    # =========================================================================
    # Service Configuration
    # =========================================================================
    
    app_name: str = "ocr-processing-service"
    """Name of the application service"""
    
    app_version: str = "1.0.0"
    """Current version of the service"""
    
    environment: str = "development"
    """Deployment environment: development, staging, or production"""
    
    debug: bool = False
    """Enable debug mode with verbose logging and stack traces"""
    
    log_level: str = "INFO"
    """Logging level: DEBUG, INFO, WARNING, ERROR, CRITICAL"""
    
    # =========================================================================
    # Server Settings
    # =========================================================================
    
    host: str = "0.0.0.0"
    """Host address to bind the service to"""
    
    port: int = 8000
    """Port number for the service"""
    
    workers: int = 4
    """Number of worker processes for handling requests"""
    
    reload: bool = False
    """Enable auto-reload on code changes (development only)"""
    
    # =========================================================================
    # Google Cloud Vision Configuration
    # =========================================================================
    
    google_application_credentials: Optional[Path] = None
    """Path to Google Cloud service account JSON key file"""
    
    google_cloud_project: Optional[str] = None
    """Google Cloud project ID"""
    
    google_vision_enabled: bool = True
    """Enable Google Cloud Vision API for OCR fallback"""
    
    # =========================================================================
    # AWS Textract Configuration
    # =========================================================================
    
    aws_access_key_id: Optional[str] = None
    """AWS access key ID for Textract API"""
    
    aws_secret_access_key: Optional[str] = None
    """AWS secret access key for Textract API"""
    
    aws_region: str = "us-east-1"
    """AWS region for Textract service"""
    
    aws_textract_enabled: bool = True
    """Enable AWS Textract API for OCR fallback"""
    
    # =========================================================================
    # Tesseract OCR Configuration
    # =========================================================================
    
    tesseract_cmd: str = "/usr/bin/tesseract"
    """Path to Tesseract OCR executable"""
    
    tesseract_lang: str = "eng"
    """Language code for Tesseract OCR (eng, fra, deu, etc.)"""
    
    tesseract_oem: int = 3
    """OCR Engine Mode: 0=Legacy, 1=Neural, 2=Both, 3=Default"""
    
    tesseract_psm: int = 3
    """Page Segmentation Mode: 3=Fully automatic, 6=Single block, etc."""
    
    # =========================================================================
    # OCR Strategy Configuration
    # =========================================================================
    
    hybrid_ocr_enabled: bool = True
    """Enable hybrid OCR approach using multiple engines"""
    
    confidence_threshold: int = 85
    """Minimum confidence percentage to accept OCR results (0-100)"""
    
    primary_engine: str = "tesseract"
    """Primary OCR engine: tesseract, google_vision, aws_textract"""
    
    fallback_engine: str = "google_vision"
    """Fallback OCR engine when primary fails or confidence is low"""
    
    # =========================================================================
    # Redis Configuration
    # =========================================================================
    
    redis_host: str = "localhost"
    """Redis server hostname"""
    
    redis_port: int = 6379
    """Redis server port"""
    
    redis_password: Optional[str] = None
    """Redis authentication password (if required)"""
    
    redis_db: int = 0
    """Redis database number (0-15)"""
    
    # =========================================================================
    # RabbitMQ Configuration
    # =========================================================================
    
    rabbitmq_host: str = "localhost"
    """RabbitMQ server hostname"""
    
    rabbitmq_port: int = 5672
    """RabbitMQ server port"""
    
    rabbitmq_user: str = "guest"
    """RabbitMQ authentication username"""
    
    rabbitmq_password: str = "guest"
    """RabbitMQ authentication password"""
    
    rabbitmq_vhost: str = "/"
    """RabbitMQ virtual host"""
    
    rabbitmq_queue_name: str = "ocr_processing_queue"
    """Name of the queue for OCR processing jobs"""
    
    # =========================================================================
    # Image Processing Configuration
    # =========================================================================
    
    max_image_size_mb: int = 50
    """Maximum allowed image file size in megabytes"""
    
    supported_formats: str = "pdf,jpg,jpeg,png,tiff"
    """Comma-separated list of supported file formats"""
    
    image_dpi: int = 300
    """Target DPI for image processing (optimal for OCR)"""
    
    enable_preprocessing: bool = True
    """Enable image preprocessing (deskew, denoise, contrast enhancement)"""
    
    # =========================================================================
    # NLP Configuration
    # =========================================================================
    
    spacy_model: str = "en_core_web_sm"
    """spaCy model for entity extraction and document classification"""
    
    huggingface_model: str = "distilbert-base-uncased"
    """Hugging Face transformer model for advanced NLP tasks"""
    
    entity_extraction_enabled: bool = True
    """Enable entity extraction (dates, amounts, names, etc.)"""
    
    # =========================================================================
    # Performance Configuration
    # =========================================================================
    
    max_concurrent_jobs: int = 10
    """Maximum number of concurrent OCR processing jobs"""
    
    job_timeout_seconds: int = 300
    """Timeout for individual job processing (5 minutes)"""
    
    batch_size: int = 5
    """Number of documents to process in a single batch"""
    
    # =========================================================================
    # Monitoring and Observability
    # =========================================================================
    
    sentry_dsn: Optional[str] = None
    """Sentry DSN for error tracking and monitoring"""
    
    datadog_api_key: Optional[str] = None
    """DataDog API key for application performance monitoring"""
    
    # =========================================================================
    # Pydantic Model Configuration
    # =========================================================================
    
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore"
    )
    
    # =========================================================================
    # Computed Properties
    # =========================================================================
    
    @property
    def rabbitmq_url(self) -> str:
        """
        Construct complete RabbitMQ connection URL.
        
        Returns:
            str: AMQP connection string in format:
                 amqp://user:password@host:port/vhost
        """
        return (
            f"amqp://{self.rabbitmq_user}:{self.rabbitmq_password}"
            f"@{self.rabbitmq_host}:{self.rabbitmq_port}/{self.rabbitmq_vhost}"
        )
    
    @property
    def redis_url(self) -> str:
        """
        Construct complete Redis connection URL.
        
        Returns:
            str: Redis connection string in format:
                 redis://[:password@]host:port/db
        """
        auth = f":{self.redis_password}@" if self.redis_password else ""
        return f"redis://{auth}{self.redis_host}:{self.redis_port}/{self.redis_db}"


# =========================================================================
# Singleton Settings Instance
# =========================================================================

settings = Settings()
"""
Global settings instance for the application.

This singleton is initialized once at module import and provides access to
all configuration values throughout the application.

Usage:
    from config import settings
    
    if settings.debug:
        print(f"Running {settings.app_name} v{settings.app_version}")
"""
