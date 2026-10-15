import { ONBOARDING_STEPS, stepIndex, type StepId } from './steps';

export interface OnboardingProgressProps {
  readonly current: StepId;
}

/** Visible progress: numbered steps, the current one marked, done ones ticked. */
export const OnboardingProgress = ({ current }: OnboardingProgressProps) => {
  const currentIndex = stepIndex(current);
  return (
    <nav className="onboarding__progress" aria-label="Setup progress">
      <ol className="steps">
        {ONBOARDING_STEPS.map((step, index) => {
          const state =
            index < currentIndex
              ? 'done'
              : index === currentIndex
                ? 'current'
                : 'todo';
          return (
            <li
              key={step.id}
              className="steps__item"
              data-state={state}
              aria-current={state === 'current' ? 'step' : undefined}
            >
              <span className="steps__number" aria-hidden="true">
                {state === 'done' ? '✓' : index + 1}
              </span>
              <span className="steps__label">
                {step.label}
                {state === 'done' ? (
                  <span className="visually-hidden"> (done)</span>
                ) : null}
              </span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
};
