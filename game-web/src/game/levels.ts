import { sampleBoard, type SampleBoardDefinition } from "../data/sampleBoard";

export interface BoardLayout {
  readonly boardId: string;
  readonly size: { readonly rows: 8; readonly cols: 8 };
  readonly tiles: typeof sampleBoard.tiles;
}

const normalizeAccepts = (accepts: readonly ("0.5B" | "1.5B" | "3B" | "7B" | "14B")[] | undefined) =>
  accepts ?? [];

export const getSampleBoardLayout = (): BoardLayout => {
  return {
    boardId: sampleBoard.board_id,
    size: { ...sampleBoard.size },
    tiles: sampleBoard.tiles.map((tile) => ({
      ...tile,
      accepts: normalizeAccepts(tile.accepts),
    })),
  };
};

export const getSampleBoardMetadata = (): Pick<SampleBoardDefinition, "name" | "schema_version" | "version"> => ({
  name: sampleBoard.name,
  schema_version: sampleBoard.schema_version,
  version: sampleBoard.version,
});
