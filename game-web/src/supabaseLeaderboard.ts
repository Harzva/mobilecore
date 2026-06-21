import { isSupabaseConfigured, SUPABASE_ANON_KEY, SUPABASE_URL } from "./config";
import type { Submission } from "./storage";

type SubmissionTelemetry = NonNullable<Submission["telemetry"]>;

interface SupabaseLeaderboardRow {
  readonly id?: string;
  readonly player_name: string;
  readonly device_class: string;
  readonly os: Submission["device"]["os"];
  readonly ram_class?: string | null;
  readonly chip_class: string;
  readonly mobilecore_version?: string | null;
  readonly runtime_backend?: string | null;
  readonly model_format?: string | null;
  readonly board_id: string;
  readonly board_version?: number | null;
  readonly board_type?: Submission["board"]["board_type"] | null;
  readonly total_score: number;
  readonly avg_decode_tok_s: number;
  readonly first_token_ms: number;
  readonly memory_peak_mb: number;
  readonly best_model: string;
  readonly cleared_models?: Submission["result"]["cleared_models"] | null;
  readonly stages_completed?: number | null;
  readonly stage_total?: number | null;
  readonly moves_used?: number | null;
  readonly cpu_activity_percent?: number | null;
  readonly cpu_cores?: number | null;
  readonly memory_gb?: number | null;
  readonly battery_percent?: number | null;
  readonly charging?: boolean | null;
  readonly network_type?: string | null;
  readonly telemetry_source?: SubmissionTelemetry["source"] | null;
  readonly viewport?: string | null;
  readonly screen_size?: string | null;
  readonly telemetry_recorded_at?: string | null;
  readonly benchmark_signature?: string | null;
  readonly signature_payload?: string | null;
  readonly signature_verified?: boolean | null;
  readonly created_at: string;
}

export type RemoteLeaderboardState = "disabled" | "loaded" | "error";

const supabaseHeaders = {
  apikey: SUPABASE_ANON_KEY,
  Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
  "Content-Type": "application/json",
};

const endpoint = (path: string) => `${SUPABASE_URL.replace(/\/$/, "")}/rest/v1/${path}`;

const fromRow = (row: SupabaseLeaderboardRow): Submission => ({
  schema_version: "tuima-push-submission-v0.1",
  anonymous_id: `remote_${row.id ?? `${row.player_name}_${row.created_at}`}`,
  player_name: row.player_name,
  device: {
    device_class: row.device_class,
    os: row.os,
    ram_class: row.ram_class ?? "Unknown",
    chip_class: row.chip_class,
  },
  runtime: {
    mobilecore_version: row.mobilecore_version ?? "0.1.0",
    backend: row.runtime_backend ?? "mobilecore",
    model_format: row.model_format ?? "GGUF",
  },
  board: {
    board_id: row.board_id,
    board_version: row.board_version ?? 1,
    board_type: row.board_type ?? "standard",
  },
  result: {
    total_score: row.total_score,
    avg_decode_tok_s: row.avg_decode_tok_s,
    first_token_ms: row.first_token_ms,
    memory_peak_mb: row.memory_peak_mb,
    best_model: row.best_model,
    cleared_models: row.cleared_models ?? [],
    stages_completed: row.stages_completed ?? 0,
    stage_total: row.stage_total ?? 5,
    moves_used: row.moves_used ?? 0,
  },
  telemetry: {
    cpu_activity_percent: row.cpu_activity_percent ?? 0,
    cpu_cores: row.cpu_cores ?? null,
    memory_gb: row.memory_gb ?? null,
    battery_percent: row.battery_percent ?? null,
    charging: row.charging ?? null,
    network_type: row.network_type ?? "unknown",
    viewport: row.viewport ?? "unknown",
    screen: row.screen_size ?? "unknown",
    source: row.telemetry_source ?? "mobilecore",
    recorded_at: row.telemetry_recorded_at ?? row.created_at,
  },
  benchmark_signature: row.benchmark_signature && row.signature_payload ? {
    payload: row.signature_payload,
    value: row.benchmark_signature,
    verified: Boolean(row.signature_verified),
  } : undefined,
  created_at: row.created_at,
});

