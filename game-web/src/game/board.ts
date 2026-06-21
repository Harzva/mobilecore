import { runMockBenchmark, type MockBenchmarkResult } from "./mockBenchmark";
import { getSampleBoardLayout } from "./levels";

export type Direction = "up" | "down" | "left" | "right";

export type ModelTier = "0.5B" | "1.5B" | "3B" | "7B" | "14B";

export interface Coordinate {
  readonly x: number;
  readonly y: number;
}

export interface BoardSize {
  readonly rows: 8;
  readonly cols: 8;
}

export interface Player {
  readonly position: Coordinate;
}

export interface Wall {
  readonly id: string;
  readonly position: Coordinate;
}

export interface TargetPhone {
  readonly id: string;
  readonly position: Coordinate;
  readonly accepts: readonly ModelTier[];
}

export interface BonusScoreTile {
  readonly id: string;
  readonly position: Coordinate;
  readonly value: number;
}

export interface SpeedBoostTile {
  readonly id: string;
  readonly position: Coordinate;
  readonly multiplier: number;
}

export interface UploadBadgeTile {
  readonly id: string;
  readonly position: Coordinate;
}

export interface ModelBox {
  readonly id: string;
  readonly modelId: string;
  readonly modelTier: ModelTier;
  readonly quantization: string;
  readonly position: Coordinate;
  readonly isCleared: boolean;
}

export interface BenchmarkRecord {
  readonly modelTier: ModelTier;
  readonly result: MockBenchmarkResult;
}

export type TileMovementTarget = "empty" | "bonus" | "speed" | "upload" | "target";

export interface MoveEvent {
  readonly type: "blocked" | "player-move" | "box-push" | "box-cleared";
  readonly direction: Direction;
  readonly blockedBy: "wall" | "box" | "bounds" | "none";
  readonly moved: boolean;
  readonly movedBoxId?: string;
  readonly from?: Coordinate;
  readonly to?: Coordinate;
  readonly clearedModelTier?: ModelTier;
  readonly benchmarkResult?: MockBenchmarkResult;
}

export interface GameState {
  readonly boardId: string;
  readonly board: BoardSize;
  readonly player: Player;
  readonly walls: readonly Wall[];
  readonly targetPhones: readonly TargetPhone[];
  readonly bonusTiles: readonly BonusScoreTile[];
  readonly speedTiles: readonly SpeedBoostTile[];
  readonly uploadTile: UploadBadgeTile | null;
  readonly modelBoxes: readonly ModelBox[];
  readonly moveCount: number;
  readonly speedMultiplier: number;
  readonly uploadCompleted: boolean;
  readonly bonusScore: number;
  readonly benchmarkLog: readonly BenchmarkRecord[];
  readonly moveHistory: readonly GameState[];
}

export interface ProgressSummary {
  readonly boardId: string;
  readonly moveCount: number;
  readonly clearedCount: number;
  readonly totalBoxes: number;
  readonly clearedModelTiers: readonly ModelTier[];
  readonly isComplete: boolean;
  readonly uploadCompleted: boolean;
}

export interface MoveResult {
  readonly state: GameState;
  readonly event: MoveEvent;
}

const isInBounds = (coord: Coordinate, board: BoardSize): boolean =>
  coord.x >= 0 &&
  coord.x < board.cols &&
  coord.y >= 0 &&
  coord.y < board.rows;

const equalCoord = (a: Coordinate, b: Coordinate): boolean => a.x === b.x && a.y === b.y;

const cloneCoordinate = (coord: Coordinate): Coordinate => ({ x: coord.x, y: coord.y });

const cloneModelBoxes = (boxes: readonly ModelBox[]): ModelBox[] =>
  boxes.map((box) => ({ ...box, position: cloneCoordinate(box.position) }));

const cloneTargets = (targets: readonly TargetPhone[]): TargetPhone[] =>
  targets.map((target) => ({ ...target, accepts: [...target.accepts], position: cloneCoordinate(target.position) }));

const cloneBonus = (bonus: readonly BonusScoreTile[]): BonusScoreTile[] =>
  bonus.map((item) => ({ ...item, position: cloneCoordinate(item.position) }));

const cloneSpeed = (tiles: readonly SpeedBoostTile[]): SpeedBoostTile[] =>
  tiles.map((item) => ({ ...item, position: cloneCoordinate(item.position) }));

const cloneWalls = (walls: readonly Wall[]): Wall[] =>
  walls.map((wall) => ({ ...wall, position: cloneCoordinate(wall.position) }));

const findWall = (state: GameState, coord: Coordinate): Wall | undefined =>
  state.walls.find((wall) => equalCoord(wall.position, coord));

const findModel = (state: GameState, coord: Coordinate): ModelBox | undefined =>
  state.modelBoxes.find((box) => equalCoord(box.position, coord));

