import type { DeviceTelemetry } from "./deviceTelemetry";
import type { GameState, ModelTier } from "./game/board";
import type { ScoreSummary } from "./game/scoring";
import type { BenchmarkResult, BenchmarkSignature } from "./game/benchmark";

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
  telemetry?: {
    cpu_activity_percent: number;
    cpu_cores: number | null;
    memory_gb: number | null;
    battery_percent: number | null;
    charging: boolean | null;
    network_type: string;
    viewport: string;
    screen: string;
    source: string;
    recorded_at: string;
  };
  benchmark_signature?: BenchmarkSignature;
  created_at: string;
}

export interface SubmissionInput {
  readonly playerName: string;
  readonly deviceClass: string;
  readonly modelName: string;
  readonly speedTokS: number;
  readonly telemetry?: DeviceTelemetry;
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

export const getBenchmarkMap = (state: GameState): Record<string, BenchmarkResult> =>
  state.benchmarkLog.reduce<Record<string, BenchmarkResult>>((acc, record) => {
    if (record.result.success && record.result.decodeTokPerSec > 0) {
      acc[record.modelTier] = record.result;
    }
    return acc;
  }, {});

export const buildSubmission = (
  state: GameState,
  score: ScoreSummary,
  input: SubmissionInput,
): Submission => {
  const benchmarkValues = state.benchmarkLog.map((record) => record.result);
  const successfulBenchmarkValues = benchmarkValues.filter((item) => item.success && item.decodeTokPerSec > 0);
  const observedAvgDecode = successfulBenchmarkValues.length
    ? successfulBenchmarkValues.reduce((sum, item) => sum + item.decodeTokPerSec, 0) / successfulBenchmarkValues.length
    : 0;
  const submittedSpeed = Number.isFinite(input.speedTokS) && input.speedTokS > 0
    ? input.speedTokS
    : observedAvgDecode;
  const firstToken = successfulBenchmarkValues.length
    ? Math.round(successfulBenchmarkValues.reduce((sum, item) => sum + item.firstTokenMs, 0) / successfulBenchmarkValues.length)
    : 0;
  const memoryPeak = successfulBenchmarkValues.length
    ? Math.max(...successfulBenchmarkValues.map((item) => item.memoryPeakMb))
    : 0;
  const signedBenchmark = state.benchmarkLog.find((record) => record.result.signature?.verified);
  const backend = state.benchmarkLog.some((record) => record.source === "mobilecore")
    ? "mobilecore"
    : state.benchmarkLog.some((record) => record.source === "demo-fallback")
      ? "demo-fallback"
      : "manual-speed";
  const clearedModels = state.modelBoxes
    .filter((box) => box.isCleared)
    .map((box) => box.modelTier);
  const bestModel = input.modelName.trim() || clearedModels[clearedModels.length - 1] || "unknown";

  return {
    schema_version: "tuima-push-submission-v0.1",
    anonymous_id: `local_${crypto.randomUUID()}`,
    player_name: input.playerName || "TuiStarter",
    device: {
      device_class: input.deviceClass || "Unknown Android",
      os: "Android",
      ram_class: input.telemetry?.memoryGb ? `${input.telemetry.memoryGb}GB` : "Unknown",
      chip_class: input.telemetry?.cpuCores ? `${input.telemetry.cpuCores} CPU cores` : "Unknown browser CPU",
    },
    runtime: {
      mobilecore_version: backend === "mobilecore" ? "0.1.0" : "0.1.0-demo",
      backend,
      model_format: "GGUF",
    },
    board: {
      board_id: state.boardId,
      board_version: state.boardVersion,
      board_type: state.boardType,
    },
    result: {
      total_score: score.totalScore,
      avg_decode_tok_s: Number(submittedSpeed.toFixed(1)),
      first_token_ms: firstToken,
      memory_peak_mb: memoryPeak,
      best_model: bestModel,
      cleared_models: clearedModels,
      stages_completed: Math.max(clearedModels.length, submittedSpeed > 0 ? 1 : 0),
      stage_total: state.modelBoxes.length,
      moves_used: state.moveCount,
    },
    telemetry: input.telemetry ? {
      cpu_activity_percent: input.telemetry.cpuActivityPercent,
      cpu_cores: input.telemetry.cpuCores,
      memory_gb: input.telemetry.memoryGb,
      battery_percent: input.telemetry.batteryPercent,
      charging: input.telemetry.charging,
      network_type: input.telemetry.networkType,
      viewport: input.telemetry.viewport,
      screen: input.telemetry.screen,
      source: input.telemetry.source,
      recorded_at: new Date().toISOString(),
    } : undefined,
    benchmark_signature: signedBenchmark?.result.signature,
    created_at: new Date().toISOString(),
  };
};
