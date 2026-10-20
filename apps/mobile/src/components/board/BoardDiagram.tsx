/**
 * The board drawn with react-native-svg. Geometry (viewBox, the one y-axis flip, hold
 * descriptions and the accessible summary) is apps/web's board-svg/geometry.ts, imported as
 * source; only the drawing is native. Glyph grammar from docs/ux/design-tokens.md section 6:
 * START double ring + label, HAND filled circle, FOOT small triangle, FINISH ring with a bar
 * + label. Shape and text carry the role; colour is the redundant fourth channel.
 */
import { memo } from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import Svg, {
  Circle,
  G,
  Line,
  Path,
  Polygon,
  Rect,
  Text as SvgText,
} from 'react-native-svg';
import {
  describePlacement,
  summarise,
  toSvgPoint,
  viewBoxFor,
  type HoldRole,
  type Placement,
} from '@web/components/board-svg/geometry';
import type { components } from '../../api/generated/schema';
import { useTheme } from '../../theme/ThemeProvider';
import { FONTS, RADIUS, type Palette } from '../../theme/theme';

export type BoardShape = Pick<
  components['schemas']['Board'],
  'holds' | 'geometry'
>;

export interface BoardDiagramProps {
  readonly board: BoardShape;
  readonly placements?: readonly Placement[];
  readonly routeName?: string;
  /** Accent rays around the route's holds: "Beacon suggested this" (recommendations only). */
  readonly recommended?: boolean;
  /** Small rendering for list rows: no labels, no texture, not separately accessible. */
  readonly thumbnail?: boolean;
  readonly style?: StyleProp<ViewStyle>;
  readonly testID?: string;
}

/* Glyph sizes in viewBox units (long side = 1000), as in the web renderer. */
const R = 22;
const RING_GAP = 8;
const FOOT = 20;
const FINISH_BAR = 14;
const NEUTRAL_R = 9;
const LABEL_OFFSET = R + RING_GAP + 16;
const RAY_INNER = R + RING_GAP + 8;
const RAY_OUTER = RAY_INNER + 18;
const CONTOUR_STEP = 90;

const roleColor = (role: HoldRole, color: Palette): string =>
  ({
    START: color.holdStart,
    HAND: color.holdHand,
    FOOT: color.holdFoot,
    FINISH: color.holdFinish,
  })[role];

interface MarkerProps {
  readonly role: HoldRole;
  readonly x: number;
  readonly y: number;
  readonly color: Palette;
  readonly showLabel: boolean;
}

/* Label with a board-coloured halo: a stroked copy underneath, then the ink fill on top. */
const Label = ({
  x,
  y,
  text,
  color,
}: {
  x: number;
  y: number;
  text: string;
  color: Palette;
}) => {
  const common = {
    x,
    y,
    textAnchor: 'middle' as const,
    fontSize: 26,
    fontFamily: FONTS.uiBold,
    fontWeight: '700' as const,
    letterSpacing: 2,
  };
  return (
    <G>
      <SvgText
        {...common}
        fill={color.boardFace}
        stroke={color.boardFace}
        strokeWidth={8}
        strokeLinejoin="round"
      >
        {text}
      </SvgText>
      <SvgText {...common} fill={color.text}>
        {text}
      </SvgText>
    </G>
  );
};

const Marker = ({ role, x, y, color, showLabel }: MarkerProps) => {
  const fill = roleColor(role, color);
  switch (role) {
    case 'START':
      return (
        <G>
          <Circle cx={x} cy={y} r={R} fill={fill} />
          <Circle
            cx={x}
            cy={y}
            r={R + RING_GAP}
            fill="none"
            stroke={fill}
            strokeWidth={5}
          />
          {showLabel ? (
            <Label x={x} y={y - LABEL_OFFSET} text="START" color={color} />
          ) : null}
        </G>
      );
    case 'HAND':
      return <Circle cx={x} cy={y} r={R} fill={fill} />;
    case 'FOOT':
      return (
        <Polygon
          points={`${x},${y - FOOT} ${x + FOOT},${y + FOOT * 0.8} ${x - FOOT},${y + FOOT * 0.8}`}
          fill={fill}
        />
      );
    case 'FINISH':
      return (
        <G>
          <Circle
            cx={x}
            cy={y}
            r={R}
            fill={color.surfaceRaised}
            stroke={fill}
            strokeWidth={6}
          />
          <Line
            x1={x - R - 4}
            x2={x + R + 4}
            y1={y - R - FINISH_BAR}
            y2={y - R - FINISH_BAR}
            stroke={fill}
            strokeWidth={7}
            strokeLinecap="round"
          />
          {showLabel ? (
            <Label x={x} y={y - LABEL_OFFSET - 6} text="FINISH" color={color} />
          ) : null}
        </G>
      );
  }
};

