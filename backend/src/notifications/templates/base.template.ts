/**
 * Base Email Template Utilities
 * 
 * Provides foundational HTML email template structure and utility functions for all email templates.
 * Implements responsive email design patterns, email client compatibility fixes, and consistent branding.
 * 
 * @module notifications/templates/base.template
 */

import { encode } from 'he';

/**
 * Parameters for wrapping email content in full HTML document structure
 */
export interface EmailWrapperParams {
  /** Email subject line (also used in title tag) */
  title: string;
  /** Short preview text shown in email clients */
  preheader: string;
  /** Main HTML content of the email body */
  content: string;
  /** URL for unsubscribe link in footer */
  unsubscribeUrl: string;
  /** Account/organization name for personalization */
  accountName: string;
}

/**
 * Parameters for generating styled button components
 */
export interface ButtonParams {
  /** Button text label */
  text: string;
  /** Target URL for button link */
  url: string;
  /** Visual style variant of the button */
  variant: 'primary' | 'secondary' | 'danger';
}

/**
 * Brand color palette for consistent email styling
 * Aligned with Section 0.7.11 UX directives for brand consistency
 */
export const EMAIL_COLORS = {
  primary: '#4F46E5',     // Indigo - primary brand color
  success: '#10B981',     // Green - success states
  warning: '#F59E0B',     // Amber - warning states
  danger: '#EF4444',      // Red - error/danger states
  text: '#1F2937',        // Dark gray - primary text
  textLight: '#6B7280',   // Medium gray - secondary text
  background: '#F9FAFB',  // Light gray - background
} as const;

/**
 * System font stack for maximum compatibility and readability across email clients
 */
export const EMAIL_FONTS = 'system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

/**
 * Sanitizes HTML content to prevent XSS attacks in email templates
 * 
 * Per Section 0.7.1: ALL user inputs MUST be validated and sanitized
 * Uses HTML entity encoding to neutralize potentially malicious HTML/JS
 * 
 * @param content - Raw text content that may contain user-generated data
 * @returns HTML-safe string with special characters encoded as entities
 * 
 * @example
 * ```typescript
 * const userInput = '<script>alert("xss")</script>';
 * const safe = sanitizeHtml(userInput);
 * // Returns: '&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;'
 * ```
 */
export function sanitizeHtml(content: string): string {
  if (!content || typeof content !== 'string') {
    return '';
  }
  // Encode special HTML characters to prevent XSS
  return encode(content, {
    useNamedReferences: true,
    decimal: false,
  });
}

/**
 * Formats a date object into human-readable string for email display
 * 
 * @param date - Date object to format
 * @param options - Intl.DateTimeFormatOptions for customization
 * @returns Formatted date string in localized format
 * 
 * @example
 * ```typescript
 * formatDate(new Date('2025-10-31'));
 * // Returns: "October 31, 2025"
 * ```
 */
export function formatDate(
  date: Date,
  options: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  }
): string {
  if (!(date instanceof Date) || isNaN(date.getTime())) {
    return 'Invalid Date';
  }
  return new Intl.DateTimeFormat('en-US', options).format(date);
}

/**
 * Formats a numeric value as currency with proper symbol and decimal places
 * 
 * @param amount - Numeric amount to format
 * @param currencyCode - ISO 4217 currency code (default: 'USD')
 * @returns Formatted currency string with symbol
 * 
 * @example
 * ```typescript
 * formatCurrency(1234.56, 'USD');
 * // Returns: "$1,234.56"
 * 
 * formatCurrency(999.99, 'EUR');
 * // Returns: "€999.99"
 * ```
 */
