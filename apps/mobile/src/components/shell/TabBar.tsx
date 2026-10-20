import type { BottomTabBarProps } from 'expo-router/tabs';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Circle, Line, Path, Rect } from 'react-native-svg';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, HIT, RADIUS, SPACE } from '../../theme/theme';
import { AppText } from '../ui/AppText';

export type TabName = 'session' | 'explore' | 'attempts' | 'scan';

export const TAB_COPY: Readonly<
  Record<TabName, { label: string; hint: string }>
> = {
  session: {
    label: 'Session',
    hint: 'Build a session and see why each route was picked',
  },
  explore: { label: 'Explore', hint: 'Browse every route on your board' },
  attempts: { label: 'Log', hint: 'Your attempt history' },
  scan: { label: 'Scan', hint: 'Scan a board photo (coming later)' },
};

/* Topo-symbol icons, drawn not borrowed: beacon arcs, a hold grid, logbook lines, a viewfinder. */
const TabIcon = ({ name, ink }: { name: TabName; ink: string }) => (
  <Svg width={26} height={26} viewBox="0 0 26 26">
    {name === 'session' ? (
      <>
        <Circle cx={13} cy={17} r={3} fill={ink} />
        <Path
          d="M7 13a7 7 0 0 1 12 0"
          stroke={ink}
          strokeWidth={2}
          fill="none"
          strokeLinecap="round"
        />
        <Path
          d="M3 9.5a12 12 0 0 1 20 0"
          stroke={ink}
          strokeWidth={2}
          fill="none"
          strokeLinecap="round"
        />
      </>
    ) : null}
    {name === 'explore' ? (
      <>
        <Rect
          x={3}
          y={3}
          width={20}
          height={20}
          rx={3}
          stroke={ink}
          strokeWidth={2}
          fill="none"
        />
        {[8, 13, 18].flatMap((x) =>
          [8, 13, 18].map((y) => (
            <Circle key={`${x}-${y}`} cx={x} cy={y} r={1.6} fill={ink} />
          )),
        )}
      </>
    ) : null}
    {name === 'attempts' ? (
      <>
        <Rect
          x={5}
          y={3}
          width={16}
          height={20}
          rx={2}
          stroke={ink}
          strokeWidth={2}
          fill="none"
        />
        <Line
          x1={9}
          x2={17}
          y1={9}
          y2={9}
          stroke={ink}
          strokeWidth={2}
          strokeLinecap="round"
        />
        <Line
          x1={9}
          x2={17}
          y1={13}
          y2={13}
          stroke={ink}
          strokeWidth={2}
          strokeLinecap="round"
        />
        <Line
          x1={9}
          x2={14}
          y1={17}
          y2={17}
          stroke={ink}
          strokeWidth={2}
          strokeLinecap="round"
        />
      </>
    ) : null}
    {name === 'scan' ? (
      <>
        <Path
          d="M3 9V4h5M18 4h5v5M23 17v5h-5M8 22H3v-5"
          stroke={ink}
          strokeWidth={2}
          fill="none"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <Circle
          cx={13}
          cy={13}
          r={3.5}
          stroke={ink}
          strokeWidth={2}
          fill="none"
        />
      </>
    ) : null}
  </Svg>
);

const isTabName = (name: string): name is TabName =>
  Object.hasOwn(TAB_COPY, name);

/**
 * Bottom tab bar: a paper strip with an ink rule on top. The selected tab gets an ink
 * "stamp" behind its icon and a bold label, so selection reads without colour.
 */
export const TabBar = ({
  state,
  emitter,
  navigateToTab,
}: BottomTabBarProps) => {
  const { color } = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={[
        styles.bar,
        {
          paddingBottom: Math.max(insets.bottom, SPACE.xs),
          backgroundColor: color.surfaceRaised,
          borderTopColor: color.border,
        },
      ]}
    >
      {state.routes.map((route, index) => {
        if (!isTabName(route.name)) return null;
        const selected = state.index === index;
        const copy = TAB_COPY[route.name];
        const onPress = () => {
          const event = emitter.emit({
            type: 'tabPress',
            target: route.key,
            canPreventDefault: true,
          });
          if (!selected && !event.defaultPrevented) navigateToTab(route.key);
        };
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityLabel={copy.label}
            accessibilityHint={copy.hint}
            accessibilityState={{ selected }}
            testID={`tab-${route.name}`}
            onPress={onPress}
            style={styles.tab}
          >
            <View
              style={[
                styles.iconWell,
                selected && {
                  backgroundColor: color.action,
                  borderColor: color.action,
                },
              ]}
            >
              <TabIcon
                name={route.name}
                ink={selected ? color.textInverse : color.textMuted}
              />
            </View>
            <AppText
              variant="stamp"
              style={{
                color: selected ? color.text : color.textMuted,
                letterSpacing: selected ? 1.4 : 1,
              }}
            >
              {copy.label}
            </AppText>
          </Pressable>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    borderTopWidth: BORDER_WIDTH,
    paddingTop: SPACE.xs,
    paddingHorizontal: SPACE.xs,
  },
  tab: {
    flex: 1,
    minHeight: HIT + 12,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
  },
  iconWell: {
    width: 52,
    height: 32,
    borderRadius: RADIUS.pill,
    borderWidth: BORDER_WIDTH,
    borderColor: 'transparent',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
