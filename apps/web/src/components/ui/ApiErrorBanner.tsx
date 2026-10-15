import { describeApiError } from './api-error-copy';

export interface ApiErrorBannerProps {
  readonly error: unknown;
  readonly title?: string;
  readonly onRetry?: () => void;
  readonly retryLabel?: string;
  readonly id?: string;
}

/**
 * Form- or zone-level error from an ErrorEnvelope: tone plus text, never colour alone,
 * with the request id in small print for support (journeys.md §2). A `title` names the
 * zone ("Routes couldn't be loaded.") and the status-specific copy becomes the detail.
 */
export const ApiErrorBanner = ({
  error,
  title,
  onRetry,
  retryLabel = 'Try again',
  id,
}: ApiErrorBannerProps) => {
  const copy = describeApiError(error);
  const heading = title ?? copy.title;
  const detail = title ? `${copy.title} ${copy.detail}` : copy.detail;
  return (
    <div className="banner banner--error" role="alert" id={id}>
      <p className="banner__title">{heading}</p>
      <p>{detail}</p>
      {copy.requestId ? (
        <p className="banner__meta">Request id {copy.requestId}</p>
      ) : null}
      {onRetry ? (
        <button
          type="button"
          className="button button--secondary banner__action"
          onClick={onRetry}
        >
          {retryLabel}
        </button>
      ) : null}
    </div>
  );
};
