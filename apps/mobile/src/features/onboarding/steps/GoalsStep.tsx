import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { fieldErrorsFrom } from '@web/components/ui/field-errors';
import { GOALS, type Goal } from '@web/features/onboarding/goals';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { AppText } from '../../../components/ui/AppText';
import { Button } from '../../../components/ui/Button';
import { Choice } from '../../../components/ui/Choice';
import { SPACE } from '../../../theme/theme';
import { GRADE_BANDS, MAX_GOALS, type GradeBand } from '../steps';
import { useSavePreferences } from '../useOnboarding';
import type { BoardDraft } from './BoardStep';

export interface GoalsStepProps {
  readonly draft: BoardDraft;
  readonly onSaved: () => void;
  readonly onBoardFieldErrors: (errors: {
    board_id?: string;
    angle_deg?: string;
  }) => void;
  readonly onBack: () => void;
}

const toggle = (list: readonly Goal[], goal: Goal): Goal[] =>
  list.includes(goal) ? list.filter((g) => g !== goal) : [...list, goal];

/** Step 4: starting band and goals, then PUT /v1/me/preferences with the board and angle. */
export const GoalsStep = ({
  draft,
  onSaved,
  onBoardFieldErrors,
  onBack,
}: GoalsStepProps) => {
  const save = useSavePreferences();
  const [band, setBand] = useState<GradeBand | null>(null);
  const [goals, setGoals] = useState<readonly Goal[]>([]);
  const [touched, setTouched] = useState(false);

  const serverErrors = fieldErrorsFrom(save.error, [
    'initial_grade_band',
    'goals',
  ]);
  const bandError =
    serverErrors.initial_grade_band ??
    (touched && band === null ? 'Choose a starting band.' : null);
  const showBanner = save.isError && Object.keys(serverErrors).length === 0;

  const submit = () => {
    setTouched(true);
    if (band === null || goals.length > MAX_GOALS) return;
    save.mutate(
      {
        board_id: draft.boardId,
        angle_deg: draft.angleDeg,
        initial_grade_band: band,
        goals: [...goals],
      },
      {
        onSuccess: onSaved,
        onError: (error) => {
          const boardErrors = fieldErrorsFrom(error, ['board_id', 'angle_deg']);
          if (Object.keys(boardErrors).length > 0)
            onBoardFieldErrors(boardErrors);
        },
      },
    );
  };

  return (
    <View style={styles.form}>
      <AppText tone="muted">
        Until you log attempts, picks come from this starting band, your goals
        and each route's grade. You can change both later.
      </AppText>
      {showBanner ? <ApiErrorBanner error={save.error} /> : null}
      <View
        style={styles.group}
        accessibilityRole="radiogroup"
        accessibilityLabel="Where should the first picks start?"
      >
        <AppText variant="title">Where should the first picks start?</AppText>
        {GRADE_BANDS.map((option) => (
          <Choice
            key={option.id}
            kind="radio"
            label={option.label}
            hint={option.hint}
            selected={band === option.id}
            invalid={bandError !== null}
            onPress={() => setBand(option.id)}
            testID={`band-${option.id}`}
          />
        ))}
        {bandError ? (
          <AppText variant="small" tone="error">
            {bandError}
          </AppText>
        ) : null}
      </View>
      <View style={styles.group}>
        <AppText variant="title">What do you want from sessions?</AppText>
        <AppText variant="small" tone="muted">
          Pick up to {MAX_GOALS}, or none.
        </AppText>
        {GOALS.map((option) => (
          <Choice
            key={option.id}
            kind="checkbox"
            label={option.label}
            hint={option.hint}
            selected={goals.includes(option.id)}
            onPress={() => setGoals(toggle(goals, option.id))}
            testID={`goal-${option.id}`}
          />
        ))}
      </View>
      <View style={styles.actions}>
        <Button label="Back" variant="secondary" onPress={onBack} />
        <Button
          label="Save and start"
          onPress={submit}
          busy={save.isPending}
          style={styles.grow}
          testID="goals-submit"
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  form: { gap: SPACE.lg },
  group: { gap: SPACE.xs },
  actions: { flexDirection: 'row', gap: SPACE.sm },
  grow: { flex: 1 },
});
