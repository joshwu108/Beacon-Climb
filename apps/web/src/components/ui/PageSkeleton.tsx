import type { ReactNode } from 'react';

export interface PageSkeletonProps {
  readonly eyebrow: string;
  readonly title: string;
  readonly lede: string;
  /** Ticket that builds the real screen; shown as a small status line. */
  readonly plannedIn: string;
  readonly children?: ReactNode;
}

const SHEET_LINE_WIDTHS = ['72%', '48%', '60%'] as const;

/**
 * Placeholder screen: real heading outline, a lede and three fixed-height sheets so the
 * layout a feature will occupy exists before the feature does (no shift on arrival).
 */
export const PageSkeleton = ({
  eyebrow,
  title,
  lede,
  plannedIn,
  children,
}: PageSkeletonProps) => (
  <article className="page">
    <p className="page__eyebrow">{eyebrow}</p>
    <h1>{title}</h1>
    <p className="page__lede">{lede}</p>
    {children}
    <section
      className="page__placeholder"
      aria-label="Screen under construction"
    >
      {SHEET_LINE_WIDTHS.map((width, index) => (
        <div className="sheet" key={index} aria-hidden="true">
          <span className="skeleton" style={{ width }} />
          <span className="skeleton" style={{ width: '90%' }} />
          <span className="skeleton" style={{ width: '35%' }} />
        </div>
      ))}
      <p className="page__status">This screen is being built in {plannedIn}.</p>
    </section>
  </article>
);
