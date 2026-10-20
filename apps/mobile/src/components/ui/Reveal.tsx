import { useEffect, useRef, type ReactNode } from 'react';
import {
  Animated,
  Platform,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useReducedMotion } from '../../hooks/useReducedMotion';
import { MOTION } from '../../theme/theme';

export interface RevealProps {
  readonly children: ReactNode;
  /** Position in a staggered group (lineup cards). */
  readonly index?: number;
  /** Change it to replay the entrance (a new decision id). */
  readonly replayKey?: string;
  readonly style?: StyleProp<ViewStyle>;
}

/**
 * Entrance for content that just arrived (a new lineup): opacity and a short rise, staggered
 * per card, transform/opacity only, native driver. Under reduced motion it renders in place.
 */
export const Reveal = ({
  children,
  index = 0,
  replayKey,
  style,
}: RevealProps) => {
  const reduced = useReducedMotion();
  const progress = useRef(new Animated.Value(reduced ? 1 : 0)).current;
  useEffect(() => {
    if (reduced) {
      progress.setValue(1);
      return;
    }
    progress.setValue(0);
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: MOTION.slow,
      delay: index * MOTION.staggerStep * 2,
      useNativeDriver: Platform.OS !== 'web',
    });
    animation.start();
    return () => animation.stop();
  }, [index, progress, reduced, replayKey]);
  return (
    <Animated.View
      style={[
        style,
        {
          opacity: progress,
          transform: [
            {
              translateY: progress.interpolate({
                inputRange: [0, 1],
                outputRange: [14, 0],
              }),
            },
          ],
        },
      ]}
    >
      {children}
    </Animated.View>
  );
};
