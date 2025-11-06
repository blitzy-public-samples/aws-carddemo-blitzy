# OCR Processing Application - API Usage Examples

**Version:** 1.0.0  
**Last Updated:** January 2025  
**Base URL:** `https://api.ocr-app.com/api/v1`

---

## Table of Contents

1. [Introduction and Quick Start](#1-introduction-and-quick-start)
2. [Authentication Examples](#2-authentication-examples)
3. [Document Upload Examples](#3-document-upload-examples)
4. [Processing Status and Retrieval](#4-processing-status-and-retrieval)
5. [Template Management](#5-template-management)
6. [Search Examples](#6-search-examples)
7. [Data Export](#7-data-export)
8. [Webhook Configuration](#8-webhook-configuration)
9. [Third-Party Integrations](#9-third-party-integrations)
10. [Error Handling and Best Practices](#10-error-handling-and-best-practices)
11. [Rate Limiting and Retry Logic](#11-rate-limiting-and-retry-logic)
12. [SDKs and Libraries](#12-sdks-and-libraries)

---

## 1. Introduction and Quick Start

### Overview

The OCR Processing Application API provides programmatic access to powerful document digitization and data extraction capabilities. This guide provides practical, production-ready examples for integrating with our API across multiple programming languages.

### API Basics

- **Base URL:** `https://api.ocr-app.com/api/v1`
- **Protocol:** HTTPS only (TLS 1.3)
- **Response Format:** JSON
- **Date Format:** ISO 8601 (UTC)
- **Rate Limit:** 1000 requests per hour per account (configurable)

### Authentication Methods

1. **JWT Bearer Tokens** - For web and mobile applications
2. **API Keys** - For server-to-server integrations
3. **OAuth 2.0** - For third-party integrations (Google, Microsoft)

### Common Headers

```
Authorization: Bearer {access_token}
Content-Type: application/json
Accept: application/json
```

### Standard Response Format

**Success Response:**
```json
{
  "success": true,
  "data": {
    ...
  },
  "meta": {
    "timestamp": "2025-01-15T12:00:00Z",
    "version": "v1"
  }
}
```

**Error Response:**
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid document format",
    "details": [
      {
        "field": "file_type",
        "message": "Must be PDF, JPG, PNG, or TIFF"
      }
    ]
  },
  "meta": {
    "timestamp": "2025-01-15T12:00:00Z",
    "version": "v1"
  }
}
```

### Quick Start Checklist

1. ✅ Register for an account
2. ✅ Obtain access token or API key
3. ✅ Upload a document
4. ✅ Poll for processing status
5. ✅ Retrieve extracted data
6. ✅ Correct any low-confidence fields
7. ✅ Export or integrate with your system

### Supported File Types

- **PDF** - Multi-page documents (max 100 pages)
- **JPG/JPEG** - Images up to 50MB
- **PNG** - Images up to 50MB
- **TIFF** - Multi-page TIFF files

### Rate Limits

| Tier | Requests/Hour | Burst Limit |
|------|---------------|-------------|
| Free | 100 | 10 |
| Professional | 1,000 | 50 |
| Enterprise | 10,000 | 200 |
| Custom | Negotiable | Negotiable |

---

## 2. Authentication Examples

### 2.1 Register New User

Create a new user account with email and password.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "password": "SecurePass123!",
    "firstName": "John",
    "lastName": "Doe",
    "accountName": "Acme Corp"
  }'
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "user": {
      "id": "usr_abc123def456",
      "email": "user@example.com",
      "firstName": "John",
      "lastName": "Doe",
      "role": "user",
      "accountId": "acc_xyz789"
    },
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 900
  },
  "meta": {
    "timestamp": "2025-01-15T12:00:00Z",
    "version": "v1"
  }
}
```

**Password Requirements:**
- Minimum 12 characters
- At least one uppercase letter
- At least one lowercase letter
- At least one number
- At least one special character

### 2.2 Login (Email/Password)

Authenticate with email and password to receive access and refresh tokens.

**JavaScript (Node.js) Example:**
```javascript
const axios = require('axios');

async function login(email, password) {
  try {
    const response = await axios.post('https://api.ocr-app.com/api/v1/auth/login', {
      email,
      password
    });
    
    const { access_token, refresh_token, expires_in } = response.data.data;
    
    // Store tokens securely (use secure storage in production)
    localStorage.setItem('access_token', access_token);
    localStorage.setItem('refresh_token', refresh_token);
    localStorage.setItem('token_expiry', Date.now() + (expires_in * 1000));
    
    console.log('Login successful');
    return {
      accessToken: access_token,
      refreshToken: refresh_token,
      expiresIn: expires_in
    };
  } catch (error) {
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 401) {
        console.error('Invalid credentials');
      } else if (status === 429) {
        console.error('Too many login attempts. Please try again later.');
      } else {
        console.error('Login failed:', data.error.message);
      }
    } else {
      console.error('Network error:', error.message);
    }
    throw error;
  }
}

// Usage
login('user@example.com', 'SecurePass123!')
  .then(tokens => console.log('Access token:', tokens.accessToken))
  .catch(error => console.error('Login error'));
```

**Python Example:**
```python
import requests
import json
from datetime import datetime, timedelta

def login(email, password):
    """
    Authenticate user and return access tokens.
    
    Args:
        email (str): User email address
        password (str): User password
    
    Returns:
        dict: Access token, refresh token, and expiry information
    
    Raises:
        Exception: If login fails
    """
    url = 'https://api.ocr-app.com/api/v1/auth/login'
    payload = {
        'email': email,
        'password': password
    }
    
    try:
        response = requests.post(url, json=payload, timeout=30)
        response.raise_for_status()
        
        data = response.json()['data']
        access_token = data['access_token']
        refresh_token = data['refresh_token']
        expires_in = data['expires_in']
        
        # Store tokens securely (use keyring or secure storage in production)
        token_expiry = datetime.now() + timedelta(seconds=expires_in)
        
        print(f"Login successful. Token expires at {token_expiry}")
        
        return {
            'access_token': access_token,
            'refresh_token': refresh_token,
            'expires_in': expires_in,
            'expiry_time': token_expiry
        }
    
    except requests.exceptions.HTTPError as e:
        if e.response.status_code == 401:
            raise Exception("Invalid email or password")
        elif e.response.status_code == 429:
            raise Exception("Too many login attempts. Please try again later.")
        else:
            error_data = e.response.json()
            raise Exception(f"Login failed: {error_data['error']['message']}")
    
    except requests.exceptions.RequestException as e:
        raise Exception(f"Network error: {str(e)}")

# Usage
try:
    tokens = login('user@example.com', 'SecurePass123!')
    print(f"Access token: {tokens['access_token'][:20]}...")
except Exception as e:
    print(f"Error: {str(e)}")
```

### 2.3 Refresh Access Token

Access tokens expire after 15 minutes. Use the refresh token to obtain a new access token without requiring the user to log in again.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }'
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 900
  }
}
```

**JavaScript (Automatic Token Refresh):**
```javascript
const axios = require('axios');

class APIClient {
  constructor() {
    this.accessToken = null;
    this.refreshToken = null;
    this.tokenExpiry = null;
    
    // Add response interceptor to handle token refresh
    axios.interceptors.response.use(
      response => response,
      async error => {
        const originalRequest = error.config;
        
        // If token expired and we haven't retried yet
        if (error.response.status === 401 && !originalRequest._retry) {
          originalRequest._retry = true;
          
          try {
            await this.refreshAccessToken();
            originalRequest.headers['Authorization'] = `Bearer ${this.accessToken}`;
            return axios(originalRequest);
          } catch (refreshError) {
            // Refresh failed, redirect to login
            console.error('Token refresh failed. Please log in again.');
            return Promise.reject(refreshError);
          }
        }
        
        return Promise.reject(error);
      }
    );
  }
  
  async refreshAccessToken() {
    if (!this.refreshToken) {
      throw new Error('No refresh token available');
    }
    
    const response = await axios.post(
      'https://api.ocr-app.com/api/v1/auth/refresh',
      { refresh_token: this.refreshToken }
    );
    
    const { access_token, refresh_token, expires_in } = response.data.data;
    
    this.accessToken = access_token;
    this.refreshToken = refresh_token;
    this.tokenExpiry = Date.now() + (expires_in * 1000);
    
    // Update stored tokens
    localStorage.setItem('access_token', access_token);
    localStorage.setItem('refresh_token', refresh_token);
    localStorage.setItem('token_expiry', this.tokenExpiry);
    
    console.log('Token refreshed successfully');
  }
  
  async makeRequest(url, options = {}) {
    // Check if token is about to expire (within 1 minute)
    if (this.tokenExpiry && Date.now() >= this.tokenExpiry - 60000) {
      await this.refreshAccessToken();
    }
    
    return axios({
      url: `https://api.ocr-app.com/api/v1${url}`,
      ...options,
      headers: {
        'Authorization': `Bearer ${this.accessToken}`,
        ...options.headers
      }
    });
  }
}

// Usage
const client = new APIClient();
client.accessToken = localStorage.getItem('access_token');
client.refreshToken = localStorage.getItem('refresh_token');

client.makeRequest('/documents', { method: 'GET' })
  .then(response => console.log('Documents:', response.data))
  .catch(error => console.error('Error:', error));
```

### 2.4 OAuth 2.0 (Google Login)

Authenticate users via their Google account.

**JavaScript (Frontend) Example:**
```javascript
// Step 1: Redirect user to OAuth flow
function loginWithGoogle() {
  const redirectUri = encodeURIComponent(window.location.origin + '/auth/callback');
  const state = generateRandomState(); // Store this in session
  
  sessionStorage.setItem('oauth_state', state);
  
  window.location.href = `https://api.ocr-app.com/api/v1/auth/google?` +
    `redirect_uri=${redirectUri}&state=${state}`;
}

function generateRandomState() {
  return Math.random().toString(36).substring(2, 15) +
         Math.random().toString(36).substring(2, 15);
}

// Step 2: Handle OAuth callback
function handleOAuthCallback() {
  const urlParams = new URLSearchParams(window.location.search);
  const accessToken = urlParams.get('access_token');
  const refreshToken = urlParams.get('refresh_token');
  const state = urlParams.get('state');
  const error = urlParams.get('error');
  
  // Verify state to prevent CSRF attacks
  const storedState = sessionStorage.getItem('oauth_state');
  if (state !== storedState) {
    console.error('State mismatch. Possible CSRF attack.');
    return;
  }
  
  sessionStorage.removeItem('oauth_state');
  
  if (error) {
    console.error('OAuth error:', error);
    return;
  }
  
  if (accessToken) {
    localStorage.setItem('access_token', accessToken);
    localStorage.setItem('refresh_token', refreshToken);
    
    // Redirect to dashboard
    window.location.href = '/dashboard';
  }
}

// Call this on your callback page
if (window.location.pathname === '/auth/callback') {
  handleOAuthCallback();
}
```

**Supported OAuth Providers:**
- Google OAuth 2.0
- Microsoft OAuth 2.0

### 2.5 OAuth 2.0 (Microsoft Login)

Similar to Google OAuth, redirect users to Microsoft authentication.

**cURL (Initiate OAuth):**
```bash
# User should be redirected to this URL in browser
https://api.ocr-app.com/api/v1/auth/microsoft?redirect_uri=https://your-app.com/auth/callback&state=random_state
```

### 2.6 API Key Authentication

For server-to-server integrations, API keys provide a simpler authentication method.

**Generate API Key (requires JWT authentication first):**

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/api-keys \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Production Server Key",
    "scopes": ["documents:read", "documents:write", "templates:read"],
    "expires_at": "2026-01-15T00:00:00Z"
  }'
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "key_abc123",
    "name": "Production Server Key",
    "key": "ocr_sk_live_a1b2c3d4e5f6g7h8i9j0",
    "scopes": ["documents:read", "documents:write", "templates:read"],
    "created_at": "2025-01-15T12:00:00Z",
    "expires_at": "2026-01-15T00:00:00Z"
  }
}
```

**⚠️ IMPORTANT:** Store the API key securely. It will not be shown again.

**Using API Key:**

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/documents \
  -H "X-API-Key: ocr_sk_live_a1b2c3d4e5f6g7h8i9j0"
```

**Python Example:**
```python
import requests

API_KEY = 'ocr_sk_live_a1b2c3d4e5f6g7h8i9j0'

def get_documents_with_api_key():
    url = 'https://api.ocr-app.com/api/v1/documents'
    headers = {
        'X-API-Key': API_KEY
    }
    
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    
    return response.json()['data']

# Usage
documents = get_documents_with_api_key()
print(f"Found {len(documents['documents'])} documents")
```

### 2.7 Logout

Invalidate the current access and refresh tokens.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/auth/logout \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "refresh_token": "eyJhbGciOiJIUzI1NiIs..."
  }'
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "message": "Successfully logged out"
  }
}
```

---

## 3. Document Upload Examples

### 3.1 Single Document Upload

Upload a single document for OCR processing.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/documents/upload \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -F "file=@/path/to/invoice.pdf" \
  -F 'metadata={"type":"invoice","vendor":"Acme Corp","purchase_order":"PO-12345"}'
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "document_id": "doc_abc123def456",
    "filename": "invoice.pdf",
    "status": "queued",
    "upload_timestamp": "2025-01-15T12:00:00Z",
    "estimated_processing_time": 25,
    "metadata": {
      "type": "invoice",
      "vendor": "Acme Corp",
      "purchase_order": "PO-12345"
    }
  }
}
```

**JavaScript (Node.js with FormData):**
```javascript
const axios = require('axios');
const FormData = require('form-data');
const fs = require('fs');

async function uploadDocument(filePath, metadata = {}, accessToken) {
  const form = new FormData();
  
  // Add file
  form.append('file', fs.createReadStream(filePath));
  
  // Add metadata
  if (Object.keys(metadata).length > 0) {
    form.append('metadata', JSON.stringify(metadata));
  }
  
  try {
    const response = await axios.post(
      'https://api.ocr-app.com/api/v1/documents/upload',
      form,
      {
        headers: {
          ...form.getHeaders(),
          'Authorization': `Bearer ${accessToken}`
        },
        maxContentLength: Infinity,
        maxBodyLength: Infinity,
        timeout: 300000 // 5 minute timeout
      }
    );
    
    const document = response.data.data;
    console.log(`Document uploaded: ${document.document_id}`);
    console.log(`Status: ${document.status}`);
    console.log(`Estimated processing time: ${document.estimated_processing_time}s`);
    
    return document;
  } catch (error) {
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 413) {
        console.error('File too large. Maximum size: 50MB');
      } else if (status === 415) {
        console.error('Unsupported file type. Allowed: PDF, JPG, PNG, TIFF');
      } else if (status === 400) {
        console.error('Validation error:', data.error.details);
      } else {
        console.error('Upload failed:', data.error.message);
      }
    } else {
      console.error('Network error:', error.message);
    }
    throw error;
  }
}

// Usage
const metadata = {
  type: 'invoice',
  vendor: 'Acme Corp',
  purchase_order: 'PO-12345',
  department: 'Finance'
};

uploadDocument('/path/to/invoice.pdf', metadata, accessToken)
  .then(doc => console.log('Upload complete:', doc.document_id))
  .catch(error => console.error('Upload failed'));
```

**Python Example:**
```python
import requests
import json
import os

def upload_document(file_path, access_token, metadata=None):
    """
    Upload a document for OCR processing.
    
    Args:
        file_path (str): Path to the document file
        access_token (str): JWT access token
        metadata (dict): Optional metadata for the document
    
    Returns:
        dict: Document information including document_id
    
    Raises:
        Exception: If upload fails
    """
    url = 'https://api.ocr-app.com/api/v1/documents/upload'
    
    # Validate file exists
    if not os.path.exists(file_path):
        raise FileNotFoundError(f"File not found: {file_path}")
    
    # Validate file size (50MB limit)
    file_size = os.path.getsize(file_path)
    max_size = 50 * 1024 * 1024  # 50MB
    if file_size > max_size:
        raise ValueError(f"File too large: {file_size} bytes. Maximum: {max_size} bytes")
    
    headers = {
        'Authorization': f'Bearer {access_token}'
    }
    
    # Prepare multipart form data
    files = {
        'file': (os.path.basename(file_path), open(file_path, 'rb'))
    }
    
    data = {}
    if metadata:
        data['metadata'] = json.dumps(metadata)
    
    try:
        response = requests.post(
            url,
            headers=headers,
            files=files,
            data=data,
            timeout=300  # 5 minute timeout
        )
        response.raise_for_status()
        
        document = response.json()['data']
        print(f"Document uploaded: {document['document_id']}")
        print(f"Status: {document['status']}")
        print(f"Estimated processing time: {document['estimated_processing_time']}s")
        
        return document
    
    except requests.exceptions.HTTPError as e:
        if e.response.status_code == 413:
            raise Exception("File too large. Maximum size: 50MB")
        elif e.response.status_code == 415:
            raise Exception("Unsupported file type. Allowed: PDF, JPG, PNG, TIFF")
        elif e.response.status_code == 400:
            error_data = e.response.json()
            raise Exception(f"Validation error: {error_data['error']['message']}")
        else:
            error_data = e.response.json()
            raise Exception(f"Upload failed: {error_data['error']['message']}")
    
    except requests.exceptions.RequestException as e:
        raise Exception(f"Network error: {str(e)}")
    
    finally:
        # Close file handle
        if 'file' in files and hasattr(files['file'][1], 'close'):
            files['file'][1].close()

# Usage
try:
    metadata = {
        'type': 'invoice',
        'vendor': 'Acme Corp',
        'purchase_order': 'PO-12345',
        'department': 'Finance'
    }
    
    document = upload_document(
        '/path/to/invoice.pdf',
        access_token,
        metadata=metadata
    )
    
    print(f"Upload successful: {document['document_id']}")
except Exception as e:
    print(f"Error: {str(e)}")
```

**Java Example:**
```java
import okhttp3.*;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

public class DocumentUploader {
    
    private static final String API_BASE_URL = "https://api.ocr-app.com/api/v1";
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    
    public static JSONObject uploadDocument(
        String filePath,
        String accessToken,
        JSONObject metadata
    ) throws IOException {
        
        File file = new File(filePath);
        
        // Validate file exists
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }
        
        // Validate file size
        if (file.length() > MAX_FILE_SIZE) {
            throw new IOException("File too large. Maximum size: 50MB");
        }
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        
        // Build multipart request
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.getName(),
                RequestBody.create(file, MediaType.parse("application/octet-stream"))
            );
        
        // Add metadata if provided
        if (metadata != null && metadata.length() > 0) {
            bodyBuilder.addFormDataPart("metadata", metadata.toString());
        }
        
        RequestBody requestBody = bodyBuilder.build();
        
        Request request = new Request.Builder()
            .url(API_BASE_URL + "/documents/upload")
            .header("Authorization", "Bearer " + accessToken)
            .post(requestBody)
            .build();
        
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body().string();
            
            if (!response.isSuccessful()) {
                JSONObject errorResponse = new JSONObject(responseBody);
                String errorMessage = errorResponse
                    .getJSONObject("error")
                    .getString("message");
                
                if (response.code() == 413) {
                    throw new IOException("File too large. Maximum size: 50MB");
                } else if (response.code() == 415) {
                    throw new IOException("Unsupported file type. Allowed: PDF, JPG, PNG, TIFF");
                } else {
                    throw new IOException("Upload failed: " + errorMessage);
                }
            }
            
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONObject document = jsonResponse.getJSONObject("data");
            
            System.out.println("Document uploaded: " + document.getString("document_id"));
            System.out.println("Status: " + document.getString("status"));
            
            return document;
        }
    }
    
    // Usage example
    public static void main(String[] args) {
        try {
            String accessToken = "your_access_token_here";
            String filePath = "/path/to/invoice.pdf";
            
            JSONObject metadata = new JSONObject();
            metadata.put("type", "invoice");
            metadata.put("vendor", "Acme Corp");
            metadata.put("purchase_order", "PO-12345");
            
            JSONObject document = uploadDocument(filePath, accessToken, metadata);
            System.out.println("Upload successful: " + document.getString("document_id"));
            
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
```

### 3.2 Upload with Template

When you have a custom template configured, you can specify it during upload to ensure fields are extracted according to your predefined zones.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/documents/upload \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -F "file=@/path/to/invoice.pdf" \
  -F "template_id=tpl_xyz789abc123"
```

**JavaScript Example:**
```javascript
async function uploadWithTemplate(filePath, templateId, accessToken) {
  const form = new FormData();
  form.append('file', fs.createReadStream(filePath));
  form.append('template_id', templateId);
  
  const response = await axios.post(
    'https://api.ocr-app.com/api/v1/documents/upload',
    form,
    {
      headers: {
        ...form.getHeaders(),
        'Authorization': `Bearer ${accessToken}`
      }
    }
  );
  
  console.log('Using template:', templateId);
  return response.data.data;
}
```

### 3.3 Batch Document Upload

Upload multiple documents in a single batch for bulk processing.

**JavaScript Example:**
```javascript
async function uploadBatch(filePaths, accessToken) {
  const uploadPromises = filePaths.map(filePath => 
    uploadDocument(filePath, {}, accessToken)
  );
  
  try {
    const results = await Promise.all(uploadPromises);
    console.log(`Successfully uploaded ${results.length} documents`);
    
    return results.map(doc => ({
      documentId: doc.document_id,
      filename: doc.filename,
      status: doc.status
    }));
  } catch (error) {
    console.error('Batch upload failed:', error);
    throw error;
  }
}

// Usage
const files = [
  '/path/to/invoice1.pdf',
  '/path/to/invoice2.pdf',
  '/path/to/invoice3.pdf'
];

uploadBatch(files, accessToken)
  .then(results => {
    console.log('Batch upload complete');
    results.forEach(doc => {
      console.log(`- ${doc.filename}: ${doc.documentId}`);
    });
  });
```

### 3.4 Upload from URL

Upload a document from a publicly accessible URL.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/documents/upload-url \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com/documents/invoice.pdf",
    "metadata": {
      "type": "invoice",
      "source": "email_attachment"
    }
  }'
```

**Python Example:**
```python
def upload_from_url(document_url, access_token, metadata=None):
    url = 'https://api.ocr-app.com/api/v1/documents/upload-url'
    
    headers = {
        'Authorization': f'Bearer {access_token}',
        'Content-Type': 'application/json'
    }
    
    payload = {
        'url': document_url
    }
    
    if metadata:
        payload['metadata'] = metadata
    
    response = requests.post(url, headers=headers, json=payload)
    response.raise_for_status()
    
    return response.json()['data']

# Usage
document = upload_from_url(
    'https://example.com/documents/invoice.pdf',
    access_token,
    metadata={'type': 'invoice', 'source': 'email'}
)
```

---

## 4. Processing Status and Retrieval

### 4.1 Check Processing Status

After uploading a document, poll the status endpoint to check processing progress.

**Document Status Values:**
- `queued` - Document is queued for processing
- `processing` - OCR is currently running
- `completed` - Processing completed successfully
- `failed` - Processing failed (check error field)
- `pending_review` - Low confidence fields require human review

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/documents/doc_abc123def456 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

**Response:**
```json
{
  "success": true,
  "data": {
    "document_id": "doc_abc123def456",
    "filename": "invoice.pdf",
    "status": "processing",
    "progress": 65,
    "upload_timestamp": "2025-01-15T12:00:00Z",
    "processing_started_at": "2025-01-15T12:00:05Z",
    "estimated_completion": "2025-01-15T12:00:30Z"
  }
}
```

**JavaScript (Polling Pattern with Exponential Backoff):**
```javascript
async function waitForProcessing(
  documentId,
  accessToken,
  maxAttempts = 60,
  initialDelay = 2000
) {
  let attempt = 0;
  let delay = initialDelay;
  
  while (attempt < maxAttempts) {
    try {
      const response = await axios.get(
        `https://api.ocr-app.com/api/v1/documents/${documentId}`,
        {
          headers: { 'Authorization': `Bearer ${accessToken}` }
        }
      );
      
      const document = response.data.data;
      
      console.log(
        `Status: ${document.status} ` +
        `(${document.progress || 0}% complete)`
      );
      
      if (document.status === 'completed') {
        console.log('Processing complete!');
        return document;
      } else if (document.status === 'failed') {
        throw new Error(`Processing failed: ${document.error_message}`);
      } else if (document.status === 'pending_review') {
        console.log('Document requires human review');
        return document;
      }
      
      // Exponential backoff: 2s, 4s, 8s, max 30s
      await new Promise(resolve => setTimeout(resolve, delay));
      delay = Math.min(delay * 1.5, 30000);
      attempt++;
      
    } catch (error) {
      if (error.response && error.response.status === 404) {
        throw new Error('Document not found');
      }
      throw error;
    }
  }
  
  throw new Error('Processing timeout exceeded');
}

// Usage
waitForProcessing('doc_abc123def456', accessToken)
  .then(document => {
    console.log('Document ready:', document.document_id);
    console.log('Extracted data:', document.extracted_data);
  })
  .catch(error => console.error('Error:', error.message));
```

**Python Example:**
```python
import time

def wait_for_processing(
    document_id,
    access_token,
    max_attempts=60,
    initial_delay=2
):
    """
    Poll document status until processing is complete.
    
    Args:
        document_id (str): Document ID to check
        access_token (str): JWT access token
        max_attempts (int): Maximum number of polling attempts
        initial_delay (int): Initial delay between polls in seconds
    
    Returns:
        dict: Completed document with extracted data
    
    Raises:
        Exception: If processing fails or times out
    """
    url = f'https://api.ocr-app.com/api/v1/documents/{document_id}'
    headers = {'Authorization': f'Bearer {access_token}'}
    
    attempt = 0
    delay = initial_delay
    
    while attempt < max_attempts:
        try:
            response = requests.get(url, headers=headers, timeout=30)
            response.raise_for_status()
            
            document = response.json()['data']
            status = document['status']
            progress = document.get('progress', 0)
            
            print(f"Status: {status} ({progress}% complete)")
            
            if status == 'completed':
                print('Processing complete!')
                return document
            elif status == 'failed':
                error_msg = document.get('error_message', 'Unknown error')
                raise Exception(f'Processing failed: {error_msg}')
            elif status == 'pending_review':
                print('Document requires human review')
                return document
            
            # Exponential backoff: 2s, 3s, 4.5s, max 30s
            time.sleep(delay)
            delay = min(delay * 1.5, 30)
            attempt += 1
        
        except requests.exceptions.HTTPError as e:
            if e.response.status_code == 404:
                raise Exception('Document not found')
            raise
    
    raise Exception('Processing timeout exceeded')

# Usage
try:
    document = wait_for_processing('doc_abc123def456', access_token)
    print(f"Document ready: {document['document_id']}")
    print(f"Extracted {len(document['extracted_data'])} fields")
except Exception as e:
    print(f"Error: {str(e)}")
```

### 4.2 Retrieve Extracted Data

Once processing is complete, retrieve the full extracted data with confidence scores.

**Response Example (Completed Document):**
```json
{
  "success": true,
  "data": {
    "document_id": "doc_abc123def456",
    "filename": "invoice.pdf",
    "status": "completed",
    "file_url": "https://s3.amazonaws.com/ocr-docs/presigned-url?expires=3600",
    "upload_timestamp": "2025-01-15T12:00:00Z",
    "processing_completed_at": "2025-01-15T12:00:28Z",
    "processing_time_seconds": 28,
    "page_count": 1,
    "document_type": "invoice",
    "document_type_confidence": 0.98,
    "extracted_data": {
      "invoice_number": "INV-2025-001",
      "invoice_date": "2025-01-15",
      "due_date": "2025-02-14",
      "vendor": {
        "name": "Acme Corporation",
        "address": "123 Main Street, Suite 400, New York, NY 10001",
        "phone": "+1-555-0123",
        "email": "billing@acme-corp.com",
        "tax_id": "12-3456789"
      },
      "customer": {
        "name": "John Doe Enterprises",
        "address": "456 Oak Avenue, Los Angeles, CA 90001",
        "contact": "John Doe"
      },
      "line_items": [
        {
          "line_number": 1,
          "description": "Professional Services - January 2025",
          "quantity": 10,
          "unit_price": 150.00,
          "amount": 1500.00
        },
        {
          "line_number": 2,
          "description": "Software License (Annual)",
          "quantity": 1,
          "unit_price": 2500.00,
          "amount": 2500.00
        }
      ],
      "subtotal": 4000.00,
      "tax_rate": 0.08,
      "tax_amount": 320.00,
      "shipping": 0.00,
      "total": 4320.00,
      "currency": "USD",
      "payment_terms": "Net 30",
      "notes": "Thank you for your business"
    },
    "confidence_scores": {
      "invoice_number": 0.98,
      "invoice_date": 0.95,
      "due_date": 0.94,
      "vendor.name": 0.99,
      "vendor.tax_id": 0.92,
      "line_items.0.description": 0.96,
      "line_items.0.amount": 0.99,
      "subtotal": 0.99,
      "tax_amount": 0.97,
      "total": 0.99
    },
    "validation_errors": [],
    "warnings": [
      {
        "field": "due_date",
        "message": "Due date is in the past",
        "severity": "warning"
      }
    ]
  }
}
```

### 4.3 Get Document List

Retrieve a paginated list of all documents.

**cURL Example:**
```bash
curl -X GET "https://api.ocr-app.com/api/v1/documents?page=1&limit=25&status=completed" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

**Response:**
```json
{
  "success": true,
  "data": {
    "documents": [
      {
        "document_id": "doc_abc123",
        "filename": "invoice.pdf",
        "status": "completed",
        "document_type": "invoice",
        "upload_timestamp": "2025-01-15T12:00:00Z"
      }
    ],
    "pagination": {
      "total": 150,
      "page": 1,
      "limit": 25,
      "total_pages": 6,
      "has_next": true,
      "has_previous": false
    }
  }
}
```

**JavaScript Example:**
```javascript
async function listDocuments(accessToken, filters = {}) {
  const params = new URLSearchParams({
    page: filters.page || 1,
    limit: filters.limit || 25,
    ...(filters.status && { status: filters.status }),
    ...(filters.document_type && { document_type: filters.document_type }),
    ...(filters.from_date && { from_date: filters.from_date }),
    ...(filters.to_date && { to_date: filters.to_date })
  });
  
  const response = await axios.get(
    `https://api.ocr-app.com/api/v1/documents?${params}`,
    {
      headers: { 'Authorization': `Bearer ${accessToken}` }
    }
  );
  
  const data = response.data.data;
  console.log(`Found ${data.pagination.total} documents`);
  console.log(`Showing page ${data.pagination.page} of ${data.pagination.total_pages}`);
  
  return data;
}

