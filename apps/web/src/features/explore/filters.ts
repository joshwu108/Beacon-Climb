/**
 * Explore filter state lives in the URL (?angle_deg=&grade_min=&grade_max=) and maps
 * one to one onto the listRoutes query. board_id always comes from preferences and the
 * angle must be one of the board's available_angles, so an incompatible request is
 * never built.
 */
export interface ExploreFilters {
  readonly angleDeg: number;
  readonly gradeMin: number | null;
  readonly gradeMax: number | null;
}

export interface ExploreDefaults {
  readonly angleDeg: number;
  readonly availableAngles: readonly number[];
}

export interface RoutesQuery {
  readonly board_id: string;
  readonly angle_deg: number;
  readonly grade_min?: number;
  readonly grade_max?: number;
  readonly limit: number;
}

/** Rows per page; small enough that the synthetic catalog pages at every angle. */
export const PAGE_LIMIT = 10;

export const parseNumber = (text: string | null): number | null => {
  if (text === null || text.trim() === '') return null;
  const value = Number(text);
  return Number.isFinite(value) ? value : null;
};

/** Read filters from the URL; an angle the board does not offer falls back to the default. */
export const parseFilters = (
  params: URLSearchParams,
  defaults: ExploreDefaults,
): ExploreFilters => {
  const angle = parseNumber(params.get('angle_deg'));
  return {
    angleDeg:
      angle !== null && defaults.availableAngles.includes(angle)
        ? angle
        : defaults.angleDeg,
    gradeMin: parseNumber(params.get('grade_min')),
    gradeMax: parseNumber(params.get('grade_max')),
  };
};

/** Write filters to the URL, omitting the defaults so the plain /explore stays clean. */
export const toSearchParams = (
  filters: ExploreFilters,
  defaults: ExploreDefaults,
): URLSearchParams => {
  const params = new URLSearchParams();
  if (filters.angleDeg !== defaults.angleDeg)
    params.set('angle_deg', String(filters.angleDeg));
  if (filters.gradeMin !== null)
    params.set('grade_min', String(filters.gradeMin));
  if (filters.gradeMax !== null)
    params.set('grade_max', String(filters.gradeMax));
  return params;
};

/** Client pre-check (wireframes/explore.md state matrix): from must not exceed to. */
export const validateFilters = (filters: ExploreFilters): string | null =>
  filters.gradeMin !== null &&
  filters.gradeMax !== null &&
  filters.gradeMin > filters.gradeMax
    ? 'Grade from must not be higher than grade to.'
    : null;

export const toRoutesQuery = (
  boardId: string,
  filters: ExploreFilters,
  limit = PAGE_LIMIT,
): RoutesQuery => ({
  board_id: boardId,
  angle_deg: filters.angleDeg,
  ...(filters.gradeMin !== null ? { grade_min: filters.gradeMin } : {}),
  ...(filters.gradeMax !== null ? { grade_max: filters.gradeMax } : {}),
  limit,
});

export const hasGradeFilter = (filters: ExploreFilters): boolean =>
  filters.gradeMin !== null || filters.gradeMax !== null;
