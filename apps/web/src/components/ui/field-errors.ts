import { isApiError } from '../../api/errors';

export type FieldErrorMap = Readonly<Record<string, string>>;

/** Pick the envelope field_errors for the given fields: { field: message }. */
export const fieldErrorsFrom = (
  error: unknown,
  fields: readonly string[],
): FieldErrorMap => {
  if (!isApiError(error)) return {};
  const byField = error.fieldErrorsByField();
  return Object.fromEntries(
    fields.flatMap((field) => {
      const fe = byField.get(field);
      return fe === undefined
        ? []
        : [[field, fe.message || 'Check this answer and try again.']];
    }),
  );
};

export const hasFieldErrors = (map: FieldErrorMap): boolean =>
  Object.keys(map).length > 0;
