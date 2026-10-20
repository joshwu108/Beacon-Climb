import {
  isHiddenFromAccessibility,
  render,
  screen,
} from '@testing-library/react-native';
import getRoute from '../../../../../../contracts/examples/getRoute/response.json';
import listBoards from '../../../../../../contracts/examples/listBoards/response.json';
import { ThemeProvider } from '../../../theme/ThemeProvider';
import { BoardDiagram } from '../BoardDiagram';

const board = listBoards.body.items[0] as Parameters<
  typeof BoardDiagram
>[0]['board'];
const route = getRoute.body as {
  name: string;
  placements: Parameters<typeof BoardDiagram>[0]['placements'];
};

describe('BoardDiagram', () => {
  it('names the board and route for assistive tech (geometry from apps/web)', async () => {
    await render(
      <ThemeProvider scheme="light">
        <BoardDiagram
          board={board}
          placements={route.placements}
          routeName={route.name}
          testID="board"
        />
      </ThemeProvider>,
    );
    const diagram = screen.getByTestId('board');
    expect(diagram.props.accessibilityRole).toBe('image');
    expect(diagram.props.accessibilityLabel).toMatch(
      new RegExp(
        `^Board with \\d+ holds?\\. Route ${route.name} uses ${route.placements!.length}:`,
      ),
    );
    expect(diagram.props.accessibilityHint).toMatch(/Start hold/);
  });

  it('a thumbnail is hidden from assistive tech', async () => {
    await render(
      <ThemeProvider scheme="dark">
        <BoardDiagram
          board={board}
          placements={route.placements}
          thumbnail
          testID="thumb"
        />
      </ThemeProvider>,
    );
    expect(screen.queryByTestId('thumb')).toBeNull();
    const thumb = screen.getByTestId('thumb', { includeHiddenElements: true });
    expect(isHiddenFromAccessibility(thumb)).toBe(true);
    expect(thumb.props.accessibilityLabel).toBeUndefined();
  });
});
