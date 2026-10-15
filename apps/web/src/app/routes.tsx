import { Navigate, type RouteObject } from 'react-router';
import { AppShell } from '../components/layout/AppShell';
import { NotFoundPage } from '../components/layout/NotFoundPage';
import { RouteFallback } from '../components/layout/RouteFallback';

/**
 * Route table. Feature screens are lazy route modules so each slice ticket
 * (BC-0115 to BC-0118) ships as its own chunk and the shell stays small.
 */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    // Shown by the data router while a lazy route module is still loading.
    hydrateFallbackElement: <RouteFallback />,
    children: [
      { index: true, element: <Navigate to="/onboarding" replace /> },
      {
        path: 'onboarding/:step?',
        lazy: async () => {
          const { OnboardingPage } =
            await import('../features/onboarding/OnboardingPage');
          return { Component: OnboardingPage };
        },
      },
      {
        path: 'explore',
        lazy: async () => {
          const { ExplorePage } =
            await import('../features/explore/ExplorePage');
          return { Component: ExplorePage };
        },
      },
      {
        path: 'session',
        lazy: async () => {
          const { SessionPage } =
            await import('../features/session/SessionPage');
          return { Component: SessionPage };
        },
      },
      {
        path: 'attempts',
        lazy: async () => {
          const { AttemptsPage } =
            await import('../features/attempts/AttemptsPage');
          return { Component: AttemptsPage };
        },
      },
      {
        path: 'decisions/:decisionId',
        lazy: async () => {
          const { DecisionPage } =
            await import('../features/decision/DecisionPage');
          return { Component: DecisionPage };
        },
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
