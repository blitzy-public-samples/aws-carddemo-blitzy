/// <reference types="vite/client" />

/** Ambient Vite client type declarations that strongly type ``import.meta.env`` for the CardDemo React SPA. */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  /** Application id for the sign-on screen's ``AppID:`` field (legacy CICS APPLID). */
  readonly VITE_APP_ID?: string;
  /** System id for the sign-on screen's ``SysID:`` field (legacy CICS SYSID). */
  readonly VITE_SYS_ID?: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
