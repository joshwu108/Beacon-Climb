import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { BoardDiagram } from '../../../components/board/BoardDiagram';
import { ApiErrorBanner } from '../../../components/ui/ApiErrorBanner';
import { AppText } from '../../../components/ui/AppText';
import { Banner } from '../../../components/ui/Banner';
import { Button } from '../../../components/ui/Button';
import { Chips } from '../../../components/ui/Chips';
import { Choice } from '../../../components/ui/Choice';
import { Skeleton } from '../../../components/ui/Skeleton';
import { Stamp } from '../../../components/ui/Stamp';
import { SPACE } from '../../../theme/theme';
import { boardLabel, findBoard, useBoards } from '../../boards/useBoards';

export interface BoardDraft {
  readonly boardId: string;
  readonly angleDeg: number;
}

export interface BoardStepProps {
  readonly draft: BoardDraft | null;
  /** board_id / angle_deg messages from a rejected PUT /v1/me/preferences. */
  readonly serverErrors: { board_id?: string; angle_deg?: string };
  readonly onContinue: (draft: BoardDraft) => void;
}

/**
 * Step 3: board and angle. The angle chips are the chosen board's available_angles, so an
 * unsupported angle cannot be picked; a server UNSUPPORTED_ANGLE still lands here.
 */
export const BoardStep = ({
  draft,
  serverErrors,
  onContinue,
}: BoardStepProps) => {
  const boards = useBoards();
  const items = boards.data?.items;
  const [boardId, setBoardId] = useState<string | null>(draft?.boardId ?? null);
  const [angle, setAngle] = useState<number | null>(draft?.angleDeg ?? null);
  const [touched, setTouched] = useState(false);

  const selected =
    findBoard(items, boardId) ?? (items?.length === 1 ? items[0]! : null);
  const angles = selected?.available_angles ?? [];
  const effectiveAngle =
    angle !== null && angles.includes(angle) ? angle : null;
  const boardError =
    serverErrors.board_id ??
    (touched && selected === null ? 'Choose a board.' : null);
  const angleError =
    serverErrors.angle_deg ??
    (touched && effectiveAngle === null ? 'Choose an angle.' : null);

  const submit = () => {
    setTouched(true);
    if (selected === null || effectiveAngle === null) return;
    onContinue({ boardId: selected.board_id, angleDeg: effectiveAngle });
  };

  return (
    <View style={styles.form}>
      <AppText tone="muted">
        Only routes set for this board and angle are shown. Use the angle
        printed on or beside the board.
      </AppText>
      {boards.isPending ? <Skeleton label="Loading boards" /> : null}
      {boards.isError ? (
        <ApiErrorBanner
          error={boards.error}
          title="Boards couldn't be loaded."
          onRetry={() => void boards.refetch()}
        />
      ) : null}
      {items !== undefined && items.length === 0 ? (
        <Banner
          tone="warning"
          title="No boards are available yet."
          actionLabel="Try again"
          onAction={() => void boards.refetch()}
        >
          Beacon can't set up routes without one.
        </Banner>
      ) : null}
      {items?.map((board) => (
        <View key={board.board_id} style={styles.board}>
          <Choice
            kind="radio"
            label={boardLabel(board)}
            hint={
              board.synthetic
                ? 'Practice routes made by a generator, not real climbs.'
                : undefined
            }
            selected={selected?.board_id === board.board_id}
            invalid={boardError !== null}
            onPress={() => {
              setBoardId(board.board_id);
              setAngle(null);
            }}
            testID={`board-${board.board_id}`}
          />
          {board.synthetic ? (
            <Stamp label="Synthetic demo board" tone="warning" />
          ) : null}
        </View>
      ))}
      {boardError ? (
        <AppText variant="small" tone="error">
          {boardError}
        </AppText>
      ) : null}
      {selected ? (
        <>
          <BoardDiagram board={selected} style={styles.preview} />
          <Chips
            label="Wall angle"
            options={angles.map((a) => ({
              value: a,
              label: `${a}°`,
              accessibilityLabel: `${a} degrees`,
            }))}
            value={effectiveAngle}
            onChange={setAngle}
            testIDPrefix="angle"
          />
          {angleError ? (
            <AppText variant="small" tone="error">
              {angleError}
            </AppText>
          ) : null}
        </>
      ) : null}
      <Button
        label="Continue"
        onPress={submit}
        disabled={items === undefined || items.length === 0}
        testID="board-continue"
      />
    </View>
  );
};

const styles = StyleSheet.create({
  form: { gap: SPACE.md },
  board: { gap: SPACE.xs },
  preview: { maxWidth: 220, alignSelf: 'center' },
});
