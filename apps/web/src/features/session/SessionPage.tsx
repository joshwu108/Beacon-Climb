import { useEffect, useRef, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import {
  fieldErrorsFrom,
  hasFieldErrors,
} from '../../components/ui/field-errors';
import { tokenStore } from '../auth/token-store';
import { useMe } from '../auth/useMe';
import type { Recommendation } from '../decision/useDecision';
import { Lineup } from './Lineup';
import { SessionForm } from './SessionForm';
import {
  buildRequest,
  parseSessionParams,
  toSessionSearch,
  type Goal,
} from './session-params';
import {
  useRecommend,
  type RecommendationResponse,
} from './useRecommendations';
import './session.css';

const REQUEST_FIELDS = ['goal', 'max_routes'] as const;

const Header = ({ children }: { children?: React.ReactNode }) => (
  <>
    <div className="session__header">
      <p className="page__eyebrow">Plan</p>
      <h1>Build a session</h1>
      <p className="page__lede">
        Pick a goal and get three to six routes with the reasons each one was
        chosen. Nothing here predicts what you will do on the wall.
      </p>
    </div>
    {children}
  </>
);

interface LineupState {
  readonly response: RecommendationResponse;
  readonly previousIds: ReadonlySet<string>;
}

/**
 * Session builder (BC-0117, wireframes/session-builder.md). Board and angle come from
 * preferences; goal, route count and the client session_id live in the URL so a reload
 * keeps the same session. Swap and Dismiss re-request with the dismissed ids excluded.
 */
export const SessionPage = () => {
  const hasToken = tokenStore.get() !== null;
  const me = useMe({ enabled: hasToken });
  const [searchParams, setSearchParams] = useSearchParams();
  const recommend = useRecommend();
  const [lineup, setLineup] = useState<LineupState | null>(null);
  const [excluded, setExcluded] = useState<readonly string[]>([]);
  const [announcement, setAnnouncement] = useState('');
  const headingRef = useRef<HTMLHeadingElement>(null);

  const preferences = me.data?.preferences ?? null;
  const params = parseSessionParams(searchParams, preferences?.goals ?? []);

  // One session id per builder visit, generated once and kept in the URL.
  useEffect(() => {
    if (hasToken && params.sessionId === null) {
      setSearchParams(
        (current) =>
          toSessionSearch(current, { sessionId: crypto.randomUUID() }),
        { replace: true },
      );
    }
  }, [hasToken, params.sessionId, setSearchParams]);

  // After every build the lineup heading takes focus so the result is announced.
  const decisionId = lineup?.response.decision_id;
  useEffect(() => {
    if (decisionId !== undefined) headingRef.current?.focus();
  }, [decisionId]);

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  if (me.isError) {
    return (
      <article className="page session">
        <Header>
          <ApiErrorBanner
            error={me.error}
            title="Your profile couldn't be loaded."
            onRetry={() => void me.refetch()}
          />
        </Header>
      </article>
    );
  }
  if (me.isPending) {
    return (
      <article className="page session" aria-busy="true">
        <Header>
          <p className="visually-hidden" role="status">
            Loading your board
          </p>
          <div className="sheet session__loading" aria-hidden="true">
            <span className="skeleton" style={{ width: '40%' }} />
            <span className="skeleton" style={{ width: '80%' }} />
          </div>
        </Header>
      </article>
    );
  }
  if (preferences === null) {
    return (
      <article className="page session">
        <Header>
          <div className="banner banner--warning" role="status">
            <p className="banner__title">Your board isn&apos;t set up yet.</p>
            <p>
              Sessions are built for the board and angle in your setup.{' '}
              <Link to="/onboarding">Finish setup</Link> to continue.
            </p>
          </div>
        </Header>
      </article>
    );
  }

  const request = (
    maxRoutes: number,
    nextExcluded: readonly string[],
    announce: (response: RecommendationResponse) => string,
  ): void => {
    if (params.sessionId === null || recommend.isPending) return;
    const body = buildRequest({
      boardId: preferences.board_id,
      angleDeg: preferences.angle_deg,
      goal: params.goal,
      maxRoutes,
      sessionId: params.sessionId,
      excluded: nextExcluded,
    });
    setExcluded(nextExcluded);
    recommend.mutate(body, {
      onSuccess: (response) => {
        setLineup((current) => ({
          response,
          previousIds: new Set(
            current?.response.recommendations.map((r) => r.route_variant_id) ??
              [],
          ),
        }));
        setAnnouncement(announce(response));
      },
    });
  };

  const build = (): void =>
    request(
      params.routes,
      excluded,
      (r) => `${r.recommendations.length} routes ready`,
    );

  const swap = (item: Recommendation): void =>
    request(
      lineup?.response.recommendations.length ?? params.routes,
      [...excluded, item.route_variant_id],
      () => `Replaced ${item.route.name}. The list was refreshed.`,
    );

  const dismiss = (item: Recommendation): void =>
    request(
      Math.max(
        1,
        (lineup?.response.recommendations.length ?? params.routes) - 1,
      ),
      [...excluded, item.route_variant_id],
      () => `Removed ${item.route.name}. The list was refreshed.`,
    );

  const change = (next: { goal?: Goal; routes?: number }): void =>
    setSearchParams(toSessionSearch(searchParams, next), { replace: true });

  const error = recommend.error;
  const fieldErrors = fieldErrorsFrom(error, REQUEST_FIELDS);
  const isConsentRequired =
    isApiError(error) &&
    error.status === 403 &&
    error.code === 'CONSENT_REQUIRED';

  return (
    <article className="page session">
      <Header />
      <p className="session__chip" aria-label="Current board and angle">
        <span>{preferences.board_id}</span>
        <span className="session__chip-angle">{preferences.angle_deg}°</span>
      </p>

      <div className="session__columns">
        <SessionForm
          goal={params.goal}
          routes={params.routes}
          isBusy={recommend.isPending}
          hasLineup={lineup !== null}
          errors={fieldErrors}
          onChange={change}
          onSubmit={build}
        />

        <div className="session__results">
          <p className="visually-hidden" role="status" aria-live="polite">
            {recommend.isPending ? 'Building your session' : announcement}
          </p>

          {isConsentRequired ? (
            <div className="banner banner--warning" role="alert">
              <p className="banner__title">
                Sessions need your consent to record decisions.
              </p>
              <p>
                Building a session records a decision, which needs logging to be
                on. <Link to="/onboarding/consent">Turn on logging</Link> and
                try again.
              </p>
            </div>
          ) : error !== null && !hasFieldErrors(fieldErrors) ? (
            isApiError(error) && error.status === 422 ? (
              <div className="banner banner--error" role="alert">
                <p className="banner__title">
                  Beacon couldn&apos;t use these settings.
                </p>
                <p>
                  Check your board, angle and goal in{' '}
                  <Link to="/onboarding">setup</Link>.
                </p>
                {error.requestId ? (
                  <p className="banner__meta">Request id {error.requestId}</p>
                ) : null}
              </div>
            ) : (
              <ApiErrorBanner
                error={error}
                title="We couldn't build your session."
                onRetry={build}
              />
            )
          ) : null}

          {lineup !== null ? (
            <Lineup
              lineup={lineup.response}
              previousIds={lineup.previousIds}
              isStale={error !== null}
              isBusy={recommend.isPending}
              dismissedCount={excluded.length}
              onSwap={swap}
              onDismiss={dismiss}
              onResetDismissed={() => setExcluded([])}
              headingRef={headingRef}
            />
          ) : recommend.isPending ? (
            <div className="session__skeleton" aria-hidden="true">
              {Array.from({ length: params.routes }, (_, i) => (
                <div className="sheet card card--skeleton" key={i}>
                  <span className="skeleton" style={{ width: '55%' }} />
                  <span className="skeleton" style={{ width: '80%' }} />
                </div>
              ))}
            </div>
          ) : null}
        </div>
      </div>
    </article>
  );
};
