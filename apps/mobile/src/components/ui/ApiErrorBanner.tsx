import { describeApiError } from '@web/components/ui/api-error-copy';
import { AppText } from './AppText';
import { Banner } from './Banner';

export interface ApiErrorBannerProps {
  readonly error: unknown;
  readonly title?: string;
  readonly onRetry?: () => void;
}

/** A failed request in plain words (apps/web's copy table), with the request id to quote. */
export const ApiErrorBanner = ({
  error,
  title,
  onRetry,
}: ApiErrorBannerProps) => {
  const copy = describeApiError(error, title);
  return (
    <Banner
      tone="error"
      title={title ?? copy.title}
      actionLabel={onRetry ? 'Try again' : undefined}
      onAction={onRetry}
    >
      <AppText variant="small">
        {title ? `${copy.title} ${copy.detail}` : copy.detail}
      </AppText>
      {copy.requestId ? (
        <AppText variant="small" tone="muted" selectable>
          Request id {copy.requestId}
        </AppText>
      ) : null}
    </Banner>
  );
};