// Usage
listDocuments(accessToken, {
  status: 'completed',
  document_type: 'invoice',
  from_date: '2025-01-01',
  to_date: '2025-01-31',
  page: 1,
  limit: 50
});
```

### 4.4 Update Document Field (Correction)

Correct a low-confidence field after human review.

**cURL Example:**
```bash
curl -X PATCH https://api.ocr-app.com/api/v1/documents/doc_abc123def456/fields \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "field_path": "total",
    "corrected_value": 4320.00,
    "original_value": 4320.00,
    "reason": "Confirmed by human reviewer"
  }'
```

**JavaScript Example:**
```javascript
async function correctField(
  documentId,
  fieldPath,
  correctedValue,
  accessToken
) {
  const response = await axios.patch(
    `https://api.ocr-app.com/api/v1/documents/${documentId}/fields`,
    {
      field_path: fieldPath,
      corrected_value: correctedValue,
      reason: 'Human review correction'
    },
    {
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      }
    }
  );
  
  console.log(`Field ${fieldPath} corrected to: ${correctedValue}`);
  return response.data.data;
}

// Usage
correctField('doc_abc123', 'vendor.tax_id', '12-3456789', accessToken);
```

### 4.5 Approve Document

Mark a document as approved after review.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/documents/doc_abc123def456/approve \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "notes": "All fields verified and approved"
  }'
```

