import { StyleSheet, View } from 'react-native';
import {
  NO_ESTIMATE_COPY,
  describeReasonCode,
  describeUncertainty,
} from '@web/features/decision/reason-codes';
import { AppText } from '../../components/ui/AppText';
import { Banner } from '../../components/ui/Banner';
import { Stamp } from '../../components/ui/Stamp';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';

/**
 * The "why" from the decision record: each reason code as a label and, when detailed, its
 * one-sentence explanation (apps/web's copy table). Numbered like a topo key, not bulleted.
 */
export const ReasonList = ({
  codes,
  detailed = false,
}: {
  codes: readonly string[];
  detailed?: boolean;
}) => {
  const { color } = useTheme();
  if (codes.length === 0) {
    return (
      <AppText variant="small" tone="muted">
        No reasons were recorded for this route.
      </AppText>
    );
  }
  return (
    <View style={styles.list} accessibilityRole="list">
      {codes.map((code, index) => {
        const copy = describeReasonCode(code);
        return (
          <View key={code} style={styles.item} accessibilityRole="text">
            <AppText
              variant="stamp"
              style={[styles.key, { color: color.accent }]}
              aria-hidden
            >
              {String.fromCharCode(65 + index)}
            </AppText>
            <View style={styles.body}>
              <AppText variant={detailed ? 'label' : 'small'}>
                {copy.label}
              </AppText>
              {detailed && copy.explanation ? (
                <AppText variant="small" tone="muted">
                  {copy.explanation}
                </AppText>
              ) : null}
            </View>
          </View>
        );
      })}
    </View>
  );
};

/** predicted_send_probability is null in v1; a number is never rendered as a chance. */
export const EstimateLine = ({
  probability,
  uncertaintyLabel,
}: {
  probability: number | null;
  uncertaintyLabel: string;
}) => (
  <View style={styles.estimate}>
    <AppText variant="small" tone="muted">
      {probability === null
        ? NO_ESTIMATE_COPY
        : 'Estimate recorded; not shown in this version'}
    </AppText>
    <Stamp label={describeUncertainty(uncertaintyLabel)} />
  </View>
);

export const ColdStartNotice = () => (
  <Banner tone="neutral" title="Starting picks">
    Not enough logged attempts yet for history-based picks, so these come from
    how well each route's grade fits your goal and starting band. Log a few
    attempts to change that.
  </Banner>
);

export const FallbackNotice = () => (
  <Banner tone="warning" title="Simplified recommendation">
    The full ranking step wasn't available, so this list uses a simpler
    fallback. No chance-of-success figures are shown.
  </Banner>
);

const styles = StyleSheet.create({
  list: { gap: SPACE.xs },
  item: { flexDirection: 'row', gap: SPACE.sm, alignItems: 'flex-start' },
  key: { width: 16, marginTop: 3 },
  body: { flex: 1, gap: 2 },
  estimate: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: SPACE.xs,
    flexWrap: 'wrap',
  },
});
