import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider, type DataRouter } from 'react-router';

export interface AppProps {
  readonly router: DataRouter;
  readonly queryClient: QueryClient;
}

/** Provider stack. main.tsx supplies a browser router; tests supply a memory router. */
export const App = ({ router, queryClient }: AppProps) => (
  <QueryClientProvider client={queryClient}>
    <RouterProvider router={router} />
  </QueryClientProvider>
);
