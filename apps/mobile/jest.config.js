/**
 * Jest (jest-expo, iOS preset) for hooks, data transforms and key screens. apps/web modules
 * shared as source (`@web/*`: errors, board geometry, reason codes, MSW handlers) are
 * transformed like local files. msw's dependencies ship ESM-only `.mjs`, so `.mjs` goes
 * through the preset's own babel-jest transform as well.
 */
const preset = require('jest-expo/ios/jest-preset');

const babelTransform = preset.transform['\\.[jt]sx?$'];

module.exports = {
  preset: 'jest-expo/ios',
  rootDir: __dirname,
  roots: ['<rootDir>/src'],
  setupFilesAfterEnv: ['<rootDir>/src/test/setup.ts'],
  moduleFileExtensions: [...preset.moduleFileExtensions, 'mjs'],
  transform: { ...preset.transform, '\\.mjs$': babelTransform },
  moduleNameMapper: {
    '^@web/(.*)$': '<rootDir>/../web/src/$1',
  },
  transformIgnorePatterns: [
    'node_modules/(?!(\\.pnpm|((jest-)?react-native|@react-native(-community)?)|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|expo-router|react-native-svg|msw|@mswjs|rettime|until-async|@open-draft|outvariant|strict-event-emitter|headers-polyfill|is-node-process|graphql|cookie|tough-cookie|tldts|tldts-core|path-to-regexp|statuses|type-fest|openapi-fetch))',
  ],
  testPathIgnorePatterns: ['/node_modules/', '/e2e/'],
};
