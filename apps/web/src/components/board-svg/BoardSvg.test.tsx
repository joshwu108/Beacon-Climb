import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import boardsFixture from '../../../../../contracts/examples/listBoards/response.json';
import routeFixture from '../../../../../contracts/examples/getRoute/response.json';
import { expectNoBlockingViolations } from '../../test/axe';
import { BoardSvg, type BoardShape } from './BoardSvg';
import {
  describePlacement,
  relativePosition,
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

describe('BoardSvg', () => {
  it('renders every hold, one role-shaped marker per placement with a title, and the hidden list', async () => {
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
    expect(container.querySelectorAll('.hold')).toHaveLength(
      board.holds.length,
    );
    expect(
      container.querySelector('[data-role="START"] .marker__ring'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-role="HAND"] circle.marker__fill'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-role="FINISH"] .marker__bar'),
    ).not.toBeNull();
    expect(container.querySelectorAll('.board__placements title')).toHaveLength(
      3,
    );
    expect(
      container.querySelector('[data-role="START"] title'),
    ).toHaveTextContent('Start hold h1 at 0.25, 0.50');
    // The flipped finish hold (y = 1) sits at the top of the SVG.
    const finish = container.querySelector('[data-role="FINISH"] circle')!;
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

  it('renders a FOOT placement as a triangle and dims the other roles when one is highlighted', () => {
    const withFoot: Placement[] = [
      ...placements,
      { hold_id: 'h2', x: 0.5, y: 0.75, role: 'FOOT' },
    ];
    const { container } = render(
      <BoardSvg board={board} placements={withFoot} highlightRole="FOOT" />,
    );
    expect(
      container.querySelector('[data-role="FOOT"] polygon'),
    ).not.toBeNull();
    expect(container.querySelector('[data-role="FOOT"]')).not.toHaveClass(
      'marker--dim',
    );
    expect(container.querySelector('[data-role="HAND"]')).toHaveClass(
      'marker--dim',
    );
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
