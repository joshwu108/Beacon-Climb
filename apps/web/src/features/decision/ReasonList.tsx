import {
  describeReasonCode,
  describeUncertainty,
  NO_ESTIMATE_COPY,
} from './reason-codes';

export interface ReasonListProps {
  readonly codes: readonly string[];
  /** Show the one-sentence explanation under each label (decision detail). */
  readonly detailed?: boolean;
}

/** Reason codes as real text in a list, so the "why" is readable without the board. */
export const ReasonList = ({ codes, detailed = false }: ReasonListProps) =>
  codes.length === 0 ? (
    <p className="field__hint">No reasons were recorded for this route.</p>
  ) : (
    <ul className={`reasons${detailed ? ' reasons--detailed' : ''}`}>
      {codes.map((code) => {
        const copy = describeReasonCode(code);
        return (
          <li className="reasons__item" key={code}>
            <span className="reasons__label">{copy.label}</span>
            {detailed && copy.explanation ? (
              <span className="reasons__explanation">{copy.explanation}</span>
            ) : null}
          </li>
        );
      })}
    </ul>
  );

export interface EstimateLineProps {
  readonly probability: number | null;
  readonly uncertaintyLabel: string;
}

/** predicted_send_probability is null in v1; a number is never rendered as a chance here. */
export const EstimateLine = ({
  probability,
  uncertaintyLabel,
}: EstimateLineProps) => (
  <p className="estimate">
    <span className="estimate__text">
      {probability === null
        ? NO_ESTIMATE_COPY
        : 'Estimate recorded; not shown in this version'}
    </span>{' '}
    <span className="stamp">{describeUncertainty(uncertaintyLabel)}</span>
  </p>
);
