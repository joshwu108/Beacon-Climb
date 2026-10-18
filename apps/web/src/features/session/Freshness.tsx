import { formatClock, formatDateTime } from '../../lib/format';
import type { RecommendationResponse } from './useRecommendations';

export type FeaturesSource = RecommendationResponse['features_source'];

export interface FreshnessProps {
  /** `feature_as_of`: the newest attempt the features considered; null/absent on a cold start. */
  readonly featureAsOf: string | null | undefined;
  readonly source: FeaturesSource;
}

const SOURCE_COPY: Record<
  FeaturesSource,
  { readonly suffix: string; readonly spoken: string }
> = {
  STREAM: { suffix: 'live', spoken: 'from the live stream' },
  SYNC: { suffix: 'recomputed', spoken: 'recomputed on request' },
};

/** The source as plain words (shared with the decision ledger). */
export const describeFeaturesSource = (source: FeaturesSource): string =>
  SOURCE_COPY[source].spoken;

/* Two glyphs that read without colour: a dot inside a ring (live signal) for STREAM and a
   circular arrow (recompute) for SYNC. Stroke follows currentColor. */
const Glyph = ({ source }: { source: FeaturesSource }) => (
  <svg
    className="freshness__glyph"
    viewBox="0 0 16 16"
    width="12"
    height="12"
    aria-hidden="true"
    focusable="false"
    data-glyph={source === 'STREAM' ? 'signal' : 'cycle'}
  >
    {source === 'STREAM' ? (
      <>
        <circle cx="8" cy="8" r="2.4" fill="currentColor" />
        <circle
          cx="8"
          cy="8"
          r="6"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeDasharray="4 2.3"
        />
      </>
    ) : (
      <>
        <path
          d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
        <path d="M13.8 1.8v3.4h-3.4z" fill="currentColor" />
      </>
    )}
  </svg>
);

/**
 * Freshness badge (BC-0310): "features as of hh:mm" in local 24 h time plus a glyph and a
 * one-word suffix that tell the two sources apart without colour. The accessible name
 * carries the full date-time and the source; the visible parts are hidden from assistive
 * tech so the note is read once.
 */
export const Freshness = ({ featureAsOf, source }: FreshnessProps) => {
  const copy = SOURCE_COPY[source];
  const hasSnapshot = typeof featureAsOf === 'string' && featureAsOf !== '';
  const label = hasSnapshot
    ? `Features as of ${formatDateTime(featureAsOf)}, ${copy.spoken}.`
    : `No attempts yet, so no feature snapshot; ${copy.spoken}.`;
  return (
    <span
      className={`freshness freshness--${source.toLowerCase()}`}
      role="note"
      aria-label={label}
    >
      <Glyph source={source} />
      <span className="freshness__text" aria-hidden="true">
        {hasSnapshot ? (
          <>
            features as of{' '}
            <time className="freshness__clock" dateTime={featureAsOf}>
              {formatClock(featureAsOf)}
            </time>
          </>
        ) : (
          'no attempts yet'
        )}
      </span>
      <span className="freshness__suffix" aria-hidden="true">
        {copy.suffix}
      </span>
    </span>
  );
};
