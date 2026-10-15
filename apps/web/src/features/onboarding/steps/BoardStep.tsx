import { useId, useState, type FormEvent } from 'react';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { FieldError, describedBy } from '../../../components/ui/FieldError';
import type { FieldErrorMap } from '../../../components/ui/field-errors';
import { boardLabel, findBoard, useBoards } from '../../boards/useBoards';
import { StepHeading } from '../StepHeading';
import type { PreferencesDraft } from '../steps';

export interface BoardStepProps {
  readonly draft: PreferencesDraft | null;
  /** Field errors from a rejected PUT /v1/me/preferences (board_id, angle_deg). */
  readonly serverErrors: FieldErrorMap;
  readonly onContinue: (draft: PreferencesDraft) => void;
}

const SKELETON_WIDTHS = ['70%', '55%', '62%'] as const;

/**
 * Step 3: board and angle. Boards come from GET /v1/boards; the angle choices are the
 * selected board's available_angles, so an unsupported angle cannot be chosen here.
 * A server UNSUPPORTED_ANGLE / UNKNOWN_BOARD still lands on these fields.
 */
export const BoardStep = ({
  draft,
  serverErrors,
  onContinue,
}: BoardStepProps) => {
  const ids = useId();
  const boards = useBoards();
  const [boardId, setBoardId] = useState<string | null>(draft?.boardId ?? null);
  const [angleDeg, setAngleDeg] = useState<number | null>(
    draft?.angleDeg ?? null,
  );
  const [touched, setTouched] = useState(false);
  const [dismissed, setDismissed] = useState<FieldErrorMap>({});

  const items = boards.data?.items;
  const selectedBoard =
    findBoard(items, boardId) ?? (items?.length === 1 ? items[0] : null);
  const effectiveBoardId = selectedBoard?.board_id ?? null;
  const angles = selectedBoard?.available_angles ?? [];
  const effectiveAngle =
    angleDeg !== null && angles.includes(angleDeg) ? angleDeg : null;

  // A server field error stays until the person changes that field.
  const isDismissed = (field: 'board_id' | 'angle_deg'): boolean =>
    serverErrors[field] !== undefined &&
    dismissed[field] === serverErrors[field];
  const boardError = isDismissed('board_id')
    ? null
    : (serverErrors.board_id ??
      (touched && effectiveBoardId === null ? 'Choose a board.' : null));
  const angleError = isDismissed('angle_deg')
    ? null
    : (serverErrors.angle_deg ??
      (touched && effectiveAngle === null ? 'Choose an angle.' : null));

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    setTouched(true);
    if (effectiveBoardId === null || effectiveAngle === null) return;
    onContinue({ boardId: effectiveBoardId, angleDeg: effectiveAngle });
  };

  const boardErrorId = `${ids}-board-error`;
  const angleErrorId = `${ids}-angle-error`;

  return (
    <form
      className="sheet onboarding__form"
      onSubmit={submit}
      aria-labelledby="step-heading"
      aria-busy={boards.isPending}
      noValidate
    >
      <StepHeading step="board" />
      <p className="field__hint">
        Only routes set for this board and angle will be shown. Use the angle
        printed on or beside your board.
      </p>

      {boards.isPending ? (
        <div className="onboarding__skeleton" aria-hidden="true">
          {SKELETON_WIDTHS.map((width) => (
            <span className="skeleton" style={{ width }} key={width} />
          ))}
        </div>
      ) : null}
      {boards.isPending ? (
        <p className="visually-hidden" role="status">
          Loading boards
        </p>
      ) : null}

      {boards.isError ? (
        <ApiErrorBanner
          error={boards.error}
          title="Boards couldn't be loaded."
          onRetry={() => void boards.refetch()}
        />
      ) : null}

      {items !== undefined && items.length === 0 ? (
        <div className="banner banner--warning" role="status">
          <p className="banner__title">No boards are available yet.</p>
          <p>
            Beacon can&apos;t set up routes without one. Try again in a moment.
          </p>
          <button
            type="button"
            className="button button--secondary banner__action"
            onClick={() => void boards.refetch()}
          >
            Try again
          </button>
        </div>
      ) : null}

      {items !== undefined && items.length > 0 ? (
        <>
          <fieldset
            className="choice-group"
            aria-describedby={describedBy(boardError !== null && boardErrorId)}
            aria-invalid={boardError !== null}
          >
            <legend className="field__label">Board</legend>
            {items.map((board) => (
              <label
                className="choice"
                key={board.board_id}
                htmlFor={`${ids}-${board.board_id}`}
              >
                <input
                  id={`${ids}-${board.board_id}`}
                  type="radio"
                  name="board_id"
                  value={board.board_id}
                  checked={effectiveBoardId === board.board_id}
                  onChange={() => {
                    setBoardId(board.board_id);
                    setAngleDeg(null);
                    setDismissed({
                      ...dismissed,
                      board_id: serverErrors.board_id ?? '',
                    });
                  }}
                  aria-invalid={boardError !== null}
                />
                <span className="choice__body">
                  <span className="choice__title">{boardLabel(board)}</span>
                  {board.synthetic ? (
                    <span className="choice__hint">
                      <span className="stamp stamp--warning">
                        Synthetic demo board
                      </span>{' '}
                      Practice routes made by a generator, not real climbs.
                    </span>
                  ) : null}
                </span>
              </label>
            ))}
            <FieldError id={boardErrorId} message={boardError} />
          </fieldset>

          <fieldset
            className="choice-group"
            aria-describedby={describedBy(angleError !== null && angleErrorId)}
            aria-invalid={angleError !== null}
            disabled={selectedBoard === null}
          >
            <legend className="field__label">Wall angle (degrees)</legend>
            {selectedBoard === null ? (
              <p className="field__hint">Choose a board first.</p>
            ) : (
              <div className="segmented" role="presentation">
                {angles.map((angle) => (
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
                      checked={effectiveAngle === angle}
                      onChange={() => {
                        setAngleDeg(angle);
                        setDismissed({
                          ...dismissed,
                          angle_deg: serverErrors.angle_deg ?? '',
                        });
                      }}
                      aria-invalid={angleError !== null}
                    />
                    <span>{angle}°</span>
                  </label>
                ))}
              </div>
            )}
            <FieldError id={angleErrorId} message={angleError} />
          </fieldset>
        </>
      ) : null}

      <div className="onboarding__actions">
        <button
          type="submit"
          className="button"
          disabled={items === undefined || items.length === 0}
        >
          Continue
        </button>
      </div>
    </form>
  );
};
