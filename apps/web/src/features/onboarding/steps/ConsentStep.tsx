import { useId, useState, type FormEvent } from 'react';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { ConsentDocumentPanel } from '../ConsentDocumentPanel';
import { StepHeading } from '../StepHeading';
import { useConsentDocument } from '../useConsentDocument';
import { useRecordConsent } from '../useOnboardingMutations';

export interface ConsentStepProps {
  readonly onRecorded: () => void;
}

/**
 * Step 2: explicit consent. The versioned document is shown in full (text nodes only);
 * the required service scope is an unchecked checkbox that gates the button; research
 * is a separate opt-in, off by default. Sends {consent_version, scopes, text_sha256}
 * taken from the document that was displayed.
 */
export const ConsentStep = ({ onRecorded }: ConsentStepProps) => {
  const ids = useId();
  const document = useConsentDocument();
  const record = useRecordConsent();
  const [service, setService] = useState(false);
  const [research, setResearch] = useState(false);

  const doc = document.data;
  const offersResearch = doc?.scopes_available.includes('research') ?? false;
  const canSubmit = doc !== undefined && service && !record.isPending;

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (doc === undefined || !service) return;
    record.mutate(
      {
        consent_version: doc.version,
        scopes: { service: true, research: offersResearch ? research : false },
        text_sha256: doc.text_sha256,
      },
      { onSuccess: onRecorded },
    );
  };

  return (
    <form
      className="sheet onboarding__form"
      onSubmit={submit}
      aria-labelledby="step-heading"
      aria-busy={record.isPending}
      noValidate
    >
      <StepHeading step="consent" />
      <p className="field__hint">
        Read the text, then tick the box that applies. Nothing is pre-ticked.
        You can revoke consent later from your account.
      </p>

      <ConsentDocumentPanel as="div" />

      {record.isError ? <ApiErrorBanner error={record.error} /> : null}

      <fieldset className="choice-group">
        <legend className="field__label">Your choice</legend>
        <label className="choice" htmlFor={`${ids}-service`}>
          <input
            id={`${ids}-service`}
            type="checkbox"
            name="service"
            checked={service}
            onChange={(e) => setService(e.target.checked)}
            aria-describedby={`${ids}-service-hint`}
            required
          />
          <span className="choice__body">
            <span className="choice__title">
              I agree to the service use described above
              {doc ? ` (consent ${doc.version})` : ''}
            </span>
            <span className="choice__hint" id={`${ids}-service-hint`}>
              Required to log attempts and ask for recommendations.
            </span>
          </span>
        </label>
        {offersResearch ? (
          <label className="choice" htmlFor={`${ids}-research`}>
            <input
              id={`${ids}-research`}
              type="checkbox"
              name="research"
              checked={research}
              onChange={(e) => setResearch(e.target.checked)}
              aria-describedby={`${ids}-research-hint`}
            />
            <span className="choice__body">
              <span className="choice__title">
                Also allow research use of my pseudonymous history
              </span>
              <span className="choice__hint" id={`${ids}-research-hint`}>
                Optional. Off unless you tick it.
              </span>
            </span>
          </label>
        ) : null}
      </fieldset>

      <div className="onboarding__actions">
        <button type="submit" className="button" disabled={!canSubmit}>
          {record.isPending ? 'Recording' : 'Record my choice and continue'}
        </button>
        {!service ? (
          <p className="field__hint" id={`${ids}-gate`}>
            Tick the service box to continue.
          </p>
        ) : null}
      </div>
    </form>
  );
};