---

## 5. Template Management

Templates define extraction zones and field mappings for recurring document types.

### 5.1 Create Template

**JavaScript Example:**
```javascript
async function createTemplate(accessToken) {
  const template = {
    name: "Standard Invoice Template - Acme Vendors",
    description: "Template for invoices from Acme Corporation",
    document_type: "invoice",
    zones: [
      {
        id: "zone_1",
        field_id: "invoice_number",
        x: 100,
        y: 50,
        width: 200,
        height: 30,
        page: 1
      },
      {
        id: "zone_2",
        field_id: "invoice_date",
        x: 100,
        y: 100,
        width: 150,
        height: 30,
        page: 1
      },
      {
        id: "zone_3",
        field_id: "total",
        x: 450,
        y: 500,
        width: 100,
        height: 30,
        page: 1
      }
    ],
    fields: [
      {
        id: "invoice_number",
        name: "Invoice Number",
        type: "text",
        required: true,
        validation: {
          pattern: "^INV-\\d{4}-\\d{3}$",
          error_message: "Invoice number must match format INV-YYYY-NNN"
        }
      },
      {
        id: "invoice_date",
        name: "Invoice Date",
        type: "date",
        required: true,
        format: "YYYY-MM-DD"
      },
      {
        id: "total",
        name: "Total Amount",
        type: "currency",
        required: true,
        validation: {
          min: 0,
          max: 1000000,
          currency: "USD"
        }
      }
    ],
    validation_rules: {
      cross_field: [
        {
          rule: "total >= subtotal",
          error_message: "Total must be greater than or equal to subtotal"
        }
      ]
    }
  };
  
  try {
    const response = await axios.post(
      'https://api.ocr-app.com/api/v1/templates',
      template,
      {
        headers: {
          'Authorization': `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        }
      }
    );
    
    const createdTemplate = response.data.data;
    console.log('Template created:', createdTemplate.id);
    console.log('Template name:', createdTemplate.name);
    
    return createdTemplate;
  } catch (error) {
    console.error('Template creation failed:', error.response.data);
    throw error;
  }
}

