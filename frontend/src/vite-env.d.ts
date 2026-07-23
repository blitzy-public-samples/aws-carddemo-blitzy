/// <reference types="vite/client" />

/** Ambient Vite client type declarations that strongly type ``import.meta.env`` for the CardDemo React SPA. */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
