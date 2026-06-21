import { LeaderboardTable } from "../components/LeaderboardTable";
import type { Submission } from "../storage";

const demoSubmission: Submission = {
  schema_version: "tuima-push-submission-v0.1",
  anonymous_id: "anon_demo_8f3a91",
  player_name: "TuiStarter",
  device: {
    device_class: "Pixel Demo",
    os: "Android",
    ram_class: "8GB",
    chip_class: "Mock silicon",
  },
  runtime: {
    mobilecore_version: "0.1.0",
    backend: "mock",
    model_format: "GGUF",
  },
  board: {
    board_id: "standard-device-challenge-04",
    board_version: 1,
    board_type: "standard",
  },
  result: {
    total_score: 12560,
    avg_decode_tok_s: 28.4,
    first_token_ms: 182,
    memory_peak_mb: 5200,
    best_model: "14B",
    cleared_models: ["0.5B", "1.5B", "3B", "7B", "14B"],
    stages_completed: 5,
    stage_total: 5,
    moves_used: 18,
  },
  created_at: "2026-06-21T00:00:00Z",
};

export function Leaderboard({ submissions }: { submissions: Submission[] }) {
  const rows = [...submissions, demoSubmission].sort((a, b) => b.result.total_score - a.result.total_score);

  return (
    <section className="surface">
      <span className="section-kicker">Leaderboard</span>
      <h2 className="section-title">See how your device ranks</h2>
      <p className="section-copy">
        Demo mode reads localStorage submissions. No personal data, prompt, file, API key, or local path is uploaded.
      </p>
      <LeaderboardTable submissions={rows} />
    </section>
  );
}