// Usage
createTemplate(accessToken)
  .then(template => console.log('Template ID:', template.id));
```

**Python Example:**
```python
def create_template(access_token):
    url = 'https://api.ocr-app.com/api/v1/templates'
    
    template = {
        'name': 'Standard Invoice Template - Acme Vendors',
        'description': 'Template for invoices from Acme Corporation',
        'document_type': 'invoice',
        'zones': [
            {
                'id': 'zone_1',
                'field_id': 'invoice_number',
                'x': 100,
                'y': 50,
                'width': 200,
                'height': 30,
                'page': 1
            },
            {
                'id': 'zone_2',
                'field_id': 'invoice_date',
                'x': 100,
                'y': 100,
                'width': 150,
                'height': 30,
                'page': 1
            }
        ],
        'fields': [
            {
                'id': 'invoice_number',
                'name': 'Invoice Number',
                'type': 'text',
                'required': True,
                'validation': {
                    'pattern': r'^INV-\d{4}-\d{3}$'
                }
            },
            {
                'id': 'invoice_date',
                'name': 'Invoice Date',
                'type': 'date',
                'required': True,
                'format': 'YYYY-MM-DD'
            }
        ]
    }
    
    headers = {
        'Authorization': f'Bearer {access_token}',
        'Content-Type': 'application/json'
    }
    
    response = requests.post(url, headers=headers, json=template)
    response.raise_for_status()
    
    created_template = response.json()['data']
    print(f"Template created: {created_template['id']}")
    
    return created_template
```

### 5.2 List Templates

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/templates \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

### 5.3 Get Template Details

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/templates/tpl_xyz789abc123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

### 5.4 Update Template

**cURL Example:**
```bash
curl -X PATCH https://api.ocr-app.com/api/v1/templates/tpl_xyz789abc123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Updated Invoice Template",
    "zones": [...]
  }'
```

### 5.5 Test Template

Test a template against a sample document to verify accuracy.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/templates/tpl_xyz789abc123/test \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "document_id": "doc_abc123def456"
  }'
```

**Response:**
```json
{
  "success": true,
  "data": {
    "template_id": "tpl_xyz789abc123",
    "document_id": "doc_abc123def456",
    "test_results": {
      "fields_matched": 8,
      "fields_total": 10,
      "accuracy_rate": 0.80,
      "field_results": [
        {
          "field_id": "invoice_number",
          "extracted_value": "INV-2025-001",
          "confidence": 0.98,
          "validation_passed": true
        }
      ]
    }
  }
}
```

### 5.6 Delete Template

**cURL Example:**
```bash
curl -X DELETE https://api.ocr-app.com/api/v1/templates/tpl_xyz789abc123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

---

## 6. Search Examples

### 6.1 Full-Text Search

Search across all document content using ElasticSearch.

**JavaScript Example:**
```javascript
async function searchDocuments(query, accessToken, options = {}) {
  const params = new URLSearchParams({
    q: query,
    page: options.page || 1,
    limit: options.limit || 25
  });
  
  try {
    const response = await axios.get(
      `https://api.ocr-app.com/api/v1/search/documents?${params}`,
      {
        headers: { 'Authorization': `Bearer ${accessToken}` }
      }
    );
    
    const results = response.data.data;
    console.log(`Found ${results.total_hits} documents matching "${query}"`);
    
    results.results.forEach(doc => {
      console.log(`\n- ${doc.filename} (${doc.document_type})`);
      console.log(`  Document ID: ${doc.document_id}`);
      console.log(`  Uploaded: ${doc.upload_timestamp}`);
      
      if (doc.highlights && doc.highlights.length > 0) {
        console.log(`  Highlights: "${doc.highlights[0]}"`);
      }
    });
    
    return results;
  } catch (error) {
    console.error('Search failed:', error.response.data);
    throw error;
  }
}

// Usage
searchDocuments('Acme Corporation invoice', accessToken, {
  page: 1,
  limit: 50
});
```

**Python Example:**
```python
def search_documents(query, access_token, page=1, limit=25):
    """
    Search documents using full-text search.
    
    Args:
        query (str): Search query
        access_token (str): JWT access token
        page (int): Page number
        limit (int): Results per page
    
    Returns:
        dict: Search results with highlights
    """
    url = 'https://api.ocr-app.com/api/v1/search/documents'
    
    params = {
        'q': query,
        'page': page,
        'limit': limit
    }
    
    headers = {'Authorization': f'Bearer {access_token}'}
    
    response = requests.get(url, params=params, headers=headers)
    response.raise_for_status()
    
    results = response.json()['data']
    
    print(f"Found {results['total_hits']} documents matching '{query}'")
    
    for doc in results['results']:
        print(f"\n- {doc['filename']} ({doc['document_type']})")
        print(f"  Document ID: {doc['document_id']}")
        
        if 'highlights' in doc and doc['highlights']:
            print(f"  Highlights: \"{doc['highlights'][0]}\"")
    
    return results

