import { useQuery } from '@tanstack/react-query';
import { api, call } from '../../api/client';
import type { components } from '../../api/generated/schema';

export type Board = components['schemas']['Board'];
export type BoardList = components['schemas']['BoardList'];

export const boardsQueryKey = ['boards'] as const;

/** GET /v1/boards: full boards with hold geometry and available angles (A1, A5). */
export const useBoards = (options: { enabled?: boolean } = {}) =>
  useQuery({
    queryKey: boardsQueryKey,
    queryFn: () => call<BoardList>(() => api.GET('/v1/boards')),
    enabled: options.enabled ?? true,
    staleTime: 5 * 60_000,
  });

/** Human label for a board: the contract has no display name (wireframes/onboarding.md). */
export const boardLabel = (board: Board): string =>
  `${board.provider} · layout ${board.layout_id} · ${board.size_id}`;

export const findBoard = (
  boards: readonly Board[] | undefined,
  boardId: string | null | undefined,
): Board | null => boards?.find((board) => board.board_id === boardId) ?? null;
