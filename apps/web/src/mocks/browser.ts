import { setupWorker } from 'msw/browser';
import { handlers } from './handlers';

export const worker = setupWorker(...handlers);

/** Start the worker; unhandled requests (assets, Vite HMR) pass through untouched. */
export const startMockWorker = (): Promise<unknown> =>
  worker.start({ onUnhandledRequest: 'bypass', quiet: false });
