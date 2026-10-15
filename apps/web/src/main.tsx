import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { App } from './app/App';
import { createQueryClient } from './app/query-client';
import { routes } from './app/routes';
import './styles/tokens.css';
import './styles/global.css';

export const useMocks = import.meta.env.VITE_USE_MOCKS === 'true';

const bootstrap = async (): Promise<void> => {
  if (useMocks) {
    const { startMockWorker } = await import('./mocks/browser');
    await startMockWorker();
  }
  const router = createBrowserRouter(routes);
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App router={router} queryClient={createQueryClient()} />
    </StrictMode>,
  );
};

void bootstrap();