# Usage
results = search_documents('Acme Corporation invoice', access_token)
```

### 6.2 Advanced Search with Filters

Apply multiple filters to narrow search results.

**cURL Example:**
```bash
curl -X GET 'https://api.ocr-app.com/api/v1/search/documents' \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -G \
  --data-urlencode 'q=invoice' \
  --data-urlencode 'filters={"status":"completed","document_type":"invoice","confidence_min":0.9}' \
  --data-urlencode 'from_date=2025-01-01' \
  --data-urlencode 'to_date=2025-01-31'
```

**Python Example:**
```python
def advanced_search(access_token, query, filters):
    """
    Perform advanced search with filters.
    
    Args:
        access_token (str): JWT access token
        query (str): Search query
        filters (dict): Filter criteria
    
    Returns:
        dict: Filtered search results
    """
    url = 'https://api.ocr-app.com/api/v1/search/documents'
    
    params = {
        'q': query,
        'page': 1,
        'limit': 50
    }
    
    # Add filters
    if filters:
        params['filters'] = json.dumps(filters)
    
    headers = {'Authorization': f'Bearer {access_token}'}
    
    response = requests.get(url, params=params, headers=headers)
    response.raise_for_status()
    
    results = response.json()['data']
    
    print(f"Found {results['total_hits']} documents")
    
    for doc in results['results']:
        print(f"- {doc['filename']} ({doc['document_type']})")
        print(f"  Status: {doc['status']}")
        print(f"  Confidence: {doc.get('average_confidence', 'N/A')}")
    
    return results

# Usage
filters = {
    'status': 'completed',
    'document_type': 'invoice',
    'confidence_min': 0.9,
    'date_range': {
        'from': '2025-01-01',
        'to': '2025-01-31'
    },
    'extracted_data': {
        'vendor.name': 'Acme Corporation'
    }
}

results = advanced_search(access_token, 'invoice', filters)
```

### 6.3 Search by Extracted Field Values

Search for documents based on specific extracted field values.

**JavaScript Example:**
```javascript
async function searchByField(fieldPath, fieldValue, accessToken) {
  const filters = {
    extracted_data: {
      [fieldPath]: fieldValue
    }
  };
  
  const params = new URLSearchParams({
    filters: JSON.stringify(filters),
    page: 1,
    limit: 25
  });
  
  const response = await axios.get(
    `https://api.ocr-app.com/api/v1/search/documents?${params}`,
    {
      headers: { 'Authorization': `Bearer ${accessToken}` }
    }
  );
  
  return response.data.data;
}

// Usage
searchByField('total', 4320.00, accessToken)
  .then(results => console.log(`Found ${results.total_hits} documents`));
```

---

## 7. Data Export

### 7.1 Export Documents (JSON)

Export one or more documents in JSON format.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/export \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "document_ids": ["doc_abc123", "doc_def456"],
    "format": "json",
    "include_metadata": true,
    "include_confidence_scores": true
  }'
```

**Response:**
```json
{
  "success": true,
  "data": {
    "export_job_id": "exp_xyz789",
    "status": "processing",
    "created_at": "2025-01-15T12:00:00Z",
    "estimated_completion": "2025-01-15T12:00:15Z"
  }
}
```

**JavaScript (Complete Export Flow):**
```javascript
async function exportDocuments(documentIds, format, accessToken) {
  // Step 1: Create export job
  const createResponse = await axios.post(
    'https://api.ocr-app.com/api/v1/export',
    {
      document_ids: documentIds,
      format: format, // 'json', 'csv', 'xml', or 'excel'
      include_metadata: true,
      include_confidence_scores: true
    },
    {
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      }
    }
  );
  
  const jobId = createResponse.data.data.export_job_id;
  console.log(`Export job created: ${jobId}`);
  
  // Step 2: Poll for completion
  let attempts = 0;
  const maxAttempts = 30;
  
  while (attempts < maxAttempts) {
    const statusResponse = await axios.get(
      `https://api.ocr-app.com/api/v1/export/${jobId}`,
      {
        headers: { 'Authorization': `Bearer ${accessToken}` }
      }
    );
    
    const job = statusResponse.data.data;
    
    console.log(`Export status: ${job.status} (${job.progress || 0}%)`);
    
    if (job.status === 'completed') {
      console.log('Export ready!');
      console.log(`Download URL: ${job.download_url}`);
      console.log(`Expires at: ${job.download_url_expires_at}`);
      return job;
    } else if (job.status === 'failed') {
      throw new Error(`Export failed: ${job.error_message}`);
    }
    
    await new Promise(resolve => setTimeout(resolve, 2000));
    attempts++;
  }
  
  throw new Error('Export timeout');
}

// Usage
exportDocuments(
  ['doc_abc123', 'doc_def456', 'doc_ghi789'],
  'excel',
  accessToken
)
.then(job => {
  console.log('Export complete. Download URL:', job.download_url);
  // Optionally, download the file automatically
  return axios.get(job.download_url, { responseType: 'blob' });
})
.then(response => {
  // Save file
  const fs = require('fs');
  fs.writeFileSync('export.xlsx', response.data);
  console.log('File saved to export.xlsx');
})
.catch(error => console.error('Export failed:', error));
```

**Python Example:**
```python
import time

def export_documents(document_ids, format_type, access_token):
    """
    Export documents and download the result file.
    
    Args:
        document_ids (list): List of document IDs to export
        format_type (str): Export format ('json', 'csv', 'xml', 'excel')
        access_token (str): JWT access token
    
    Returns:
        bytes: Downloaded file content
    """
    url = 'https://api.ocr-app.com/api/v1/export'
    
    headers = {
        'Authorization': f'Bearer {access_token}',
        'Content-Type': 'application/json'
    }
    
    payload = {
        'document_ids': document_ids,
        'format': format_type,
        'include_metadata': True,
        'include_confidence_scores': True
    }
    
    # Step 1: Create export job
    response = requests.post(url, headers=headers, json=payload)
    response.raise_for_status()
    
    job_id = response.json()['data']['export_job_id']
    print(f"Export job created: {job_id}")
    
    # Step 2: Poll for completion
    status_url = f'https://api.ocr-app.com/api/v1/export/{job_id}'
    max_attempts = 30
    
    for attempt in range(max_attempts):
        status_response = requests.get(status_url, headers=headers)
        status_response.raise_for_status()
        
        job = status_response.json()['data']
        status = job['status']
        progress = job.get('progress', 0)
        
        print(f"Export status: {status} ({progress}%)")
        
        if status == 'completed':
            print('Export ready!')
            download_url = job['download_url']
            
            # Step 3: Download file
            download_response = requests.get(download_url)
            download_response.raise_for_status()
            
            return download_response.content
        
        elif status == 'failed':
            error_msg = job.get('error_message', 'Unknown error')
            raise Exception(f'Export failed: {error_msg}')
        
        time.sleep(2)
    
    raise Exception('Export timeout')

# Usage
try:
    file_content = export_documents(
        ['doc_abc123', 'doc_def456'],
        'excel',
        access_token
    )
    
    # Save file
    with open('export.xlsx', 'wb') as f:
        f.write(file_content)
    
    print('File saved to export.xlsx')

except Exception as e:
    print(f"Error: {str(e)}")
```

### 7.2 Export by Date Range

Export all documents processed within a date range.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/export/date-range \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "from_date": "2025-01-01",
    "to_date": "2025-01-31",
    "format": "csv",
    "filters": {
      "status": "completed",
      "document_type": "invoice"
    }
  }'
```

### 7.3 Export Formats

**Supported Export Formats:**

1. **JSON** - Structured data with full field hierarchy
2. **CSV** - Flat table format (flattens nested fields)
3. **XML** - Hierarchical XML structure
4. **Excel** - Multi-sheet workbook with formatting

**Format-Specific Options:**

**CSV Options:**
```json
{
  "format": "csv",
  "csv_options": {
    "delimiter": ",",
    "include_headers": true,
    "flatten_nested_fields": true,
    "date_format": "YYYY-MM-DD"
  }
}
```

**Excel Options:**
```json
{
  "format": "excel",
  "excel_options": {
    "include_summary_sheet": true,
    "freeze_header_row": true,
    "auto_column_width": true,
    "highlight_low_confidence": true
  }
}
```

---

## 8. Webhook Configuration

### 8.1 Create Webhook Subscription

Subscribe to events to receive real-time notifications.

**Available Events:**
- `document.uploaded` - Document uploaded successfully
- `document.processing` - Processing started
- `document.processed` - OCR/NLP completed
- `document.approved` - Document approved by user
- `document.failed` - Processing failed
- `batch.completed` - Batch job completed
- `template.created` - New template created
- `template.updated` - Template modified

**JavaScript Example:**
```javascript
async function setupWebhook(url, events, accessToken) {
  // Generate a secret for signature verification
  const crypto = require('crypto');
  const secret = crypto.randomBytes(32).toString('hex');
  
  const response = await axios.post(
    'https://api.ocr-app.com/api/v1/integrations/webhooks',
    {
      url: url,
      events: events,
      secret: secret,
      description: 'Production webhook for document processing'
    },
    {
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      }
    }
  );
  
  const webhook = response.data.data;
  console.log('Webhook created:', webhook.id);
  console.log('Secret (store securely):', secret);
  
  return webhook;
}

// Usage
setupWebhook(
  'https://your-app.com/webhooks/ocr',
  ['document.processed', 'document.approved', 'batch.completed'],
  accessToken
)
.then(webhook => {
  console.log('Webhook ID:', webhook.id);
  console.log('Events:', webhook.events);
});
```

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/integrations/webhooks \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://your-app.com/webhooks/ocr",
    "events": ["document.processed", "document.approved"],
    "secret": "your_webhook_secret_for_signature_verification"
  }'
