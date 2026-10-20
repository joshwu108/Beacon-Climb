// Jest setup: mock mode is the test transport (in-process MSW handlers), and every test
// starts from fresh mock state and an empty session.
process.env.EXPO_PUBLIC_USE_MOCKS = 'true';
