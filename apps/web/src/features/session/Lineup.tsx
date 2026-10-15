import { Link } from 'react-router';
import { formatDateTime } from '../../lib/format';
import { ColdStartNotice, FallbackNotice } from '../decision/LineupNotices';
import { EstimateLine, ReasonList } from '../decision/ReasonList';
import { byPosition, type Recommendation } from '../decision/useDecision';
import { formatGrade, formatObservations } from '../explore/grade';
import type { RecommendationResponse } from './useRecommendations';

export interface LineupProps {
  readonly lineup: RecommendationResponse;
  readonly previousIds: ReadonlySet<string>;
  readonly isStale: boolean;
  readonly isBusy: boolean;
  readonly dismissedCount: number;
  readonly onSwap: (item: Recommendation) => void;
  readonly onDismiss: (item: Recommendation) => void;
  readonly onResetDismissed: () => void;
  readonly headingRef: React.RefObject<HTMLHeadingElement | null>;
}

const MAX_CHIPS = 3;

const LineupCard = ({
  item,
  decisionId,
  isNew,
  fallbackUsed,
  isBusy,
  onSwap,
  onDismiss,
}: {
  item: Recommendation;
  decisionId: string;
  isNew: boolean;
  fallbackUsed: boolean;
  isBusy: boolean;
  onSwap: () => void;
  onDismiss: () => void;
}) => {
  const grade = formatGrade(item.route);
  const routeHref = `/explore/${encodeURIComponent(item.route_variant_id)}`;
  const whyHref = `/decisions/${decisionId}?position=${item.position}`;
  const extra = item.reason_codes.length - MAX_CHIPS;
  return (
    <li className="sheet card" aria-labelledby={`card-${item.position}`}>
      <div className="card__head">
        <span className="card__position" aria-hidden="true">
          {String(item.position).padStart(2, '0')}
        </span>
        <h3 className="card__name" id={`card-${item.position}`}>
          <span className="visually-hidden">Position {item.position}: </span>
          <Link to={routeHref}>{item.route.name}</Link>
        </h3>
        <span className="card__grade">
          <span className="card__grade-number">
            {grade.isUngraded ? '—' : grade.primary}
          </span>
          <span className="card__grade-label">
            {grade.isUngraded ? 'ungraded' : (grade.label ?? 'label pending')}
          </span>
        </span>
      </div>
      <p className="card__meta">
        <span>{formatObservations(item.route.grade_observation_count)}</span>
        {isNew ? <span className="stamp stamp--accent">New</span> : null}
        {fallbackUsed ? (
          <span className="stamp stamp--warning">
            Simplified recommendation
          </span>
        ) : null}
      </p>
      <ReasonList codes={item.reason_codes.slice(0, MAX_CHIPS)} />
      {extra > 0 ? (
        <p className="field__hint">
          <Link to={whyHref}>
            +{extra} more reason{extra === 1 ? '' : 's'}
          </Link>
        </p>
      ) : null}
      <EstimateLine
        probability={item.predicted_send_probability}
        uncertaintyLabel={item.uncertainty_label}
      />
      <div className="card__actions">
        <Link
          className="button"
          to={`/attempts/new?route_variant_id=${encodeURIComponent(item.route_variant_id)}`}
        >
          Log attempt
        </Link>
        <Link className="card__why" to={whyHref}>
          Why this route
        </Link>
        <button
          type="button"
          className="button button--secondary"
          onClick={onSwap}
          disabled={isBusy}
        >
          Swap
        </button>
        <button
          type="button"
          className="button button--secondary"
          onClick={onDismiss}
          disabled={isBusy}
        >
          Dismiss
        </button>
      </div>
    </li>
  );
};

/** The lineup: banners, freshness line, decision link and the ordered cards. */
export const Lineup = ({
  lineup,
  previousIds,
  isStale,
  isBusy,
  dismissedCount,
  onSwap,
  onDismiss,
  onResetDismissed,
  headingRef,
}: LineupProps) => {
  const ranked = byPosition(lineup.recommendations);
  return (
    <section
      className={`session__lineup${isStale ? ' session__lineup--stale' : ''}`}
      aria-labelledby="lineup-heading"
      aria-busy={isBusy}
    >
      <div className="session__lineup-head">
        <h2 id="lineup-heading" tabIndex={-1} ref={headingRef}>
          Your session: {ranked.length} route{ranked.length === 1 ? '' : 's'}
        </h2>
        {isStale ? (
          <span className="stamp stamp--warning">Not refreshed</span>
        ) : null}
        <p className="field__hint">
          Picks use your history up to{' '}
          <time dateTime={lineup.feature_as_of}>
            {formatDateTime(lineup.feature_as_of)}
          </time>
          .{' '}
          <Link to={`/decisions/${lineup.decision_id}`}>Why these routes</Link>
        </p>
        {dismissedCount > 0 ? (
          <p className="field__hint">
            Dismissed: {dismissedCount} route{dismissedCount === 1 ? '' : 's'}{' '}
            (remembered on this device for this session).{' '}
            <button
              type="button"
              className="link-button"
              onClick={onResetDismissed}
              disabled={isBusy}
            >
              Reset
            </button>
          </p>
        ) : null}
      </div>

      {lineup.cold_start ? <ColdStartNotice /> : null}
      {lineup.fallback_used ? <FallbackNotice /> : null}

      {ranked.length === 0 ? (
        <div className="sheet session__empty">
          <p className="banner__title">No routes fit this goal.</p>
          <p>
            No routes fit this goal on your board and angle right now. Try
            another goal or <Link to="/explore">browse all routes</Link>.
          </p>
        </div>
      ) : (
        <ol className="cards" aria-label="Recommended routes in order">
          {ranked.map((item) => (
            <LineupCard
              key={item.route_variant_id}
              item={item}
              decisionId={lineup.decision_id}
              isNew={
                previousIds.size > 0 && !previousIds.has(item.route_variant_id)
              }
              fallbackUsed={lineup.fallback_used}
              isBusy={isBusy}
              onSwap={() => onSwap(item)}
              onDismiss={() => onDismiss(item)}
            />
          ))}
        </ol>
      )}
    </section>
  );
};
