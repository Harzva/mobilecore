import { ChartNoAxesColumnIncreasing, Cpu, Grid3X3, Play, Trophy } from "lucide-react";
import type { Submission } from "../storage";

export function HomePage({
  submissions,
  onStart,
  onCustom,
  onLeaderboard,
}: {
  submissions: Submission[];
  onStart: () => void;
  onCustom: () => void;
  onLeaderboard: () => void;
}) {
  const fastest = [...submissions].sort((a, b) => b.result.avg_decode_tok_s - a.result.avg_decode_tok_s)[0];
  const best = fastest?.result.best_model ?? "0.5B";
  const bestSpeed = fastest?.result.avg_decode_tok_s ?? 0;
  const pipeline = ["0.5B", "1.5B", "3B", "7B", "14B"];

  return (
    <div className="home-shell">
      <section className="hero-panel">
        <div className="hero-copy-block">
          <div className="eyebrow">TuiMa 推嘛</div>
          <h1 className="hero-title">Run model on your phone</h1>
          <p className="hero-copy">
            Push model boxes into phone targets, measure local LLM speed, and climb the shared inference board.
          </p>
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
        </div>

        <div className="model-lane" aria-label="Model tiers">
          {pipeline.map((item) => (
            <span className="model-chip" key={item}>{item}</span>
          ))}
          <span className="phone-chip">Phone ready</span>
        </div>

        <div className="hero-metrics">
          <div className="metric compact">
            <Cpu size={18} />
            <span>Best model</span>
            <strong>{best}</strong>
          </div>
          <div className="metric compact">
            <Trophy size={18} />
            <span>Top speed</span>
            <strong>{bestSpeed.toFixed(1)} tok/s</strong>
          </div>
        </div>
      </section>

      <aside className="hero-preview" aria-label="TuiMa app preview">
        <div className="preview-header">
          <span>TuiMa Push</span>
          <strong>{bestSpeed.toFixed(1)} tok/s</strong>
        </div>
        <img
          alt="TuiMa Push home screen"
          className="hero-screenshot"
          src={`${import.meta.env.BASE_URL}assets/tuima-push-home.png`}
        />
      </aside>
    </div>
  );
}
