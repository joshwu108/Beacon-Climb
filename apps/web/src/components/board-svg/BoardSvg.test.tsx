import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import boardsFixture from '../../../../../contracts/examples/listBoards/response.json';
import routeFixture from '../../../../../contracts/examples/getRoute/response.json';
import { expectNoBlockingViolations } from '../../test/axe';
import { BoardSvg, type BoardShape } from './BoardSvg';
import { CUE_LETTER } from './RoleCue';
import {
  describePlacement,
  hashSeed,
  holdLook,
  HOLD_SHAPE_PATHS,
  HOLD_TURNS,
  relativePosition,
  seededRandom,
  summarise,
  toSvgPoint,
  viewBoxFor,
  type Placement,
} from './geometry';

const board = boardsFixture.body.items[0] as BoardShape;
const placements = routeFixture.body.placements as Placement[];

describe('geometry', () => {
  it('derives the viewBox from the board geometry with padding', () => {
    const view = viewBoxFor({
      units: 'NORMALIZED',
      width: 1,
      height: 1,
      y_axis: 'UP',
    });
    expect(view).toMatchObject({
      minX: -110,
      minY: -110,
      width: 1220,
      height: 1220,
      scale: 1000,
    });
    const wide = viewBoxFor({
      units: 'MILLIMETRES',
      width: 2000,
      height: 1000,
      y_axis: 'DOWN',
    });
    expect(wide.scale).toBe(0.5);
    expect(wide.width).toBe(1220);
    expect(wide.height).toBe(720);
  });

  it('flips y once when y_axis is UP and not when DOWN', () => {
    const up = {
      units: 'NORMALIZED',
      width: 1,
      height: 1,
      y_axis: 'UP',
    } as const;
    const down = { ...up, y_axis: 'DOWN' } as const;
    const view = viewBoxFor(up);
    // y = 1 is the top of the board: it must land at the top of the SVG (y = 0).
    expect(toSvgPoint(up, view, { x: 0.75, y: 1 })).toEqual({ x: 750, y: 0 });
    expect(toSvgPoint(up, view, { x: 0.25, y: 0.5 })).toEqual({
      x: 250,
      y: 500,
    });
    expect(toSvgPoint(down, view, { x: 0.75, y: 1 })).toEqual({
      x: 750,
      y: 1000,
    });
  });

  it('describes placements with role, id, coordinates and a relative position', () => {
    expect(describePlacement(board.geometry, placements[0])).toBe(
      'Start hold h1 at 0.25, 0.50 (middle left)',
    );
    expect(describePlacement(board.geometry, placements[2])).toBe(
      'Finish hold h3 at 0.75, 1 (upper right)',
    );
    expect(relativePosition(board.geometry, { x: 0.5, y: 0.5 })).toBe('centre');
    expect(
      relativePosition(
        { ...board.geometry, y_axis: 'DOWN' },
        { x: 0.1, y: 0.1 },
      ),
    ).toBe('upper left');
  });

  it('summarises counts per role', () => {
    expect(summarise(3, placements, 'Synthetic Traverse 1')).toBe(
      'Board with 3 holds. Route Synthetic Traverse 1 uses 3: 1 start, 1 hand, 1 finish.',
    );
    expect(summarise(1, [])).toBe('Board with 1 hold. No route drawn.');
  });
});

describe('seeded hold looks', () => {
  it('gives the same hold_id the same look every time', () => {
    expect(hashSeed('h1')).toBe(hashSeed('h1'));
    expect(holdLook('h1', 0.5)).toEqual(holdLook('h1', 0.5));
    const a = seededRandom(42);
    const b = seededRandom(42);
    expect([a(), a(), a()]).toEqual([b(), b(), b()]);
  });

  it('varies the look across hold ids', () => {
    const looks = Array.from({ length: 40 }, (_, i) =>
      holdLook(`hold-${i}`, 0.5),
    );
    expect(new Set(looks.map((l) => l.shape)).size).toBeGreaterThan(3);
    expect(new Set(looks.map((l) => l.resin)).size).toBeGreaterThan(3);
    expect(new Set(looks.map((l) => l.rotation)).size).toBeGreaterThan(30);
  });

  it('puts small foot chips along the bottom and bigger holds in the middle band', () => {
    const ids = Array.from({ length: 40 }, (_, i) => `h${i}`);
    const low = ids.map((id) => holdLook(id, 0.05));
    const mid = ids.map((id) => holdLook(id, 0.55));
    expect(low.every((l) => l.shape >= 6)).toBe(true);
    expect(mid.every((l) => l.shape < 6)).toBe(true);
    const mean = (ls: typeof low) =>
      ls.reduce((sum, l) => sum + l.scale, 0) / ls.length;
    expect(mean(mid)).toBeGreaterThan(mean(low));
  });

  it('builds closed outline paths for the shape library', () => {
    expect(HOLD_SHAPE_PATHS).toHaveLength(8);
    for (const turns of HOLD_SHAPE_PATHS) {
      expect(turns).toHaveLength(HOLD_TURNS);
      for (const d of turns) expect(d).toMatch(/^M[^Z]+Z$/);
      // Each turn is a genuinely rotated outline (the light stays top left).
      expect(new Set(turns).size).toBe(HOLD_TURNS);
    }
  });
});