const directionDelta = (direction: Direction): Coordinate => {
  switch (direction) {
    case "up":
      return { x: 0, y: -1 };
    case "down":
      return { x: 0, y: 1 };
    case "left":
      return { x: -1, y: 0 };
    case "right":
      return { x: 1, y: 0 };
    default:
      return { x: 0, y: 0 };
  }
};

const tileType = (state: GameState, coord: Coordinate): TileMovementTarget => {
  if (state.bonusTiles.some((item) => equalCoord(item.position, coord))) return "bonus";
  if (state.speedTiles.some((item) => equalCoord(item.position, coord))) return "speed";
  if (state.uploadTile && equalCoord(state.uploadTile.position, coord)) return "upload";
  if (state.targetPhones.some((item) => equalCoord(item.position, coord))) return "target";
  return "empty";
};

const collectStepBonus = (state: GameState, coord: Coordinate): { speedMultiplier: number; bonusScore: number; uploadCompleted: boolean } => {
  const tile = tileType(state, coord);
  const speedMultiplier = tile === "speed"
    ? state.speedMultiplier * (state.speedTiles.find((item) => equalCoord(item.position, coord))?.multiplier ?? 1)
    : state.speedMultiplier;
  const bonusScore = tile === "bonus"
    ? state.bonusTiles.find((item) => equalCoord(item.position, coord))?.value ?? 0
    : 0;
  const uploadCompleted = tile === "upload" ? true : state.uploadCompleted;
  return { speedMultiplier, bonusScore, uploadCompleted };
};

export const getClearedModels = (state: GameState): ModelTier[] =>
  state.modelBoxes
    .filter((box) => box.isCleared)
    .map((box) => box.modelTier);

export const getProgressSummary = (state: GameState): ProgressSummary => {
  const clearedModels = getClearedModels(state);
  return {
    boardId: state.boardId,
    moveCount: state.moveCount,
    clearedCount: clearedModels.length,
    totalBoxes: state.modelBoxes.length,
    clearedModelTiers: clearedModels,
    isComplete: clearedModels.length === state.modelBoxes.length,
    uploadCompleted: state.uploadCompleted,
  };
};

const cloneStateShallow = (state: GameState): GameState => ({
  ...state,
  player: { position: cloneCoordinate(state.player.position) },
  walls: cloneWalls(state.walls),
  targetPhones: cloneTargets(state.targetPhones),
  bonusTiles: cloneBonus(state.bonusTiles),
  speedTiles: cloneSpeed(state.speedTiles),
  uploadTile: state.uploadTile ? { ...state.uploadTile, position: cloneCoordinate(state.uploadTile.position) } : null,
  modelBoxes: cloneModelBoxes(state.modelBoxes),
  benchmarkLog: [...state.benchmarkLog],
  moveHistory: [...state.moveHistory],
});

export const createStateFromLayout = (): GameState => {
  const layout = getSampleBoardLayout();
  const player = layout.tiles.find((tile) => tile.type === "player_start");
  const upload = layout.tiles.find((tile) => tile.type === "upload_badge");
  if (!player) {
    throw new Error("sample board is missing player_start");
  }
  const boardSize: BoardSize = { rows: layout.size.rows, cols: layout.size.cols };
  if (boardSize.rows !== 8 || boardSize.cols !== 8) {
    throw new Error("MVP board must be 8x8");
  }

  const toModelBox = (tile: (typeof layout.tiles)[number]): ModelBox => ({
    id: `${tile.type}-${tile.x}-${tile.y}`,
    modelId: tile.model_id ?? "",
    modelTier: tile.model_tier as ModelTier,
    quantization: tile.quantization ?? "",
    position: { x: tile.x, y: tile.y },
    isCleared: false,
  });

  return {
    boardId: layout.boardId,
    board: boardSize,
    player: { position: { x: player.x, y: player.y } },
    walls: layout.tiles
      .filter((tile) => tile.type === "wall")
      .map((tile) => ({ id: `wall-${tile.x}-${tile.y}`, position: { x: tile.x, y: tile.y } })),
    targetPhones: layout.tiles
      .filter((tile) => tile.type === "target_phone")
      .map((tile) => ({ id: `target-${tile.x}-${tile.y}`, position: { x: tile.x, y: tile.y }, accepts: tile.accepts ?? [] })),
    bonusTiles: layout.tiles
      .filter((tile) => tile.type === "bonus_score")
      .map((tile) => ({ id: `bonus-${tile.x}-${tile.y}`, position: { x: tile.x, y: tile.y }, value: tile.value ?? 0 })),
    speedTiles: layout.tiles
      .filter((tile) => tile.type === "speed_boost")
      .map((tile) => ({ id: `speed-${tile.x}-${tile.y}`, position: { x: tile.x, y: tile.y }, multiplier: tile.multiplier ?? 1 })),
    uploadTile: upload
      ? { id: "upload-badge", position: { x: upload.x, y: upload.y } }
      : null,
    modelBoxes: layout.tiles.filter((tile) => tile.type === "model_box").map(toModelBox),
    moveCount: 0,
    speedMultiplier: 1,
    uploadCompleted: false,
    bonusScore: 0,
    benchmarkLog: [],
    moveHistory: [],
  };
};

