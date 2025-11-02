# CardDemo REST API Documentation

## Table of Contents

- [Overview](#overview)
- [Base Configuration](#base-configuration)
- [Authentication](#authentication)
- [Authentication Endpoints](#authentication-endpoints)
- [Account Management Endpoints](#account-management-endpoints)
- [Card Management Endpoints](#card-management-endpoints)
- [Transaction Endpoints](#transaction-endpoints)
- [Bill Payment Endpoints](#bill-payment-endpoints)
- [Report Endpoints](#report-endpoints)
- [User Management Endpoints](#user-management-endpoints)
- [Administrative Endpoints](#administrative-endpoints)
- [Error Handling](#error-handling)
- [Common Patterns](#common-patterns)
- [OpenAPI/Swagger Documentation](#openapiswagger-documentation)

---

## Overview

The CardDemo REST API provides comprehensive credit card management functionality converted from the legacy COBOL/CICS mainframe application to a modern Java Spring Boot microservices architecture. This API maintains 100% functional equivalence with the original mainframe application while providing a RESTful interface for web and mobile clients.

### API Design Principles

- **RESTful Architecture**: All endpoints follow REST conventions with appropriate HTTP methods and status codes
- **JSON Format**: All request and response bodies use JSON format with UTF-8 encoding
- **Stateless Authentication**: JWT token-based authentication replaces CICS session management
- **Functional Equivalence**: Each endpoint maps directly to a COBOL/CICS transaction, preserving exact business logic
- **Pagination Support**: List endpoints support Spring Data pagination patterns
- **Consistent Error Handling**: Standardized error response format across all endpoints

### Technology Stack

- **Framework**: Spring Boot 3.2.1
- **Security**: Spring Security 6.2.1 with JWT authentication
- **Database**: PostgreSQL 15+ with Spring Data JPA
- **API Documentation**: OpenAPI 3.0 / Swagger UI
- **Serialization**: Jackson 2.16.1 for JSON processing

---

## Base Configuration

### Base URL

```
Development: http://localhost:8080/api
Production:  https://api.carddemo.example.com/api
```

### API Versioning

The current API version is **v1**, which is implicitly included in the base path. Future major versions will be explicitly versioned (e.g., `/api/v2`).

### Content Type

- **Request Content-Type**: `application/json`
- **Response Content-Type**: `application/json`
- **Character Encoding**: UTF-8

### CORS Configuration

Cross-Origin Resource Sharing (CORS) is configured to allow requests from:
- Development: `http://localhost:3000` (React development server)
- Production: Configured frontend domain

Allowed methods: GET, POST, PUT, DELETE, OPTIONS

### Rate Limiting

- **Default Limit**: 100 requests per minute per authenticated user
- **Burst Limit**: 20 requests per second
- **Response Headers**: 
  - `X-RateLimit-Limit`: Total request limit
  - `X-RateLimit-Remaining`: Remaining requests in current window
  - `X-RateLimit-Reset`: Unix timestamp when limit resets

---

## Authentication

### JWT Token-Based Authentication

The API uses JWT (JSON Web Token) bearer token authentication, replacing the legacy USRSEC file-based authentication from the mainframe application.

### Authentication Flow

1. Client sends credentials to `/api/auth/login`
2. Server validates credentials against `user_security` table
3. Server generates JWT token with 24-hour expiration
4. Client includes token in `Authorization` header for subsequent requests
5. Server validates token on each request and extracts user identity

### Authorization Header Format

```
Authorization: Bearer <JWT_TOKEN>
```

### Token Structure

The JWT token contains the following claims:
- `sub`: User ID (userId)
- `name`: User full name
- `role`: User type ('R' = ROLE_USER, 'A' = ROLE_ADMIN)
- `iat`: Issued at timestamp
- `exp`: Expiration timestamp (24 hours from issuance)

### Security Configuration

- **Password Encryption**: BCrypt with strength 12
- **Token Signing Algorithm**: HS512 (HMAC with SHA-512)
- **Session Management**: Redis-backed session storage for additional state
- **TLS Requirement**: HTTPS enforced in production

---

## Authentication Endpoints

### POST /api/auth/login

**Description**: User authentication and JWT token generation

**COBOL/CICS Mapping**: Transaction CC00 (COSGN00C program)

**Authorization**: None (public endpoint)

**Request Body**:

```json
{
  "userId": "string",
  "password": "string"
}
```

**Request Field Validation**:
- `userId`: Required, 1-8 characters, alphanumeric
- `password`: Required, 8 characters minimum

**Success Response** (200 OK):

```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJVU0VSMDAwMSIsIm5hbWUiOiJKb2huIERvZSIsInJvbGUiOiJSIiwiaWF0IjoxNzA5MjQwMDAwLCJleHAiOjE3MDkzMjY0MDB9.signature",
  "userId": "USER0001",
  "userName": "John Doe",
  "userType": "R",
  "expiresIn": 86400
}
```

**Response Fields**:
- `token`: JWT bearer token (string)
- `userId`: User identifier, 8 characters (string)
- `userName`: User full name, up to 50 characters (string)
- `userType`: User role - 'R' (Regular User) or 'A' (Admin) (string, 1 char)
- `expiresIn`: Token expiration time in seconds (integer, always 86400)

**Error Responses**:

**401 Unauthorized** - Invalid credentials:
```json
{
  "timestamp": "2024-02-29T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid user ID or password",
  "path": "/api/auth/login"
}
```

**400 Bad Request** - Validation failure:
```json
{
  "timestamp": "2024-02-29T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/auth/login",
  "errors": [
    {
      "field": "userId",
      "rejectedValue": "",
      "message": "User ID is required"
    },
    {
      "field": "password",
      "rejectedValue": null,
      "message": "Password must be at least 8 characters"
    }
  ]
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER0001",
    "password": "PASSWORD"
  }'
```

---

### POST /api/auth/logout

**Description**: Invalidate JWT token and clear Redis session

**COBOL/CICS Mapping**: CICS RETURN with session termination

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Request Body**: None

**Success Response** (200 OK):

```json
{
  "message": "Logout successful",
  "timestamp": "2024-02-29T10:35:00Z"
}
```

**Error Responses**:

**401 Unauthorized** - Missing or invalid token:
```json
{
  "timestamp": "2024-02-29T10:35:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "JWT token is missing or invalid",
  "path": "/api/auth/logout"
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

## Account Management Endpoints

### GET /api/accounts/{accountId}

**Description**: Retrieve detailed account information including balance, limits, and customer details

**COBOL/CICS Mapping**: Transaction CAVW (COACTVWC program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)
- Regular users can only view their own accounts
- Admin users can view any account

**Path Parameters**:
- `accountId`: 11-digit account number (string, pattern: `\d{11}`)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (200 OK):

```json
{
  "accountId": "00000000001",
  "accountStatus": "A",
  "accountStatusDescription": "Active",
  "customerId": "000000001",
  "customerName": "John Doe",
  "customerFirstName": "John",
  "customerMiddleName": "M",
  "customerLastName": "Doe",
  "openDate": "2020-01-15",
  "expirationDate": "2025-01-15",
  "currentBalance": 1523.45,
  "creditLimit": 5000.00,
  "cashCreditLimit": 1000.00,
  "availableCredit": 3476.55,
  "cashAdvanceLimit": 1000.00,
  "interestRate": 15.99,
  "reissueDate": "2023-01-15",
  "groupId": "DEFAULT"
}
```

**Response Fields**:
- `accountId`: 11-digit account identifier (string)
- `accountStatus`: Account status code (string, 1 char: 'A'=Active, 'C'=Closed, 'S'=Suspended)
- `accountStatusDescription`: Human-readable status (string)
- `customerId`: 9-digit customer identifier (string)
- `customerName`: Full customer name (string, max 50 chars)
- `customerFirstName`: First name (string, max 25 chars)
- `customerMiddleName`: Middle initial (string, 1 char)
- `customerLastName`: Last name (string, max 25 chars)
- `openDate`: Account opening date (string, ISO 8601 date format)
- `expirationDate`: Account expiration date (string, ISO 8601 date format)
- `currentBalance`: Current balance in dollars (number, precision 15 scale 2)
- `creditLimit`: Maximum credit limit (number, precision 15 scale 2)
- `cashCreditLimit`: Cash advance limit (number, precision 15 scale 2)
- `availableCredit`: Available credit (creditLimit - currentBalance) (number, precision 15 scale 2)
- `cashAdvanceLimit`: Cash advance limit (number, precision 15 scale 2)
- `interestRate`: Annual interest rate percentage (number, precision 5 scale 2)
- `reissueDate`: Card reissue date (string, ISO 8601 date format)
- `groupId`: Account group identifier (string, max 10 chars)

**Error Responses**:

**404 Not Found** - Account doesn't exist:
```json
{
  "timestamp": "2024-02-29T10:40:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account not found with ID: 00000000001",
  "path": "/api/accounts/00000000001"
}
```

**403 Forbidden** - User lacks permission:
```json
{
  "timestamp": "2024-02-29T10:40:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to view this account",
  "path": "/api/accounts/00000000001"
}
```

**Example Request**:

```bash
curl -X GET http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### POST /api/accounts

**Description**: Create a new credit card account

**COBOL/CICS Mapping**: Program COACTADD

**Authorization**: Required (ROLE_ADMIN only)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Request Body**:

```json
{
  "customerId": "000000001",
  "accountStatus": "A",
  "creditLimit": 5000.00,
  "cashCreditLimit": 1000.00,
  "interestRate": 15.99,
  "expirationDate": "2029-12-31",
  "groupId": "DEFAULT"
}
```

**Request Field Validation**:
- `customerId`: Required, 9 digits, must reference existing customer
- `accountStatus`: Required, 1 character ('A', 'C', or 'S')
- `creditLimit`: Required, positive number, max 99999999999.99
- `cashCreditLimit`: Required, positive number, cannot exceed creditLimit
- `interestRate`: Required, 0.00 to 99.999
- `expirationDate`: Required, future date in ISO 8601 format
- `groupId`: Optional, max 10 characters, defaults to 'DEFAULT'

**Success Response** (201 Created):

```json
{
  "accountId": "00000000099",
  "customerId": "000000001",
  "accountStatus": "A",
  "openDate": "2024-02-29",
  "creditLimit": 5000.00,
  "cashCreditLimit": 1000.00,
  "currentBalance": 0.00,
  "availableCredit": 5000.00,
  "interestRate": 15.99,
  "expirationDate": "2029-12-31",
  "groupId": "DEFAULT"
}
```

**Response Headers**:
```
Location: /api/accounts/00000000099
```

**Error Responses**:

**400 Bad Request** - Validation errors:
```json
{
  "timestamp": "2024-02-29T11:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/accounts",
  "errors": [
    {
      "field": "cashCreditLimit",
      "rejectedValue": 6000.00,
      "message": "Cash credit limit cannot exceed credit limit"
    }
  ]
}
```

**403 Forbidden** - Insufficient permissions:
```json
{
  "timestamp": "2024-02-29T11:00:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Admin role required to create accounts",
  "path": "/api/accounts"
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "000000001",
    "accountStatus": "A",
    "creditLimit": 5000.00,
    "cashCreditLimit": 1000.00,
    "interestRate": 15.99,
    "expirationDate": "2029-12-31",
    "groupId": "DEFAULT"
  }'
```

---

### PUT /api/accounts/{accountId}

**Description**: Update account information including limits, status, and interest rate

**COBOL/CICS Mapping**: Transaction CAUP (COACTUPC program)

**Authorization**: Required (ROLE_ADMIN only)

**Path Parameters**:
- `accountId`: 11-digit account number (string)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Request Body**:

```json
{
  "accountStatus": "A",
  "creditLimit": 7500.00,
  "cashCreditLimit": 1500.00,
  "interestRate": 14.99,
  "expirationDate": "2029-12-31"
}
```

**Request Field Validation**:
- `accountStatus`: Optional, 1 character ('A', 'C', or 'S')
- `creditLimit`: Optional, positive number, max 99999999999.99
- `cashCreditLimit`: Optional, positive number, cannot exceed creditLimit
- `interestRate`: Optional, 0.00 to 99.999
- `expirationDate`: Optional, future date in ISO 8601 format

**Note**: Only fields provided in the request will be updated (partial update supported)

**Success Response** (200 OK):

```json
{
  "accountId": "00000000001",
  "customerId": "000000001",
  "accountStatus": "A",
  "openDate": "2020-01-15",
  "creditLimit": 7500.00,
  "cashCreditLimit": 1500.00,
  "currentBalance": 1523.45,
  "availableCredit": 5976.55,
  "interestRate": 14.99,
  "expirationDate": "2029-12-31",
  "groupId": "DEFAULT",
  "lastUpdatedDate": "2024-02-29T11:15:00Z"
}
```

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T11:15:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account not found with ID: 00000000001",
  "path": "/api/accounts/00000000001"
}
```

**400 Bad Request** - Invalid update:
```json
{
  "timestamp": "2024-02-29T11:15:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot reduce credit limit below current balance",
  "path": "/api/accounts/00000000001"
}
```

**Example Request**:

```bash
curl -X PUT http://localhost:8080/api/accounts/00000000001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "creditLimit": 7500.00,
    "interestRate": 14.99
  }'
```

---

## Card Management Endpoints

### GET /api/cards

**Description**: List credit cards with pagination and filtering

**COBOL/CICS Mapping**: Transaction CCLI (COCRDLIC program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)
- Regular users see only their own cards
- Admin users see all cards (with optional filtering)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters**:
- `page`: Page number (zero-indexed, default: 0)
- `size`: Items per page (default: 7, max: 50)
- `sort`: Sort field and direction (default: `cardNumber,asc`)
  - Valid sort fields: `cardNumber`, `customerId`, `accountId`, `expiryDate`, `cardStatus`
  - Direction: `asc` or `desc`
- `accountId`: Filter by account ID (optional, 11 digits)
- `customerId`: Filter by customer ID (optional, 9 digits, admin only)
- `cardStatus`: Filter by status (optional, 1 character: 'A', 'E', 'B')

**Success Response** (200 OK):

```json
{
  "content": [
    {
      "cardNumber": "4111111111111111",
      "customerId": "000000001",
      "accountId": "00000000001",
      "cardType": "VISA",
      "expiryDate": "12/25",
      "cvv": "***",
      "embossedName": "JOHN DOE",
      "cardStatus": "A",
      "cardStatusDescription": "Active",
      "issueDate": "2023-01-15",
      "activeDate": "2023-01-20"
    },
    {
      "cardNumber": "4222222222222222",
      "customerId": "000000001",
      "accountId": "00000000001",
      "cardType": "VISA",
      "expiryDate": "06/26",
      "cvv": "***",
      "embossedName": "JOHN DOE",
      "cardStatus": "A",
      "cardStatusDescription": "Active",
      "issueDate": "2024-01-10",
      "activeDate": "2024-01-15"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 7,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 42,
  "totalPages": 6,
  "last": false,
  "first": true,
  "size": 7,
  "number": 0,
  "numberOfElements": 7,
  "empty": false
}
```

**Response Fields**:
- `content`: Array of card objects
  - `cardNumber`: 16-digit card number (string, masked for non-admin users)
  - `customerId`: Customer identifier (string, 9 digits)
  - `accountId`: Account identifier (string, 11 digits)
  - `cardType`: Card brand (string, e.g., "VISA", "MASTERCARD")
  - `expiryDate`: Expiration date (string, MM/YY format)
  - `cvv`: Card verification value (string, masked as "***" for security)
  - `embossedName`: Name on card (string, max 50 chars, uppercase)
  - `cardStatus`: Status code (string, 1 char: 'A'=Active, 'E'=Expired, 'B'=Blocked)
  - `cardStatusDescription`: Human-readable status (string)
  - `issueDate`: Card issuance date (string, ISO 8601 date)
  - `activeDate`: Card activation date (string, ISO 8601 date)
- `pageable`: Pagination metadata
- `totalElements`: Total number of cards matching criteria (integer)
- `totalPages`: Total number of pages (integer)
- `last`: Whether this is the last page (boolean)
- `first`: Whether this is the first page (boolean)
- `size`: Page size (integer)
- `number`: Current page number (integer)
- `numberOfElements`: Number of elements in current page (integer)
- `empty`: Whether the result is empty (boolean)

**Example Request**:

```bash
# Get first page with default size
curl -X GET "http://localhost:8080/api/cards?page=0&size=7" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."

# Get cards for specific account, sorted by expiry date
curl -X GET "http://localhost:8080/api/cards?accountId=00000000001&sort=expiryDate,asc" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### GET /api/cards/{cardNumber}

**Description**: Retrieve detailed information for a specific card including recent transactions

**COBOL/CICS Mapping**: Transaction CCDL (COCRDSLC program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Path Parameters**:
- `cardNumber`: 16-digit card number (string, pattern: `\d{16}`)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (200 OK):

```json
{
  "cardNumber": "4111111111111111",
  "customerId": "000000001",
  "customerName": "John Doe",
  "accountId": "00000000001",
  "cardType": "VISA",
  "expiryDate": "12/25",
  "cvv": "***",
  "embossedName": "JOHN DOE",
  "cardStatus": "A",
  "cardStatusDescription": "Active",
  "issueDate": "2023-01-15",
  "activeDate": "2023-01-20",
  "currentBalance": 1523.45,
  "creditLimit": 5000.00,
  "availableCredit": 3476.55,
  "recentTransactions": [
    {
      "transactionId": "T000001234567890",
      "transactionDate": "2024-02-28T14:30:00Z",
      "transactionAmount": 45.67,
      "merchantName": "AMAZON.COM",
      "transactionType": "Purchase",
      "transactionStatus": "Posted"
    },
    {
      "transactionId": "T000001234567889",
      "transactionDate": "2024-02-27T09:15:00Z",
      "transactionAmount": 125.00,
      "merchantName": "SHELL GAS STATION",
      "transactionType": "Purchase",
      "transactionStatus": "Posted"
    }
  ]
}
```

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T11:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Card not found with number: 4111111111111111",
  "path": "/api/cards/4111111111111111"
}
```

**403 Forbidden**:
```json
{
  "timestamp": "2024-02-29T11:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to view this card",
  "path": "/api/cards/4111111111111111"
}
```

**Example Request**:

```bash
curl -X GET http://localhost:8080/api/cards/4111111111111111 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### PUT /api/cards/{cardNumber}

**Description**: Update card information including status and limits

**COBOL/CICS Mapping**: Transaction CCUP (COCRDUPC program)

**Authorization**: Required (ROLE_ADMIN only)

**Path Parameters**:
- `cardNumber`: 16-digit card number (string)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Request Body**:

```json
{
  "cardStatus": "B",
  "expiryDate": "12/26",
  "embossedName": "JOHN M DOE"
}
```

**Request Field Validation**:
- `cardStatus`: Optional, 1 character ('A'=Active, 'E'=Expired, 'B'=Blocked)
- `expiryDate`: Optional, MM/YY format, future date
- `embossedName`: Optional, max 50 characters, uppercase letters and spaces only

**Success Response** (200 OK):

```json
{
  "cardNumber": "4111111111111111",
  "customerId": "000000001",
  "accountId": "00000000001",
  "cardType": "VISA",
  "expiryDate": "12/26",
  "cvv": "***",
  "embossedName": "JOHN M DOE",
  "cardStatus": "B",
  "cardStatusDescription": "Blocked",
  "issueDate": "2023-01-15",
  "activeDate": "2023-01-20",
  "lastUpdatedDate": "2024-02-29T11:45:00Z"
}
```

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T11:45:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Card not found with number: 4111111111111111",
  "path": "/api/cards/4111111111111111"
}
```

**400 Bad Request**:
```json
{
  "timestamp": "2024-02-29T11:45:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot set expiry date to past date",
  "path": "/api/cards/4111111111111111"
}
```

**Example Request**:

```bash
curl -X PUT http://localhost:8080/api/cards/4111111111111111 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "cardStatus": "B"
  }'
```

---

## Transaction Endpoints

### GET /api/transactions

**Description**: List transactions with pagination, filtering, and date range support

**COBOL/CICS Mapping**: Transaction CT00 (COTRN00C program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)
- Regular users see only transactions for their own accounts
- Admin users can view all transactions

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters**:
- `page`: Page number (zero-indexed, default: 0)
- `size`: Items per page (default: 10, max: 100)
- `sort`: Sort field and direction (default: `transactionDate,desc`)
  - Valid sort fields: `transactionDate`, `transactionAmount`, `merchantName`, `transactionType`
  - Direction: `asc` or `desc`
- `accountId`: Filter by account ID (required for regular users, optional for admin)
- `cardNumber`: Filter by card number (optional, 16 digits)
- `startDate`: Filter transactions from date (optional, ISO 8601 date)
- `endDate`: Filter transactions to date (optional, ISO 8601 date)
- `categoryId`: Filter by transaction category (optional, integer)
- `minAmount`: Minimum transaction amount (optional, decimal)
- `maxAmount`: Maximum transaction amount (optional, decimal)

**Success Response** (200 OK):

```json
{
  "content": [
    {
      "transactionId": "T000001234567890",
      "accountId": "00000000001",
      "cardNumber": "4111111111111111",
      "transactionDate": "2024-02-28T14:30:00Z",
      "transactionAmount": 45.67,
      "transactionTypeCode": "01",
      "transactionType": "Purchase",
      "transactionCategoryId": 5010,
      "transactionCategory": "Retail",
      "merchantName": "AMAZON.COM",
      "merchantCity": "Seattle",
      "merchantZip": "98101",
      "transactionDesc": "Online purchase",
      "transactionStatus": "Posted",
      "originalAmount": 45.67,
      "confirmationNumber": "CONF123456789"
    },
    {
      "transactionId": "T000001234567889",
      "accountId": "00000000001",
      "cardNumber": "4111111111111111",
      "transactionDate": "2024-02-27T09:15:00Z",
      "transactionAmount": 125.00,
      "transactionTypeCode": "01",
      "transactionType": "Purchase",
      "transactionCategoryId": 5411,
      "transactionCategory": "Grocery Stores",
      "merchantName": "WHOLE FOODS MARKET",
      "merchantCity": "Austin",
      "merchantZip": "78701",
      "transactionDesc": "Grocery purchase",
      "transactionStatus": "Posted",
      "originalAmount": 125.00,
      "confirmationNumber": "CONF123456788"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 247,
  "totalPages": 25,
  "last": false,
  "first": true,
  "size": 10,
  "number": 0,
  "numberOfElements": 10,
  "empty": false
}
```

**Response Fields**:
- `content`: Array of transaction objects
  - `transactionId`: Unique transaction identifier (string, 17 characters)
  - `accountId`: Account number (string, 11 digits)
  - `cardNumber`: Card number (string, 16 digits, masked for regular users)
  - `transactionDate`: Transaction timestamp (string, ISO 8601 datetime with timezone)
  - `transactionAmount`: Amount in dollars (number, precision 12 scale 2)
  - `transactionTypeCode`: Type code (string, 2 digits)
  - `transactionType`: Type description (string)
  - `transactionCategoryId`: Category ID (integer)
  - `transactionCategory`: Category description (string)
  - `merchantName`: Merchant name (string, max 50 chars)
  - `merchantCity`: Merchant city (string, max 50 chars)
  - `merchantZip`: Merchant ZIP code (string, max 10 chars)
  - `transactionDesc`: Description (string, max 100 chars)
  - `transactionStatus`: Status (string: "Posted", "Pending", "Declined")
  - `originalAmount`: Original amount before fees (number, precision 12 scale 2)
  - `confirmationNumber`: Confirmation code (string, max 20 chars)

**Example Request**:

```bash
# Get transactions for account with date range
curl -X GET "http://localhost:8080/api/transactions?accountId=00000000001&startDate=2024-02-01&endDate=2024-02-29&page=0&size=10" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."

# Get transactions filtered by category and amount
curl -X GET "http://localhost:8080/api/transactions?accountId=00000000001&categoryId=5411&minAmount=50&maxAmount=200" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### GET /api/transactions/categories/summary

**Description**: Retrieve transaction category aggregation summary with totals by category

**COBOL/CICS Mapping**: Transaction CT01 (COTRN01C program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters**:
- `accountId`: Account ID (required, 11 digits)
- `startDate`: Start date for aggregation (required, ISO 8601 date)
- `endDate`: End date for aggregation (required, ISO 8601 date)

**Success Response** (200 OK):

```json
{
  "accountId": "00000000001",
  "periodStart": "2024-02-01",
  "periodEnd": "2024-02-29",
  "totalTransactions": 42,
  "totalAmount": 3456.78,
  "categories": [
    {
      "categoryId": 5411,
      "categoryName": "Grocery Stores",
      "transactionCount": 12,
      "totalAmount": 1234.56,
      "averageAmount": 102.88,
      "percentageOfTotal": 35.7
    },
    {
      "categoryId": 5812,
      "categoryName": "Restaurants",
      "transactionCount": 8,
      "totalAmount": 567.89,
      "averageAmount": 70.99,
      "percentageOfTotal": 16.4
    },
    {
      "categoryId": 5541,
      "categoryName": "Service Stations",
      "transactionCount": 6,
      "totalAmount": 456.78,
      "averageAmount": 76.13,
      "percentageOfTotal": 13.2
    },
    {
      "categoryId": 5999,
      "categoryName": "Miscellaneous",
      "transactionCount": 16,
      "totalAmount": 1197.55,
      "averageAmount": 74.85,
      "percentageOfTotal": 34.7
    }
  ]
}
```

**Response Fields**:
- `accountId`: Account identifier (string)
- `periodStart`: Start date of summary period (string, ISO 8601 date)
- `periodEnd`: End date of summary period (string, ISO 8601 date)
- `totalTransactions`: Total number of transactions (integer)
- `totalAmount`: Sum of all transaction amounts (number, precision 15 scale 2)
- `categories`: Array of category summaries
  - `categoryId`: Category identifier (integer)
  - `categoryName`: Category description (string)
  - `transactionCount`: Number of transactions in category (integer)
  - `totalAmount`: Sum of amounts in category (number, precision 15 scale 2)
  - `averageAmount`: Average transaction amount (number, precision 15 scale 2)
  - `percentageOfTotal`: Percentage of total spending (number, precision 5 scale 2)

**Error Responses**:

**400 Bad Request** - Invalid date range:
```json
{
  "timestamp": "2024-02-29T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "End date must be after start date",
  "path": "/api/transactions/categories/summary"
}
```

**Example Request**:

```bash
curl -X GET "http://localhost:8080/api/transactions/categories/summary?accountId=00000000001&startDate=2024-02-01&endDate=2024-02-29" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### POST /api/transactions

**Description**: Create a new transaction (post a charge to an account)

**COBOL/CICS Mapping**: Transaction CT02 (COTRN02C program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body**:

```json
{
  "accountId": "00000000001",
  "cardNumber": "4111111111111111",
  "transactionTypeCode": "01",
  "transactionCategoryId": 5411,
  "transactionAmount": 125.50,
  "transactionDesc": "Grocery purchase",
  "merchantName": "WHOLE FOODS MARKET",
  "merchantCity": "Austin",
  "merchantState": "TX",
  "merchantZip": "78701"
}
```

**Request Field Validation**:
- `accountId`: Required, 11 digits, must be active account
- `cardNumber`: Required, 16 digits, must be active card for the account
- `transactionTypeCode`: Required, 2 digits, must be valid transaction type
- `transactionCategoryId`: Required, integer, must be valid category
- `transactionAmount`: Required, positive number 0.01 to 9999999999.99
- `transactionDesc`: Required, max 100 characters
- `merchantName`: Required, max 50 characters
- `merchantCity`: Optional, max 50 characters
- `merchantState`: Optional, 2 characters (US state code)
- `merchantZip`: Optional, max 10 characters

**Success Response** (201 Created):

```json
{
  "transactionId": "T000001234567891",
  "accountId": "00000000001",
  "cardNumber": "4111111111111111",
  "transactionDate": "2024-02-29T12:15:30Z",
  "transactionAmount": 125.50,
  "transactionTypeCode": "01",
  "transactionType": "Purchase",
  "transactionCategoryId": 5411,
  "transactionCategory": "Grocery Stores",
  "merchantName": "WHOLE FOODS MARKET",
  "merchantCity": "Austin",
  "merchantState": "TX",
  "merchantZip": "78701",
  "transactionDesc": "Grocery purchase",
  "transactionStatus": "Posted",
  "confirmationNumber": "CONF123456790",
  "newBalance": 1648.95,
  "availableCredit": 3351.05
}
```

**Response Headers**:
```
Location: /api/transactions/T000001234567891
```

**Error Responses**:

**400 Bad Request** - Insufficient credit:
```json
{
  "timestamp": "2024-02-29T12:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Insufficient available credit for this transaction",
  "path": "/api/transactions",
  "details": {
    "availableCredit": 500.00,
    "requestedAmount": 1250.00
  }
}
```

**400 Bad Request** - Card not active:
```json
{
  "timestamp": "2024-02-29T12:15:30Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Card is not active (status: Blocked)",
  "path": "/api/transactions"
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "00000000001",
    "cardNumber": "4111111111111111",
    "transactionTypeCode": "01",
    "transactionCategoryId": 5411,
    "transactionAmount": 125.50,
    "transactionDesc": "Grocery purchase",
    "merchantName": "WHOLE FOODS MARKET",
    "merchantCity": "Austin",
    "merchantState": "TX",
    "merchantZip": "78701"
  }'
```

---

## Bill Payment Endpoints

### POST /api/payments/bill

**Description**: Process a bill payment transaction

**COBOL/CICS Mapping**: Transaction CB00 (COBIL00C program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body**:

```json
{
  "accountId": "00000000001",
  "paymentAmount": 250.00,
  "payeeId": "PAYEE001",
  "payeeName": "Electric Company",
  "paymentDate": "2024-03-01",
  "paymentMethod": "ACH",
  "confirmationEmail": "john.doe@example.com"
}
```

**Request Field Validation**:
- `accountId`: Required, 11 digits, must be active account
- `paymentAmount`: Required, positive number 0.01 to 9999999999.99, cannot exceed current balance
- `payeeId`: Required, max 20 characters
- `payeeName`: Required, max 50 characters
- `paymentDate`: Required, ISO 8601 date, current or future date (max 30 days in future)
- `paymentMethod`: Required, one of: "ACH", "Check", "Wire"
- `confirmationEmail`: Optional, valid email format

**Success Response** (200 OK):

```json
{
  "paymentId": "PAY000123456",
  "accountId": "00000000001",
  "paymentAmount": 250.00,
  "payeeId": "PAYEE001",
  "payeeName": "Electric Company",
  "paymentDate": "2024-03-01",
  "paymentMethod": "ACH",
  "paymentStatus": "Scheduled",
  "confirmationNumber": "BILLPAY987654321",
  "transactionId": "T000001234567892",
  "newBalance": 1273.45,
  "availableCredit": 3726.55,
  "processedDate": "2024-02-29T12:30:00Z"
}
```

**Response Fields**:
- `paymentId`: Unique payment identifier (string)
- `accountId`: Account number (string, 11 digits)
- `paymentAmount`: Payment amount (number, precision 12 scale 2)
- `payeeId`: Payee identifier (string)
- `payeeName`: Payee name (string)
- `paymentDate`: Scheduled payment date (string, ISO 8601 date)
- `paymentMethod`: Payment method (string)
- `paymentStatus`: Status (string: "Scheduled", "Processing", "Completed", "Failed")
- `confirmationNumber`: Payment confirmation code (string)
- `transactionId`: Associated transaction ID (string)
- `newBalance`: Updated account balance (number, precision 15 scale 2)
- `availableCredit`: Updated available credit (number, precision 15 scale 2)
- `processedDate`: Processing timestamp (string, ISO 8601 datetime)

**Error Responses**:

**400 Bad Request** - Payment amount exceeds balance:
```json
{
  "timestamp": "2024-02-29T12:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Payment amount exceeds current balance",
  "path": "/api/payments/bill",
  "details": {
    "currentBalance": 1523.45,
    "requestedPayment": 2000.00
  }
}
```

**400 Bad Request** - Invalid payment date:
```json
{
  "timestamp": "2024-02-29T12:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Payment date cannot be more than 30 days in the future",
  "path": "/api/payments/bill"
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/payments/bill \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "00000000001",
    "paymentAmount": 250.00,
    "payeeId": "PAYEE001",
    "payeeName": "Electric Company",
    "paymentDate": "2024-03-01",
    "paymentMethod": "ACH"
  }'
```

---

## Report Endpoints

### GET /api/reports/menu

**Description**: Retrieve available report types based on user role

**COBOL/CICS Mapping**: Transaction CR00 (CORPT00C program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (200 OK):

```json
{
  "userId": "USER0001",
  "userRole": "R",
  "availableReports": [
    {
      "reportId": "ACCT_STMT",
      "reportName": "Account Statement",
      "reportDescription": "Detailed account statement for specified date range",
      "category": "Account",
      "accessLevel": "USER"
    },
    {
      "reportId": "TXN_HIST",
      "reportName": "Transaction History",
      "reportDescription": "Transaction history report with filtering options",
      "category": "Transaction",
      "accessLevel": "USER"
    },
    {
      "reportId": "CAT_SUM",
      "reportName": "Category Summary",
      "reportDescription": "Spending summary by transaction category",
      "category": "Transaction",
      "accessLevel": "USER"
    }
  ]
}
```

**Admin User Response** (includes additional reports):

```json
{
  "userId": "ADMIN001",
  "userRole": "A",
  "availableReports": [
    {
      "reportId": "ACCT_STMT",
      "reportName": "Account Statement",
      "reportDescription": "Detailed account statement for specified date range",
      "category": "Account",
      "accessLevel": "USER"
    },
    {
      "reportId": "TXN_HIST",
      "reportName": "Transaction History",
      "reportDescription": "Transaction history report with filtering options",
      "category": "Transaction",
      "accessLevel": "USER"
    },
    {
      "reportId": "CAT_SUM",
      "reportName": "Category Summary",
      "reportDescription": "Spending summary by transaction category",
      "category": "Transaction",
      "accessLevel": "USER"
    },
    {
      "reportId": "ALL_ACCT",
      "reportName": "All Accounts Report",
      "reportDescription": "Comprehensive report of all accounts in the system",
      "category": "Admin",
      "accessLevel": "ADMIN"
    },
    {
      "reportId": "USR_ACT",
      "reportName": "User Activity Report",
      "reportDescription": "User activity and audit trail report",
      "category": "Admin",
      "accessLevel": "ADMIN"
    },
    {
      "reportId": "SYS_PERF",
      "reportName": "System Performance Report",
      "reportDescription": "System performance metrics and statistics",
      "category": "Admin",
      "accessLevel": "ADMIN"
    }
  ]
}
```

**Example Request**:

```bash
curl -X GET http://localhost:8080/api/reports/menu \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### GET /api/reports/account-statement

**Description**: Generate account statement report for specified date range

**COBOL/CICS Mapping**: Statement generation functionality (CBSTM03A program)

**Authorization**: Required (ROLE_USER or ROLE_ADMIN)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Accept: application/json or application/pdf
```

**Query Parameters**:
- `accountId`: Account ID (required, 11 digits)
- `startDate`: Statement start date (required, ISO 8601 date)
- `endDate`: Statement end date (required, ISO 8601 date)
- `format`: Output format (optional, "json" or "pdf", default: "json")

**Success Response** (200 OK) - JSON Format:

```json
{
  "statementId": "STMT202402290001",
  "accountId": "00000000001",
  "customerId": "000000001",
  "customerName": "John Doe",
  "statementPeriod": {
    "startDate": "2024-02-01",
    "endDate": "2024-02-29"
  },
  "previousBalance": 1400.00,
  "totalCredits": 0.00,
  "totalDebits": 123.45,
  "currentBalance": 1523.45,
  "creditLimit": 5000.00,
  "availableCredit": 3476.55,
  "minimumPaymentDue": 45.00,
  "paymentDueDate": "2024-03-25",
  "transactions": [
    {
      "transactionDate": "2024-02-28T14:30:00Z",
      "merchantName": "AMAZON.COM",
      "transactionAmount": 45.67,
      "transactionType": "Purchase"
    },
    {
      "transactionDate": "2024-02-27T09:15:00Z",
      "merchantName": "SHELL GAS STATION",
      "transactionAmount": 125.00,
      "transactionType": "Purchase"
    }
  ],
  "categoryBreakdown": [
    {
      "categoryName": "Retail",
      "amount": 45.67,
      "percentage": 37.0
    },
    {
      "categoryName": "Gas/Fuel",
      "amount": 77.78,
      "percentage": 63.0
    }
  ],
  "generatedDate": "2024-02-29T13:00:00Z"
}
```

**Success Response** (200 OK) - PDF Format:

Returns binary PDF file with appropriate `Content-Type: application/pdf` header.

**Example Request**:

```bash
# JSON format
curl -X GET "http://localhost:8080/api/reports/account-statement?accountId=00000000001&startDate=2024-02-01&endDate=2024-02-29&format=json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."

# PDF format
curl -X GET "http://localhost:8080/api/reports/account-statement?accountId=00000000001&startDate=2024-02-01&endDate=2024-02-29&format=pdf" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -o statement.pdf
```

---

## User Management Endpoints

### GET /api/users

**Description**: List all users in the system

**COBOL/CICS Mapping**: Transaction CU00 (COUSR00C program)

**Authorization**: Required (ROLE_ADMIN only)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Query Parameters**:
- `page`: Page number (zero-indexed, default: 0)
- `size`: Items per page (default: 20, max: 100)
- `sort`: Sort field and direction (default: `userId,asc`)
- `userType`: Filter by user type (optional, 'R' or 'A')
- `search`: Search by userId or userName (optional)

**Success Response** (200 OK):

```json
{
  "content": [
    {
      "userId": "USER0001",
      "userName": "John Doe",
      "userType": "R",
      "userTypeDescription": "Regular User",
      "email": "john.doe@example.com",
      "phoneNumber": "512-555-1234",
      "accountStatus": "Active",
      "lastLoginDate": "2024-02-29T08:30:00Z",
      "createdDate": "2020-01-15T10:00:00Z",
      "lastUpdatedDate": "2024-01-10T14:20:00Z"
    },
    {
      "userId": "ADMIN001",
      "userName": "Admin User",
      "userType": "A",
      "userTypeDescription": "Admin",
      "email": "admin@example.com",
      "phoneNumber": "512-555-9999",
      "accountStatus": "Active",
      "lastLoginDate": "2024-02-29T09:00:00Z",
      "createdDate": "2020-01-01T10:00:00Z",
      "lastUpdatedDate": "2024-02-01T11:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 42,
  "totalPages": 3,
  "last": false,
  "first": true,
  "size": 20,
  "number": 0,
  "numberOfElements": 20,
  "empty": false
}
```

**Example Request**:

```bash
curl -X GET "http://localhost:8080/api/users?page=0&size=20&userType=R" \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### GET /api/users/{userId}

**Description**: Retrieve detailed user profile information

**COBOL/CICS Mapping**: Transaction CU01 (COUSR01C program)

**Authorization**: Required (ROLE_ADMIN or own user)

**Path Parameters**:
- `userId`: User identifier (string, 1-8 characters)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (200 OK):

```json
{
  "userId": "USER0001",
  "userName": "John Doe",
  "userType": "R",
  "userTypeDescription": "Regular User",
  "email": "john.doe@example.com",
  "phoneNumber": "512-555-1234",
  "accountStatus": "Active",
  "lastLoginDate": "2024-02-29T08:30:00Z",
  "failedLoginAttempts": 0,
  "passwordLastChanged": "2024-01-15T10:00:00Z",
  "passwordExpiryDate": "2024-04-15",
  "createdDate": "2020-01-15T10:00:00Z",
  "createdBy": "ADMIN001",
  "lastUpdatedDate": "2024-01-10T14:20:00Z",
  "lastUpdatedBy": "ADMIN001",
  "linkedCustomerId": "000000001"
}
```

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T13:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "User not found with ID: USER9999",
  "path": "/api/users/USER9999"
}
```

**403 Forbidden** - Non-admin viewing other user:
```json
{
  "timestamp": "2024-02-29T13:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to view this user profile",
  "path": "/api/users/USER0002"
}
```

**Example Request**:

```bash
curl -X GET http://localhost:8080/api/users/USER0001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

### POST /api/users

**Description**: Create a new user account

**COBOL/CICS Mapping**: User creation functionality (COUSR00C program)

**Authorization**: Required (ROLE_ADMIN only)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body**:

```json
{
  "userId": "USER0099",
  "userName": "Jane Smith",
  "password": "SecureP@ssw0rd",
  "userType": "R",
  "email": "jane.smith@example.com",
  "phoneNumber": "512-555-5678",
  "linkedCustomerId": "000000002"
}
```

**Request Field Validation**:
- `userId`: Required, 1-8 characters, alphanumeric, must be unique
- `userName`: Required, max 50 characters
- `password`: Required, 8-20 characters, must contain uppercase, lowercase, digit, and special character
- `userType`: Required, 1 character ('R' or 'A')
- `email`: Required, valid email format, max 100 characters
- `phoneNumber`: Optional, valid phone format
- `linkedCustomerId`: Optional, 9 digits, must reference existing customer

**Success Response** (201 Created):

```json
{
  "userId": "USER0099",
  "userName": "Jane Smith",
  "userType": "R",
  "userTypeDescription": "Regular User",
  "email": "jane.smith@example.com",
  "phoneNumber": "512-555-5678",
  "accountStatus": "Active",
  "passwordLastChanged": "2024-02-29T13:45:00Z",
  "passwordExpiryDate": "2024-05-29",
  "createdDate": "2024-02-29T13:45:00Z",
  "createdBy": "ADMIN001",
  "linkedCustomerId": "000000002"
}
```

**Response Headers**:
```
Location: /api/users/USER0099
```

**Error Responses**:

**400 Bad Request** - User ID already exists:
```json
{
  "timestamp": "2024-02-29T13:45:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "User ID already exists: USER0099",
  "path": "/api/users"
}
```

**400 Bad Request** - Password validation failure:
```json
{
  "timestamp": "2024-02-29T13:45:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/users",
  "errors": [
    {
      "field": "password",
      "rejectedValue": "weak",
      "message": "Password must be 8-20 characters and contain uppercase, lowercase, digit, and special character"
    }
  ]
}
```

**Example Request**:

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "USER0099",
    "userName": "Jane Smith",
    "password": "SecureP@ssw0rd",
    "userType": "R",
    "email": "jane.smith@example.com",
    "phoneNumber": "512-555-5678",
    "linkedCustomerId": "000000002"
  }'
```

---

### PUT /api/users/{userId}

**Description**: Update user information

**COBOL/CICS Mapping**: Transaction CU02 (COUSR02C program)

**Authorization**: Required (ROLE_ADMIN or own user for non-sensitive fields)

**Path Parameters**:
- `userId`: User identifier (string)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

**Request Body**:

```json
{
  "userName": "John M Doe",
  "email": "john.m.doe@example.com",
  "phoneNumber": "512-555-1111",
  "password": "NewP@ssw0rd123"
}
```

**Request Field Validation**:
- `userName`: Optional, max 50 characters
- `email`: Optional, valid email format
- `phoneNumber`: Optional, valid phone format
- `password`: Optional, 8-20 characters with complexity requirements
- `userType`: Admin only, 1 character ('R' or 'A')
- `accountStatus`: Admin only, valid status value

**Success Response** (200 OK):

```json
{
  "userId": "USER0001",
  "userName": "John M Doe",
  "userType": "R",
  "userTypeDescription": "Regular User",
  "email": "john.m.doe@example.com",
  "phoneNumber": "512-555-1111",
  "accountStatus": "Active",
  "lastLoginDate": "2024-02-29T08:30:00Z",
  "passwordLastChanged": "2024-02-29T14:00:00Z",
  "passwordExpiryDate": "2024-05-29",
  "createdDate": "2020-01-15T10:00:00Z",
  "lastUpdatedDate": "2024-02-29T14:00:00Z",
  "lastUpdatedBy": "USER0001",
  "linkedCustomerId": "000000001"
}
```

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T14:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "User not found with ID: USER9999",
  "path": "/api/users/USER9999"
}
```

**403 Forbidden** - Attempting to change restricted fields:
```json
{
  "timestamp": "2024-02-29T14:00:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Only admin users can change user type",
  "path": "/api/users/USER0001"
}
```

**Example Request**:

```bash
curl -X PUT http://localhost:8080/api/users/USER0001 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john.m.doe@example.com",
    "phoneNumber": "512-555-1111"
  }'
```

---

### DELETE /api/users/{userId}

**Description**: Delete a user account

**COBOL/CICS Mapping**: Transaction CU03 (COUSR03C program)

**Authorization**: Required (ROLE_ADMIN only)

**Path Parameters**:
- `userId`: User identifier (string)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (204 No Content):

No response body. HTTP status 204 indicates successful deletion.

**Error Responses**:

**404 Not Found**:
```json
{
  "timestamp": "2024-02-29T14:15:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "User not found with ID: USER9999",
  "path": "/api/users/USER9999"
}
```

**400 Bad Request** - Cannot delete own account:
```json
{
  "timestamp": "2024-02-29T14:15:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot delete your own user account",
  "path": "/api/users/ADMIN001"
}
```

**Example Request**:

```bash
curl -X DELETE http://localhost:8080/api/users/USER0099 \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

## Administrative Endpoints

### GET /api/admin/menu

**Description**: Retrieve administrative menu options based on admin user permissions

**COBOL/CICS Mapping**: Transaction CA00 (COADM01C program)

**Authorization**: Required (ROLE_ADMIN only)

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
```

**Success Response** (200 OK):

```json
{
  "adminUserId": "ADMIN001",
  "adminUserName": "Admin User",
  "menuOptions": [
    {
      "optionId": "USER_MGMT",
      "optionName": "User Management",
      "optionDescription": "Create, update, delete users",
      "endpoint": "/api/users",
      "accessLevel": "ADMIN"
    },
    {
      "optionId": "ACCT_ADMIN",
      "optionName": "Account Administration",
      "optionDescription": "Administrative account operations",
      "endpoint": "/api/accounts",
      "accessLevel": "ADMIN"
    },
    {
      "optionId": "CARD_ADMIN",
      "optionName": "Card Administration",
      "optionDescription": "Administrative card operations",
      "endpoint": "/api/cards",
      "accessLevel": "ADMIN"
    },
    {
      "optionId": "REPORTS",
      "optionName": "Administrative Reports",
      "optionDescription": "Generate system and audit reports",
      "endpoint": "/api/reports",
      "accessLevel": "ADMIN"
    },
    {
      "optionId": "BATCH_JOBS",
      "optionName": "Batch Job Management",
      "optionDescription": "View and manage batch processing jobs",
      "endpoint": "/api/admin/batch-jobs",
      "accessLevel": "ADMIN"
    },
    {
      "optionId": "SYS_CONFIG",
      "optionName": "System Configuration",
      "optionDescription": "System settings and configuration",
      "endpoint": "/api/admin/config",
      "accessLevel": "ADMIN"
    }
  ]
}
```

**Error Responses**:

**403 Forbidden** - Non-admin user:
```json
{
  "timestamp": "2024-02-29T14:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Admin role required to access administrative functions",
  "path": "/api/admin/menu"
}
```

**Example Request**:

```bash
curl -X GET http://localhost:8080/api/admin/menu \
  -H "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9..."
```

---

## Error Handling

### Standard Error Response Format

All error responses follow a consistent structure to facilitate client-side error handling:

```json
{
  "timestamp": "2024-02-29T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for request",
  "path": "/api/accounts",
  "errors": [
    {
      "field": "creditLimit",
      "rejectedValue": -1000,
      "message": "Credit limit must be a positive number"
    }
  ]
}
```

**Error Response Fields**:
- `timestamp`: ISO 8601 datetime when error occurred (string)
- `status`: HTTP status code (integer)
- `error`: HTTP status text (string)
- `message`: Human-readable error description (string)
- `path`: Request path where error occurred (string)
- `errors`: Array of field-level validation errors (array, optional)
  - `field`: Field name that failed validation (string)
  - `rejectedValue`: Value that was rejected (any type)
  - `message`: Field-specific error message (string)

### Common HTTP Status Codes

**Success Codes**:
- `200 OK`: Successful GET, PUT, or DELETE request with response body
- `201 Created`: Successful POST request creating a new resource
- `204 No Content`: Successful DELETE request with no response body

**Client Error Codes**:
- `400 Bad Request`: Invalid request parameters, validation failure, or business rule violation
- `401 Unauthorized`: Missing, invalid, or expired JWT token
- `403 Forbidden`: Valid authentication but insufficient permissions
- `404 Not Found`: Requested resource does not exist
- `409 Conflict`: Request conflicts with current resource state (e.g., duplicate user ID)

**Server Error Codes**:
- `500 Internal Server Error`: Unexpected server-side error
- `503 Service Unavailable`: Service temporarily unavailable (maintenance, overload)

### Field Validation Error Codes

The `errors` array in validation failures provides detailed field-level error information:

```json
{
  "timestamp": "2024-02-29T15:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/accounts",
  "errors": [
    {
      "field": "creditLimit",
      "rejectedValue": -1000,
      "message": "Credit limit must be a positive number"
    },
    {
      "field": "customerId",
      "rejectedValue": "ABC123",
      "message": "Customer ID must be exactly 9 digits"
    },
    {
      "field": "expirationDate",
      "rejectedValue": "2020-01-01",
      "message": "Expiration date must be in the future"
    }
  ]
}
```

### Business Rule Violation Errors

Business rule violations return 400 Bad Request with descriptive messages:

```json
{
  "timestamp": "2024-02-29T15:05:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Insufficient available credit for transaction",
  "path": "/api/transactions",
  "details": {
    "availableCredit": 500.00,
    "requestedAmount": 1250.00,
    "accountId": "00000000001"
  }
}
```

### Authentication and Authorization Errors

**401 Unauthorized** - Missing or invalid token:
```json
{
  "timestamp": "2024-02-29T15:10:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "JWT token is missing or invalid",
  "path": "/api/accounts/00000000001"
}
```

**401 Unauthorized** - Expired token:
```json
{
  "timestamp": "2024-02-29T15:10:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "JWT token has expired",
  "path": "/api/accounts/00000000001",
  "details": {
    "expiredAt": "2024-02-28T15:10:00Z"
  }
}
```

**403 Forbidden** - Insufficient permissions:
```json
{
  "timestamp": "2024-02-29T15:15:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Admin role required for this operation",
  "path": "/api/users",
  "details": {
    "requiredRole": "ROLE_ADMIN",
    "userRole": "ROLE_USER"
  }
}
```

---

## Common Patterns

### Pagination

All list endpoints support Spring Data pagination with consistent query parameters:

**Query Parameters**:
- `page`: Zero-indexed page number (default: 0)
- `size`: Number of items per page (default varies by endpoint, max: 100)
- `sort`: Sort specification as `field,direction` (e.g., `accountId,desc`)

**Pagination Response Structure**:

```json
{
  "content": [ /* array of results */ ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 247,
  "totalPages": 25,
  "last": false,
  "first": true,
  "size": 10,
  "number": 0,
  "numberOfElements": 10,
  "empty": false
}
```

**Pagination Metadata Fields**:
- `totalElements`: Total count of items across all pages (integer)
- `totalPages`: Total number of pages (integer)
- `last`: Is this the last page? (boolean)
- `first`: Is this the first page? (boolean)
- `size`: Page size (integer)
- `number`: Current page number (integer)
- `numberOfElements`: Count of items in current page (integer)
- `empty`: Is the result set empty? (boolean)

**Example Pagination Requests**:

```bash
# First page with default size
curl -X GET "http://localhost:8080/api/cards?page=0" \
  -H "Authorization: Bearer TOKEN"

# Second page with custom size
curl -X GET "http://localhost:8080/api/cards?page=1&size=20" \
  -H "Authorization: Bearer TOKEN"

# Sorted results
curl -X GET "http://localhost:8080/api/cards?sort=expiryDate,asc" \
  -H "Authorization: Bearer TOKEN"

# Multiple sort fields
curl -X GET "http://localhost:8080/api/cards?sort=cardStatus,asc&sort=expiryDate,asc" \
  -H "Authorization: Bearer TOKEN"
```

### Date and Time Formats

**Request Date Formats**:
- Full dates: ISO 8601 date format `YYYY-MM-DD` (e.g., `2024-02-29`)
- Datetimes: ISO 8601 with timezone `YYYY-MM-DDTHH:mm:ssZ` (e.g., `2024-02-29T14:30:00Z`)
- Short dates (card expiry): `MM/YY` format (e.g., `12/25`)

**Response Date Formats**:
- All timestamps: ISO 8601 with UTC timezone (e.g., `2024-02-29T14:30:00Z`)
- All dates: ISO 8601 date format (e.g., `2024-02-29`)

**Timezone Handling**:
- All timestamps stored and returned in UTC
- Client applications responsible for timezone conversion for display
- Date-only fields (like expiration dates) have no timezone component

**Example Date Usage**:

```bash
# Query with date range
curl -X GET "http://localhost:8080/api/transactions?accountId=00000000001&startDate=2024-02-01&endDate=2024-02-29" \
  -H "Authorization: Bearer TOKEN"

# POST with datetime
curl -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "00000000001",
    "transactionDate": "2024-02-29T14:30:00Z",
    ...
  }'
```

### Numeric Precision and Rounding

All monetary amounts and decimal values use Java BigDecimal to preserve COBOL COMP-3 precision:

**Monetary Amounts**:
- Precision: 15 digits total
- Scale: 2 decimal places
- Rounding: HALF_UP (matches COBOL rounding behavior)
- Range: -9999999999999.99 to 9999999999999.99

**Interest Rates**:
- Precision: 8 digits total
- Scale: 5 decimal places
- Format: Annual percentage (e.g., 15.99 for 15.99%)
- Range: 0.00000 to 99.99999

**JSON Number Representation**:

```json
{
  "currentBalance": 1523.45,        // Always 2 decimal places
  "interestRate": 15.99000,         // Always 5 decimal places
  "transactionAmount": 125.50       // Always 2 decimal places
}
```

**Precision Preservation**:
- All calculations maintain exact decimal precision
- No floating-point arithmetic used
- Rounding only applied at final presentation layer
- Database stores as PostgreSQL NUMERIC type with matching precision

### Filtering and Search

Many endpoints support filtering via query parameters:

**Filter Types**:
- Exact match: `accountId=00000000001`
- Enum match: `cardStatus=A`
- Range: `minAmount=100&maxAmount=500`
- Date range: `startDate=2024-02-01&endDate=2024-02-29`
- Text search: `search=john` (searches multiple fields)

**Example Filter Requests**:

```bash
# Filter cards by status
curl -X GET "http://localhost:8080/api/cards?cardStatus=A" \
  -H "Authorization: Bearer TOKEN"

# Filter transactions by amount range
curl -X GET "http://localhost:8080/api/transactions?accountId=00000000001&minAmount=100&maxAmount=500" \
  -H "Authorization: Bearer TOKEN"

# Search users
curl -X GET "http://localhost:8080/api/users?search=john" \
  -H "Authorization: Bearer TOKEN"
```

### Partial Updates (PUT)

PUT endpoints support partial updates - only fields provided in the request are updated:

**Full Update**:
```json
{
  "userName": "John M Doe",
  "email": "john.m.doe@example.com",
  "phoneNumber": "512-555-1111",
  "userType": "R"
}
```

**Partial Update (only email)**:
```json
{
  "email": "john.m.doe@example.com"
}
```

Both requests are valid. Partial updates allow clients to modify specific fields without retrieving and resending the entire resource.

### Idempotency

**GET, PUT, DELETE**: Inherently idempotent
- Multiple identical requests produce the same result
- Safe to retry on network failure

**POST**: Not idempotent by default
- Creates new resource on each request
- Clients should implement retry logic carefully
- Consider using idempotency keys for critical operations

---

## OpenAPI/Swagger Documentation

### Interactive API Documentation

The API provides interactive OpenAPI 3.0 documentation via Swagger UI:

**Swagger UI URL**:
```
Development: http://localhost:8080/swagger-ui.html
Production:  https://api.carddemo.example.com/swagger-ui.html
```

**OpenAPI JSON Specification**:
```
http://localhost:8080/v3/api-docs
```

**OpenAPI YAML Specification**:
```
http://localhost:8080/v3/api-docs.yaml
```

### Using Swagger UI

**Authentication in Swagger UI**:

1. Click the "Authorize" button at the top right
2. Enter JWT token in the format: `Bearer <your_token>`
3. Click "Authorize" to apply to all requests
4. Click "Close"

**Testing Endpoints**:

1. Navigate to desired endpoint section
2. Click "Try it out" button
3. Fill in required parameters
4. Click "Execute"
5. View response below with status code, headers, and body

### OpenAPI Specification Highlights

The OpenAPI specification includes:

- **Complete endpoint documentation**: All REST endpoints with descriptions
- **Request/response schemas**: Full JSON schema definitions
- **Authentication configuration**: JWT bearer token security scheme
- **Field validation rules**: Min/max lengths, patterns, required fields
- **Example requests/responses**: Sample data for all endpoints
- **Error responses**: All possible error status codes and formats
- **Enum definitions**: Valid values for status codes, types, etc.

### Generating Client SDKs

The OpenAPI specification can be used to generate client SDKs in various languages:

**Using OpenAPI Generator**:

```bash
# Generate Java client
openapi-generator-cli generate \
  -i http://localhost:8080/v3/api-docs \
  -g java \
  -o ./generated-client/java

# Generate Python client
openapi-generator-cli generate \
  -i http://localhost:8080/v3/api-docs \
  -g python \
  -o ./generated-client/python

# Generate TypeScript/JavaScript client
openapi-generator-cli generate \
  -i http://localhost:8080/v3/api-docs \
  -g typescript-axios \
  -o ./generated-client/typescript
```

### API Versioning Strategy

**Current Version**: v1 (implicit in base path `/api`)

**Future Versioning**:
- Major breaking changes will introduce new version: `/api/v2`
- Minor backward-compatible changes added to existing version
- Previous major versions maintained for minimum 12 months
- Deprecation warnings included in response headers

**Version Deprecation Headers**:
```
Deprecation: true
Sunset: Sat, 31 Dec 2025 23:59:59 GMT
Link: </api/v2/accounts>; rel="successor-version"
```

---

## Additional Resources

### Related Documentation

- **Architecture Documentation**: See `docs/ARCHITECTURE.md` for system architecture details
- **Deployment Guide**: See `docs/DEPLOYMENT.md` for deployment instructions
- **Migration Guide**: See `docs/MIGRATION_GUIDE.md` for COBOL-to-Java transformation mappings
- **Testing Guide**: See `docs/TESTING.md` for API testing strategies

### COBOL/CICS Transaction Mappings

Complete mapping of legacy CICS transactions to REST endpoints:

| CICS Transaction | COBOL Program | REST Endpoint | HTTP Method |
|------------------|---------------|---------------|-------------|
| CC00 | COSGN00C | /api/auth/login | POST |
| CM00 | COMEN01C | /api/menu | GET |
| CAVW | COACTVWC | /api/accounts/{id} | GET |
| CAUP | COACTUPC | /api/accounts/{id} | PUT |
| CADD | COACTADD | /api/accounts | POST |
| CCLI | COCRDLIC | /api/cards | GET |
| CCDL | COCRDSLC | /api/cards/{cardNumber} | GET |
| CCUP | COCRDUPC | /api/cards/{cardNumber} | PUT |
| CT00 | COTRN00C | /api/transactions | GET |
| CT01 | COTRN01C | /api/transactions/categories/summary | GET |
| CT02 | COTRN02C | /api/transactions | POST |
| CB00 | COBIL00C | /api/payments/bill | POST |
| CR00 | CORPT00C | /api/reports/menu | GET |
| CA00 | COADM01C | /api/admin/menu | GET |
| CU00 | COUSR00C | /api/users | GET |
| CU01 | COUSR01C | /api/users/{userId} | GET |
| CU02 | COUSR02C | /api/users/{userId} | PUT |
| CU03 | COUSR03C | /api/users/{userId} | DELETE |

### Support and Feedback

For questions, issues, or feedback regarding the API:

- **GitHub Issues**: [Repository Issues Page]
- **Email**: api-support@carddemo.example.com
- **Documentation Updates**: Submit pull requests to update this documentation

### API Change Log

**Version 1.0.0** (2024-03-01)
- Initial release of CardDemo REST API
- Complete COBOL/CICS transaction migration to REST endpoints
- JWT authentication implementation
- OpenAPI 3.0 documentation
- Pagination and filtering support for all list endpoints

---

**Document Version**: 1.0.0  
**Last Updated**: 2024-02-29  
**API Version**: v1  
**Spring Boot Version**: 3.2.1
