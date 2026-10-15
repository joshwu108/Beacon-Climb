import { useEffect, useRef } from 'react';
import { ONBOARDING_STEPS, stepIndex, type StepId } from './steps';

export interface StepHeadingProps {
  readonly step: StepId;
  readonly id?: string;
}

/**
 * Step title (h2 under the page h1). It takes programmatic focus on every step change
 * so keyboard and screen-reader users land on "Step n of 4, <title>".
 */
export const StepHeading = ({
  step,
  id = 'step-heading',
}: StepHeadingProps) => {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus({ preventScroll: false });
  }, [step]);
  const definition = ONBOARDING_STEPS[stepIndex(step)];
  return (
    <h2 id={id} tabIndex={-1} ref={ref} className="step-heading">
      <span className="onboarding__step-count">
        Step {stepIndex(step) + 1} of {ONBOARDING_STEPS.length}
      </span>
      {definition.title}
    </h2>
  );
};