```

**Response:**
```json
{
  "success": true,
  "data": {
    "id": "webhook_abc123",
    "url": "https://your-app.com/webhooks/ocr",
    "events": ["document.processed", "document.approved"],
    "status": "active",
    "created_at": "2025-01-15T12:00:00Z"
  }
}
```

### 8.2 Webhook Receiver Example (Express.js)

Implement a webhook receiver to process incoming events.

**JavaScript (Express.js):**
```javascript
const express = require('express');
const crypto = require('crypto');

const app = express();
app.use(express.json());

const WEBHOOK_SECRET = 'your_webhook_secret';

/**
 * Verify webhook signature to ensure request is from OCR API
 */
function verifyWebhookSignature(payload, signature) {
  const hmac = crypto.createHmac('sha256', WEBHOOK_SECRET);
  const digest = 'sha256=' + hmac.update(JSON.stringify(payload)).digest('hex');
  
  return crypto.timingSafeEqual(
    Buffer.from(signature),
    Buffer.from(digest)
  );
}

/**
 * Webhook endpoint
 */
app.post('/webhooks/ocr', (req, res) => {
  const signature = req.headers['x-webhook-signature'];
  const eventType = req.headers['x-event-type'];
  const requestId = req.headers['x-request-id'];
  
  console.log(`Received webhook: ${eventType} (${requestId})`);
  
  // Verify signature
  if (!signature || !verifyWebhookSignature(req.body, signature)) {
    console.error('Invalid webhook signature');
    return res.status(401).send('Invalid signature');
  }
  
  // Process event based on type
  try {
    switch (eventType) {
      case 'document.processed':
        handleDocumentProcessed(req.body);
        break;
      
      case 'document.approved':
        handleDocumentApproved(req.body);
        break;
      
      case 'document.failed':
        handleDocumentFailed(req.body);
        break;
      
      case 'batch.completed':
        handleBatchCompleted(req.body);
        break;
      
      default:
        console.log(`Unhandled event type: ${eventType}`);
    }
    
    // Acknowledge receipt immediately
    res.status(200).json({ received: true });
  } catch (error) {
    console.error('Error processing webhook:', error);
    res.status(500).send('Internal error');
  }
});

function handleDocumentProcessed(data) {
  const { document_id, status, extracted_data } = data;
  
  console.log(`Document ${document_id} processed`);
  console.log(`Status: ${status}`);
  console.log(`Extracted fields: ${Object.keys(extracted_data).length}`);
  
  // Your business logic here
  // For example: Update database, send notification, trigger workflow
  
  // Example: Check if invoice total exceeds threshold
  if (extracted_data.total > 10000) {
    console.log('High-value invoice detected. Sending alert...');
    // Send alert to finance team
  }
}

function handleDocumentApproved(data) {
  const { document_id, approved_by, approved_at } = data;
  
  console.log(`Document ${document_id} approved by ${approved_by}`);
  
  // Your business logic here
  // For example: Export to accounting system, archive document
}

function handleDocumentFailed(data) {
  const { document_id, error_message } = data;
  
  console.error(`Document ${document_id} failed: ${error_message}`);
  
  // Your business logic here
  // For example: Alert user, log error, retry processing
}

function handleBatchCompleted(data) {
  const { batch_id, total_documents, succeeded, failed } = data;
  
  console.log(`Batch ${batch_id} completed:`);
  console.log(`- Total: ${total_documents}`);
  console.log(`- Succeeded: ${succeeded}`);
  console.log(`- Failed: ${failed}`);
  
  // Your business logic here
}

// Start server
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`Webhook receiver listening on port ${PORT}`);
});
```

**Python (Flask) Example:**
```python
from flask import Flask, request, jsonify
import hmac
import hashlib
import json

app = Flask(__name__)

WEBHOOK_SECRET = 'your_webhook_secret'

def verify_webhook_signature(payload, signature):
    """Verify webhook signature"""
    if not signature:
        return False
    
    mac = hmac.new(
        WEBHOOK_SECRET.encode(),
        json.dumps(payload).encode(),
        hashlib.sha256
    )
    expected_signature = 'sha256=' + mac.hexdigest()
    
    return hmac.compare_digest(signature, expected_signature)

@app.route('/webhooks/ocr', methods=['POST'])
def webhook_handler():
    """Handle incoming webhooks"""
    signature = request.headers.get('X-Webhook-Signature')
    event_type = request.headers.get('X-Event-Type')
    request_id = request.headers.get('X-Request-Id')
    
    print(f"Received webhook: {event_type} ({request_id})")
    
    # Verify signature
    if not verify_webhook_signature(request.json, signature):
        print('Invalid webhook signature')
        return jsonify({'error': 'Invalid signature'}), 401
    
    # Process event
    try:
        data = request.json
        
        if event_type == 'document.processed':
            handle_document_processed(data)
        elif event_type == 'document.approved':
            handle_document_approved(data)
        elif event_type == 'document.failed':
            handle_document_failed(data)
        elif event_type == 'batch.completed':
            handle_batch_completed(data)
        else:
            print(f"Unhandled event type: {event_type}")
        
        return jsonify({'received': True}), 200
    
    except Exception as e:
        print(f"Error processing webhook: {str(e)}")
        return jsonify({'error': 'Internal error'}), 500

def handle_document_processed(data):
    document_id = data['document_id']
    status = data['status']
    extracted_data = data['extracted_data']
    
    print(f"Document {document_id} processed")
    print(f"Status: {status}")
    print(f"Extracted fields: {len(extracted_data)}")
    
    # Your business logic here

def handle_document_approved(data):
    document_id = data['document_id']
    print(f"Document {document_id} approved")
    # Your business logic here

def handle_document_failed(data):
    document_id = data['document_id']
    error_message = data['error_message']
    print(f"Document {document_id} failed: {error_message}")
    # Your business logic here

def handle_batch_completed(data):
    batch_id = data['batch_id']
    print(f"Batch {batch_id} completed")
    # Your business logic here

if __name__ == '__main__':
    app.run(port=3000)
```

### 8.3 Webhook Payload Example

**document.processed Event:**
```json
{
  "event": "document.processed",
  "timestamp": "2025-01-15T12:00:30Z",
  "data": {
    "document_id": "doc_abc123def456",
    "filename": "invoice.pdf",
    "status": "completed",
    "document_type": "invoice",
    "processing_time_seconds": 28,
    "extracted_data": {
      "invoice_number": "INV-2025-001",
      "total": 4320.00
    },
    "average_confidence": 0.96,
    "validation_errors": []
  }
}
```

### 8.4 List Webhooks

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/integrations/webhooks \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

### 8.5 Delete Webhook

**cURL Example:**
```bash
curl -X DELETE https://api.ocr-app.com/api/v1/integrations/webhooks/webhook_abc123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

### 8.6 Test Webhook

Send a test event to verify your webhook receiver is working.

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/integrations/webhooks/webhook_abc123/test \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

---

## 9. Third-Party Integrations

### 9.1 QuickBooks Integration

#### 9.1.1 Connect to QuickBooks

Initiate OAuth 2.0 flow to connect user's QuickBooks account.

**JavaScript (Frontend) Example:**
```javascript
function connectQuickBooks() {
  const redirectUri = encodeURIComponent(
    window.location.origin + '/integrations/quickbooks/callback'
  );
  const state = generateRandomState();
  
  sessionStorage.setItem('quickbooks_state', state);
  
  window.location.href = 
    `https://api.ocr-app.com/api/v1/integrations/quickbooks/connect?` +
    `redirect_uri=${redirectUri}&state=${state}`;
}

