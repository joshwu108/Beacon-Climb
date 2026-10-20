/**
 * Beacon Climb mobile theme: "Topo Field Guide, pocket edition" (.impeccable.md).
 *
 * Every colour comes from docs/ux/design-tokens.md (BC-0008), the same cream-paper palette as
 * apps/web/src/styles/tokens.css, so contrast ratios checked there hold here. Hex is used
 * (not oklch) because React Native's native colour parser does not read oklch.
 *
 * Rules carried over from the token document:
 * - ink (`action`) is the primary action colour; `accent` (beacon teal) means
 *   "Beacon suggested this" and nothing else;
 * - warning (ochre) and error (brick) are separate channels from the accent and hold roles;
 * - elevation is stacked paper: a hard offset shadow with no blur.
 */
import { Platform, type TextStyle } from 'react-native';

export type ColorSchemeName = 'light' | 'dark';

export interface Palette {
  readonly surface: string;
  readonly surfaceRaised: string;
  readonly surfaceSunken: string;
  readonly boardFace: string;
  readonly border: string;
  readonly borderSubtle: string;
  readonly text: string;
  readonly textMuted: string;
  readonly textInverse: string;
  readonly action: string;
  readonly actionPressed: string;
  readonly accent: string;
  readonly accentBg: string;
  readonly onAccent: string;
  readonly warning: string;
  readonly warningBg: string;
  readonly error: string;
  readonly errorBg: string;
  readonly success: string;
  readonly successBg: string;
  readonly holdStart: string;
  readonly holdHand: string;
  readonly holdFoot: string;
  readonly holdFinish: string;
  readonly holdNeutral: string;
  readonly focus: string;
  /** Behind the phone frame on wide web screens (a desk under the guidebook). */
  readonly desk: string;
}

/** Light: warm uncoated paper and ink. */
const light: Palette = {
  surface: '#f6f1e7',
  surfaceRaised: '#fffdf8',
  surfaceSunken: '#ece5d6',
  boardFace: '#e6d8b9',
  border: '#857e6c',
  borderSubtle: '#d3cab6',
  text: '#1d1b16',
  textMuted: '#5a5446',
  textInverse: '#fffdf8',
  action: '#1d1b16',
  actionPressed: '#3a362b',
  accent: '#00606e',
  accentBg: '#dcefef',
  onAccent: '#ffffff',
  warning: '#7a4e00',
  warningBg: '#fbebc8',
  error: '#a3231b',
  errorBg: '#f8ddd9',
  success: '#2e5e2a',
  successBg: '#ddebd8',
  holdStart: '#3f5a1e',
  holdHand: '#243556',
  holdFoot: '#7f3f22',
  holdFinish: '#612857',
  holdNeutral: '#857e6c',
  focus: '#1d1b16',
  desk: '#e2d8c3',
};

/** Dark: "lamp-lit crag", espresso paper with cream ink; hold colours re-derived, not inverted. */
const dark: Palette = {
  surface: '#16140f',
  surfaceRaised: '#201d16',
  surfaceSunken: '#0f0e0a',
  boardFace: '#2a261c',
  border: '#7d7664',
  borderSubtle: '#3a362b',
  text: '#f0eadb',
  textMuted: '#b3ac9a',
  textInverse: '#16140f',
  action: '#f0eadb',
  actionPressed: '#d9d2bf',
  accent: '#6cd0dc',
  accentBg: '#14363b',
  onAccent: '#0f0e0a',
  warning: '#f0b84a',
  warningBg: '#3a2a0a',
  error: '#ff8a80',
  errorBg: '#3e1714',
  success: '#9ed08f',
  successBg: '#18301a',
  holdStart: '#a9cb73',
  holdHand: '#9fb6e8',
  holdFoot: '#e6a07a',
  holdFinish: '#d69ac9',
  holdNeutral: '#7d7664',
  focus: '#f0eadb',
  desk: '#0b0a07',
};

export const PALETTES: Readonly<Record<ColorSchemeName, Palette>> = {
  light,
  dark,
};

/**
 * Type pairing. Young Serif (display: screen titles, route names, grade numerals) reads as a
 * printed guidebook; Atkinson Hyperlegible Next (UI and body) was drawn for legibility at
 * a glance, which is the job here: a phone held at arm's length under the board.
 */
export const FONTS = {
  display: 'YoungSerif_400Regular',
  ui: 'AtkinsonHyperlegibleNext_400Regular',
  uiMedium: 'AtkinsonHyperlegibleNext_500Medium',
  uiBold: 'AtkinsonHyperlegibleNext_700Bold',
} as const;

/** Five steps, >= 1.25 apart; fixed (not fluid) because this is app UI. */
export const TYPE = {
  stamp: { fontSize: 12, lineHeight: 16, letterSpacing: 1 },
  small: { fontSize: 14, lineHeight: 20 },
  body: { fontSize: 17, lineHeight: 25 },
  title: { fontSize: 22, lineHeight: 28 },
  screen: { fontSize: 30, lineHeight: 36 },
  grade: { fontSize: 56, lineHeight: 60 },
} as const satisfies Record<string, TextStyle>;

/** 4 pt base; tight inside components, generous between sections. */
export const SPACE = {
  xxs: 4,
  xs: 8,
  sm: 12,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
  gutter: 20,
} as const;

/** Non-uniform radii: stamps square, inputs between, sheets soft. */
export const RADIUS = {
  stamp: 2,
  input: 6,
  sheet: 16,
  pill: 999,
} as const;

/** Minimum touch target (Apple HIG 44 pt). */
export const HIT = 44;

export const BORDER_WIDTH = 1.5;

export const MOTION = {
  fast: 150,
  normal: 240,
  slow: 400,
  staggerStep: 40,
} as const;

/** Phone frame for the web demo on wide screens (iPhone 14/15 logical size). */
export const PHONE_FRAME = {
  width: 390,
  height: 844,
  minViewport: 520,
} as const;

/** Hard offset paper shadow (no blur). `boxShadow` works on web and the new architecture. */
export const paperShadow = (
  palette: Palette,
  offset: number,
  color: string = palette.borderSubtle,
): { boxShadow: string } => ({
  boxShadow: `${offset}px ${offset}px 0px 0px ${color}`,
});

export const isWeb = Platform.OS === 'web';

export interface Theme {
  readonly scheme: ColorSchemeName;
  readonly color: Palette;
}

export const themeFor = (scheme: ColorSchemeName): Theme => ({
  scheme,
  color: PALETTES[scheme],
});
