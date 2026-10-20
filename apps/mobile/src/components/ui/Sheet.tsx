import type { ReactNode } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { useTheme } from '../../theme/ThemeProvider';
import { BORDER_WIDTH, RADIUS, SPACE, paperShadow } from '../../theme/theme';

export interface SheetProps {
  readonly children: ReactNode;
  readonly style?: StyleProp<ViewStyle>;
  /** `lifted` = 6 pt paper offset (hero content); `flat` = no shadow. */
  readonly elevation?: 'flat' | 'sheet' | 'lifted';
  readonly testID?: string;
}

/** A sheet of paper on the page: raised surface, hairline ink border, hard offset shadow. */
export const Sheet = ({
  children,
  style,
  elevation = 'sheet',
  testID,
}: SheetProps) => {
  const { color } = useTheme();
  return (
    <View
      testID={testID}
      style={[
        styles.sheet,
        {
          backgroundColor: color.surfaceRaised,
          borderColor: color.borderSubtle,
        },
        elevation === 'sheet' && paperShadow(color, 3),
        elevation === 'lifted' && paperShadow(color, 6),
        style,
      ]}
    >
      {children}
    </View>
  );
};

const styles = StyleSheet.create({
  sheet: {
    borderRadius: RADIUS.sheet,
    borderWidth: BORDER_WIDTH,
    padding: SPACE.md,
    gap: SPACE.sm,
  },
});
