import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Line, Polygon } from 'react-native-svg';
import {
  HOLD_ROLES,
  ROLE_WORD,
  type HoldRole,
} from '@web/components/board-svg/geometry';
import { useTheme } from '../../theme/ThemeProvider';
import { SPACE } from '../../theme/theme';
import { AppText } from '../ui/AppText';

const SHAPE_WORD: Readonly<Record<HoldRole, string>> = {
  START: 'double ring',
  HAND: 'filled circle',
  FOOT: 'small triangle',
  FINISH: 'ring with a bar',
};

const Glyph = ({ role }: { role: HoldRole }) => {
  const { color } = useTheme();
  const fill = {
    START: color.holdStart,
    HAND: color.holdHand,
    FOOT: color.holdFoot,
    FINISH: color.holdFinish,
  }[role];
  return (
    <Svg width={28} height={28} viewBox="0 0 40 40">
      {role === 'START' ? (
        <>
          <Circle cx={20} cy={20} r={9} fill={fill} />
          <Circle
            cx={20}
            cy={20}
            r={15}
            fill="none"
            stroke={fill}
            strokeWidth={3}
          />
        </>
      ) : null}
      {role === 'HAND' ? <Circle cx={20} cy={20} r={11} fill={fill} /> : null}
      {role === 'FOOT' ? (
        <Polygon points="20,8 32,30 8,30" fill={fill} />
      ) : null}
      {role === 'FINISH' ? (
        <>
          <Circle
            cx={20}
            cy={24}
            r={10}
            fill="none"
            stroke={fill}
            strokeWidth={4}
          />
          <Line
            x1={6}
            x2={34}
            y1={7}
            y2={7}
            stroke={fill}
            strokeWidth={4}
            strokeLinecap="round"
          />
        </>
      ) : null}
    </Svg>
  );
};

/** Shape key: each role in words next to its glyph (never colour alone). */
export const BoardLegend = () => (
  <View style={styles.legend} accessibilityLabel="Hold roles">
    {HOLD_ROLES.map((role) => (
      <View
        key={role}
        style={styles.item}
        accessible
        accessibilityLabel={`${ROLE_WORD[role]}: ${SHAPE_WORD[role]}`}
      >
        <Glyph role={role} />
        <AppText variant="small">{ROLE_WORD[role]}</AppText>
      </View>
    ))}
  </View>
);

const styles = StyleSheet.create({
  legend: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACE.md },
  item: { flexDirection: 'row', alignItems: 'center', gap: SPACE.xxs },
});
