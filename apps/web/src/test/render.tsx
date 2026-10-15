import { render, type RenderResult } from '@testing-library/react';
import { createMemoryRouter } from 'react-router';
import { App } from '../app/App';
import { createQueryClient } from '../app/query-client';
import { routes } from '../app/routes';

/** Render the whole app (providers, shell, routes) at a path with a memory router. */
export const renderApp = (path: string): RenderResult => {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <App router={router} queryClient={createQueryClient({ retry: false })} />,
  );
};