/** Four short accent ticks at N/E/S/W: the "recommended route" mark. */
const Rays = ({ x, y, color }: { x: number; y: number; color: string }) => (
  <G>
    {[
      [0, -1],
      [1, 0],
      [0, 1],
      [-1, 0],
    ].map(([dx, dy]) => (
      <Line
        key={`${dx}${dy}`}
        x1={x + dx! * RAY_INNER}
        y1={y + dy! * RAY_INNER}
        x2={x + dx! * RAY_OUTER}
        y2={y + dy! * RAY_OUTER}
        stroke={color}
        strokeWidth={6}
        strokeLinecap="round"
      />
    ))}
  </G>
);

/** Survey-map contour lines behind the board face (texture lives only here). */
const contourPath = (width: number, height: number): string => {
  const lines: string[] = [];
  for (let y = CONTOUR_STEP / 2, i = 0; y < height; y += CONTOUR_STEP, i += 1) {
    const amp = 14 + (i % 3) * 6;
    const segments = 4;
    const seg = width / segments;
    let d = `M0 ${y}`;
    for (let s = 0; s < segments; s += 1) {
      const dir = (s + i) % 2 === 0 ? -1 : 1;
      d += ` Q${seg * s + seg / 2} ${y + dir * amp} ${seg * (s + 1)} ${y}`;
    }
    lines.push(d);
  }
  return lines.join(' ');
};

export const BoardDiagram = memo(function BoardDiagram({
  board,
  placements = [],
  routeName,
  recommended = false,
  thumbnail = false,
  style,
  testID,
}: BoardDiagramProps) {
  const { color } = useTheme();
  const view = viewBoxFor(board.geometry);
  const faceWidth = board.geometry.width * view.scale;
  const faceHeight = board.geometry.height * view.scale;
  const placedIds = new Set(placements.map((p) => p.hold_id));
  const summary = summarise(board.holds.length, placements, routeName);
  const detail = placements
    .map((p) => describePlacement(board.geometry, p))
    .join('. ');

  return (
    <View
      testID={testID}
      accessible={!thumbnail}
      accessibilityRole={thumbnail ? undefined : 'image'}
      accessibilityLabel={thumbnail ? undefined : summary}
      accessibilityHint={thumbnail || detail === '' ? undefined : detail}
      importantForAccessibility={thumbnail ? 'no-hide-descendants' : 'yes'}
      aria-hidden={thumbnail || undefined}
      style={[
        styles.frame,
        {
          aspectRatio: view.width / view.height,
          backgroundColor: color.surfaceSunken,
        },
        thumbnail && styles.thumbnail,
        style,
      ]}
    >
      <Svg
        width="100%"
        height="100%"
        viewBox={`${view.minX} ${view.minY} ${view.width} ${view.height}`}
        preserveAspectRatio="xMidYMid meet"
      >
        <Rect
          x={0}
          y={0}
          width={faceWidth}
          height={faceHeight}
          rx={12}
          fill={color.boardFace}
          stroke={color.border}
          strokeWidth={thumbnail ? 6 : 3}
        />
        {thumbnail ? null : (
          <Path
            d={contourPath(faceWidth, faceHeight)}
            fill="none"
            stroke={color.text}
            strokeOpacity={0.08}
            strokeWidth={3}
          />
        )}
        <G>
          {board.holds.map((hold) => {
            const p = toSvgPoint(board.geometry, view, hold);
            return (
              <Circle
                key={hold.hold_id}
                cx={p.x}
                cy={p.y}
                r={NEUTRAL_R}
                fill={color.holdNeutral}
                opacity={placedIds.has(hold.hold_id) ? 0 : 0.45}
              />
            );
          })}
        </G>
        {recommended
          ? placements.map((placement, index) => {
              const p = toSvgPoint(board.geometry, view, placement);
              return (
                <Rays
                  key={`ray-${placement.hold_id}-${index}`}
                  x={p.x}
                  y={p.y}
                  color={color.accent}
                />
              );
            })
          : null}
        <G>
          {placements.map((placement, index) => {
            const p = toSvgPoint(board.geometry, view, placement);
            return (
              <Marker
                key={`${placement.hold_id}-${placement.role}-${index}`}
                role={placement.role}
                x={p.x}
                y={p.y}
                color={color}
                showLabel={!thumbnail}
              />
            );
          })}
        </G>
      </Svg>
    </View>
  );
});

const styles = StyleSheet.create({
  frame: { width: '100%', borderRadius: RADIUS.sheet, overflow: 'hidden' },
  thumbnail: { borderRadius: RADIUS.input },
});
