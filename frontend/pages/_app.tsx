/**
 * Root Application Component for OCR Processing Application
 *
 * This is the entry point for all pages in the Next.js application.
 * It wraps all page components and provides global functionality including:
 * - Global styles and TailwindCSS
 * - Theme providers
 * - Authentication context
 * - React Query provider for data fetching
 *
 * @see https://nextjs.org/docs/advanced-features/custom-app
 */

import type { AppProps } from 'next/app';
import '../styles/globals.css';

export default function App({ Component, pageProps }: AppProps): JSX.Element {
  return <Component {...pageProps} />;
}
