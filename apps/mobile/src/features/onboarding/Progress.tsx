import { StyleSheet, View } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from '../../components/ui/AppText';
import { ONBOARDING_STEPS, stepIndex, type StepId } from './steps';

/** "Step 2 of 4" plus four stamped segments: done (filled), current (outlined thick), to do. */
export const Progress = ({ current }: { current: StepId }) => {
  const { color } = useTheme();
  const index = stepIndex(current);
  return (
    <View
      style={styles.wrap}
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel={`Step ${index + 1} of ${ONBOARDING_STEPS.length}: ${ONBOARDING_STEPS[index]!.label}`}
      accessibilityValue={{
        min: 1,
        max: ONBOARDING_STEPS.length,
        now: index + 1,
      }}
    >
      <AppText variant="stamp" tone="muted">
        Step {index + 1} of {ONBOARDING_STEPS.length}
      </AppText>
      <View style={styles.row}>
        {ONBOARDING_STEPS.map((step, i) => (
          <View key={step.id} style={styles.segment}>
            <View
              style={[
                styles.bar,
                {
                  borderColor: i <= index ? color.action : color.border,
                  backgroundColor: i < index ? color.action : 'transparent',
                },
                i === index && { borderWidth: 3 },
              ]}
            />
            <AppText variant="small" tone={i === index ? 'default' : 'muted'}>
              {step.label}
            </AppText>
          </View>
        ))}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  wrap: { gap: SPACE.xs },
  row: { flexDirection: 'row', gap: SPACE.xs },
  segment: { flex: 1, gap: SPACE.xxs },
  bar: { height: 10, borderWidth: BORDER_WIDTH, borderRadius: RADIUS.stamp },
});
