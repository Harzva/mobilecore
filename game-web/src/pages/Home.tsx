import { ArrowRight, ChartNoAxesColumnIncreasing, Grid3X3, Play } from "lucide-react";
import type { ScoreSummary } from "../game/scoring";
import type { Submission } from "../storage";

export function HomePage({
  score,
  submissions,
  onStart,
  onCustom,
  onLeaderboard,
}: {
  score: ScoreSummary;
  submissions: Submission[];
  onStart: () => void;
  onCustom: () => void;
  onLeaderboard: () => void;
}) {
  const best = submissions[0]?.result.best_model ?? "0.5B";
  const bestScore = submissions[0]?.result.total_score ?? score.totalScore;

  return (
    <div className="hero-grid">
      <section className="hero-panel">
        <div>
          <div className="eyebrow">TuiMa 推嘛 · Benchmark by playing</div>
          <h1 className="hero-title">Run model on your phone</h1>
          <p className="hero-copy">
            Play a soft sokoban challenge, push model boxes into phone targets, and discover which local LLM tiers your device can run.
          </p>
        </div>
        <div>
          <div className="action-row">
            <button className="primary-button" onClick={onStart} type="button">
              <Play size={19} /> Start Challenge
            </button>
            <button className="secondary-button" onClick={onCustom} type="button">
              <Grid3X3 size={19} /> Custom Grid
            </button>
            <button className="ghost-button" onClick={onLeaderboard} type="button">
              <ChartNoAxesColumnIncreasing size={19} /> Leaderboard
            </button>
          </div>
          <div className="path-strip" aria-label="0.5B to 1.5B to 3B to 7B to 14B to Phone cleared">
            {["0.5B", "1.5B", "3B", "7B", "14B", "Phone ✓"].map((item, index, items) => (
              <div className="path-step" key={item}>
                <span>{item}</span>
                {index < items.length - 1 && <ArrowRight size={16} aria-hidden="true" />}
              </div>
            ))}
          </div>
          <div className="stat-grid">
            <div className="metric">
              <span>Best model</span>
              <strong>{best}</strong>
            </div>
            <div className="metric">
              <span>Best score</span>
              <strong>{bestScore.toLocaleString()}</strong>
            </div>
            <div className="metric">
              <span>Mode</span>
              <strong>Local demo</strong>
            </div>
          </div>
        </div>
      </section>
      <div
        className="hero-art"
        role="img"
        aria-label="TuiMa Push visual reference"
        style={{
          backgroundImage: `linear-gradient(180deg, rgba(255, 255, 255, 0.1), rgba(255, 255, 255, 0.7)), url("${import.meta.env.BASE_URL}assets/tuima-push-home.png")`,
        }}
      ></div>
    </div>
  );
}
