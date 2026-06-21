import { LeaderboardTable } from "../components/LeaderboardTable";
import type { Submission } from "../storage";

const demoSubmissions: Submission[] = [{
  schema_version: "tuima-push-submission-v0.1",
  anonymous_id: "anon_demo_speed_01",
  player_name: "MintPhone",
  device: {
    device_class: "Snapdragon demo phone",
    os: "Android",
    ram_class: "12GB",
    chip_class: "Demo silicon",
  },
  runtime: {
    mobilecore_version: "0.1.0",
    backend: "manual-speed",
    model_format: "GGUF",
  },
  board: {
    board_id: "standard-device-challenge-04",
    board_version: 1,
    board_type: "standard",
  },
  result: {
    total_score: 0,
    avg_decode_tok_s: 42.6,
    first_token_ms: 0,
    memory_peak_mb: 0,
    best_model: "Qwen2.5 3B Q4",
    cleared_models: [],
    stages_completed: 1,
    stage_total: 5,
    moves_used: 0,
  },
  telemetry: {
    cpu_activity_percent: 18,
    cpu_cores: 8,
    memory_gb: 12,
    battery_percent: 82,
    charging: false,
    network_type: "wifi",
    viewport: "390 x 844",
    screen: "390 x 844",
    source: "browser",
    recorded_at: "2026-06-21T00:00:00Z",
  },
  created_at: "2026-06-21T00:00:00Z",
}, {
  schema_version: "tuima-push-submission-v0.1",
  anonymous_id: "anon_demo_speed_02",
  player_name: "TuiStarter",
  device: {
    device_class: "Pixel Demo",
    os: "Android",
    ram_class: "8GB",
    chip_class: "Demo silicon",
  },
  runtime: {
    mobilecore_version: "0.1.0",
    backend: "demo-speed",
    model_format: "GGUF",
  },
  board: {
    board_id: "standard-device-challenge-04",
    board_version: 1,
    board_type: "standard",
  },
  result: {
    total_score: 4156,
    avg_decode_tok_s: 28.0,
    first_token_ms: 544,
    memory_peak_mb: 2400,
    best_model: "7B",
    cleared_models: ["7B"],
    stages_completed: 1,
    stage_total: 5,
    moves_used: 6,
  },
  telemetry: {
    cpu_activity_percent: 12,
    cpu_cores: 8,
    memory_gb: 8,
    battery_percent: 76,
    charging: true,
    network_type: "wifi",
    viewport: "390 x 844",
    screen: "390 x 844",
    source: "browser",
    recorded_at: "2026-06-21T00:01:00Z",
  },
  created_at: "2026-06-21T00:01:00Z",
}];

export function Leaderboard({ submissions }: { submissions: Submission[] }) {
  const rows = [...submissions, ...demoSubmissions].sort((a, b) =>
    b.result.avg_decode_tok_s - a.result.avg_decode_tok_s ||
    b.result.total_score - a.result.total_score ||
    a.created_at.localeCompare(b.created_at)
  );
  const fastest = rows[0];

  return (
    <section className="surface">
      <span className="section-kicker">Speed Leaderboard</span>
      <h2 className="section-title">Inference speed ranking</h2>
      <p className="section-copy">
        Ranked by tokens per second. This MVP reads localStorage submissions plus demo rows; no benchmark is started from this page.
      </p>
      <div className="stat-grid leaderboard-summary">
        <div className="metric">
          <span>Fastest</span>
          <strong>{fastest.result.avg_decode_tok_s.toFixed(1)} tok/s</strong>
        </div>
        <div className="metric">
          <span>Model</span>
          <strong>{fastest.result.best_model}</strong>
        </div>
        <div className="metric">
          <span>Entries</span>
          <strong>{rows.length}</strong>
        </div>
      </div>
      <LeaderboardTable submissions={rows} />
    </section>
  );
}
