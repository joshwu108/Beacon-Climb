// Jest setup. Mock mode itself is switched on in jest.config.js (see there).

// expo-crypto's native module is absent under Jest; Node's crypto has the same function.
jest.mock('expo-crypto', () => ({
  randomUUID: () => require('node:crypto').randomUUID(),
}));
