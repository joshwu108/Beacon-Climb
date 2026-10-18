import { builtinEnvironments, type Environment } from 'vitest/runtime';

/**
 * jsdom environment that keeps Node's AbortController/AbortSignal.
 *
 * Vitest's jsdom environment replaces both globals with jsdom's versions. React Router
 * builds a `Request` (Node/undici) with a `signal` from `new AbortController()` whenever
 * it runs a lazy route, and undici rejects a signal that is not a Node AbortSignal
 * ("Expected signal to be an instance of AbortSignal"). Restoring Node's classes after
 * jsdom is set up keeps DOM APIs intact and makes fetch, MSW and the router agree.
 */
const environment: Environment = {
  name: 'jsdom-node-abort',
  viteEnvironment: 'client',
  async setup(global, options) {
    const nodeAbortController = global.AbortController;
    const nodeAbortSignal = global.AbortSignal;
    const inner = await builtinEnvironments.jsdom.setup(global, options);
    global.AbortController = nodeAbortController;
    global.AbortSignal = nodeAbortSignal;
    return inner;
  },
};

export default environment;
