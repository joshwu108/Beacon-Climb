import { useQuery } from '@tanstack/react-query';
import { fieldErrorsFrom } from '@web/components/ui/field-errors';
import {
  ATTEMPT_FIELDS,
  EFFORT_MAX,
  EFFORT_MIN,
  NOTES_MAX,
  defaultValues,
  toAttemptCreate,
  toAttemptPatch,
  toLocalInputValue,
  validateAttempt,
  valuesFromAttempt,
  type AttemptFormValues,
} from '@web/features/attempts/attempt-form';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { isApiError } from '../api/errors';
import { BackBar } from '../components/shell/BackBar';
import { ApiErrorBanner } from '../components/ui/ApiErrorBanner';
import { AppText } from '../components/ui/AppText';
import { Banner } from '../components/ui/Banner';
import { Button } from '../components/ui/Button';
import { Chips } from '../components/ui/Chips';
import { Choice } from '../components/ui/Choice';
import { GradeMark } from '../components/ui/GradeMark';
import { Screen } from '../components/ui/Screen';
import { Skeleton } from '../components/ui/Skeleton';
import { Stepper } from '../components/ui/Stepper';
import { TextField } from '../components/ui/TextField';
import {
  fetchAttempt,
  useCreateAttempt,
  usePatchAttempt,
} from '../features/attempts/useAttempts';
import { useRoute } from '../features/explore/useRoutes';
import {
  sessionStore,
  withAttemptLogged,
} from '../features/session/session-store';
import { useTheme } from '../theme/ThemeProvider';
import { SPACE } from '../theme/theme';

const MAX_ATTEMPTS = 50;
/** "When" as quick offsets instead of a date picker: the climber is standing at the board. */
const WHEN_OPTIONS = [
  { value: 0, label: 'Now' },
  { value: 15, label: '15 min ago' },
  { value: 60, label: '1 h ago' },
  { value: 180, label: '3 h ago' },
] as const;
const PROGRESS_OPTIONS = [
  { value: -1, label: 'Skip' },
  { value: 25, label: '¼' },
  { value: 50, label: '½' },
  { value: 75, label: '¾' },
  { value: 90, label: 'Almost' },
] as const;
const EFFORT_OPTIONS = Array.from(
  { length: EFFORT_MAX - EFFORT_MIN + 1 },
  (_, i) => ({
    value: EFFORT_MIN + i,
    label: String(EFFORT_MIN + i),
    accessibilityLabel: `Effort ${EFFORT_MIN + i} of ${EFFORT_MAX}`,
  }),
);

const minutesAgo = (minutes: number): string =>
  toLocalInputValue(new Date(Date.now() - minutes * 60_000));

/**
 * Log an attempt (POST, one Idempotency-Key per form) or correct one (?attempt_id=, PATCH
 * with If-Match). Sent, attempts and the 1-10 effort rating up front; progress and notes are
 * optional and omitted when untouched. A successful log marks the session lineup for refresh.
 */