describe('BoardSvg', () => {
  it('renders every hold, a lit cue per placement with a title, and the hidden list', async () => {
    const { container } = render(
      <BoardSvg
        board={board}
        placements={placements}
        routeName="Synthetic Traverse 1"
      />,
    );
    const svg = screen.getByRole('img', {
      name: /route synthetic traverse 1 uses 3/i,
    });
    expect(svg).toHaveAttribute('viewBox', '-110 -110 1220 1220');
    const holds = container.querySelectorAll('use.hold');
    expect(holds).toHaveLength(board.holds.length);
    expect(
      [...holds].map((h) => h.getAttribute('data-hold-id')).sort(),
    ).toEqual(board.holds.map((h) => h.hold_id).sort());
    // Placed holds are drawn above the dark-gym scrim, unlit ones below it.
    expect(
      container.querySelectorAll('.board__lit .hold--placed'),
    ).toHaveLength(3);
    expect(container.querySelector('figure')).toHaveClass('board--lit');
    expect(container.querySelectorAll('.board__bloom .led')).toHaveLength(3);
    expect(
      container.querySelectorAll('[data-role="START"] .cue__ring'),
    ).toHaveLength(2);
    expect(
      container.querySelector('[data-role="FINISH"] .cue__bar'),
    ).not.toBeNull();
    expect(container.querySelectorAll('.board__placements title')).toHaveLength(
      3,
    );
    expect(
      container.querySelector('[data-role="START"] title'),
    ).toHaveTextContent('Start hold h1 at 0.25, 0.50');
    // The flipped finish hold (y = 1) sits at the top of the SVG.
    const finish = container.querySelector('[data-role="FINISH"] .cue__ring')!;
    expect(finish.getAttribute('cy')).toBe('0');

    const list = document.getElementById(
      svg.getAttribute('aria-describedby')!,
    )!;
    expect(list.tagName).toBe('OL');
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      'Start hold h1 at 0.25, 0.50 (middle left)',
      'Hand hold h2 at 0.50, 0.75 (upper centre)',
      'Finish hold h3 at 0.75, 1 (upper right)',
    ]);
    await expectNoBlockingViolations(container);
  });

  it('draws each hold the same way on every render', () => {
    const first = render(<BoardSvg board={board} />).container;
    const second = render(<BoardSvg board={board} />).container;
    const looks = (root: HTMLElement) =>
      [...root.querySelectorAll('use.hold')].map((h) => [
        h.getAttribute('data-hold-id'),
        h.getAttribute('transform'),
        h.getAttribute('class'),
        h.getAttribute('href')?.replace(/^#.*s(\d+-\d+)$/, '$1'),
      ]);
    expect(looks(first)).toEqual(looks(second));
  });

  it('gives every placement a non-colour role cue: a ring style and a letter', () => {
    const all: Placement[] = [
      ...placements,
      { hold_id: 'h2', x: 0.5, y: 0.75, role: 'FOOT' },
    ];
    const { container } = render(<BoardSvg board={board} placements={all} />);
    const markers = container.querySelectorAll(
      '.board__placements [data-role]',
    );
    expect(markers).toHaveLength(all.length);
    for (const marker of markers) {
      const role = marker.getAttribute('data-role') as Placement['role'];
      expect(marker.querySelector('.cue__letter')).toHaveTextContent(
        CUE_LETTER[role],
      );
      expect(marker.querySelector('.cue__ring')).not.toBeNull();
    }
    const ring = (role: string) =>
      container.querySelector(`[data-role="${role}"] .cue__ring`)!;
    expect(ring('FOOT')).toHaveAttribute('stroke-dasharray');
    expect(ring('HAND')).not.toHaveAttribute('stroke-dasharray');
    expect(new Set(Object.values(CUE_LETTER)).size).toBe(4);
  });

  it('dims the other roles when one is highlighted', () => {
    const withFoot: Placement[] = [
      ...placements,
      { hold_id: 'h2', x: 0.5, y: 0.75, role: 'FOOT' },
    ];
    const { container } = render(
      <BoardSvg board={board} placements={withFoot} highlightRole="FOOT" />,
    );
    expect(container.querySelector('[data-role="FOOT"]')).not.toHaveClass(
      'is-dim',
    );
    expect(container.querySelector('[data-role="HAND"]')).toHaveClass('is-dim');
    expect(container.querySelector('.led.role--start')).toHaveClass('is-dim');
    expect(container.querySelector('.led.role--foot')).not.toHaveClass(
      'is-dim',
    );
  });

  it('renders a millimetre, y-down board through the same geometry', () => {
    const mm: BoardShape = {
      geometry: {
        units: 'MILLIMETRES',
        width: 2000,
        height: 1000,
        y_axis: 'DOWN',
      },
      holds: [
        { hold_id: 'a', x: 0, y: 0 },
        { hold_id: 'b', x: 2000, y: 1000 },
      ],
    };
    const { container } = render(
      <BoardSvg
        board={mm}
        placements={[{ hold_id: 'b', x: 2000, y: 1000, role: 'FINISH' }]}
      />,
    );
    expect(container.querySelector('svg')).toHaveAttribute(
      'viewBox',
      '-110 -110 1220 720',
    );
    const ring = container.querySelector('[data-role="FINISH"] .cue__ring')!;
    expect([ring.getAttribute('cx'), ring.getAttribute('cy')]).toEqual([
      '1000',
      '500',
    ]);
  });

  it('without placements has no description list and says so in the label', () => {
    render(<BoardSvg board={board} />);
    const svg = screen.getByRole('img', {
      name: 'Board with 3 holds. No route drawn.',
    });
    expect(svg).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });
});
