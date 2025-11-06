/**
 * String Manipulation Utilities
 * 
 * Provides comprehensive string sanitization, transformation, validation, and formatting functions
 * for the OCR Processing Application backend. Implements security requirements from Section 0.7.1
 * including XSS prevention, SQL injection prevention, and input validation.
 * 
 * Security Features:
 * - HTML entity encoding for XSS prevention
 * - Dangerous character removal for SQL injection prevention
 * - Input sanitization for all user-provided strings
 * - Safe string masking for sensitive data display
 * 
 * @module string.utils
 */

/**
 * Sanitizes user input by removing or escaping dangerous characters to prevent security vulnerabilities.
 * Removes HTML tags, script tags, SQL injection patterns, and other potentially malicious content.
 * 
 * Security Implementation per Section 0.7.1:
 * - Removes <script> tags and JavaScript event handlers
 * - Strips SQL injection patterns (UNION, SELECT, DROP, etc.)
 * - Removes path traversal sequences (../, ..\)
 * - Strips null bytes and other control characters
 * 
 * @param {string | null | undefined} input - The string to sanitize
 * @returns {string} Sanitized string safe for database storage and display
 * 
 * @example
 * sanitizeInput('<script>alert("xss")</script>Hello'); // Returns: 'Hello'
 * sanitizeInput('DROP TABLE users; --'); // Returns: 'users'
 * sanitizeInput(null); // Returns: ''
 */
export function sanitizeInput(input: string | null | undefined): string {
  // Handle null, undefined, or empty inputs
  if (input == null || input === '') {
    return '';
  }

  // Convert to string if not already
  let sanitized = String(input);

  // Remove script tags and their content (case-insensitive)
  sanitized = sanitized.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '');

  // Remove all HTML tags
  sanitized = sanitized.replace(/<[^>]*>/g, '');

  // Remove JavaScript event handlers
  sanitized = sanitized.replace(/on\w+\s*=\s*["'][^"']*["']/gi, '');
  sanitized = sanitized.replace(/on\w+\s*=\s*[^\s>]*/gi, '');

  // Remove javascript: protocol
  sanitized = sanitized.replace(/javascript:/gi, '');

  // Remove data: protocol (can be used for XSS)
  sanitized = sanitized.replace(/data:text\/html/gi, '');

  // Remove SQL injection patterns
  const sqlPatterns = [
    /\b(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|DECLARE|TABLE|FROM|WHERE|JOIN)\b/gi,
    /(--|\;|\/\*|\*\/)/g,
    /\b(OR|AND)\b\s+\d+\s*=\s*\d+/gi,
  ];

  sqlPatterns.forEach(pattern => {
    sanitized = sanitized.replace(pattern, '');
  });

  // Remove path traversal sequences
  sanitized = sanitized.replace(/\.\.[\/\\]/g, '');
  sanitized = sanitized.replace(/\.\./g, '');

  // Remove null bytes and control characters (except newlines and tabs)
  sanitized = sanitized.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');

  // Normalize whitespace
  sanitized = sanitized.replace(/\s+/g, ' ').trim();

  return sanitized;
}

/**
 * Escapes special HTML characters to prevent XSS attacks when displaying user content.
 * Converts characters like <, >, &, ", and ' to their HTML entity equivalents.
 * 
 * Essential for XSS Prevention per Section 0.7.1 Security Requirements.
 * 
 * @param {string | null | undefined} text - The text to escape
 * @returns {string} HTML-safe string with special characters encoded
 * 
 * @example
 * escapeHTML('<div>Test & "quotes"</div>'); 
 * // Returns: '&lt;div&gt;Test &amp; &quot;quotes&quot;&lt;/div&gt;'
 * 
 * escapeHTML("It's a <test>"); 
 * // Returns: 'It&#x27;s a &lt;test&gt;'
 */