export default function LogAttempt() {
  const { color } = useTheme();
  const params = useLocalSearchParams<{
    route_variant_id?: string;
    attempt_id?: string;
  }>();
  const attemptId = params.attempt_id ?? null;
  const existing = useQuery({
    queryKey: ['attempts', 'detail', attemptId],
    queryFn: () => fetchAttempt(attemptId!),
    enabled: attemptId !== null,
  });
  const routeVariantId =
    params.route_variant_id ?? existing.data?.route_variant_id ?? '';
  const route = useRoute(routeVariantId);
  const create = useCreateAttempt();
  const patch = usePatchAttempt();
  const [values, setValues] = useState<AttemptFormValues>(() =>
    defaultValues(),
  );
  const [when, setWhen] = useState<number>(0);
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    if (existing.data) setValues(valuesFromAttempt(existing.data));
  }, [existing.data]);

  const isEdit = attemptId !== null;
  const mutation = isEdit ? patch : create;
  const serverErrors = fieldErrorsFrom(mutation.error, ATTEMPT_FIELDS);
  const errors: Record<string, string> = { ...serverErrors, ...clientErrors };
  const conflict =
    isApiError(patch.error) && patch.error.code === 'REVISION_CONFLICT';
  const routeName =
    route.data?.name ?? existing.data?.route.name ?? 'this route';
  const set = (next: Partial<AttemptFormValues>) =>
    setValues((v) => ({ ...v, ...next }));

  const submit = () => {
    const problems = validateAttempt(values, { requireTime: !isEdit });
    setClientErrors(problems);
    if (Object.keys(problems).length > 0 || routeVariantId === '') return;
    if (isEdit && existing.data) {
      const body = toAttemptPatch(existing.data, values);
      if (Object.keys(body).length === 0) return router.back();
      patch.mutate(
        {
          attemptId: existing.data.attempt_id,
          revision: existing.data.revision,
          patch: body,
        },
        { onSuccess: () => router.back() },
      );
      return;
    }
    create.mutate(toAttemptCreate(routeVariantId, values), {
      onSuccess: () => {
        sessionStore.setState((s) => withAttemptLogged(s, routeName));
        router.back();
      },
    });
  };

  if (routeVariantId === '' && !existing.isPending) {
    return (
      <View style={[styles.fill, { backgroundColor: color.surface }]}>
        <BackBar />
        <Screen underHeader eyebrow="Log" title="Pick a route first">
          <AppText tone="muted">
            Open a route from your session or Explore, then log it.
          </AppText>
        </Screen>
      </View>
    );
  }

  const showBanner =
    mutation.isError && !conflict && Object.keys(serverErrors).length === 0;

  return (
    <View
      style={[styles.fill, { backgroundColor: color.surface }]}
      testID="log-attempt"
    >
      <BackBar context={isEdit ? 'Correct an attempt' : 'Log an attempt'} />
      <Screen
        underHeader
        eyebrow={isEdit ? 'Correct' : 'Log'}
        title={route.data?.name ?? existing.data?.route.name ?? 'Loading route'}
        aside={
          route.data ? <GradeMark route={route.data} size="row" /> : undefined
        }
      >
        {(isEdit && existing.isPending) || route.isPending ? (
          <Skeleton label="Loading" />
        ) : null}
        {existing.isError ? (
          <ApiErrorBanner
            error={existing.error}
            onRetry={() => void existing.refetch()}
          />
        ) : null}

        <View
          style={styles.group}
          accessibilityRole="radiogroup"
          accessibilityLabel="Did you send it?"
        >
          <AppText variant="title">Did you send it?</AppText>
          <View style={styles.pair}>
            <View style={styles.half}>
              <Choice
                kind="radio"
                label="Sent"
                selected={values.sent}
                onPress={() => set({ sent: true })}
                testID="sent-yes"
              />
            </View>
            <View style={styles.half}>
              <Choice
                kind="radio"
                label="Not yet"
                selected={!values.sent}
                onPress={() => set({ sent: false })}
                testID="sent-no"
              />
            </View>
          </View>
        </View>

        <Stepper
          label="Attempts"
          value={values.attemptsCount}
          min={1}
          max={MAX_ATTEMPTS}
          onChange={(attemptsCount) => set({ attemptsCount })}
          testID="attempts"
        />
        {errors.attempts_count ? (
          <AppText variant="small" tone="error">
            {errors.attempts_count}
          </AppText>
        ) : null}

        <Chips
          label="Effort (1 easy – 10 maximal), optional"
          options={EFFORT_OPTIONS}
          value={values.perceivedEffort}
          onChange={(perceivedEffort) =>
            set({
              perceivedEffort:
                perceivedEffort === values.perceivedEffort
                  ? null
                  : perceivedEffort,
            })
          }
          testIDPrefix="effort"
          scroll
        />
        {errors.perceived_effort ? (
          <AppText variant="small" tone="error">
            {errors.perceived_effort}
          </AppText>
        ) : null}

        {!values.sent ? (
          <Chips
            label="How far did you get? optional"
            options={PROGRESS_OPTIONS}
            value={values.completionPercent ?? -1}
            onChange={(p) => set({ completionPercent: p < 0 ? null : p })}
            testIDPrefix="progress"
          />
        ) : null}

        {!isEdit ? (
          <Chips
            label="When"
            options={WHEN_OPTIONS}
            value={when}
            onChange={(minutes) => {
              setWhen(minutes);
              set({ occurredAt: minutesAgo(minutes) });
            }}
            testIDPrefix="when"
          />
        ) : null}
        {errors.occurred_at ? (
          <AppText variant="small" tone="error">
            {errors.occurred_at}
          </AppText>
        ) : null}

        <TextField
          label="Notes (only you see these)"
          value={values.notes}
          onChangeText={(notes) => set({ notes })}
          multiline
          maxLength={NOTES_MAX}
          hint={`${values.notes.length}/${NOTES_MAX}`}
          error={errors.notes}
          testID="notes"
        />

        {conflict ? (
          <Banner
            tone="warning"
            title="This attempt changed somewhere else."
            actionLabel="Load the latest version"
            onAction={() => {
              patch.reset();
              void existing.refetch();
            }}
          >
            Your edit wasn't saved. Load the latest version, then make the
            change again.
          </Banner>
        ) : null}
        {showBanner ? (
          <ApiErrorBanner
            error={mutation.error}
            title="The attempt wasn't saved."
            onRetry={submit}
          />
        ) : null}

        <Button
          label={isEdit ? 'Save correction' : 'Save attempt'}
          onPress={submit}
          busy={mutation.isPending}
          disabled={isEdit && existing.data === undefined}
          testID="save-attempt"
        />
        {!isEdit ? (
          <AppText variant="small" tone="muted">
            Saving refreshes your session picks.
          </AppText>
        ) : null}
      </Screen>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  group: { gap: SPACE.xs },
  pair: { flexDirection: 'row', gap: SPACE.xs },
  half: { flex: 1 },
});
