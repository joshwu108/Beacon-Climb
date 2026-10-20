import { useQuery } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type Board = components['schemas']['Board'];
export type BoardList = components['schemas']['BoardList'];

/** GET /v1/boards: hold geometry and available angles. */
export const useBoards = (enabled = true) =>
  useQuery({
    queryKey: ['boards'],
    queryFn: () => call<BoardList>(() => api.GET('/v1/boards')),
    enabled,
    staleTime: 5 * 60_000,
  });

/** The contract has no display name; the board id says what it is. */
export const boardLabel = (board: Board): string =>
  `${board.provider} · layout ${board.layout_id} · ${board.size_id}`;

export const findBoard = (
  boards: readonly Board[] | undefined,
  boardId: string | null | undefined,
): Board | null => boards?.find((board) => board.board_id === boardId) ?? null;

/** The board the signed-in climber set up, with its geometry (null while loading). */
export const usePreferredBoard = (boardId: string | null | undefined) => {
  const boards = useBoards(boardId !== null && boardId !== undefined);
  return { ...boards, board: findBoard(boards.data?.items, boardId) };
};
