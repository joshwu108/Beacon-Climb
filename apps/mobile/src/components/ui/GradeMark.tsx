import { formatGrade } from '@web/features/explore/grade';
import { StyleSheet, View } from 'react-native';
import { SPACE } from '../../theme/theme';
import { AppText } from './AppText';

/** Fixed width of the grade column in list rows, so names line up. */
export const GRADE_COLUMN = 64;

export interface GradeMarkProps {
  readonly route: { grade_numeric: number | null; grade_label: string | null };
  readonly size?: 'hero' | 'row';
}

/**
 * The grade as a guidebook prints it: a big serif numeral (the numeric community grade,
 * which is primary while the label table is blocked) over its label in small caps.
 */
export const GradeMark = ({ route, size = 'hero' }: GradeMarkProps) => {
  const grade = formatGrade(route);
  const spoken = grade.isUngraded
    ? 'Ungraded'
    : `Grade ${grade.primary}${grade.label ? `, ${grade.label}` : ', label pending'}`;
  return (
    <View
      style={[styles.mark, size === 'row' && styles.rowMark]}
      accessible
      accessibilityLabel={spoken}
    >
      <AppText
        variant={size === 'hero' ? 'grade' : 'title'}
        style={size === 'row' ? styles.rowNumeral : undefined}
      >
        {grade.isUngraded ? '—' : grade.primary}
      </AppText>
      <AppText
        variant="stamp"
        tone="muted"
        style={size === 'row' ? styles.rowLabel : undefined}
        numberOfLines={2}
      >
        {grade.isUngraded ? 'ungraded' : (grade.label ?? 'label pending')}
      </AppText>
    </View>
  );
};

const styles = StyleSheet.create({
  mark: { alignItems: 'center', gap: SPACE.xxs, minWidth: 56 },
  rowMark: { width: GRADE_COLUMN },
  rowNumeral: { fontSize: 28, lineHeight: 32 },
  rowLabel: { textAlign: 'center', letterSpacing: 0.5, fontSize: 11 },
});