export function escapeHTML(text: string | null | undefined): string {
  if (text == null || text === '') {
    return '';
  }

  const htmlEscapeMap: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#x27;',
    '/': '&#x2F;',
  };

  return String(text).replace(/[&<>"'\/]/g, (char) => htmlEscapeMap[char] || char);
}

/**
 * Normalizes a string by trimming whitespace, normalizing Unicode characters,
 * and standardizing formatting for consistent data storage and comparison.
 * 
 * Normalization Operations:
 * - Trims leading and trailing whitespace
 * - Converts to Unicode NFC (Canonical Decomposition followed by Canonical Composition)
 * - Normalizes multiple spaces to single space
 * - Removes zero-width characters
 * 
 * @param {string | null | undefined} input - The string to normalize
 * @returns {string} Normalized string
 * 
 * @example
 * normalizeString('  Hello   World  '); // Returns: 'Hello World'
 * normalizeString('Café'); // Returns: 'Café' (NFC normalized)
 * normalizeString('\u200BHidden'); // Returns: 'Hidden' (zero-width removed)
 */
export function normalizeString(input: string | null | undefined): string {
  if (input == null || input === '') {
    return '';
  }

  let normalized = String(input);

  // Unicode normalization to NFC form
  normalized = normalized.normalize('NFC');

  // Remove zero-width characters (zero-width space, zero-width non-joiner, zero-width joiner)
  normalized = normalized.replace(/[\u200B-\u200D\uFEFF]/g, '');

  // Normalize whitespace: replace multiple spaces with single space
  normalized = normalized.replace(/\s+/g, ' ');

  // Trim leading and trailing whitespace
  normalized = normalized.trim();

  return normalized;
}

/**
 * Truncates a string to a specified maximum length and adds an ellipsis if truncated.
 * Useful for display purposes where space is limited (e.g., document titles, descriptions).
 * 
 * @param {string | null | undefined} text - The text to truncate
 * @param {number} maxLength - Maximum length before truncation (default: 100)
 * @param {string} ellipsis - String to append when truncated (default: '...')
 * @returns {string} Truncated string with ellipsis if applicable
 * 
 * @example
 * truncate('This is a very long document title', 20); 
 * // Returns: 'This is a very lo...'
 * 
 * truncate('Short', 20); 
 * // Returns: 'Short'
 * 
 * truncate('Exactly twenty chars', 20, ''); 
 * // Returns: 'Exactly twenty chars'
 */
export function truncate(
  text: string | null | undefined,
  maxLength: number = 100,
  ellipsis: string = '...',
): string {
  if (text == null || text === '') {
    return '';
  }

  const str = String(text);

  // Validate maxLength
  if (maxLength <= 0) {
    return '';
  }

  if (str.length <= maxLength) {
    return str;
  }

  // Calculate the cut-off point accounting for ellipsis length
  const cutoff = Math.max(0, maxLength - ellipsis.length);
  
  return str.substring(0, cutoff) + ellipsis;
}

/**
 * Checks if a string is empty, null, undefined, or contains only whitespace.
 * 
 * @param {string | null | undefined} value - The value to check
 * @returns {boolean} True if the string is empty or whitespace-only
 * 
 * @example
 * isEmpty(''); // Returns: true
 * isEmpty('   '); // Returns: true
 * isEmpty(null); // Returns: true
 * isEmpty(undefined); // Returns: true
 * isEmpty('Hello'); // Returns: false
 */
export function isEmpty(value: string | null | undefined): boolean {
  if (value == null) {
    return true;
  }

  return String(value).trim().length === 0;
}

/**
 * Validates if a string is a properly formatted email address.
 * Uses RFC 5322 compliant regex pattern for email validation.
 * 
 * Validation Criteria:
 * - Valid local part (before @)
 * - Valid domain part (after @)
 * - Proper domain extension (2-6 characters)
 * - No special characters that violate RFC 5322
 * 
 * @param {string | null | undefined} email - The email address to validate
 * @returns {boolean} True if the string is a valid email address
 * 
 * @example
 * isEmail('user@example.com'); // Returns: true
 * isEmail('user.name+tag@example.co.uk'); // Returns: true
 * isEmail('invalid.email'); // Returns: false
 * isEmail('@example.com'); // Returns: false
 * isEmail(null); // Returns: false
 */
export function isEmail(email: string | null | undefined): boolean {
  if (isEmpty(email)) {
    return false;
  }

  const str = String(email).trim();

  // RFC 5322 compliant email regex (simplified but comprehensive)
  const emailRegex = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;

  // Additional validation: check for valid TLD length (2-6 characters)
  const parts = str.split('@');
  if (parts.length !== 2) {
    return false;
  }

  const domain = parts[1];
  if (!domain) {
    return false;
  }

  const domainParts = domain.split('.');
  
  if (domainParts.length < 2) {
    return false;
  }

  const tld = domainParts[domainParts.length - 1];
  if (!tld || tld.length < 2 || tld.length > 6) {
    return false;
  }

  return emailRegex.test(str);
}

/**
 * Validates if a string is a properly formatted URL.
 * Supports http, https, ftp, and ftps protocols.
 * 
 * Validation Criteria:
 * - Valid protocol (http, https, ftp, ftps)
 * - Valid domain name or IP address
 * - Optional port number
 * - Optional path, query parameters, and fragment
 * 
 * @param {string | null | undefined} url - The URL to validate
 * @returns {boolean} True if the string is a valid URL
 * 
 * @example
 * isURL('https://example.com'); // Returns: true
 * isURL('http://example.com/path?query=value'); // Returns: true
 * isURL('ftp://files.example.com:21/files'); // Returns: true
 * isURL('not-a-url'); // Returns: false
 * isURL('//example.com'); // Returns: false
 * isURL(null); // Returns: false
 */
export function isURL(url: string | null | undefined): boolean {
  if (isEmpty(url)) {
    return false;
  }

  const str = String(url).trim();

  try {
    // Use built-in URL constructor for validation
    const urlObj = new URL(str);
    
    // Check for valid protocols
    const validProtocols = ['http:', 'https:', 'ftp:', 'ftps:'];
    if (!validProtocols.includes(urlObj.protocol)) {
      return false;
    }

    // Check that hostname exists
    if (!urlObj.hostname) {
      return false;
    }

    return true;
  } catch (error) {
    return false;
  }
}

/**
 * Validates if a string is a properly formatted phone number.
 * Supports various international phone number formats.
 * 
 * Validation Criteria:
 * - Minimum 7 digits (local numbers)
 * - Maximum 15 digits (international standard)
 * - Allows common formatting characters: +, -, (), spaces
 * - Optional country code prefix (+)
 * 
 * @param {string | null | undefined} phone - The phone number to validate
 * @returns {boolean} True if the string is a valid phone number
 * 
 * @example
 * isPhoneNumber('+1-555-123-4567'); // Returns: true
 * isPhoneNumber('(555) 123-4567'); // Returns: true
 * isPhoneNumber('5551234567'); // Returns: true
 * isPhoneNumber('+44 20 7946 0958'); // Returns: true
 * isPhoneNumber('123'); // Returns: false (too short)
 * isPhoneNumber('abc-def-ghij'); // Returns: false (no digits)
 * isPhoneNumber(null); // Returns: false
 */
export function isPhoneNumber(phone: string | null | undefined): boolean {
  if (isEmpty(phone)) {
    return false;
  }

  const str = String(phone).trim();

  // Remove common formatting characters to get digits only
  const digitsOnly = str.replace(/[\s\-().+]/g, '');

  // Check if remaining string contains only digits
  if (!/^\d+$/.test(digitsOnly)) {
    return false;
  }

  // International phone number standard: minimum 7 digits, maximum 15 digits
  const digitCount = digitsOnly.length;
  if (digitCount < 7 || digitCount > 15) {
    return false;
  }

  return true;
}

/**
 * Converts a string to a URL-safe slug format.
 * Useful for generating SEO-friendly URLs, document identifiers, and file names.
 * 
 * Transformation Rules:
 * - Converts to lowercase
 * - Replaces spaces and special characters with hyphens
 * - Removes consecutive hyphens
 * - Trims leading/trailing hyphens
 * - Removes non-alphanumeric characters (except hyphens)
 * 
 * @param {string | null | undefined} text - The text to convert to a slug
 * @returns {string} URL-safe slug string
 * 
 * @example
 * toSlug('Hello World!'); // Returns: 'hello-world'
 * toSlug('Product Name (2024)'); // Returns: 'product-name-2024'
 * toSlug('  Multiple   Spaces  '); // Returns: 'multiple-spaces'
 */
export function toSlug(text: string | null | undefined): string {
  if (text == null || text === '') {
    return '';
  }

  let slug = String(text);

  // Convert to lowercase
  slug = slug.toLowerCase();

  // Remove accents and diacritics
  slug = slug.normalize('NFD').replace(/[\u0300-\u036f]/g, '');

  // Replace spaces and underscores with hyphens
  slug = slug.replace(/[\s_]+/g, '-');

  // Remove all non-alphanumeric characters except hyphens
  slug = slug.replace(/[^a-z0-9-]/g, '');

  // Replace multiple consecutive hyphens with single hyphen
  slug = slug.replace(/-+/g, '-');

  // Trim hyphens from start and end
  slug = slug.replace(/^-+|-+$/g, '');

  return slug;
}

/**
 * Converts a string to camelCase format.
 * Useful for converting user input to JavaScript property names.
 * 
 * @param {string | null | undefined} text - The text to convert
 * @returns {string} camelCase formatted string
 * 
 * @example
 * toCamelCase('hello world'); // Returns: 'helloWorld'
 * toCamelCase('user-first-name'); // Returns: 'userFirstName'
 * toCamelCase('API_KEY_VALUE'); // Returns: 'apiKeyValue'
 */
export function toCamelCase(text: string | null | undefined): string {
  if (text == null || text === '') {
    return '';
  }

  const str = String(text).trim();

  // Split by spaces, hyphens, underscores, or capital letters
  const words = str
    .replace(/([a-z])([A-Z])/g, '$1 $2') // Add space before capital letters
    .split(/[\s_-]+/)
    .filter(word => word.length > 0);

  if (words.length === 0) {
    return '';
  }

  // First word lowercase, rest with first letter capitalized
  return words
    .map((word, index) => {
      const lower = word.toLowerCase();
      if (index === 0) {
        return lower;
      }
      return lower.charAt(0).toUpperCase() + lower.slice(1);
    })
    .join('');
}

/**
 * Converts a string to snake_case format.
 * Useful for database column names and Python-style variable names.
 * 
 * @param {string | null | undefined} text - The text to convert
 * @returns {string} snake_case formatted string
 * 
 * @example
 * toSnakeCase('helloWorld'); // Returns: 'hello_world'
 * toSnakeCase('User First Name'); // Returns: 'user_first_name'
 * toSnakeCase('APIKeyValue'); // Returns: 'api_key_value'
 */
export function toSnakeCase(text: string | null | undefined): string {
  if (text == null || text === '') {
    return '';
  }

  const str = String(text).trim();

  // Insert underscore before capital letters and convert to lowercase
  return str
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2') // Handle consecutive capitals like "API"
    .replace(/([a-z])([A-Z])/g, '$1_$2') // camelCase to snake_case
    .replace(/[\s-]+/g, '_') // spaces and hyphens to underscores
    .replace(/_+/g, '_') // multiple underscores to single
    .toLowerCase()
    .replace(/^_+|_+$/g, ''); // trim underscores
}

/**
 * Converts a string to kebab-case format.
 * Useful for CSS class names and URL slugs.
 * 
 * @param {string | null | undefined} text - The text to convert
 * @returns {string} kebab-case formatted string
 * 
 * @example
 * toKebabCase('helloWorld'); // Returns: 'hello-world'
 * toKebabCase('User First Name'); // Returns: 'user-first-name'
 * toKebabCase('APIKeyValue'); // Returns: 'api-key-value'
 */
export function toKebabCase(text: string | null | undefined): string {
  if (text == null || text === '') {
    return '';
  }

  const str = String(text).trim();

  // Insert hyphen before capital letters and convert to lowercase
  return str
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2') // Handle consecutive capitals like "API"
    .replace(/([a-z])([A-Z])/g, '$1-$2') // camelCase to kebab-case
    .replace(/[\s_]+/g, '-') // spaces and underscores to hyphens
    .replace(/-+/g, '-') // multiple hyphens to single
    .toLowerCase()
    .replace(/^-+|-+$/g, ''); // trim hyphens
}

/**
 * Masks an email address for secure display, showing only first character and domain.
 * Prevents exposure of full email addresses in logs and user interfaces per Section 0.7.1.
 * 
 * @param {string | null | undefined} email - The email address to mask
 * @returns {string} Masked email address
 * 
 * @example
 * maskEmail('john.doe@example.com'); // Returns: 'j***@example.com'
 * maskEmail('a@test.com'); // Returns: 'a***@test.com'
 * maskEmail('invalid'); // Returns: '***'
 */
export function maskEmail(email: string | null | undefined): string {
  if (email == null || email === '') {
    return '***';
  }

  const str = String(email).trim();
  const atIndex = str.indexOf('@');

  if (atIndex <= 0) {
    // Invalid email format
    return '***';
  }

  const localPart = str.substring(0, atIndex);
  const domain = str.substring(atIndex);

  // Show first character of local part, mask the rest
  if (localPart.length === 0) {
    return '***' + domain;
  }

  return localPart.charAt(0) + '***' + domain;
}

/**
 * Masks a phone number for secure display, showing only last 4 digits.
 * Prevents exposure of full phone numbers in logs and user interfaces per Section 0.7.1.
 * 
 * @param {string | null | undefined} phone - The phone number to mask
 * @returns {string} Masked phone number
 * 
 * @example
 * maskPhone('1234567890'); // Returns: '******7890'
 * maskPhone('+1-555-123-4567'); // Returns: '***-***-4567'
 * maskPhone('123'); // Returns: '***'
 */
export function maskPhone(phone: string | null | undefined): string {
  if (phone == null || phone === '') {
    return '***';
  }

  const str = String(phone).trim();

  // Remove common phone number formatting
  const digitsOnly = str.replace(/[\s()-]/g, '');

  if (digitsOnly.length < 4) {
    return '***';
  }

  // Show last 4 digits, mask the rest
  const last4 = digitsOnly.slice(-4);
  const maskedLength = digitsOnly.length - 4;
  const mask = '*'.repeat(Math.min(maskedLength, 6));

  // Try to preserve original formatting if present
  if (str.includes('-')) {
    return '***-***-' + last4;
  } else if (str.includes(' ')) {
    return '*** *** ' + last4;
  } else {
    return mask + last4;
  }
}

/**
 * Masks a credit card number for secure display, showing only last 4 digits.
 * Prevents exposure of full credit card numbers per PCI DSS requirements and Section 0.7.1.
 * 
 * @param {string | null | undefined} cardNumber - The credit card number to mask
 * @returns {string} Masked credit card number
 * 
 * @example
 * maskCreditCard('4532123456789012'); // Returns: '************9012'
 * maskCreditCard('4532-1234-5678-9012'); // Returns: '****-****-****-9012'
 * maskCreditCard('1234'); // Returns: '****1234'
 */
export function maskCreditCard(cardNumber: string | null | undefined): string {
  if (cardNumber == null || cardNumber === '') {
    return '****';
  }

  const str = String(cardNumber).trim();

  // Remove spaces and hyphens
  const digitsOnly = str.replace(/[\s-]/g, '');

  if (digitsOnly.length < 4) {
    return '****';
  }

  // Show last 4 digits, mask the rest
  const last4 = digitsOnly.slice(-4);

  // For exactly 4 digits, still show masking for security
  if (digitsOnly.length === 4) {
    return '****' + last4;
  }

  // Try to preserve formatting if present (every 4 digits)
  if (str.includes('-')) {
    return '****-****-****-' + last4;
  } else if (str.includes(' ')) {
    return '**** **** **** ' + last4;
  } else {
    const maskedLength = digitsOnly.length - 4;
    const mask = '*'.repeat(maskedLength);
    return mask + last4;
  }
}
