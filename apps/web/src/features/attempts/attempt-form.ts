import type { FieldErrorMap } from '../../components/ui/field-errors';
import type { Attempt, AttemptCreate } from './draft-store';
import type { AttemptPatch } from './useAttempts';

/** Mirrors the server rule behind 422 OCCURRED_AT_IN_FUTURE. */
export const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;
export const NOTES_MAX = 500;
export const EFFORT_MIN = 1;
export const EFFORT_MAX = 10;

export interface AttemptFormValues {
  /** `datetime-local` value (local time, minute precision). */
  readonly occurredAt: string;
  readonly attemptsCount: number;
  readonly sent: boolean;
  /** 0..100, null when untouched. */
  readonly completionPercent: number | null;
  readonly perceivedEffort: number | null;
  readonly notes: string;
}

const pad2 = (n: number): string => String(n).padStart(2, '0');

/** Local wall-clock value for a datetime-local input. */
export const toLocalInputValue = (date: Date): string =>
  `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}T${pad2(date.getHours())}:${pad2(date.getMinutes())}`;

export const defaultValues = (now = new Date()): AttemptFormValues => ({
  occurredAt: toLocalInputValue(now),
  attemptsCount: 1,
  sent: false,
  completionPercent: null,
  perceivedEffort: null,
  notes: '',
});

export const valuesFromAttempt = (attempt: Attempt): AttemptFormValues => ({
  occurredAt: toLocalInputValue(new Date(attempt.occurred_at)),
  attemptsCount: attempt.attempts_count,
  sent: attempt.sent,
  completionPercent:
    attempt.completion_fraction === null
      ? null
      : Math.round(attempt.completion_fraction * 100),
  perceivedEffort: attempt.perceived_effort,
  notes: attempt.notes ?? '',
});

export const FUTURE_MESSAGE =
  'That time is in the future. Pick a time that has already happened.';

export const validateAttempt = (
  values: AttemptFormValues,
  options: { readonly requireTime?: boolean; readonly now?: number } = {},
): FieldErrorMap => {
  const errors: Record<string, string> = {};
  if (options.requireTime !== false) {
    const ms = Date.parse(values.occurredAt);
    if (!Number.isFinite(ms)) errors.occurred_at = 'Enter the date and time.';
    else if (ms > (options.now ?? Date.now()) + FUTURE_TOLERANCE_MS)
      errors.occurred_at = FUTURE_MESSAGE;
  }
  if (!Number.isInteger(values.attemptsCount) || values.attemptsCount < 1)
    errors.attempts_count = 'Log at least one attempt.';
  if (
    values.completionPercent !== null &&
    (values.completionPercent < 0 || values.completionPercent > 100)
  )
    errors.completion_fraction = 'Progress is between 0 and 100 percent.';
  if (
    values.perceivedEffort !== null &&
    (!Number.isInteger(values.perceivedEffort) ||
      values.perceivedEffort < EFFORT_MIN ||
      values.perceivedEffort > EFFORT_MAX)
  )
    errors.perceived_effort = `Effort is a whole number from ${EFFORT_MIN} to ${EFFORT_MAX}.`;
  if (values.notes.length > NOTES_MAX)
    errors.notes = `Notes are at most ${NOTES_MAX} characters.`;
  return errors;
};

const optionalFields = (values: AttemptFormValues) => ({
  ...(values.completionPercent !== null && !values.sent
    ? { completion_fraction: values.completionPercent / 100 }
    : {}),
  ...(values.perceivedEffort !== null
    ? { perceived_effort: values.perceivedEffort }
    : {}),
  ...(values.notes.trim() !== '' ? { notes: values.notes.trim() } : {}),
});

/** The exact AttemptCreate body; untouched optional answers are omitted, never inferred. */
export const toAttemptCreate = (
  routeVariantId: string,
  values: AttemptFormValues,
): AttemptCreate => ({
  route_variant_id: routeVariantId,
  occurred_at: new Date(values.occurredAt).toISOString(),
  attempts_count: values.attemptsCount,
  sent: values.sent,
  ...optionalFields(values),
});

/** Only the fields that differ from the saved attempt go into the PATCH. */
export const toAttemptPatch = (
  attempt: Attempt,
  values: AttemptFormValues,
): AttemptPatch => {
  const next = {
    attempts_count: values.attemptsCount,
    sent: values.sent,
    completion_fraction:
      values.completionPercent === null || values.sent
        ? attempt.completion_fraction
        : values.completionPercent / 100,
    perceived_effort: values.perceivedEffort ?? attempt.perceived_effort,
    notes: values.notes.trim() === '' ? attempt.notes : values.notes.trim(),
  };
  const patch: Record<string, unknown> = {};
  if (next.attempts_count !== attempt.attempts_count)
    patch.attempts_count = next.attempts_count;
  if (next.sent !== attempt.sent) patch.sent = next.sent;
  if (
    next.completion_fraction !== attempt.completion_fraction &&
    next.completion_fraction !== null
  )
    patch.completion_fraction = next.completion_fraction;
  if (
    next.perceived_effort !== attempt.perceived_effort &&
    next.perceived_effort !== null
  )
    patch.perceived_effort = next.perceived_effort;
  if (next.notes !== attempt.notes && next.notes !== null)
    patch.notes = next.notes;
  return patch as AttemptPatch;
};

export const ATTEMPT_FIELDS = [
  'occurred_at',
  'attempts_count',
  'sent',
  'completion_fraction',
  'perceived_effort',
  'notes',
] as const;
