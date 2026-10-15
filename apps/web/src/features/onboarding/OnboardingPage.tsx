import { type FormEvent } from 'react';
import { ConsentDocumentPanel } from './ConsentDocumentPanel';
import './onboarding.css';

export const ONBOARDING_STEPS = [
  { id: 'account', label: 'Account' },
  { id: 'consent', label: 'Consent' },
  { id: 'board', label: 'Board and angle' },
  { id: 'goals', label: 'Level and goals' },
] as const;

const preventSubmit = (event: FormEvent<HTMLFormElement>): void => {
  // Account creation (registerUser / createSession) is wired in BC-0115.
  event.preventDefault();
};

/**
 * Onboarding shell showing step 1 (account). Heading outline, progress nav and the
 * consent document round trip exist; the step logic is BC-0115.
 */
export const OnboardingPage = () => (
  <article className="page onboarding">
    <div className="onboarding__header">
      <p className="page__eyebrow">First run</p>
      <h1>Set up Beacon Climb</h1>
      <p className="page__lede">
        A pseudonymous account, a clear choice about logging, and the board you
        climb on. Nothing about your body is asked.
      </p>
    </div>

    <nav className="onboarding__progress" aria-label="Setup progress">
      <ol className="steps">
        {ONBOARDING_STEPS.map((step, index) => (
          <li
            key={step.id}
            className="steps__item"
            aria-current={index === 0 ? 'step' : undefined}
          >
            <span className="steps__number" aria-hidden="true">
              {index + 1}
            </span>
            <span className="steps__label">{step.label}</span>
          </li>
        ))}
      </ol>
    </nav>

    <div className="onboarding__columns">
      <form
        className="sheet onboarding__form"
        onSubmit={preventSubmit}
        aria-labelledby="step-heading"
        noValidate
      >
        <h2 id="step-heading" tabIndex={-1}>
          <span className="onboarding__step-count">
            Step 1 of {ONBOARDING_STEPS.length}
          </span>
          Choose a username
        </h2>
        <p className="field__hint">
          Lowercase letters, digits and underscores, 3 to 32 characters. It is
          the only identifier Beacon keeps.
        </p>

        <div className="field">
          <label className="field__label" htmlFor="username">
            Username
          </label>
          <input
            className="field__input"
            id="username"
            name="username"
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            pattern="[a-z0-9_]{3,32}"
            inputMode="text"
          />
        </div>

        <div className="field">
          <label className="field__label" htmlFor="password">
            Password
          </label>
          <input
            className="field__input"
            id="password"
            name="password"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={128}
            aria-describedby="password-hint"
          />
          <p className="field__hint" id="password-hint">
            8 to 128 characters.
          </p>
        </div>

        <div className="onboarding__actions">
          <button type="submit" className="button">
            Continue
          </button>
          <p className="field__hint">
            Account creation is connected in the next build.
          </p>
        </div>
      </form>

      <aside className="onboarding__aside" aria-label="Consent document">
        <ConsentDocumentPanel />
      </aside>
    </div>
  </article>
);
