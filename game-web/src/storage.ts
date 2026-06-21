import type { GameState, ModelTier } from "./game/board";
import type { ScoreSummary } from "./game/scoring";
import type { MockBenchmarkResult } from "./game/mockBenchmark";

const STORAGE_KEY = "tuima-push-submissions-v0.1";

export interface Submission {
  schema_version: "tuima-push-submission-v0.1";
  anonymous_id: string;
  player_name: string;
  device: {
    device_class: string;
    os: "iOS" | "Android" | "iPadOS" | "Unknown";
    ram_class: string;
    chip_class: string;
  };
  runtime: {
    mobilecore_version: string;
    backend: string;
    model_format: string;
  };
  board: {
    board_id: string;
    board_version: number;
    board_type: "standard" | "custom";
  };
  result: {
    total_score: number;
    avg_decode_tok_s: number;
    first_token_ms: number;
    memory_peak_mb: number;
    best_model: string;
    cleared_models: ModelTier[];
    stages_completed: number;
    stage_total: number;
    moves_used: number;
  };
  created_at: string;
}

export const loadSubmissions = (): Submission[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
};

export const saveSubmission = (submission: Submission): Submission[] => {
  const next = [submission, ...loadSubmissions()].slice(0, 50);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  return next;
};

export const getBenchmarkMap = (state: GameState): Record<string, MockBenchmarkResult> =>
  state.benchmarkLog.reduce<Record<string, MockBenchmarkResult>>((acc, record) => {
    acc[record.modelTier] = record.result;
    return acc;
  }, {});

export const buildSubmission = (
  state: GameState,
  score: ScoreSummary,
  playerName: string,
  deviceClass: string,
): Submission => {
  const benchmarkValues = state.benchmarkLog.map((record) => record.result);
  const avgDecode = benchmarkValues.length
    ? benchmarkValues.reduce((sum, item) => sum + item.decodeTokPerSec, 0) / benchmarkValues.length
    : 0;
  const firstToken = benchmarkValues.length
    ? Math.round(benchmarkValues.reduce((sum, item) => sum + item.firstTokenMs, 0) / benchmarkValues.length)
    : 0;
  const memoryPeak = benchmarkValues.length
    ? Math.max(...benchmarkValues.map((item) => item.memoryPeakMb))
    : 0;
  const clearedModels = state.modelBoxes
    .filter((box) => box.isCleared)
    .map((box) => box.modelTier);

  return {
    schema_version: "tuima-push-submission-v0.1",
    anonymous_id: `local_${crypto.randomUUID()}`,
    player_name: playerName || "TuiStarter",
    device: {
      device_class: deviceClass || "Unknown Android",
      os: "Android",
      ram_class: "Demo",
      chip_class: "Local browser",
    },
    runtime: {
      mobilecore_version: "0.1.0-demo",
      backend: "mock",
      model_format: "GGUF",
    },
    board: {
      board_id: state.boardId,
      board_version: 1,
      board_type: "standard",
    },
    result: {
      total_score: score.totalScore,
      avg_decode_tok_s: Number(avgDecode.toFixed(1)),
      first_token_ms: firstToken,
      memory_peak_mb: memoryPeak,
      best_model: clearedModels[clearedModels.length - 1] ?? "none",
      cleared_models: clearedModels,
      stages_completed: clearedModels.length,
      stage_total: state.modelBoxes.length,
      moves_used: state.moveCount,
    },
    created_at: new Date().toISOString(),
  };
};