function handleQuickBooksCallback() {
  const urlParams = new URLSearchParams(window.location.search);
  const status = urlParams.get('status');
  const state = urlParams.get('state');
  const error = urlParams.get('error');
  
  const storedState = sessionStorage.getItem('quickbooks_state');
  if (state !== storedState) {
    console.error('State mismatch');
    return;
  }
  
  if (status === 'success') {
    console.log('QuickBooks connected successfully!');
    // Redirect to integrations page
    window.location.href = '/settings/integrations';
  } else {
    console.error('QuickBooks connection failed:', error);
  }
}
```

#### 9.1.2 Sync Invoice to QuickBooks

**JavaScript Example:**
```javascript
async function syncToQuickBooks(documentIds, accessToken) {
  try {
    const response = await axios.post(
      'https://api.ocr-app.com/api/v1/integrations/quickbooks/sync',
      {
        document_ids: documentIds,
        action: 'create_bill', // or 'create_invoice', 'create_expense'
        mapping: {
          'vendor.name': 'vendor',
          'invoice_date': 'txn_date',
          'due_date': 'due_date',
          'line_items': 'lines',
          'total': 'total_amt'
        }
      },
      {
        headers: {
          'Authorization': `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        }
      }
    );
    
    const syncJob = response.data.data;
    console.log('Sync job created:', syncJob.sync_job_id);
    console.log('Status:', syncJob.status);
    
    return syncJob;
  } catch (error) {
    if (error.response && error.response.status === 403) {
      console.error('QuickBooks not connected. Please connect first.');
    } else {
      console.error('Sync failed:', error.response.data);
    }
    throw error;
  }
}

// Usage
syncToQuickBooks(['doc_abc123', 'doc_def456'], accessToken);
```

**Python Example:**
```python
def sync_to_quickbooks(document_ids, access_token):
    url = 'https://api.ocr-app.com/api/v1/integrations/quickbooks/sync'
    
    headers = {
        'Authorization': f'Bearer {access_token}',
        'Content-Type': 'application/json'
    }
    
    payload = {
        'document_ids': document_ids,
        'action': 'create_bill',
        'mapping': {
            'vendor.name': 'vendor',
            'invoice_date': 'txn_date',
            'total': 'total_amt'
        }
    }
    
    response = requests.post(url, headers=headers, json=payload)
    response.raise_for_status()
    
    sync_job = response.json()['data']
    print(f"Sync job created: {sync_job['sync_job_id']}")
    
    return sync_job
```

#### 9.1.3 Check QuickBooks Sync Status

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/integrations/quickbooks/sync/sync_abc123 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

**Response:**
```json
{
  "success": true,
  "data": {
    "sync_job_id": "sync_abc123",
    "status": "completed",
    "documents_synced": 2,
    "documents_failed": 0,
    "results": [
      {
        "document_id": "doc_abc123",
        "quickbooks_id": "123",
        "quickbooks_type": "Bill",
        "status": "success"
      }
    ],
    "completed_at": "2025-01-15T12:01:00Z"
  }
}
```

### 9.2 Salesforce Integration

#### 9.2.1 Connect to Salesforce

Similar OAuth flow to QuickBooks.

**JavaScript Example:**
```javascript
function connectSalesforce() {
  const redirectUri = encodeURIComponent(
    window.location.origin + '/integrations/salesforce/callback'
  );
  
  window.location.href = 
    `https://api.ocr-app.com/api/v1/integrations/salesforce/connect?` +
    `redirect_uri=${redirectUri}`;
}
```

#### 9.2.2 Create Salesforce Lead from Document

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/integrations/salesforce/sync \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "document_id": "doc_abc123",
    "object_type": "Lead",
    "field_mapping": {
      "customer.name": "Company",
      "customer.contact": "LastName",
      "customer.email": "Email"
    }
  }'
```

### 9.3 NetSuite Integration

#### 9.3.1 Connect to NetSuite

NetSuite uses Token-Based Authentication (TBA) instead of OAuth.

**JavaScript Example:**
```javascript
async function connectNetSuite(credentials, accessToken) {
  const response = await axios.post(
    'https://api.ocr-app.com/api/v1/integrations/netsuite/connect',
    {
      account_id: credentials.accountId,
      consumer_key: credentials.consumerKey,
      consumer_secret: credentials.consumerSecret,
      token_id: credentials.tokenId,
      token_secret: credentials.tokenSecret
    },
    {
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      }
    }
  );
  
  console.log('NetSuite connected successfully');
  return response.data.data;
}
```

#### 9.3.2 Sync to NetSuite

**cURL Example:**
```bash
curl -X POST https://api.ocr-app.com/api/v1/integrations/netsuite/sync \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..." \
  -H "Content-Type: application/json" \
  -d '{
    "document_ids": ["doc_abc123"],
    "record_type": "vendorbill"
  }'
```

### 9.4 Integration Status

Check connection status for all integrations.

**cURL Example:**
```bash
curl -X GET https://api.ocr-app.com/api/v1/integrations/status \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

**Response:**
```json
{
  "success": true,
  "data": {
    "quickbooks": {
      "connected": true,
      "company_name": "Acme Corp",
      "connected_at": "2025-01-15T10:00:00Z",
      "last_sync": "2025-01-15T11:30:00Z"
    },
    "salesforce": {
      "connected": false
    },
    "netsuite": {
      "connected": true,
      "account_id": "1234567",
      "connected_at": "2025-01-10T09:00:00Z"
    }
  }
}
```

---

## 10. Error Handling and Best Practices

### 10.1 HTTP Status Codes

The API uses standard HTTP status codes:

| Code | Meaning | Description |
|------|---------|-------------|
| 200 | OK | Request succeeded |
| 201 | Created | Resource created successfully |
| 204 | No Content | Request succeeded, no content to return |
| 400 | Bad Request | Invalid request parameters or validation error |
| 401 | Unauthorized | Missing or invalid authentication |
| 403 | Forbidden | Authenticated but insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Resource conflict (e.g., duplicate) |
| 413 | Payload Too Large | File size exceeds maximum |
| 415 | Unsupported Media Type | Invalid file type |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |
| 503 | Service Unavailable | Service temporarily unavailable |

### 10.2 Error Response Format

All errors follow a consistent format:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid document format",
    "details": [
      {
        "field": "file_type",
        "message": "Must be PDF, JPG, PNG, or TIFF"
      }
    ]
  },
  "meta": {
    "timestamp": "2025-01-15T12:00:00Z",
    "request_id": "req_xyz789"
  }
}
```

### 10.3 Comprehensive Error Handling

**JavaScript Example:**
```javascript
async function apiRequest(url, options, accessToken) {
  try {
    const response = await axios({
      url: `https://api.ocr-app.com/api/v1${url}`,
      ...options,
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        ...options.headers
      },
      timeout: 30000 // 30 second timeout
    });
    
    return response.data;
  } catch (error) {
    // Network errors
    if (!error.response) {
      console.error('Network error. Please check your connection.');
      throw new Error('NETWORK_ERROR');
    }
    
    const { status, data } = error.response;
    const errorCode = data.error?.code || 'UNKNOWN_ERROR';
    const errorMessage = data.error?.message || 'An error occurred';
    
    switch (status) {
      case 400:
        console.error('Validation error:', data.error.details);
        // Show validation errors to user
        if (data.error.details && Array.isArray(data.error.details)) {
          data.error.details.forEach(detail => {
            console.error(`- ${detail.field}: ${detail.message}`);
          });
        }
        break;
      
      case 401:
        console.error('Authentication failed. Token may be expired.');
        // Attempt token refresh or redirect to login
        await handleAuthError();
        break;
      
      case 403:
        console.error('Insufficient permissions for this operation');
        throw new Error('FORBIDDEN');
      
      case 404:
        console.error('Resource not found');
        throw new Error('NOT_FOUND');
      
      case 409:
        console.error('Resource conflict:', errorMessage);
        throw new Error('CONFLICT');
      
      case 413:
        console.error('File too large. Maximum size: 50MB');
        throw new Error('FILE_TOO_LARGE');
      
      case 415:
        console.error('Unsupported file type. Allowed: PDF, JPG, PNG, TIFF');
        throw new Error('UNSUPPORTED_FILE_TYPE');
      
      case 429:
        const retryAfter = parseInt(error.response.headers['retry-after'] || 60);
        console.error(`Rate limit exceeded. Retry after ${retryAfter} seconds`);
        throw new Error(`RATE_LIMIT_EXCEEDED:${retryAfter}`);
      
      case 500:
        console.error('Server error. Please try again later.');
        // Log error for monitoring
        logErrorToMonitoring(error);
        throw new Error('SERVER_ERROR');
      
      case 503:
        console.error('Service temporarily unavailable');
        throw new Error('SERVICE_UNAVAILABLE');
      
      default:
        console.error('Unexpected error:', errorMessage);
        throw new Error(errorCode);
    }
  }
}

async function handleAuthError() {
  // Try to refresh token
  try {
    const refreshToken = localStorage.getItem('refresh_token');
    const response = await axios.post(
      'https://api.ocr-app.com/api/v1/auth/refresh',
      { refresh_token: refreshToken }
    );
    
    const { access_token } = response.data.data;
    localStorage.setItem('access_token', access_token);
    
    console.log('Token refreshed successfully');
  } catch (refreshError) {
    console.error('Token refresh failed. Redirecting to login...');
    // Redirect to login page
    window.location.href = '/auth/login';
  }
}

function logErrorToMonitoring(error) {
  // Send to monitoring service (Sentry, DataDog, etc.)
  if (typeof Sentry !== 'undefined') {
    Sentry.captureException(error);
  }
}
```

**Python Example:**
```python
import requests
from requests.exceptions import RequestException
import time

class APIError(Exception):
    """Base exception for API errors"""
    def __init__(self, code, message, details=None):
        self.code = code
        self.message = message
        self.details = details
        super().__init__(self.message)

def api_request(url, method='GET', data=None, access_token=None, timeout=30):
    """
    Make API request with comprehensive error handling.
    
    Args:
        url (str): API endpoint path (without base URL)
        method (str): HTTP method
        data (dict): Request payload
        access_token (str): JWT access token
        timeout (int): Request timeout in seconds
    
    Returns:
        dict: Response data
    
    Raises:
        APIError: For all API-related errors
    """
    full_url = f'https://api.ocr-app.com/api/v1{url}'
    
    headers = {}
    if access_token:
        headers['Authorization'] = f'Bearer {access_token}'
    if data:
        headers['Content-Type'] = 'application/json'
    
    try:
        response = requests.request(
            method,
            full_url,
            headers=headers,
            json=data,
            timeout=timeout
        )
        
        # Success
        if response.status_code < 400:
            return response.json()
        
        # Error handling
        try:
            error_data = response.json()
            error_code = error_data['error']['code']
            error_message = error_data['error']['message']
            error_details = error_data['error'].get('details')
        except:
            error_code = 'UNKNOWN_ERROR'
            error_message = response.text or 'An error occurred'
            error_details = None
        
        status = response.status_code
        
        if status == 400:
            print(f"Validation error: {error_message}")
            if error_details:
                for detail in error_details:
                    print(f"  - {detail['field']}: {detail['message']}")
            raise APIError('VALIDATION_ERROR', error_message, error_details)
        
        elif status == 401:
            print("Authentication failed")
            raise APIError('UNAUTHORIZED', 'Authentication failed')
        
        elif status == 403:
            print("Insufficient permissions")
            raise APIError('FORBIDDEN', error_message)
        
        elif status == 404:
            print("Resource not found")
            raise APIError('NOT_FOUND', error_message)
        
        elif status == 429:
            retry_after = int(response.headers.get('Retry-After', 60))
            print(f"Rate limit exceeded. Retry after {retry_after}s")
            raise APIError('RATE_LIMIT_EXCEEDED', error_message, {'retry_after': retry_after})
        
        elif status == 500:
            print("Server error")
            raise APIError('SERVER_ERROR', error_message)
        
        elif status == 503:
            print("Service unavailable")
            raise APIError('SERVICE_UNAVAILABLE', error_message)
        
        else:
            raise APIError(error_code, error_message)
    
    except RequestException as e:
        print(f"Network error: {str(e)}")
        raise APIError('NETWORK_ERROR', str(e))

