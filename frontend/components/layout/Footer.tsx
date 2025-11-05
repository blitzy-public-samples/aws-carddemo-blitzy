/**
 * Footer Component
 * 
 * Application footer component providing consistent bottom section across all pages.
 * Displays copyright information with current year, application version number,
 * support/help center links, terms of service and privacy policy links, and company branding.
 * 
 * Features:
 * - Semantic HTML footer element with ARIA labels for accessibility
 * - Responsive TailwindCSS grid layout (single column on mobile, multi-column on desktop)
 * - Internal navigation links using Next.js Link for client-side routing
 * - External links with security attributes (target='_blank', rel='noopener noreferrer')
 * - Dynamic copyright year and application version
 * - Memoized for performance optimization
 * - WCAG 2.1 AA compliant with proper focus states and keyboard navigation
 * 
 * @component
 * @example
 * ```tsx
 * import Footer from '@/components/layout/Footer';
 * 
 * function Layout() {
 *   return (
 *     <div>
 *       <main>{children}</main>
 *       <Footer />
 *     </div>
 *   );
 * }
 * ```
 */

import React from 'react';
import Link from 'next/link';

/**
 * Props interface for the Footer component
 * 
 * Currently empty but extensible for future enhancements such as:
 * - showSocialLinks: boolean - Toggle social media links visibility
 * - customLinks: FooterLink[] - Additional custom footer links
 * - theme: 'light' | 'dark' - Footer color theme
 * 
 * @interface FooterProps
 */
export interface FooterProps {
  // Intentionally empty for future extensibility
}

/**
 * Footer component providing application-wide footer with navigation, support, and legal links
 * 
 * Implements a responsive four-column layout on desktop that collapses to single column on mobile:
 * 1. Company/Product branding with logo and tagline
 * 2. Quick navigation links to main application sections
 * 3. Support resources and help center links
 * 4. Legal links (Terms of Service, Privacy Policy)
 * 
 * Bottom section includes copyright notice with dynamic year and application version.
 * All links implement proper focus states and hover effects for accessibility and UX.
 * 
 * @param {FooterProps} props - Component props (currently unused, reserved for future features)
 * @returns {React.ReactElement} Rendered footer component
 */
const Footer: React.FC<FooterProps> = () => {
  // Get current year for copyright notice
  const currentYear = new Date().getFullYear();
  
  // Get application version from environment variable or use fallback
  const appVersion = process.env.NEXT_PUBLIC_APP_VERSION || 'v1.0.0';

  return (
    <footer 
      className="border-t border-gray-200 bg-white"
      aria-label="Site footer"
    >
      <div className="mx-auto max-w-7xl px-4 py-8 md:px-8 md:py-12 lg:px-16">
        {/* Main Footer Content Grid */}
        <div className="grid grid-cols-1 gap-8 md:grid-cols-2 lg:grid-cols-4">
          
          {/* Column 1: Company/Product Branding */}
          <div className="space-y-4">
            <div className="flex items-center space-x-2">
              <div className="flex h-8 w-8 items-center justify-center rounded bg-blue-600 text-white font-bold text-sm">
                OCR
              </div>
              <span className="text-lg font-semibold text-gray-900">
                OCR Processing
              </span>
            </div>
            <p className="text-sm text-gray-600 leading-relaxed">
              Automate document digitization with intelligent OCR and data extraction.
              Transform physical documents into searchable, editable digital formats.
            </p>
          </div>

          {/* Column 2: Quick Links */}
          <nav aria-label="Quick navigation links">
            <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-gray-900">
              Quick Links
            </h3>
            <ul className="space-y-3">
              <li>
                <Link
                  href="/dashboard"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Dashboard
                </Link>
              </li>
              <li>
                <Link
                  href="/documents"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Documents
                </Link>
              </li>
              <li>
                <Link
                  href="/templates"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Templates
                </Link>
              </li>
              <li>
                <Link
                  href="/analytics"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Analytics
                </Link>
              </li>
            </ul>
          </nav>

          {/* Column 3: Support */}
          <nav aria-label="Support resources">
            <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-gray-900">
              Support
            </h3>
            <ul className="space-y-3">
              <li>
                <a
                  href="https://help.ocrprocessing.app"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Help Center
                </a>
              </li>
              <li>
                <a
                  href="https://docs.ocrprocessing.app/api"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  API Documentation
                </a>
              </li>
              <li>
                <a
                  href="mailto:support@ocrprocessing.app"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Contact Support
                </a>
              </li>
              <li>
                <a
                  href="https://status.ocrprocessing.app"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  System Status
                </a>
              </li>
            </ul>
          </nav>

          {/* Column 4: Legal */}
          <nav aria-label="Legal information">
            <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-gray-900">
              Legal
            </h3>
            <ul className="space-y-3">
              <li>
                <Link
                  href="/terms-of-service"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Terms of Service
                </Link>
              </li>
              <li>
                <Link
                  href="/privacy-policy"
                  className="text-sm text-gray-600 underline-offset-4 transition-colors hover:text-blue-600 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2"
                >
                  Privacy Policy
                </Link>
              </li>
            </ul>
          </nav>
        </div>

        {/* Bottom Section: Copyright and Version */}
        <div className="mt-8 border-t border-gray-200 pt-8">
          <p className="text-center text-sm text-gray-500">
            <span>&copy; {currentYear} OCR Processing Application. All rights reserved.</span>
            <span className="mx-2" aria-hidden="true">•</span>
            <span>Version {appVersion}</span>
          </p>
        </div>
      </div>
    </footer>
  );
};

// Memoize component to prevent unnecessary re-renders when parent re-renders
// This is a performance optimization per Section 0.7.2 React best practices
export default React.memo(Footer);
