import { isApiError } from '../../api/errors';
import { useConsentDocument } from './useConsentDocument';

/** Split the consent text into blocks; a leading "# " line becomes a heading (no HTML injection). */
const toBlocks = (
  text: string,
): { kind: 'heading' | 'paragraph'; text: string }[] =>
  text
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter((block) => block.length > 0)
    .map((block) =>
      block.startsWith('# ')
        ? { kind: 'heading', text: block.slice(2) }
        : { kind: 'paragraph', text: block },
    );

const SKELETON_WIDTHS = ['55%', '95%', '88%', '70%'] as const;

/**
 * The current consent document, fetched through the typed client. Shown beside the
 * account step so people read what they are signing up to before the consent step asks.
 */
export const ConsentDocumentPanel = () => {
  const query = useConsentDocument();

  if (query.isPending) {
    return (
      <section
        className="sheet consent"
        aria-labelledby="consent-heading"
        aria-busy="true"
      >
        <h2 id="consent-heading">What you&apos;re agreeing to</h2>
        <p className="visually-hidden" role="status">
          Loading the consent document
        </p>
        <div className="consent__skeleton" aria-hidden="true">
          {SKELETON_WIDTHS.map((width) => (
            <span className="skeleton" style={{ width }} key={width} />
          ))}
        </div>
      </section>
    );
  }

  if (query.isError) {
    const error = query.error;
    const requestId = isApiError(error) ? error.requestId : null;
    return (
      <section className="sheet consent" aria-labelledby="consent-heading">
        <h2 id="consent-heading">What you&apos;re agreeing to</h2>
        <div className="banner banner--error" role="alert">
          <p className="banner__title">
            The consent text couldn&apos;t be loaded.
          </p>
          <p>Something went wrong on our side. Try again.</p>
          {requestId ? (
            <details>
              <summary>Details</summary>
              <p>Request id {requestId}</p>
            </details>
          ) : null}
        </div>
        <button
          type="button"
          className="button button--secondary"
          onClick={() => void query.refetch()}
        >
          Try again
        </button>
      </section>
    );
  }

  const doc = query.data;
  return (
    <section className="sheet consent" aria-labelledby="consent-heading">
      <div className="consent__header">
        <h2 id="consent-heading">What you&apos;re agreeing to</h2>
        <span className="stamp">Consent {doc.version}</span>
      </div>
      <div className="consent__text">
        {toBlocks(doc.text).map((block, index) =>
          block.kind === 'heading' ? (
            <h3 key={index}>{block.text}</h3>
          ) : (
            <p key={index}>{block.text}</p>
          ),
        )}
      </div>
      <dl className="consent__meta">
        <dt>Scopes offered</dt>
        <dd>{doc.scopes_available.join(', ')}</dd>
        <dt>Text digest</dt>
        <dd>
          <code title={doc.text_sha256}>{doc.text_sha256.slice(0, 12)}…</code>
        </dd>
      </dl>
    </section>
  );
};
