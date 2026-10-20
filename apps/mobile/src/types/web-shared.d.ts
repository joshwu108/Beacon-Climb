// apps/web modules imported as source (`@web/*`) reach its Vite client through type-only
// imports (e.g. explore/grade.ts -> useRoutes.ts -> api/client.ts). Nothing from that file
// runs in this app, but tsc still checks it, so the one Vite global it reads is declared.
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_USE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
