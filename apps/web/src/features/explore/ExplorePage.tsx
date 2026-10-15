import { Navigate, useSearchParams } from 'react-router';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { tokenStore } from '../auth/token-store';
import { useMe } from '../auth/useMe';
import { findBoard, useBoards } from '../boards/useBoards';
import { RouteFilters } from './RouteFilters';
import { RouteList } from './RouteList';
import {
  hasGradeFilter,
  parseFilters,
  toRoutesQuery,
  toSearchParams,
  type ExploreDefaults,
  type ExploreFilters,
} from './filters';
import { useRoutes } from './useRoutes';
import './explore.css';

const Header = ({ children }: { children?: React.ReactNode }) => (
  <>
    <div className="explore__header">
      <p className="page__eyebrow">Routes on your board</p>
      <h1>Explore routes</h1>
      <p className="page__lede">
        The routes set for your board and angle, by community grade. Open one to
        see its holds drawn on the board.
      </p>
    </div>
    {children}
  </>
);

const SKELETON_WIDTHS = ['40%', '85%', '60%'] as const;

const LoadingSheet = ({ label }: { label: string }) => (
  <div className="sheet explore__loading" aria-busy="true">
    <p className="visually-hidden" role="status">
      {label}
    </p>
    {SKELETON_WIDTHS.map((width) => (
      <span
        className="skeleton"
        style={{ width }}
        key={width}
        aria-hidden="true"
      />
    ))}
  </div>
);

/**
 * Explore (BC-0116): board and default angle from preferences, filters in the URL,
 * cursor-paged list with "Load more". Routes for another board or an angle the board
 * does not offer are never requested.
 */
export const ExplorePage = () => {
  const hasToken = tokenStore.get() !== null;
  const me = useMe({ enabled: hasToken });
  const boards = useBoards({ enabled: hasToken });
  const [searchParams, setSearchParams] = useSearchParams();

  if (!hasToken) return <Navigate to="/onboarding" replace />;

  if (me.isError) {
    return (
      <article className="page explore">
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
  if (boards.isError) {
    return (
      <article className="page explore">
        <Header>
          <ApiErrorBanner
            error={boards.error}
            title="Your board couldn't be loaded."
            onRetry={() => void boards.refetch()}
          />
        </Header>
      </article>
    );
  }
  if (me.isPending || boards.isPending) {
    return (
      <article className="page explore">
        <Header>
          <LoadingSheet label="Loading your board" />
        </Header>
      </article>
    );
  }

  const preferences = me.data.preferences;
  if (preferences === null) return <Navigate to="/onboarding" replace />;
  const board = findBoard(boards.data.items, preferences.board_id);

  if (board === null) {
    return (
      <article className="page explore">
        <Header>
          <div className="banner banner--warning" role="status">
            <p className="banner__title">Your board isn&apos;t available.</p>
            <p>
              Board {preferences.board_id} is not in the catalog right now. Pick
              another in setup.
            </p>
          </div>
        </Header>
      </article>
    );
  }

  const defaults: ExploreDefaults = {
    angleDeg: board.available_angles.includes(preferences.angle_deg)
      ? preferences.angle_deg
      : board.available_angles[0],
    availableAngles: board.available_angles,
  };
  const filters = parseFilters(searchParams, defaults);
  const applyFilters = (next: ExploreFilters): void =>
    setSearchParams(toSearchParams(next, defaults));

  return (
    <article className="page explore">
      <Header />
      <ExploreBody
        key={`${board.board_id}:${searchParams.toString()}`}
        boardId={board.board_id}
        boardIsSynthetic={board.synthetic}
        defaults={defaults}
        filters={filters}
        onApply={applyFilters}
        onClear={() =>
          applyFilters({ ...filters, gradeMin: null, gradeMax: null })
        }
      />
    </article>
  );
};

interface ExploreBodyProps {
  readonly boardId: string;
  readonly boardIsSynthetic: boolean;
  readonly defaults: ExploreDefaults;
  readonly filters: ExploreFilters;
  readonly onApply: (filters: ExploreFilters) => void;
  readonly onClear: () => void;
}

const ExploreBody = ({
  boardId,
  boardIsSynthetic,
  defaults,
  filters,
  onApply,
  onClear,
}: ExploreBodyProps) => {
  const routes = useRoutes(toRoutesQuery(boardId, filters));
  const items = routes.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <>
      <div className="explore__chip" aria-label="Current board and angle">
        <span className="explore__chip-board">{boardId}</span>
        <span className="explore__chip-angle">{filters.angleDeg}°</span>
        {boardIsSynthetic ? (
          <span className="stamp stamp--warning">Synthetic demo board</span>
        ) : null}
      </div>
      <section className="sheet explore__filters" aria-label="Filters">
        <RouteFilters
          filters={filters}
          availableAngles={defaults.availableAngles}
          onApply={onApply}
          onClear={onClear}
        />
      </section>
      <section className="explore__index" aria-labelledby="routes-heading">
        <h2 id="routes-heading" className="explore__index-heading">
          Routes at {filters.angleDeg} degrees
        </h2>
        <RouteList
          routes={items}
          angleDeg={filters.angleDeg}
          isLoading={routes.isPending}
          error={routes.error}
          hasNextPage={routes.hasNextPage}
          isFetchingNextPage={routes.isFetchingNextPage}
          hasFilters={hasGradeFilter(filters)}
          onLoadMore={() => void routes.fetchNextPage()}
          onRetry={() => void routes.refetch()}
          onClearFilters={onClear}
        />
      </section>
    </>
  );
};