# Usage
try:
    result = api_request('/documents', access_token=access_token)
    print(f"Success: {len(result['data'])} documents")
except APIError as e:
    print(f"API Error [{e.code}]: {e.message}")
    if e.details:
        print(f"Details: {e.details}")
```

### 10.4 Validation Error Handling

When receiving validation errors (400 status), display field-specific messages to users.

**JavaScript Example:**
```javascript
function handleValidationErrors(errorDetails) {
  const errors = {};
  
  errorDetails.forEach(detail => {
    errors[detail.field] = detail.message;
  });
  
  // Display errors in form
  Object.keys(errors).forEach(field => {
    const inputElement = document.querySelector(`[name="${field}"]`);
    if (inputElement) {
      const errorElement = document.createElement('span');
      errorElement.className = 'error-message';
      errorElement.textContent = errors[field];
      inputElement.parentNode.appendChild(errorElement);
      inputElement.classList.add('error');
    }
  });
}
```

### 10.5 Best Practices

1. **Always Use HTTPS** - Never use HTTP for API requests
2. **Store Tokens Securely** - Use secure storage (not localStorage for sensitive data)
3. **Implement Token Refresh** - Automatically refresh expired tokens
4. **Handle Rate Limits** - Implement exponential backoff with retry logic
5. **Set Timeouts** - Always set request timeouts (30s recommended)
6. **Validate Inputs** - Validate data before sending to API
7. **Log Errors** - Send errors to monitoring service
8. **Use Request IDs** - Include request IDs in logs for tracing
9. **Implement Idempotency** - Use idempotency keys for critical operations
10. **Test Error Scenarios** - Test your error handling code

---

## 11. Rate Limiting and Retry Logic

### 11.1 Rate Limit Headers

Every API response includes rate limit information:

```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 987
X-RateLimit-Reset: 1642348800
```

### 11.2 Handling Rate Limits

When you exceed the rate limit, the API returns a 429 status with a `Retry-After` header.

**JavaScript Example with Exponential Backoff:**
```javascript
async function apiRequestWithRetry(
  url,
  options,
  accessToken,
  maxRetries = 3,
  baseDelay = 1000
) {
  let attempt = 0;
  
  while (attempt <= maxRetries) {
    try {
      return await apiRequest(url, options, accessToken);
    } catch (error) {
      attempt++;
      
      // Rate limit exceeded
      if (error.message.startsWith('RATE_LIMIT_EXCEEDED')) {
        const retryAfter = parseInt(error.message.split(':')[1] || 60);
        console.log(`Rate limited. Waiting ${retryAfter}s before retry...`);
        await sleep(retryAfter * 1000);
        continue;
      }
      
      // Server errors - retry with exponential backoff
      if (error.message === 'SERVER_ERROR' || error.message === 'SERVICE_UNAVAILABLE') {
        if (attempt > maxRetries) {
          throw error;
        }
        
        const delay = baseDelay * Math.pow(2, attempt - 1);
        const jitter = Math.random() * 1000; // Add jitter to prevent thundering herd
        const totalDelay = delay + jitter;
        
        console.log(`Retry attempt ${attempt}/${maxRetries} after ${Math.round(totalDelay)}ms`);
        await sleep(totalDelay);
        continue;
      }
      
      // Other errors - don't retry
      throw error;
    }
  }
  
  throw new Error('Max retries exceeded');
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

// Usage
apiRequestWithRetry('/documents', { method: 'GET' }, accessToken)
  .then(data => console.log('Success:', data))
  .catch(error => console.error('Failed after retries:', error));
```

**Python Example:**
```python
import time
import random

def api_request_with_retry(
    url,
    access_token,
    method='GET',
    data=None,
    max_retries=3,
    base_delay=1
):
    """
    Make API request with retry logic and exponential backoff.
    
    Args:
        url (str): API endpoint path
        access_token (str): JWT access token
        method (str): HTTP method
        data (dict): Request payload
        max_retries (int): Maximum number of retry attempts
        base_delay (int): Base delay in seconds for exponential backoff
    
    Returns:
        dict: Response data
    
    Raises:
        APIError: After all retries are exhausted
    """
    attempt = 0
    
    while attempt <= max_retries:
        try:
            return api_request(url, method, data, access_token)
        
        except APIError as e:
            attempt += 1
            
            # Rate limit exceeded
            if e.code == 'RATE_LIMIT_EXCEEDED':
                retry_after = e.details.get('retry_after', 60)
                print(f"Rate limited. Waiting {retry_after}s before retry...")
                time.sleep(retry_after)
                continue
            
            # Server errors - retry with exponential backoff
            if e.code in ['SERVER_ERROR', 'SERVICE_UNAVAILABLE']:
                if attempt > max_retries:
                    raise
                
                delay = base_delay * (2 ** (attempt - 1))
                jitter = random.random()  # Add jitter 0-1 seconds
                total_delay = delay + jitter
                
                print(f"Retry attempt {attempt}/{max_retries} after {total_delay:.2f}s")
                time.sleep(total_delay)
                continue
            
            # Network errors - retry
            if e.code == 'NETWORK_ERROR':
                if attempt > max_retries:
                    raise
                
                delay = base_delay * (2 ** (attempt - 1))
                print(f"Network error. Retrying in {delay}s...")
                time.sleep(delay)
                continue
            
            # Other errors - don't retry
            raise
    
    raise APIError('MAX_RETRIES_EXCEEDED', 'Failed after maximum retry attempts')

# Usage
try:
    result = api_request_with_retry('/documents', access_token)
    print(f"Success: {len(result['data'])} documents")
except APIError as e:
    print(f"Failed after retries: {e.message}")
```

### 11.3 Rate Limit Monitoring

**JavaScript Example:**
```javascript
function checkRateLimits(response) {
  const limit = parseInt(response.headers['x-ratelimit-limit']);
  const remaining = parseInt(response.headers['x-ratelimit-remaining']);
  const reset = parseInt(response.headers['x-ratelimit-reset']);
  
  const percentUsed = ((limit - remaining) / limit) * 100;
  
  if (percentUsed > 80) {
    const resetDate = new Date(reset * 1000);
    console.warn(
      `Rate limit warning: ${percentUsed.toFixed(1)}% used. ` +
      `Resets at ${resetDate.toISOString()}`
    );
  }
  
  return { limit, remaining, reset, percentUsed };
}
```

---

## 12. SDKs and Libraries

### 12.1 Official SDKs

Official SDKs are under development and will be available soon:

- **JavaScript/TypeScript SDK** - Coming Q2 2025
- **Python SDK** - Coming Q2 2025
- **Java SDK** - Coming Q3 2025
- **PHP SDK** - Coming Q3 2025
- **.NET SDK** - Coming Q4 2025

Sign up for SDK early access: https://developers.ocr-app.com/sdk-access

### 12.2 OpenAPI Generator

Generate client libraries in any language using our OpenAPI specification:

**Install OpenAPI Generator:**
```bash
npm install -g @openapitools/openapi-generator-cli
```

**Generate JavaScript Client:**
```bash
openapi-generator-cli generate \
  -i https://api.ocr-app.com/api/v1/openapi.yaml \
  -g javascript \
  -o ./ocr-api-client \
  --additional-properties=projectName=ocr-api-client
```

**Generate Python Client:**
```bash
openapi-generator-cli generate \
  -i https://api.ocr-app.com/api/v1/openapi.yaml \
  -g python \
  -o ./ocr-api-client \
  --additional-properties=packageName=ocr_api_client
```

**Supported Languages:**
- JavaScript/TypeScript
- Python
- Java
- PHP
- Ruby
- Go
- C#/.NET
- Swift
- Kotlin
- And 50+ more languages

### 12.3 Community Libraries

Community-maintained libraries and wrappers:

- **ocr-app-node** (by @johndoe) - Unofficial Node.js wrapper
- **ocr-app-python** (by @janedoe) - Unofficial Python SDK

*Note: Community libraries are not officially supported. Use at your own discretion.*

### 12.4 Postman Collection

Import our Postman collection for easy API testing:

**Download Collection:**
https://api.ocr-app.com/api/v1/postman-collection.json

**Import to Postman:**
1. Open Postman
2. Click "Import" button
3. Paste the collection URL
4. Set your access token in the collection variables

---

## Additional Resources

### Documentation
- **Full API Reference:** https://docs.ocr-app.com/api
- **Developer Portal:** https://developers.ocr-app.com
- **OpenAPI Specification:** https://api.ocr-app.com/api/v1/openapi.yaml

### Support
- **Community Forum:** https://community.ocr-app.com
- **GitHub Discussions:** https://github.com/ocr-app/discussions
- **Email Support:** support@ocr-app.com
- **Enterprise Support:** enterprise@ocr-app.com

### Status and Monitoring
- **API Status Page:** https://status.ocr-app.com
- **Incident History:** https://status.ocr-app.com/history
- **Maintenance Schedule:** https://status.ocr-app.com/maintenance

### Legal
- **Terms of Service:** https://ocr-app.com/terms
- **Privacy Policy:** https://ocr-app.com/privacy
- **SLA Agreement:** https://ocr-app.com/sla

---

## Changelog

### v1.0.0 (January 15, 2025)
- Initial API release
- Authentication endpoints (JWT, OAuth 2.0, API Keys)
- Document upload and processing
- Template management
- Search functionality
- Data export (JSON, CSV, XML, Excel)
- Webhook system
- Third-party integrations (QuickBooks, Salesforce, NetSuite)

---

## Feedback

We value your feedback! Help us improve this documentation:

- **Report errors:** docs@ocr-app.com
- **Suggest improvements:** https://github.com/ocr-app/docs/issues
- **Request examples:** https://community.ocr-app.com/feature-requests

---

**© 2025 OCR Processing Application. All rights reserved.**
