import { formatDateTime } from '@web/lib/format';
import { router } from 'expo-router';
import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../../api/errors';
import { ApiErrorBanner } from '../../components/ui/ApiErrorBanner';
import { AppText } from '../../components/ui/AppText';
import { Button } from '../../components/ui/Button';
import { GRADE_COLUMN, GradeMark } from '../../components/ui/GradeMark';
import { Stamp } from '../../components/ui/Stamp';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, SPACE } from '../../theme/theme';
import { usePatchAttempt, type Attempt } from './useAttempts';

/** One logbook line: when, what, how it went; Correct opens the form, Retract confirms inline. */
export const AttemptRow = ({ attempt }: { attempt: Attempt }) => {
  const { color } = useTheme();
  const patch = usePatchAttempt();
  const [confirming, setConfirming] = useState(false);
  const retracted = attempt.status === 'RETRACTED';
  const conflict =
    isApiError(patch.error) && patch.error.code === 'REVISION_CONFLICT';

  const facts = [
    `${attempt.attempts_count} attempt${attempt.attempts_count === 1 ? '' : 's'}`,
    attempt.perceived_effort !== null
      ? `effort ${attempt.perceived_effort}/10`
      : null,
    !attempt.sent && attempt.completion_fraction !== null
      ? `${Math.round(attempt.completion_fraction * 100)}% of the route`
      : null,
  ].filter(Boolean);

  return (
    <View
      style={[
        styles.row,
        { borderBottomColor: color.borderSubtle },
        retracted && styles.retracted,
      ]}
      testID={`attempt-${attempt.attempt_id}`}
    >
      <View style={styles.top}>
        <GradeMark route={attempt.route} size="row" />
        <View style={styles.text}>
          <AppText variant="small" tone="muted">
            {formatDateTime(attempt.occurred_at)}
          </AppText>
          <AppText variant="label" numberOfLines={2}>
            {attempt.route.name}
          </AppText>
          <View style={styles.stamps}>
            {retracted ? (
              <Stamp label="Retracted" tone="warning" />
            ) : (
              <Stamp label={attempt.sent ? 'Sent' : 'Not yet'} />
            )}
            <AppText variant="small" tone="muted">
              {facts.join(' · ')}
            </AppText>
          </View>
          {attempt.notes ? (
            <AppText variant="small" style={styles.notes}>
              “{attempt.notes}”
            </AppText>
          ) : null}
        </View>
      </View>
      {patch.isError ? (
        <ApiErrorBanner
          error={patch.error}
          title={
            conflict
              ? 'This attempt changed elsewhere. Pull to refresh, then try again.'
              : 'The change wasn’t saved.'
          }
        />
      ) : null}
      {!retracted ? (
        confirming ? (
          <View style={styles.actions}>
            <AppText variant="small" style={styles.grow}>
              Retract this attempt? It stays in your log, marked retracted.
            </AppText>
            <Button
              label="Retract"
              compact
              busy={patch.isPending}
              accessibilityLabel={`Confirm retracting the attempt on ${attempt.route.name}`}
              onPress={() =>
                patch.mutate(
                  {
                    attemptId: attempt.attempt_id,
                    revision: attempt.revision,
                    patch: { retract: true },
                  },
                  { onSettled: () => setConfirming(false) },
                )
              }
            />
            <Button
              label="Keep"
              variant="quiet"
              compact
              onPress={() => setConfirming(false)}
            />
          </View>
        ) : (
          <View style={styles.actions}>
            <Button
              label="Correct"
              variant="secondary"
              compact
              accessibilityLabel={`Correct the attempt on ${attempt.route.name}`}
              onPress={() =>
                router.push(
                  `/log-attempt?attempt_id=${encodeURIComponent(attempt.attempt_id)}`,
                )
              }
            />
            <Button
              label="Retract"
              variant="quiet"
              compact
              accessibilityLabel={`Retract the attempt on ${attempt.route.name}`}
              onPress={() => setConfirming(true)}
            />
          </View>
        )
      ) : null}
    </View>
  );
};

const styles = StyleSheet.create({
  row: {
    paddingVertical: SPACE.md,
    gap: SPACE.sm,
    borderBottomWidth: BORDER_WIDTH,
  },
  retracted: { opacity: 0.6 },
  top: { flexDirection: 'row', gap: SPACE.md, alignItems: 'flex-start' },
  text: { flex: 1, gap: 2 },
  stamps: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
    marginTop: 2,
  },
  notes: { marginTop: SPACE.xxs, fontStyle: 'italic' },
  actions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
    paddingLeft: GRADE_COLUMN + SPACE.md,
  },
  grow: { flex: 1, minWidth: 160 },
});
