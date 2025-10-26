/**
 * TypeScript Module Declarations for Static Assets
 * 
 * This file provides TypeScript type definitions for importing static assets
 * (images, icons, fonts, etc.) in React components. Vite processes these imports
 * and returns URL strings pointing to the optimized asset files.
 * 
 * Usage Example:
 *   import logo from './images/logo.png';
 *   import icon from './icons/menu-icon.svg';
 *   
 *   <img src={logo} alt="Logo" />
 *   <img src={icon} alt="Menu" />
 * 
 * Asset Naming Convention: kebab-case (e.g., card-logo.png, user-icon.svg)
 * 
 * Vite Asset Handling:
 * - Assets are processed through Vite's build pipeline
 * - Import returns a URL string (either hashed filename or base64 data URL)
 * - Small assets (< 4KB) are inlined as base64
 * - Large assets get content-hashed filenames for cache busting
 */

// PNG Images
declare module '*.png' {
  const value: string;
  export default value;
}

// JPEG Images (both .jpg and .jpeg extensions)
declare module '*.jpg' {
  const value: string;
  export default value;
}

declare module '*.jpeg' {
  const value: string;
  export default value;
}

// GIF Images
declare module '*.gif' {
  const value: string;
  export default value;
}

// SVG Images
// Default import: URL string
declare module '*.svg' {
  const value: string;
  export default value;
}

// SVG as React Components (using vite-plugin-svgr with ?react suffix)
// Usage: import Icon from './icon.svg?react'
declare module '*.svg?react' {
  import React from 'react';
  const SVGComponent: React.FC<React.SVGProps<SVGSVGElement>>;
  export default SVGComponent;
}

// WebP Images (modern format with better compression)
declare module '*.webp' {
  const value: string;
  export default value;
}

// AVIF Images (modern format with even better compression)
declare module '*.avif' {
  const value: string;
  export default value;
}

// ICO Files (favicons)
declare module '*.ico' {
  const value: string;
  export default value;
}

// BMP Images
declare module '*.bmp' {
  const value: string;
  export default value;
}

// TIFF Images
declare module '*.tiff' {
  const value: string;
  export default value;
}

declare module '*.tif' {
  const value: string;
  export default value;
}

// Font Files
declare module '*.woff' {
  const value: string;
  export default value;
}

declare module '*.woff2' {
  const value: string;
  export default value;
}

declare module '*.eot' {
  const value: string;
  export default value;
}

declare module '*.ttf' {
  const value: string;
  export default value;
}

declare module '*.otf' {
  const value: string;
  export default value;
}

// Video Files
declare module '*.mp4' {
  const value: string;
  export default value;
}

declare module '*.webm' {
  const value: string;
  export default value;
}

declare module '*.ogg' {
  const value: string;
  export default value;
}

// Audio Files
declare module '*.mp3' {
  const value: string;
  export default value;
}

declare module '*.wav' {
  const value: string;
  export default value;
}

declare module '*.m4a' {
  const value: string;
  export default value;
}

// PDF Documents
declare module '*.pdf' {
  const value: string;
  export default value;
}

// Text Files
declare module '*.txt' {
  const value: string;
  export default value;
}