export function formatCurrency(amount: number, currencyCode: string = 'USD'): string {
  if (typeof amount !== 'number' || isNaN(amount)) {
    return '$0.00';
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: currencyCode,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

/**
 * Generates a styled button component with email client compatibility
 * 
 * Uses table-based layout with VML fallback for Outlook compatibility.
 * Implements proper padding, colors, and hover states with inline CSS.
 * 
 * @param params - Button configuration parameters
 * @returns HTML string for button component with inline styles
 * 
 * @example
 * ```typescript
 * generateButton({
 *   text: 'View Document',
 *   url: 'https://app.example.com/documents/123',
 *   variant: 'primary'
 * });
 * ```
 */
export function generateButton(params: ButtonParams): string {
  const { text, url, variant } = params;
  
  // Determine colors based on variant
  const colorMap = {
    primary: EMAIL_COLORS.primary,
    secondary: EMAIL_COLORS.textLight,
    danger: EMAIL_COLORS.danger,
  };
  
  const backgroundColor = colorMap[variant];
  const textColor = '#FFFFFF';
  
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: separate; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: auto;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; font-size: 16px; vertical-align: top; background-color: ${backgroundColor}; border-radius: 6px; text-align: center;" valign="top" align="center">
            <!--[if mso]>
            <v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="urn:schemas-microsoft-com:office:word" href="${sanitizeHtml(url)}" style="height:44px;v-text-anchor:middle;width:200px;" arcsize="14%" stroke="f" fillcolor="${backgroundColor}">
              <w:anchorlock/>
              <center style="color:${textColor};font-family:${EMAIL_FONTS};font-size:16px;font-weight:600;">${sanitizeHtml(text)}</center>
            </v:roundrect>
            <![endif]-->
            <!--[if !mso]><!-->
            <a href="${sanitizeHtml(url)}" target="_blank" style="display: inline-block; background-color: ${backgroundColor}; border-radius: 6px; box-sizing: border-box; color: ${textColor}; cursor: pointer; text-decoration: none; font-size: 16px; font-weight: 600; margin: 0; padding: 12px 24px; border: solid 1px ${backgroundColor}; min-width: 200px;">
              ${sanitizeHtml(text)}
            </a>
            <!--<![endif]-->
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Generates a horizontal divider line for visual separation
 * 
 * @param marginTop - Top margin in pixels (default: 24)
 * @param marginBottom - Bottom margin in pixels (default: 24)
 * @returns HTML string for horizontal rule with proper spacing
 */
export function generateDivider(marginTop: number = 24, marginBottom: number = 24): string {
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; margin-top: ${marginTop}px; margin-bottom: ${marginBottom}px;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; font-size: 14px; vertical-align: top; border-top: 1px solid #E5E7EB;" valign="top">
            &nbsp;
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Generates a styled info box for notices, warnings, errors, or success messages
 * 
 * @param variant - Visual style indicating message type
 * @param content - HTML content to display in the box
 * @returns HTML string for styled notification box
 * 
 * @example
 * ```typescript
 * generateInfoBox('warning', 'Low confidence detected on 3 fields');
 * generateInfoBox('success', 'Document processed successfully');
 * ```
 */
export function generateInfoBox(variant: 'info' | 'warning' | 'success' | 'error', content: string): string {
  const colorMap = {
    info: { bg: '#EFF6FF', border: '#3B82F6', text: '#1E40AF' },
    warning: { bg: '#FFF7ED', border: EMAIL_COLORS.warning, text: '#92400E' },
    success: { bg: '#ECFDF5', border: EMAIL_COLORS.success, text: '#065F46' },
    error: { bg: '#FEF2F2', border: EMAIL_COLORS.danger, text: '#991B1B' },
  };
  
  const colors = colorMap[variant];
  
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; margin-top: 16px; margin-bottom: 16px;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; font-size: 14px; vertical-align: top; background-color: ${colors.bg}; border-left: 4px solid ${colors.border}; padding: 16px; border-radius: 4px;" valign="top">
            <div style="color: ${colors.text}; line-height: 1.5;">
              ${content}
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Generates a metric card for displaying KPIs or statistics
 * 
 * @param label - Descriptive label for the metric
 * @param value - Metric value to display prominently
 * @returns HTML string for metric display card
 * 
 * @example
 * ```typescript
 * generateMetricCard('Documents Processed', '1,234');
 * generateMetricCard('Success Rate', '98.5%');
 * ```
 */
export function generateMetricCard(label: string, value: string): string {
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; background-color: ${EMAIL_COLORS.background}; border-radius: 8px; margin-bottom: 12px;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; vertical-align: top; padding: 20px; text-align: center;" valign="top" align="center">
            <div style="font-size: 14px; color: ${EMAIL_COLORS.textLight}; margin-bottom: 8px; font-weight: 500;">
              ${sanitizeHtml(label)}
            </div>
            <div style="font-size: 32px; color: ${EMAIL_COLORS.text}; font-weight: 700; line-height: 1.2;">
              ${sanitizeHtml(value)}
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Generates a data table with headers and rows
 * 
 * @param columns - Array of column header labels
 * @param rows - Array of row data (each row is array of cell values)
 * @returns HTML string for responsive data table
 * 
 * @example
 * ```typescript
 * generateTable(
 *   ['Document', 'Status', 'Date'],
 *   [
 *     ['Invoice-001.pdf', 'Completed', '2025-10-31'],
 *     ['Receipt-042.jpg', 'Processing', '2025-10-31']
 *   ]
 * );
 * ```
 */
export function generateTable(columns: string[], rows: string[][]): string {
  const headerCells = columns.map(col => 
    `<th style="font-family: ${EMAIL_FONTS}; font-size: 14px; font-weight: 600; color: ${EMAIL_COLORS.text}; text-align: left; padding: 12px 16px; background-color: ${EMAIL_COLORS.background}; border-bottom: 2px solid #E5E7EB;">${sanitizeHtml(col)}</th>`
  ).join('');
  
  const bodyRows = rows.map(row => {
    const cells = row.map(cell => 
      `<td style="font-family: ${EMAIL_FONTS}; font-size: 14px; color: ${EMAIL_COLORS.text}; text-align: left; padding: 12px 16px; border-bottom: 1px solid #E5E7EB;">${sanitizeHtml(cell)}</td>`
    ).join('');
    return `<tr>${cells}</tr>`;
  }).join('');
  
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; margin-top: 16px; margin-bottom: 16px; border: 1px solid #E5E7EB; border-radius: 8px; overflow: hidden;">
      <thead>
        <tr>${headerCells}</tr>
      </thead>
      <tbody>
        ${bodyRows}
      </tbody>
    </table>
  `;
}

/**
 * Generates email header with logo and branding
 * 
 * @param logoUrl - Optional custom logo URL (uses default if not provided)
 * @param navigationLinks - Optional array of {text, url} navigation items
 * @returns HTML string for email header section
 */
export function generateEmailHeader(
  logoUrl?: string,
  navigationLinks?: Array<{ text: string; url: string }>
): string {
  const defaultLogoUrl = 'https://app.ocrprocessing.com/logo.png'; // Placeholder URL
  const finalLogoUrl = logoUrl || defaultLogoUrl;
  
  const navHtml = navigationLinks ? navigationLinks.map(link => 
    `<a href="${sanitizeHtml(link.url)}" style="color: ${EMAIL_COLORS.textLight}; text-decoration: none; font-size: 14px; margin-left: 20px;">${sanitizeHtml(link.text)}</a>`
  ).join('') : '';
  
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; background-color: #FFFFFF; border-bottom: 1px solid #E5E7EB;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; vertical-align: middle; padding: 24px 32px;" valign="middle">
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%;">
              <tr>
                <td style="vertical-align: middle; width: 70%;" valign="middle">
                  <img src="${sanitizeHtml(finalLogoUrl)}" alt="OCR Processing Application" style="display: block; height: 32px; width: auto; border: 0; outline: none; text-decoration: none; -ms-interpolation-mode: bicubic;" />
                  <span style="font-size: 20px; font-weight: 700; color: ${EMAIL_COLORS.text}; margin-left: 12px; vertical-align: middle;">OCR Processing Application</span>
                </td>
                <td style="vertical-align: middle; width: 30%; text-align: right;" valign="middle" align="right">
                  ${navHtml}
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Generates email footer with company info, links, and unsubscribe option
 * 
 * @param unsubscribeUrl - URL for unsubscribe functionality
 * @param accountName - Account/organization name for personalization
 * @returns HTML string for email footer section
 */
export function generateEmailFooter(unsubscribeUrl: string, accountName: string): string {
  const currentYear = new Date().getFullYear();
  const supportEmail = 'support@ocrprocessing.com';
  const privacyUrl = 'https://ocrprocessing.com/privacy';
  const termsUrl = 'https://ocrprocessing.com/terms';
  
  // Social media links (placeholders)
  const socialLinks = [
    { name: 'Twitter', url: 'https://twitter.com/ocrprocessing', icon: '𝕏' },
    { name: 'LinkedIn', url: 'https://linkedin.com/company/ocrprocessing', icon: 'in' },
    { name: 'GitHub', url: 'https://github.com/ocrprocessing', icon: 'GH' },
  ];
  
  const socialHtml = socialLinks.map(social => 
    `<a href="${sanitizeHtml(social.url)}" style="color: ${EMAIL_COLORS.textLight}; text-decoration: none; margin: 0 8px;" title="${sanitizeHtml(social.name)}">${social.icon}</a>`
  ).join('');
  
  return `
    <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; background-color: ${EMAIL_COLORS.background}; border-top: 1px solid #E5E7EB;">
      <tbody>
        <tr>
          <td style="font-family: ${EMAIL_FONTS}; vertical-align: top; padding: 32px;" valign="top">
            <!-- Social Links -->
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%; margin-bottom: 20px;">
              <tr>
                <td style="text-align: center; font-size: 18px;" align="center">
                  ${socialHtml}
                </td>
              </tr>
            </table>
            
            <!-- Company Info -->
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%; margin-bottom: 16px;">
              <tr>
                <td style="text-align: center; font-size: 14px; color: ${EMAIL_COLORS.textLight}; line-height: 1.6;" align="center">
                  <strong>OCR Processing Application</strong><br />
                  Automated Document Digitization & Data Extraction<br />
                  Sent to ${sanitizeHtml(accountName)}
                </td>
              </tr>
            </table>
            
            <!-- Support Email -->
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%; margin-bottom: 16px;">
              <tr>
                <td style="text-align: center; font-size: 14px; color: ${EMAIL_COLORS.textLight};" align="center">
                  Questions? Contact us at <a href="mailto:${supportEmail}" style="color: ${EMAIL_COLORS.primary}; text-decoration: none;">${supportEmail}</a>
                </td>
              </tr>
            </table>
            
            <!-- Links -->
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%; margin-bottom: 16px;">
              <tr>
                <td style="text-align: center; font-size: 12px; color: ${EMAIL_COLORS.textLight};" align="center">
                  <a href="${sanitizeHtml(privacyUrl)}" style="color: ${EMAIL_COLORS.textLight}; text-decoration: underline; margin: 0 8px;">Privacy Policy</a>
                  <span style="color: #D1D5DB;">|</span>
                  <a href="${sanitizeHtml(termsUrl)}" style="color: ${EMAIL_COLORS.textLight}; text-decoration: underline; margin: 0 8px;">Terms of Service</a>
                  <span style="color: #D1D5DB;">|</span>
                  <a href="${sanitizeHtml(unsubscribeUrl)}" style="color: ${EMAIL_COLORS.textLight}; text-decoration: underline; margin: 0 8px;">Unsubscribe</a>
                </td>
              </tr>
            </table>
            
            <!-- Copyright -->
            <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; width: 100%;">
              <tr>
                <td style="text-align: center; font-size: 12px; color: ${EMAIL_COLORS.textLight};" align="center">
                  &copy; ${currentYear} OCR Processing Application. All rights reserved.
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </tbody>
    </table>
  `;
}

/**
 * Wraps email content in full HTML document structure with proper DOCTYPE and meta tags
 * 
 * Implements comprehensive email client compatibility including:
 * - Proper DOCTYPE for HTML5
 * - Meta tags for viewport, character encoding, format detection
 * - CSS reset for email clients (Outlook, Gmail, Apple Mail)
 * - Responsive design with mobile breakpoints
 * - Outlook conditional comments
 * - Table-based layout for maximum compatibility
 * 
 * Per Section 0.7.2 and email best practices, uses inline CSS and absolute URLs.
 * 
 * @param params - Email wrapper parameters
 * @returns Complete HTML email document ready to send
 * 
 * @example
 * ```typescript
 * const emailHtml = wrapEmailTemplate({
 *   title: 'Document Processing Complete',
 *   preheader: 'Your invoice has been successfully processed',
 *   content: generateDocumentContent(),
 *   unsubscribeUrl: 'https://app.example.com/unsubscribe',
 *   accountName: 'Acme Corp'
 * });
 * ```
 */
export function wrapEmailTemplate(params: EmailWrapperParams): string {
  const { title, preheader, content, unsubscribeUrl, accountName } = params;
  
  return `<!DOCTYPE html>
<html lang="en" xmlns="http://www.w3.org/1999/xhtml" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <meta name="x-apple-disable-message-reformatting">
  <meta name="format-detection" content="telephone=no,address=no,email=no,date=no">
  <meta name="color-scheme" content="light">
  <meta name="supported-color-schemes" content="light">
  <title>${sanitizeHtml(title)}</title>
  
  <!--[if mso]>
  <noscript>
    <xml>
      <o:OfficeDocumentSettings>
        <o:AllowPNG/>
        <o:PixelsPerInch>96</o:PixelsPerInch>
      </o:OfficeDocumentSettings>
    </xml>
  </noscript>
  <![endif]-->
  
  <style type="text/css">
    /* CSS Reset for Email Clients */
    body, table, td, a { -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
    table, td { mso-table-lspace: 0pt; mso-table-rspace: 0pt; }
    img { -ms-interpolation-mode: bicubic; border: 0; height: auto; line-height: 100%; outline: none; text-decoration: none; }
    body { height: 100% !important; margin: 0 !important; padding: 0 !important; width: 100% !important; }
    
    /* Prevent Gmail and iOS font size adjustments */
    * { -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale; }
    
    /* Remove spacing around tables in Outlook 2007 and up */
    table { border-collapse: collapse !important; }
    
    /* Yahoo Mail link color fix */
    .yshortcuts a { border-bottom: none !important; }
    
    /* iOS blue links */
    a[x-apple-data-detectors] {
      color: inherit !important;
      text-decoration: none !important;
      font-size: inherit !important;
      font-family: inherit !important;
      font-weight: inherit !important;
      line-height: inherit !important;
    }
    
    /* Gmail blue links */
    u + #body a {
      color: inherit;
      text-decoration: none;
      font-size: inherit;
      font-family: inherit;
      font-weight: inherit;
      line-height: inherit;
    }
    
    /* Responsive Media Queries */
    @media only screen and (max-width: 640px) {
      .email-container {
        width: 100% !important;
        margin: auto !important;
      }
      
      .mobile-padding {
        padding: 16px !important;
      }
      
      .mobile-hide {
        display: none !important;
      }
      
      .mobile-center {
        text-align: center !important;
      }
      
      .mobile-full-width {
        width: 100% !important;
        max-width: 100% !important;
      }
      
      /* Stack columns on mobile */
      .stack-column {
        display: block !important;
        width: 100% !important;
        max-width: 100% !important;
        direction: ltr !important;
      }
      
      /* Adjust font sizes for mobile */
      .mobile-text-small {
        font-size: 12px !important;
        line-height: 1.4 !important;
      }
      
      .mobile-button {
        width: auto !important;
        min-width: 160px !important;
      }
    }
    
    /* Dark mode support */
    @media (prefers-color-scheme: dark) {
      .dark-mode-bg {
        background-color: #1F2937 !important;
      }
      
      .dark-mode-text {
        color: #F9FAFB !important;
      }
    }
  </style>
</head>
<body style="background-color: #F3F4F6; margin: 0; padding: 0; width: 100%; font-family: ${EMAIL_FONTS}; -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale;">
  
  <!-- Preheader Text (hidden but used by email clients) -->
  <div style="display: none; max-height: 0; overflow: hidden; mso-hide: all;">
    ${sanitizeHtml(preheader)}
  </div>
  
  <!-- Preheader Spacer (prevents email clients from showing other content) -->
  <div style="display: none; max-height: 0; overflow: hidden; mso-hide: all;">
    &nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;&zwnj;&nbsp;
  </div>
  
  <!-- Email Container -->
  <table border="0" cellpadding="0" cellspacing="0" role="presentation" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 100%; background-color: #F3F4F6;">
    <tbody>
      <tr>
        <td style="padding: 20px 0;" align="center" valign="top">
          
          <!-- Main Content Table -->
          <table border="0" cellpadding="0" cellspacing="0" role="presentation" class="email-container" style="border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt; width: 600px; max-width: 600px; background-color: #FFFFFF; box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.06); border-radius: 8px; overflow: hidden;">
            <tbody>
              
              <!-- Header -->
              <tr>
                <td style="padding: 0;">
                  ${generateEmailHeader()}
                </td>
              </tr>
              
              <!-- Main Content -->
              <tr>
                <td style="padding: 32px 40px; font-family: ${EMAIL_FONTS}; font-size: 16px; line-height: 1.6; color: ${EMAIL_COLORS.text};" class="mobile-padding">
                  ${content}
                </td>
              </tr>
              
              <!-- Footer -->
              <tr>
                <td style="padding: 0;">
                  ${generateEmailFooter(unsubscribeUrl, accountName)}
                </td>
              </tr>
              
            </tbody>
          </table>
          
        </td>
      </tr>
    </tbody>
  </table>
  
</body>
</html>`;
}
