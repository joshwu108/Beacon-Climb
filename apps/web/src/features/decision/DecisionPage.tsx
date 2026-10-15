import { Link, Navigate, useParams, useSearchParams } from 'react-router';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { formatDateTime } from '../../lib/format';
import { tokenStore } from '../auth/token-store';
import { formatGrade, formatObservations } from '../explore/grade';
import { goalLabel } from '../onboarding/goals';
import { ColdStartNotice, FallbackNotice } from './LineupNotices';
import { EstimateLine, ReasonList } from './ReasonList';
import { describeModelVersion, formatRankScore } from './reason-codes';
import { byPosition, useDecision } from './useDecision';
import './decision.css';

const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const Header = () => (
  <>
    <p className="detail__back">
      <Link to="/session">← Back to the session builder</Link>
    </p>
    <p className="page__eyebrow">Decision record</p>
  </>
);

/**
 * Decision detail (BC-0117, wireframes/decision-detail.md): what was asked, which
 * versions answered, the flags, and every ranked route with its reasons. Read-only;
 * a decision never changes once recorded.
 */
export const DecisionPage = () => {
  const { decisionId = '' } = useParams();
  const [searchParams] = useSearchParams();
  const hasToken = tokenStore.get() !== null;
  const isValidId = UUID_RE.test(decisionId);
  const decision = useDecision(decisionId, hasToken && isValidId);

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  if (!isValidId || decision.isError) {
    const notFound =
      !isValidId ||
      (isApiError(decision.error) && decision.error.status === 404);
    return (
      <article className="page decision">
        <Header />
        <h1>
          {notFound
            ? "We couldn't find this decision."
            : "This decision couldn't be loaded."}
        </h1>
        {notFound ? (
          <p className="page__lede">
            The link may be old or it isn&apos;t yours.
            {isApiError(decision.error) && decision.error.requestId ? (
              <span className="banner__meta">
                {' '}
                Request id {decision.error.requestId}
              </span>
            ) : null}
          </p>
        ) : (
          <ApiErrorBanner
            error={decision.error}
            onRetry={() => void decision.refetch()}
          />
        )}
      </article>
    );
  }

  if (decision.isPending) {
    return (
      <article className="page decision" aria-busy="true">
        <Header />
        <p className="visually-hidden" role="status">
          Loading this decision
        </p>
        <h1>
          <span
            className="skeleton"
            style={{ width: '12rem', height: '1.2em' }}
            aria-hidden="true"
          />
        </h1>
        <div className="sheet decision__loading" aria-hidden="true">
          <span className="skeleton" style={{ width: '60%' }} />
          <span className="skeleton" style={{ width: '85%' }} />
          <span className="skeleton" style={{ width: '40%' }} />
        </div>
      </article>
    );
  }

  const data = decision.data;
  const ranked = byPosition(data.recommendations);
  const highlighted = Number(searchParams.get('position'));

  return (
    <article className="page decision">
      <Header />
      <h1>Why these routes</h1>
      <p className="page__lede">
        Decision <code className="decision__id">{data.decision_id}</code>,
        recorded{' '}
        <time dateTime={data.created_at}>
          {formatDateTime(data.created_at)}
        </time>
        .
      </p>

      {data.cold_start ? <ColdStartNotice detailed /> : null}
      {data.fallback_used ? <FallbackNotice /> : null}

      <div className="decision__columns">
        <section
          className="decision__list"
          aria-labelledby="decision-routes-heading"
        >
          <h2 id="decision-routes-heading">
            All routes in this list ({ranked.length})
          </h2>
          {ranked.length === 0 ? (
            <p className="field__hint">
              This decision didn&apos;t list any routes.
            </p>
          ) : (
            <ol className="ranked" aria-label="Recommended routes in order">
              {ranked.map((item) => {
                const grade = formatGrade(item.route);
                const isHighlighted = item.position === highlighted;
                return (
                  <li
                    className={`ranked__item sheet${isHighlighted ? ' ranked__item--current' : ''}`}
                    key={item.route_variant_id}
                    aria-current={isHighlighted ? 'true' : undefined}
                  >
                    <p className="ranked__position">Position {item.position}</p>
                    <h3 className="ranked__name">
                      <Link
                        to={`/explore/${encodeURIComponent(item.route_variant_id)}`}
                      >
                        {item.route.name}
                      </Link>
                    </h3>
                    <p className="ranked__facts">
                      <span className="ranked__grade">
                        {grade.isUngraded ? 'ungraded' : grade.primary}
                      </span>
                      {grade.label ? <span>{grade.label}</span> : null}
                      <span>
                        {formatObservations(item.route.grade_observation_count)}
                      </span>
                    </p>
                    <h4 className="ranked__subheading">Reasons</h4>
                    <ReasonList codes={item.reason_codes} detailed />
                    <p className="ranked__score">
                      Ordering value{' '}
                      <span className="ranked__score-value">
                        {formatRankScore(item.rank_score)}
                      </span>{' '}
                      <span className="field__hint">
                        (relative order only, not a chance of success)
                      </span>
                    </p>
                    <EstimateLine
                      probability={item.predicted_send_probability}
                      uncertaintyLabel={item.uncertainty_label}
                    />
                    <p>
                      <Link
                        className="button button--secondary"
                        to={`/attempts/new?route_variant_id=${encodeURIComponent(item.route_variant_id)}`}
                      >
                        Log an attempt
                      </Link>
                    </p>
                  </li>
                );
              })}
            </ol>
          )}
        </section>

        <aside className="sheet decision__about" aria-label="About this list">
          <h2>About this list</h2>
          <dl className="facts">
            <div className="facts__row">
              <dt>Asked for</dt>
              <dd>
                {goalLabel(data.request.goal)}, up to {data.request.max_routes}{' '}
                routes
              </dd>
            </div>
            <div className="facts__row">
              <dt>Board and angle</dt>
              <dd>
                {data.request.board_id} at {data.request.angle_deg}°
              </dd>
            </div>
            <div className="facts__row">
              <dt>Left out on request</dt>
              <dd>
                {data.request.exclude_route_variant_ids.length === 0
                  ? 'none'
                  : data.request.exclude_route_variant_ids.join(', ')}
              </dd>
            </div>
            <div className="facts__row">
              <dt>Policy</dt>
              <dd>{data.policy_version}</dd>
            </div>
            <div className="facts__row">
              <dt>Model</dt>
              <dd>{describeModelVersion(data.model_version)}</dd>
            </div>
            <div className="facts__row">
              <dt>History used up to</dt>
              <dd>
                <time dateTime={data.feature_as_of}>
                  {formatDateTime(data.feature_as_of)}
                </time>
              </dd>
            </div>
            <div className="facts__row">
              <dt>Flags</dt>
              <dd>
                {data.cold_start ? 'cold start' : 'history-based'};{' '}
                {data.fallback_used ? 'fallback used' : 'full ranking'}
              </dd>
            </div>
            <div className="facts__row">
              <dt>Session</dt>
              <dd>
                <code className="decision__id">{data.request.session_id}</code>
              </dd>
            </div>
          </dl>
          <p className="field__hint">
            Feature values and the candidate list are not part of the public
            decision, so the evidence shown is the reason codes and the history
            time only.
          </p>
        </aside>
      </div>
    </article>
  );
};