export const fetchSharedLeaderboard = async (): Promise<Submission[]> => {
  if (!isSupabaseConfigured) return [];
  const query = new URLSearchParams({
    select: [
      "id",
      "player_name",
      "device_class",
      "os",
      "ram_class",
      "chip_class",
      "mobilecore_version",
      "runtime_backend",
      "model_format",
      "board_id",
      "board_version",
      "board_type",
      "total_score",
      "avg_decode_tok_s",
      "first_token_ms",
      "memory_peak_mb",
      "best_model",
      "cleared_models",
      "stages_completed",
      "stage_total",
      "moves_used",
      "cpu_activity_percent",
      "cpu_cores",
      "memory_gb",
      "battery_percent",
      "charging",
      "network_type",
      "telemetry_source",
      "viewport",
      "screen_size",
      "telemetry_recorded_at",
      "benchmark_signature",
      "signature_payload",
      "signature_verified",
      "created_at",
    ].join(","),
    status: "eq.accepted",
    order: "avg_decode_tok_s.desc,total_score.desc,created_at.asc",
    limit: "50",
  });
  const response = await fetch(endpoint(`submissions?${query}`), {
    headers: supabaseHeaders,
  });
  if (!response.ok) {
    throw new Error(`Supabase leaderboard ${response.status}`);
  }
  const rows = (await response.json()) as SupabaseLeaderboardRow[];
  return rows.map(fromRow);
};

const toInsertRow = (submission: Submission) => ({
  schema_version: submission.schema_version,
  anonymous_id: submission.anonymous_id,
  player_name: submission.player_name,
  device_class: submission.device.device_class,
  os: submission.device.os,
  ram_class: submission.device.ram_class,
  chip_class: submission.device.chip_class,
  mobilecore_version: submission.runtime.mobilecore_version,
  runtime_backend: submission.runtime.backend,
  model_format: submission.runtime.model_format,
  board_id: submission.board.board_id,
  board_version: submission.board.board_version,
  board_type: submission.board.board_type,
  total_score: submission.result.total_score,
  avg_decode_tok_s: submission.result.avg_decode_tok_s,
  first_token_ms: submission.result.first_token_ms,
  memory_peak_mb: submission.result.memory_peak_mb,
  best_model: submission.result.best_model,
  cleared_models: submission.result.cleared_models,
  stages_completed: submission.result.stages_completed,
  stage_total: submission.result.stage_total,
  moves_used: submission.result.moves_used,
  cpu_activity_percent: submission.telemetry?.cpu_activity_percent,
  cpu_cores: submission.telemetry?.cpu_cores,
  memory_gb: submission.telemetry?.memory_gb,
  battery_percent: submission.telemetry?.battery_percent,
  charging: submission.telemetry?.charging,
  network_type: submission.telemetry?.network_type,
  telemetry_source: submission.telemetry?.source,
  viewport: submission.telemetry?.viewport,
  screen_size: submission.telemetry?.screen,
  telemetry_recorded_at: submission.telemetry?.recorded_at,
  benchmark_signature: submission.benchmark_signature?.value,
  signature_payload: submission.benchmark_signature?.payload,
  signature_verified: submission.benchmark_signature?.verified ?? false,
});

export const insertSharedSubmission = async (submission: Submission): Promise<void> => {
  if (!isSupabaseConfigured) return;
  if (!submission.benchmark_signature?.verified) {
    throw new Error("Shared leaderboard requires a verified MobileCore signature");
  }
  const response = await fetch(endpoint("submissions"), {
    method: "POST",
    headers: {
      ...supabaseHeaders,
      Prefer: "return=minimal",
    },
    body: JSON.stringify(toInsertRow(submission)),
  });
  if (!response.ok) {
    throw new Error(`Supabase insert ${response.status}`);
  }
};
