import { renderToStaticMarkup } from 'react-dom/server';
import { expect, test } from 'vitest';
import { App } from './App';

test('root route renders the Beacon Climb heading', () => {
  expect(renderToStaticMarkup(<App />)).toContain('<h1>Beacon Climb</h1>');
});
