import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { toConsentBlocks } from '@web/features/onboarding/consent-blocks';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { AppText } from '../../../components/ui/AppText';
import { Button } from '../../../components/ui/Button';
import { Choice } from '../../../components/ui/Choice';
import { Sheet } from '../../../components/ui/Sheet';
import { Skeleton } from '../../../components/ui/Skeleton';
import { Stamp } from '../../../components/ui/Stamp';
import { SPACE } from '../../../theme/theme';
import { useConsentDocument, useRecordConsent } from '../useOnboarding';

/**
 * Step 2: the versioned consent text in full (text nodes only), an unticked required
 * service box that gates the button, and a separate research opt-in, off by default.
 */
export const ConsentStep = ({ onRecorded }: { onRecorded: () => void }) => {
  const document = useConsentDocument();
  const record = useRecordConsent();
  const [service, setService] = useState(false);
  const [research, setResearch] = useState(false);
  const doc = document.data;
  const offersResearch = doc?.scopes_available.includes('research') ?? false;

  const submit = () => {
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
    <View style={styles.form}>
      <AppText tone="muted">
        Read the text, then tick what applies. Nothing is pre-ticked; you can
        revoke consent later.
      </AppText>
      <Sheet elevation="flat">
        {document.isPending ? (
          <Skeleton label="Loading the consent text" />
        ) : null}
        {document.isError ? (
          <ApiErrorBanner
            error={document.error}
            title="The consent text couldn't be loaded."
            onRetry={() => void document.refetch()}
          />
        ) : null}
        {doc ? (
          <>
            <Stamp label={`Consent ${doc.version}`} />
            {toConsentBlocks(doc.text).map((block, index) =>
              block.kind === 'heading' ? (
                <AppText key={index} variant="title" accessibilityRole="header">
                  {block.text}
                </AppText>
              ) : block.kind === 'list' ? (
                <View key={index} style={styles.list}>
                  {block.items.map((item, i) => (
                    <AppText key={i} variant="small">
                      • {item}
                    </AppText>
                  ))}
                </View>
              ) : (
                <AppText key={index} variant="small">
                  {block.text}
                </AppText>
              ),
            )}
          </>
        ) : null}
      </Sheet>
      {record.isError ? <ApiErrorBanner error={record.error} /> : null}
      <Choice
        kind="checkbox"
        label={`I agree to the service use described above${doc ? ` (consent ${doc.version})` : ''}`}
        hint="Required to log attempts and ask for recommendations."
        selected={service}
        onPress={() => setService(!service)}
        testID="consent-service"
      />
      {offersResearch ? (
        <Choice
          kind="checkbox"
          label="Also allow research use of my pseudonymous history"
          hint="Optional. Off unless you tick it."
          selected={research}
          onPress={() => setResearch(!research)}
          testID="consent-research"
        />
      ) : null}
      <Button
        label="Record my choice and continue"
        onPress={submit}
        disabled={doc === undefined || !service}
        busy={record.isPending}
        accessibilityHint={service ? undefined : 'Tick the service box first'}
        testID="consent-submit"
      />
      {!service ? (
        <AppText variant="small" tone="muted">
          Tick the service box to continue.
        </AppText>
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  form: { gap: SPACE.md },
  list: { gap: SPACE.xxs },
});
