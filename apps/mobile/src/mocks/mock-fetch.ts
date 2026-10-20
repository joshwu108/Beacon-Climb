/**
 * Mock mode transport (EXPO_PUBLIC_USE_MOCKS=true). apps/web's MSW handlers answer every
 * request in-process through msw's getResponse, so the same fixtures, synthetic catalog and
 * per-session state serve both apps, on web and on native, without a service worker.
 * The similar-routes handler is listed first: msw takes the first match, and the web
 * handlers' `/v1/routes/:routeVariantId` would not match `/similar` anyway.
 */
import { getResponse } from 'msw';
import { handlers } from '@web/mocks/handlers';
import { withRequestedPolicy } from './policy';
import { similarHandler } from './similar';

export const mockHandlers = [similarHandler, ...handlers];

/** Operations without a handler (x-planned) answer like the API: 501 with an envelope. */
const notImplemented = (request: Request): Response =>
  new Response(
    JSON.stringify({
      code: 'UNKNOWN',
      message: `No mock for ${request.method} ${new URL(request.url).pathname}`,
      request_id: 'mock-not-implemented',
      field_errors: [],
    }),
    {
      status: 501,
      headers: {
        'Content-Type': 'application/json',
        'X-Request-Id': 'mock-not-implemented',
      },
    },
  );

const RECOMMENDATIONS_PATH = '/v1/me/sessions/recommendations';

export const handleMockRequest = async (
  request: Request,
): Promise<Response> => {
  const isRecommendation =
    request.method === 'POST' &&
    new URL(request.url).pathname === RECOMMENDATIONS_PATH;
  // getResponse consumes the body; keep a copy for the policy echo.
  const original = isRecommendation ? request.clone() : null;
  const response =
    (await getResponse(mockHandlers, request)) ?? notImplemented(request);
  return original === null ? response : withRequestedPolicy(original, response);
};
