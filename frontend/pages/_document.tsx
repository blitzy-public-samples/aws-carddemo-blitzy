import Document, { Html, Head, Main, NextScript, DocumentContext, DocumentInitialProps } from 'next/document';

/**
 * Custom Next.js Document Component
 * 
 * This component customizes the HTML document structure for all pages in the application.
 * It runs on the server-side only and is used to augment the application's <html> and <body> tags.
 * 
 * Key Features:
 * - Sets HTML lang attribute to 'en' for accessibility compliance (WCAG 2.1 AA - Section 0.7.10)
 * - Preloads Inter font for optimal performance (Section 0.7.1: <2s load target)
 * - Configures responsive viewport for mobile/tablet support
 * - Sets theme color for PWA compatibility
 * - Provides consistent document structure across all pages
 * 
 * Performance Optimizations:
 * - Font preloading with preconnect and crossorigin for faster font delivery
 * - Minimal blocking resources in head
 * - Optimized meta tags for quick initial render
 * 
 * @see https://nextjs.org/docs/advanced-features/custom-document
 */
export default class CustomDocument extends Document {
  /**
   * Custom getInitialProps for server-side rendering setup
   * 
   * This method runs on the server and allows customization of the initial props
   * for the Document component. It's required when extending the Document class
   * to ensure proper server-side rendering.
   * 
   * @param ctx - Document context containing request information and rendering options
   * @returns Document initial props including HTML markup and head elements
   */
  static async getInitialProps(ctx: DocumentContext): Promise<DocumentInitialProps> {
    const initialProps = await Document.getInitialProps(ctx);
    return initialProps;
  }

  /**
   * Renders the custom HTML document structure
   * 
   * This method defines the complete HTML document shell that wraps all pages.
   * It includes:
   * - HTML element with lang attribute for accessibility
   * - Head with meta tags, font preloading, and theme configuration
   * - Body with consistent styling and structure
   * - Main component where page content is rendered
   * - NextScript for Next.js runtime and hydration
   * 
   * @returns React element representing the complete HTML document
   */
  render() {
    return (
      <Html lang="en">
        <Head>
          {/* Character encoding for proper text rendering */}
          <meta charSet="UTF-8" />
          
          {/* Responsive viewport configuration for mobile and tablet devices */}
          {/* Ensures proper scaling across different screen sizes per Section 0.7.11 */}
          <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
          
          {/* Theme color for browser chrome and PWA support */}
          {/* Uses primary brand color for consistent appearance */}
          <meta name="theme-color" content="#3b82f6" />
          
          {/* Preconnect to Google Fonts for faster font loading */}
          {/* Performance optimization to reduce font loading time (Section 0.7.1) */}
          <link rel="preconnect" href="https://fonts.googleapis.com" />
          <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
          
          {/* Preload Inter font for optimal performance */}
          {/* Inter font provides excellent readability and professional appearance */}
          {/* Weights: 400 (regular), 500 (medium), 600 (semibold), 700 (bold) */}
          <link
            href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap"
            rel="stylesheet"
          />
          
          {/* Favicon configuration */}
          <link rel="icon" href="/favicon.ico" />
          <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png" />
          <link rel="icon" type="image/png" sizes="16x16" href="/favicon-16x16.png" />
          <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
          
          {/* Manifest for PWA support */}
          <link rel="manifest" href="/manifest.json" />
          
          {/* Default meta tags (can be overridden by individual pages) */}
          <meta name="description" content="OCR Processing Application - Automate document digitization with intelligent data extraction" />
          <meta name="keywords" content="OCR, document processing, data extraction, digitization" />
          
          {/* Open Graph meta tags for social sharing */}
          <meta property="og:type" content="website" />
          <meta property="og:title" content="OCR Processing Application" />
          <meta property="og:description" content="Automate document digitization with intelligent data extraction" />
          
          {/* Security headers via meta tags */}
          <meta httpEquiv="X-UA-Compatible" content="IE=edge" />
        </Head>
        
        {/* Body element with consistent styling classes */}
        {/* antialiased: Smoother font rendering */}
        {/* bg-gray-50: Light background color for the application */}
        {/* text-gray-900: Default text color with good contrast (accessibility) */}
        <body className="antialiased bg-gray-50 text-gray-900">
          {/* Main component - renders the actual page content */}
          {/* This is where Next.js injects the page components */}
          <Main />
          
          {/* NextScript - injects Next.js runtime scripts */}
          {/* Required for client-side hydration, routing, and React functionality */}
          <NextScript />
        </body>
      </Html>
    );
  }
}
