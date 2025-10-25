/// <reference types="vite/client" />

/**
 * Type definitions for Vite-specific features and environment variables.
 * 
 * This file provides TypeScript IntelliSense for:
 * - import.meta.env (environment variables)
 * - import.meta.hot (Hot Module Replacement API)
 * - Asset imports (images, CSS, JSON, etc.)
 * 
 * Per Agent Action Plan Section 0.4.17: Standard TypeScript declaration file
 * required for Vite build tool integration.
 */

/**
 * Environment variables interface for CardDemo Frontend application.
 * All custom environment variables must be prefixed with VITE_ to be exposed to client code.
 * 
 * Usage example:
 *   const apiUrl = import.meta.env.VITE_API_URL;
 *   const appTitle = import.meta.env.VITE_APP_TITLE;
 * 
 * Configuration files:
 *   - .env (base environment variables)
 *   - .env.development (development-specific variables)
 *   - .env.production (production-specific variables)
 *   - .env.local (local overrides, not committed to Git)
 */
interface ImportMetaEnv {
  /**
   * Backend API base URL.
   * Example: http://localhost:8080/api or https://api.carddemo.com/api
   */
  readonly VITE_API_URL: string;
  
  /**
   * Application title displayed in browser tab and header.
   * Example: CardDemo - Credit Card Management System
   */
  readonly VITE_APP_TITLE: string;
  
  /**
   * API request timeout in milliseconds.
   * Default: 30000 (30 seconds)
   */
  readonly VITE_API_TIMEOUT?: string;
  
  /**
   * Enable debug logging in console.
   * Values: 'true' or 'false' (as string)
   */
  readonly VITE_DEBUG_MODE?: string;
  
  /**
   * JWT token storage key name.
   * Default: 'carddemo_auth_token'
   */
  readonly VITE_AUTH_TOKEN_KEY?: string;
  
  /**
   * Number of items per page for tables/lists.
   * Default: 10
   */
  readonly VITE_PAGE_SIZE?: string;
  
  // Built-in Vite environment variables (provided by Vite):
  // readonly MODE: string;           // 'development' or 'production'
  // readonly BASE_URL: string;       // Base URL for the app
  // readonly PROD: boolean;          // true in production build
  // readonly DEV: boolean;           // true in development mode
  // readonly SSR: boolean;           // true during server-side rendering
}

/**
 * Extends ImportMeta interface with Vite-specific properties.
 * Most types are provided by vite/client, but this ensures proper typing.
 */
interface ImportMeta {
  /**
   * Environment variables object with type-safe access.
   */
  readonly env: ImportMetaEnv;
  
  /**
   * Hot Module Replacement API (only available in development mode).
   * Used for hot reloading without full page refresh.
   * 
   * Usage example:
   *   if (import.meta.hot) {
   *     import.meta.hot.accept((newModule) => {
   *       // Handle module update
   *     });
   *   }
   */
  readonly hot?: {
    readonly data: any;
    
    accept(): void;
    accept(cb: (mod: any) => void): void;
    accept(dep: string, cb: (mod: any) => void): void;
    accept(deps: readonly string[], cb: (mods: any[]) => void): void;
    
    dispose(cb: (data: any) => void): void;
    decline(): void;
    invalidate(): void;
    
    on(event: string, cb: (...args: any[]) => void): void;
    off(event: string, cb: (...args: any[]) => void): void;
    send(event: string, data?: any): void;
  };
}

/**
 * Asset Import Type Declarations
 * (Most are provided by vite/client reference above)
 * 
 * Supported asset imports:
 * 
 * Images:
 *   import logo from './logo.png'  // string (URL)
 * 
 * CSS:
 *   import './style.css'  // void (side effect)
 * 
 * CSS Modules:
 *   import styles from './Component.module.css'  // CSSModuleClasses
 * 
 * JSON:
 *   import config from './config.json'  // typed object
 * 
 * Raw text:
 *   import text from './file.txt?raw'  // string
 * 
 * Web Workers:
 *   import Worker from './worker?worker'  // Worker constructor
 * 
 * WASM:
 *   import init from './module.wasm'  // WebAssembly.Module
 */
