import { useId, useRef, useState, type FormEvent } from 'react';
import { isApiError } from '../../../api/errors';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { FieldError, describedBy } from '../../../components/ui/FieldError';
import {
  fieldErrorsFrom,
  hasFieldErrors,
  type FieldErrorMap,
} from '../../../components/ui/field-errors';
import {
  useAuthenticate,
  type AuthMode,
  type AuthResponse,
} from '../../auth/useAuthenticate';
import { StepHeading } from '../StepHeading';

export interface AccountStepProps {
  readonly onAuthenticated: (auth: AuthResponse) => void;
}

const USERNAME_RE = /^[a-z0-9_]{3,32}$/;
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;
const FIELDS = ['username', 'password'] as const;

const validate = (username: string, password: string): FieldErrorMap => ({
  ...(USERNAME_RE.test(username)
    ? {}
    : {
        username: 'Use 3 to 32 lowercase letters, digits or underscores.',
      }),
  ...(password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX
    ? {}
    : { password: `Use ${PASSWORD_MIN} to ${PASSWORD_MAX} characters.` }),
});

const COPY: Record<AuthMode, { submit: string; busy: string; hint: string }> = {
  register: {
    submit: 'Create account',
    busy: 'Creating account',
    hint: 'Lowercase letters, digits and underscores, 3 to 32 characters. It is the only identifier Beacon keeps.',
  },
  'sign-in': {
    submit: 'Sign in',
    busy: 'Signing in',
    hint: 'Sign in with the username and password you chose before.',
  },
};

/**
 * Step 1: pseudonymous account. Registers by default; a 409 USERNAME_TAKEN offers to
 * switch the same form to sign-in (POST /v1/auth/session). 422 field errors map onto
 * the fields; 429 and everything else are shown at form level with the request id.
 */
export const AccountStep = ({ onAuthenticated }: AccountStepProps) => {
  const ids = useId();
  const [mode, setMode] = useState<AuthMode>('register');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [clientErrors, setClientErrors] = useState<FieldErrorMap>({});
  const passwordRef = useRef<HTMLInputElement>(null);
  const authenticate = useAuthenticate();

  const serverErrors = fieldErrorsFrom(authenticate.error, FIELDS);
  const errors: FieldErrorMap = { ...serverErrors, ...clientErrors };
  const isUsernameTaken =
    isApiError(authenticate.error) &&
    authenticate.error.code === 'USERNAME_TAKEN';
  const showBanner =
    authenticate.isError && !hasFieldErrors(serverErrors) && !isUsernameTaken;

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const problems = validate(username, password);
    setClientErrors(problems);
    if (hasFieldErrors(problems)) return;
    authenticate.mutate(
      { mode, username, password },
      { onSuccess: onAuthenticated },
    );
  };

  const switchToSignIn = (): void => {
    setMode('sign-in');
    authenticate.reset();
    passwordRef.current?.focus();
  };

  const copy = COPY[mode];
  const usernameErrorId = `${ids}-username-error`;
  const passwordErrorId = `${ids}-password-error`;
  const passwordHintId = `${ids}-password-hint`;

  return (
    <form
      className="sheet onboarding__form"
      onSubmit={submit}
      aria-labelledby="step-heading"
      aria-busy={authenticate.isPending}
      noValidate
    >
      <StepHeading step="account" />
      <p className="field__hint">{copy.hint}</p>

      {showBanner ? <ApiErrorBanner error={authenticate.error} /> : null}

      <div className="field">
        <label className="field__label" htmlFor={`${ids}-username`}>
          Username
        </label>
        <input
          className="field__input"
          id={`${ids}-username`}
          name="username"
          type="text"
          autoComplete="username"
          autoCapitalize="none"
          spellCheck={false}
          inputMode="text"
          value={username}
          onChange={(e) => {
            setUsername(e.target.value);
            if (clientErrors.username)
              setClientErrors({ ...clientErrors, username: '' });
          }}
          aria-invalid={Boolean(errors.username) || isUsernameTaken}
          aria-describedby={describedBy(
            (Boolean(errors.username) || isUsernameTaken) && usernameErrorId,
          )}
        />
        {isUsernameTaken ? (
          <p className="field__error" id={usernameErrorId} role="alert">
            That username is already registered.{' '}
            <button
              type="button"
              className="link-button"
              onClick={switchToSignIn}
            >
              Sign in instead
            </button>
          </p>
        ) : (
          <FieldError id={usernameErrorId} message={errors.username} />
        )}
      </div>

      <div className="field">
        <label className="field__label" htmlFor={`${ids}-password`}>
          Password
        </label>
        <input
          className="field__input"
          id={`${ids}-password`}
          name="password"
          type="password"
          ref={passwordRef}
          autoComplete={
            mode === 'register' ? 'new-password' : 'current-password'
          }
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
            if (clientErrors.password)
              setClientErrors({ ...clientErrors, password: '' });
          }}
          aria-invalid={Boolean(errors.password)}
          aria-describedby={describedBy(
            passwordHintId,
            Boolean(errors.password) && passwordErrorId,
          )}
        />
        <p className="field__hint" id={passwordHintId}>
          {PASSWORD_MIN} to {PASSWORD_MAX} characters.
        </p>
        <FieldError id={passwordErrorId} message={errors.password} />
      </div>

      <div className="onboarding__actions">
        <button
          type="submit"
          className="button"
          disabled={authenticate.isPending}
        >
          {authenticate.isPending ? copy.busy : copy.submit}
        </button>
        {mode === 'register' ? (
          <p className="field__hint">
            Already have one?{' '}
            <button
              type="button"
              className="link-button"
              onClick={switchToSignIn}
            >
              Sign in instead
            </button>
          </p>
        ) : (
          <p className="field__hint">
            New here?{' '}
            <button
              type="button"
              className="link-button"
              onClick={() => {
                setMode('register');
                authenticate.reset();
              }}
            >
              Create an account instead
            </button>
          </p>
        )}
      </div>
    </form>
  );
};
