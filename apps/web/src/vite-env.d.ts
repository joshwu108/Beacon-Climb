/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the Beacon Climb API. Defaults to http://127.0.0.1:8080. */
  readonly VITE_API_BASE_URL?: string;
  /** "true" starts the MSW worker so the app runs against contracts/examples fixtures. */
  readonly VITE_USE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
