import { Link, Navigate, useParams } from 'react-router';
import { isApiError } from '../../api/errors';
import { BoardLegend } from '../../components/board-svg/BoardLegend';
import { BoardSvg } from '../../components/board-svg/BoardSvg';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { tokenStore } from '../auth/token-store';
import { findBoard, useBoards } from '../boards/useBoards';
import { formatGrade, formatObservations, isLowEvidence } from './grade';
import { useRoute } from './useRoutes';
import './explore.css';

const BackLink = () => (
  <p className="detail__back">
    <Link to="/explore">← Back to the index</Link>
  </p>
);

const SKELETON_WIDTHS = ['50%', '30%', '80%'] as const;

/**
 * Route detail (/explore/:routeVariantId): the board with this route's placements,
 * grade and evidence, tags or the explicit missing-tags notice.
 */
export const RouteDetailPage = () => {
  const { routeVariantId = '' } = useParams();
  const hasToken = tokenStore.get() !== null;
  const route = useRoute(routeVariantId);
  const boards = useBoards({ enabled: hasToken });

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  if (route.isError) {
    const notFound = isApiError(route.error) && route.error.status === 404;
    return (
      <article className="page detail">
        <BackLink />
        <p className="page__eyebrow">Route</p>
        <h1>
          {notFound
            ? "This route isn't available."
            : "This route couldn't be loaded."}
        </h1>
        {notFound ? (
          <p className="page__lede">
            It may have been retired, or it&apos;s not on your board.
            {isApiError(route.error) && route.error.requestId ? (
              <span className="banner__meta">
                {' '}
                Request id {route.error.requestId}
              </span>
            ) : null}
          </p>
        ) : (
          <ApiErrorBanner
            error={route.error}
            onRetry={() => void route.refetch()}
          />
        )}
      </article>
    );
  }

  if (route.isPending || boards.isPending) {
    return (
      <article className="page detail" aria-busy="true">
        <BackLink />
        <p className="page__eyebrow">Route</p>
        <p className="visually-hidden" role="status">
          Loading route
        </p>
        <h1>
          <span
            className="skeleton"
            style={{ width: '14rem', height: '1.2em' }}
            aria-hidden="true"
          />
        </h1>
        <div className="sheet detail__loading">
          {SKELETON_WIDTHS.map((width) => (
            <span
              className="skeleton"
              style={{ width }}
              key={width}
              aria-hidden="true"
            />
          ))}
        </div>
      </article>
    );
  }

  const variant = route.data;
  const board = boards.isSuccess
    ? findBoard(boards.data.items, variant.board_id)
    : null;
  const grade = formatGrade(variant);
  const lowEvidence = isLowEvidence(variant);

  return (
    <article className="page detail">
      <BackLink />
      <div className="detail__header">
        <p className="page__eyebrow">
          Route · {variant.angle_deg}° · {variant.board_id}
        </p>
        <h1>{variant.name}</h1>
      </div>

      <div className="detail__columns">
        <section className="detail__board" aria-labelledby="board-heading">
          <h2 id="board-heading" className="visually-hidden">
            Board view
          </h2>
          {board !== null ? (
            <BoardSvg
              board={board}
              placements={variant.placements}
              routeName={variant.name}
            />
          ) : boards.isError ? (
            <ApiErrorBanner
              error={boards.error}
              title="The board couldn't be drawn."
              onRetry={() => void boards.refetch()}
            />
          ) : (
            <div className="banner banner--warning" role="status">
              <p className="banner__title">
                Board {variant.board_id} isn&apos;t in the catalog.
              </p>
              <p>The hold list below is still complete.</p>
            </div>
          )}
          <BoardLegend />
          <p className="field__hint">Holds shown are this route only.</p>
          <p>
            <Link
              className="button button--secondary"
              to={`/attempts/new?route_variant_id=${encodeURIComponent(variant.route_variant_id)}`}
            >
              Log an attempt on this route
            </Link>
          </p>
        </section>

        <aside className="sheet detail__sheet" aria-label="Route facts">
          <dl className="facts">
            <div className="facts__row facts__row--grade">
              <dt>Community grade</dt>
              <dd>
                <span className="facts__grade">
                  {grade.isUngraded ? '—' : grade.primary}
                </span>
                <span className="facts__grade-label">
                  {grade.isUngraded
                    ? 'ungraded'
                    : (grade.label ?? 'label pending')}
                </span>
              </dd>
            </div>
            <div className="facts__row">
              <dt>Evidence</dt>
              <dd>
                {formatObservations(variant.grade_observation_count)}
                {lowEvidence ? (
                  <>
                    {' '}
                    <span className="stamp stamp--warning">Low evidence</span>
                  </>
                ) : null}
                {variant.grade_observation_count === 0 ? (
                  <p className="field__hint">
                    No one has logged a grade for this route yet, so its grade
                    is not backed by observations.
                  </p>
                ) : null}
              </dd>
            </div>
            <div className="facts__row">
              <dt>Style tags</dt>
              <dd>
                {variant.missing_tags || variant.tags.length === 0 ? (
                  <span className="facts__missing">
                    Style tags: not available for this route.
                  </span>
                ) : (
                  <ul className="tags">
                    {variant.tags.map((tag) => (
                      <li className="stamp" key={tag}>
                        {tag}
                      </li>
                    ))}
                  </ul>
                )}
              </dd>
            </div>
            <div className="facts__row">
              <dt>Holds</dt>
              <dd>
                {variant.placements.length} placement
                {variant.placements.length === 1 ? '' : 's'}
              </dd>
            </div>
          </dl>
        </aside>
      </div>
    </article>
  );
};
