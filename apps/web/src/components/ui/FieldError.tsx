export interface FieldErrorProps {
  readonly id: string;
  readonly message: string | null | undefined;
}

/** Inline field message, announced on arrival. Rendered only while there is a message. */
export const FieldError = ({ id, message }: FieldErrorProps) =>
  message ? (
    <p className="field__error" id={id} role="alert">
      {message}
    </p>
  ) : null;

/** Join the ids that apply (hint, error) into one aria-describedby value, or undefined. */
export const describedBy = (
  ...ids: readonly (string | false | null | undefined)[]
): string | undefined => {
  const present = ids.filter((id): id is string => Boolean(id));
  return present.length > 0 ? present.join(' ') : undefined;
};
