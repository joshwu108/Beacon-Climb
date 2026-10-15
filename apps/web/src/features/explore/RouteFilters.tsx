import { useId, useState, type FormEvent } from 'react';
import { FieldError, describedBy } from '../../components/ui/FieldError';
import { parseNumber, validateFilters, type ExploreFilters } from './filters';

export interface RouteFiltersProps {
  readonly filters: ExploreFilters;
  readonly availableAngles: readonly number[];
  readonly onApply: (filters: ExploreFilters) => void;
  readonly onClear: () => void;
}

/**
 * Filters mirror the listRoutes query. The angle is a radio group over the board's
 * available_angles; grades are numeric (grade_numeric) because the label table is
 * still blocked. Nothing is sent until Apply, and from > to is blocked client-side.
 */
export const RouteFilters = ({
  filters,
  availableAngles,
  onApply,
  onClear,
}: RouteFiltersProps) => {
  const ids = useId();
  const [angleDeg, setAngleDeg] = useState(filters.angleDeg);
  const [gradeMin, setGradeMin] = useState(
    filters.gradeMin === null ? '' : String(filters.gradeMin),
  );
  const [gradeMax, setGradeMax] = useState(
    filters.gradeMax === null ? '' : String(filters.gradeMax),
  );
  const [error, setError] = useState<string | null>(null);

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const next: ExploreFilters = {
      angleDeg,
      gradeMin: parseNumber(gradeMin),
      gradeMax: parseNumber(gradeMax),
    };
    const problem = validateFilters(next);
    setError(problem);
    if (problem === null) onApply(next);
  };

  const clear = (): void => {
    setGradeMin('');
    setGradeMax('');
    setError(null);
    onClear();
  };

  const gradeErrorId = `${ids}-grade-error`;
  const gradeNoteId = `${ids}-grade-note`;

  return (
    <form
      className="filters"
      role="search"
      aria-label="Route filters"
      onSubmit={submit}
      noValidate
    >
      <fieldset className="filters__group">
        <legend className="field__label">Wall angle</legend>
        <div className="segmented" role="presentation">
          {availableAngles.map((angle) => (
            <label
              className="segmented__option"
              key={angle}
              htmlFor={`${ids}-angle-${angle}`}
            >
              <input
                id={`${ids}-angle-${angle}`}
                type="radio"
                name="angle_deg"
                value={angle}
                checked={angleDeg === angle}
                onChange={() => setAngleDeg(angle)}
              />
              <span>{angle}°</span>
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset
        className="filters__group"
        aria-describedby={describedBy(
          gradeNoteId,
          error !== null && gradeErrorId,
        )}
        aria-invalid={error !== null}
      >
        <legend className="field__label">Grade range (numeric)</legend>
        <div className="filters__range">
          <label className="filters__range-field" htmlFor={`${ids}-grade-min`}>
            <span className="field__hint">from</span>
            <input
              className="field__input"
              id={`${ids}-grade-min`}
              name="grade_min"
              type="number"
              inputMode="decimal"
              step="1"
              value={gradeMin}
              onChange={(e) => setGradeMin(e.target.value)}
              aria-invalid={error !== null}
            />
          </label>
          <label className="filters__range-field" htmlFor={`${ids}-grade-max`}>
            <span className="field__hint">to</span>
            <input
              className="field__input"
              id={`${ids}-grade-max`}
              name="grade_max"
              type="number"
              inputMode="decimal"
              step="1"
              value={gradeMax}
              onChange={(e) => setGradeMax(e.target.value)}
              aria-invalid={error !== null}
            />
          </label>
        </div>
        <p className="field__hint" id={gradeNoteId}>
          Community grade numbers. Grade labels (V-scale) are pending the grade
          table.
        </p>
        <FieldError id={gradeErrorId} message={error} />
      </fieldset>

      <div className="filters__actions">
        <button type="submit" className="button">
          Apply filters
        </button>
        <button
          type="button"
          className="button button--secondary"
          onClick={clear}
        >
          Clear
        </button>
      </div>
    </form>
  );
};
