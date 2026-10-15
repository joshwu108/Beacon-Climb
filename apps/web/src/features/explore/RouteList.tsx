import { Link } from 'react-router';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { formatGrade, formatObservations, isLowEvidence } from './grade';
import type { RouteVariantSummary } from './useRoutes';

export interface RouteListProps {
  readonly routes: readonly RouteVariantSummary[];
  readonly angleDeg: number;
  readonly isLoading: boolean;
  readonly error: unknown;
  readonly hasNextPage: boolean;
  readonly isFetchingNextPage: boolean;
  readonly hasFilters: boolean;
  readonly onLoadMore: () => void;
  readonly onRetry: () => void;
  readonly onClearFilters: () => void;
}

const SKELETON_ROWS = 6;

const RouteRow = ({
  route,
  index,
}: {
  route: RouteVariantSummary;
  index: number;
}) => {
  const grade = formatGrade(route);
  const lowEvidence = isLowEvidence(route);
  return (
    <li className="index__row">
      <Link
        to={`/explore/${encodeURIComponent(route.route_variant_id)}`}
        className="index__link"
      >
        <span className="index__ordinal" aria-hidden="true">
          {String(index + 1).padStart(2, '0')}
        </span>
        <span className="index__grade" aria-label={`Grade ${grade.primary}`}>
          <span
            className={`index__grade-number${grade.isUngraded ? ' index__grade-number--none' : ''}`}
          >
            {grade.isUngraded ? '—' : grade.primary}
          </span>
          {grade.label ? (
            <span className="index__grade-label">{grade.label}</span>
          ) : null}
          {grade.isUngraded ? (
            <span className="index__grade-label">ungraded</span>
          ) : null}
        </span>
        <span className="index__body">
          <span className="index__name">{route.name}</span>
          <span className="index__meta">
            <span>{formatObservations(route.grade_observation_count)}</span>
            {lowEvidence ? (
              <span className="stamp stamp--warning">Low evidence</span>
            ) : null}
          </span>
        </span>
        <span className="index__arrow" aria-hidden="true">
          →
        </span>
      </Link>
    </li>
  );
};

/** The field-notebook index: numbered rows, grade column, name, evidence. */
export const RouteList = ({
  routes,
  angleDeg,
  isLoading,
  error,
  hasNextPage,
  isFetchingNextPage,
  hasFilters,
  onLoadMore,
  onRetry,
  onClearFilters,
}: RouteListProps) => {
  if (isLoading) {
    return (
      <div className="index" aria-busy="true">
        <p role="status" className="index__status">
          Loading routes
        </p>
        <ol className="index__rows" aria-hidden="true">
          {Array.from({ length: SKELETON_ROWS }, (_, i) => (
            <li className="index__row index__row--skeleton" key={i}>
              <span className="skeleton" style={{ width: '2rem' }} />
              <span
                className="skeleton"
                style={{ width: `${55 + (i % 3) * 12}%` }}
              />
            </li>
          ))}
        </ol>
      </div>
    );
  }

  if (error !== null && error !== undefined && routes.length === 0) {
    return (
      <div className="index">
        <ApiErrorBanner
          error={error}
          title="Routes couldn't be loaded."
          onRetry={onRetry}
        />
      </div>
    );
  }

  if (routes.length === 0) {
    return (
      <div className="index index__empty">
        <p role="status" className="index__status">
          0 routes loaded
        </p>
        <p className="index__empty-title">No routes match.</p>
        <p>
          {hasFilters
            ? `No routes match these filters at ${angleDeg} degrees. Widen the grade range or try another angle.`
            : `This board has no routes loaded at ${angleDeg} degrees yet.`}
        </p>
        {hasFilters ? (
          <button
            type="button"
            className="button button--secondary"
            onClick={onClearFilters}
          >
            Clear filters
          </button>
        ) : null}
      </div>
    );
  }

  return (
    <div className="index">
      <p role="status" className="index__status">
        {routes.length} route{routes.length === 1 ? '' : 's'} loaded
        {hasNextPage ? ', more available' : ''}
      </p>
      <ol className="index__rows">
        {routes.map((route, index) => (
          <RouteRow route={route} index={index} key={route.route_variant_id} />
        ))}
      </ol>
      {error !== null && error !== undefined ? (
        <ApiErrorBanner
          error={error}
          title="More routes couldn't be loaded."
          onRetry={onLoadMore}
        />
      ) : null}
      {hasNextPage ? (
        <button
          type="button"
          className="button button--secondary index__more"
          onClick={onLoadMore}
          disabled={isFetchingNextPage}
          aria-busy={isFetchingNextPage}
        >
          {isFetchingNextPage ? 'Loading more' : 'Load more routes'}
        </button>
      ) : (
        <p className="index__end">End of the index for {angleDeg} degrees.</p>
      )}
    </div>
  );
};