export const createInitialGameState = (): GameState => createStateFromLayout();

const pushBoxInto = (
  state: GameState,
  pushedFrom: Coordinate,
  pushedTo: Coordinate,
): { nextModelBoxes: readonly ModelBox[]; clearedModelTier?: ModelTier; benchmarkResult?: MockBenchmarkResult } => {
  const boxes = cloneModelBoxes(state.modelBoxes);
  const pushingIndex = boxes.findIndex((box) => equalCoord(box.position, pushedFrom));
  if (pushingIndex < 0) {
    return { nextModelBoxes: boxes };
  }
  const candidate = { ...boxes[pushingIndex], position: cloneCoordinate(pushedTo) };
  let benchmarkResult: MockBenchmarkResult | undefined;
  let clearedModelTier: ModelTier | undefined;
  if (state.targetPhones.some((target) => equalCoord(target.position, pushedTo) && target.accepts.includes(candidate.modelTier))) {
    if (!candidate.isCleared) {
      clearedModelTier = candidate.modelTier;
      benchmarkResult = runMockBenchmark(candidate.modelTier);
    }
    candidate.isCleared = true;
  }
  boxes[pushingIndex] = candidate;
  return { nextModelBoxes: boxes, clearedModelTier, benchmarkResult };
};

export const movePlayer = (state: GameState, direction: Direction): MoveResult => {
  const delta = directionDelta(direction);
  const destination = { x: state.player.position.x + delta.x, y: state.player.position.y + delta.y };
  if (!isInBounds(destination, state.board)) {
    return {
      state,
      event: { type: "blocked", direction, blockedBy: "bounds", moved: false },
    };
  }

  const wall = findWall(state, destination);
  if (wall) {
    return { state, event: { type: "blocked", direction, blockedBy: "wall", moved: false } };
  }

  const currentBox = findModel(state, destination);
  if (currentBox) {
    const pushedTo = { x: destination.x + delta.x, y: destination.y + delta.y };
    if (!isInBounds(pushedTo, state.board)) {
      return { state, event: { type: "blocked", direction, blockedBy: "bounds", moved: false, movedBoxId: currentBox.id, from: destination, to: pushedTo } };
    }
    if (findWall(state, pushedTo) || findModel(state, pushedTo)) {
      return { state, event: { type: "blocked", direction, blockedBy: "box", moved: false, movedBoxId: currentBox.id, from: destination, to: pushedTo } };
    }
    if (!["empty", "bonus", "speed", "upload", "target"].includes(tileType(state, pushedTo))) {
      return { state, event: { type: "blocked", direction, blockedBy: "wall", moved: false, movedBoxId: currentBox.id, from: destination, to: pushedTo } };
    }

    const { nextModelBoxes, clearedModelTier, benchmarkResult } = pushBoxInto(state, destination, pushedTo);
    const tileEffects = collectStepBonus({ ...state, modelBoxes: nextModelBoxes }, pushedTo);
    const nextState: GameState = {
      ...cloneStateShallow(state),
      player: { position: destination },
      modelBoxes: nextModelBoxes,
      moveCount: state.moveCount + 1,
      speedMultiplier: tileEffects.speedMultiplier,
      bonusScore: state.bonusScore + tileEffects.bonusScore,
      uploadCompleted: tileEffects.uploadCompleted,
      benchmarkLog: benchmarkResult
        ? [...state.benchmarkLog, { modelTier: currentBox.modelTier, result: benchmarkResult }]
        : state.benchmarkLog,
      moveHistory: [...state.moveHistory, cloneStateShallow(state)],
    };
    return {
      state: nextState,
      event: {
        type: clearedModelTier ? "box-cleared" : "box-push",
        direction,
        blockedBy: "none",
        moved: true,
        movedBoxId: currentBox.id,
        from: destination,
        to: pushedTo,
        clearedModelTier,
        benchmarkResult,
      },
    };
  }

  const tileEffects = collectStepBonus(state, destination);
  const nextState: GameState = {
    ...cloneStateShallow(state),
    player: { position: destination },
    moveCount: state.moveCount + 1,
    speedMultiplier: tileEffects.speedMultiplier,
    bonusScore: state.bonusScore + tileEffects.bonusScore,
    uploadCompleted: tileEffects.uploadCompleted,
    moveHistory: [...state.moveHistory, cloneStateShallow(state)],
  };
  return {
    state: nextState,
    event: {
      type: "player-move",
      direction,
      blockedBy: "none",
      moved: true,
      from: state.player.position,
      to: destination,
    },
  };
};

export const undoMove = (state: GameState): GameState =>
  state.moveHistory.length > 0 ? cloneStateShallow(state.moveHistory[state.moveHistory.length - 1]) : state;

export const resetGame = (): GameState => createInitialGameState();
