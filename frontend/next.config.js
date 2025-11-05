/**
 * Next.js Configuration for OCR Processing Application
 * 
 * This configuration file defines build settings, performance optimizations,
 * security headers, image optimization, and environment variable handling
 * for the React frontend application.
 * 
 * Requirements:
 * - Next.js 14.2.21 with SSR support
 * - Image optimization for <2 second load time (Section 0.7.1)
 * - Security headers for TLS 1.3 and application security (Section 0.7.1)
 * - Compression and minification for optimal performance
 * 
 * @see https://nextjs.org/docs/app/api-reference/next-config-js
 */

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Enable React strict mode for better development experience and error detection
  reactStrictMode: true,

  // Use SWC minification for faster builds (replaces Terser)
  swcMinify: true,

  // Disable X-Powered-By header for security (don't expose Next.js)
  poweredByHeader: false,

  // Enable gzip/brotli compression for responses
  compress: true,

  // Configure ETag generation for caching
  generateEtags: true,

  // Image optimization configuration
  images: {
    // Allowed external image domains (S3 buckets for document storage)
    domains: [
      'localhost',
      's3.amazonaws.com',
      // Add your S3 bucket domains here when deployed
      // Example: 'ocr-documents.s3.us-east-1.amazonaws.com',
      // Example: 'ocr-processed.s3.us-east-1.amazonaws.com',
    ],
    
    // Modern image formats for optimal performance (AVIF is smaller than WebP)
    formats: ['image/avif', 'image/webp'],
    
    // Device sizes for responsive images (breakpoints in pixels)
    deviceSizes: [640, 750, 828, 1080, 1200, 1920, 2048, 3840],
    
    // Image sizes for different layouts (in pixels)
    imageSizes: [16, 32, 48, 64, 96, 128, 256, 384],
    
    // Minimum cache TTL for optimized images (in seconds)
    minimumCacheTTL: 60,
    
    // Disable static imports for images (use next/image component)
    disableStaticImages: false,
    
    // Allow SVG images (with caution - validate sources)
    dangerouslyAllowSVG: true,
    contentSecurityPolicy: "default-src 'self'; script-src 'none'; sandbox;",
  },

  // Environment variables exposed to the browser (must start with NEXT_PUBLIC_)
  env: {
    // These will be replaced at build time and available in the browser
    // Actual values come from .env.local or deployment environment
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL,
    NEXT_PUBLIC_WS_URL: process.env.NEXT_PUBLIC_WS_URL,
    NEXT_PUBLIC_GOOGLE_CLIENT_ID: process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID,
    NEXT_PUBLIC_MICROSOFT_CLIENT_ID: process.env.NEXT_PUBLIC_MICROSOFT_CLIENT_ID,
    NEXT_PUBLIC_SENTRY_DSN: process.env.NEXT_PUBLIC_SENTRY_DSN,
    NEXT_PUBLIC_ENV: process.env.NEXT_PUBLIC_ENV || 'development',
  },

  // Security headers configuration (Section 0.7.1 requirements)
  async headers() {
    return [
      {
        // Apply security headers to all routes
        source: '/:path*',
        headers: [
          {
            // Prevent clickjacking attacks - deny embedding in iframes
            key: 'X-Frame-Options',
            value: 'DENY',
          },
          {
            // Prevent MIME type sniffing
            key: 'X-Content-Type-Options',
            value: 'nosniff',
          },
          {
            // Enable XSS protection in older browsers
            key: 'X-XSS-Protection',
            value: '1; mode=block',
          },
          {
            // Control referrer information sent with requests
            key: 'Referrer-Policy',
            value: 'origin-when-cross-origin',
          },
          {
            // Permissions policy for browser features
            key: 'Permissions-Policy',
            value: 'camera=(), microphone=(), geolocation=(), interest-cohort=()',
          },
          {
            // Strict Transport Security - enforce HTTPS (production only)
            key: 'Strict-Transport-Security',
            value: 'max-age=63072000; includeSubDomains; preload',
          },
          {
            // Content Security Policy - comprehensive security policy
            key: 'Content-Security-Policy',
            value: [
              "default-src 'self'",
              "script-src 'self' 'unsafe-eval' 'unsafe-inline'", // unsafe-inline needed for Next.js
              "style-src 'self' 'unsafe-inline'", // unsafe-inline needed for styled-components/emotion
              "img-src 'self' data: https: blob:", // Allow images from S3 and data URLs
              "font-src 'self' data:",
              "connect-src 'self' https: wss:", // Allow API calls and WebSocket
              "frame-ancestors 'none'", // Same as X-Frame-Options
              "base-uri 'self'",
              "form-action 'self'",
            ].join('; '),
          },
        ],
      },
    ];
  },

  // Webpack customization for advanced build configuration
  webpack: (config, { buildId, dev, isServer, defaultLoaders, webpack }) => {
    // Add custom webpack rules or plugins here if needed
    
    // Example: Optimize bundle size by using specific imports
    config.resolve.alias = {
      ...config.resolve.alias,
      // Add custom path aliases if needed
      // '@components': path.join(__dirname, 'components'),
      // '@lib': path.join(__dirname, 'lib'),
    };

    // Optimize PDF.js for document viewing
    config.resolve.alias['pdfjs-dist'] = 'pdfjs-dist/legacy/build/pdf';

    // Add source maps in production for error tracking (Sentry)
    if (!dev && !isServer) {
      config.devtool = 'source-map';
    }

    // Ignore unnecessary files from bundles
    config.plugins.push(
      new webpack.IgnorePlugin({
        resourceRegExp: /^\.\/locale$/,
        contextRegExp: /moment$/,
      })
    );

    return config;
  },

  // Experimental features (Next.js 14+)
  experimental: {
    // Enable optimistic client cache for faster navigation
    optimisticClientCache: true,
    
    // Use SWC for CSS minimization
    swcMinify: true,
    
    // Server actions for form submissions (if using app directory)
    serverActions: true,
    
    // Optimize package imports to reduce bundle size
    optimizePackageImports: [
      'lucide-react',
      '@headlessui/react',
      'date-fns',
    ],
  },

  // TypeScript configuration
  typescript: {
    // Fail build on TypeScript errors (production safety)
    ignoreBuildErrors: false,
  },

  // ESLint configuration
  eslint: {
    // Fail build on ESLint errors (code quality enforcement)
    ignoreDuringBuilds: false,
  },

  // Output configuration
  output: process.env.BUILD_STANDALONE === 'true' ? 'standalone' : undefined,

  // Internationalization (i18n) - placeholder for future multi-language support
  // i18n: {
  //   locales: ['en'],
  //   defaultLocale: 'en',
  // },

  // Redirects configuration (if needed)
  async redirects() {
    return [
      // Example: Redirect old routes to new routes
      // {
      //   source: '/old-path',
      //   destination: '/new-path',
      //   permanent: true,
      // },
    ];
  },

  // Rewrites configuration (for API proxy if needed)
  async rewrites() {
    return [
      // Example: Proxy API requests to backend server
      // {
      //   source: '/api/:path*',
      //   destination: 'http://localhost:3001/api/:path*',
      // },
    ];
  },

  // Production optimizations
  productionBrowserSourceMaps: true, // Enable source maps for Sentry error tracking
  
  // Compiler options
  compiler: {
    // Remove console logs in production
    removeConsole: process.env.NODE_ENV === 'production' ? {
      exclude: ['error', 'warn'],
    } : false,
    
    // Enable styled-components if used
    // styledComponents: true,
  },

  // Static page generation timeout (for large documents)
  staticPageGenerationTimeout: 90,
};

module.exports = nextConfig;
