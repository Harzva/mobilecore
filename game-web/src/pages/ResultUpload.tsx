import { useState } from "react";
import { Send, ShieldCheck } from "lucide-react";
import type { GameState } from "../game/board";
import type { ScoreSummary } from "../game/scoring";
import { buildSubmission, type Submission } from "../storage";

export function ResultUpload({
  gameState,
  score,
  onSave,
  onBack,
}: {
  gameState: GameState;
  score: ScoreSummary;
  onSave: (submission: Submission) => void;
  onBack: () => void;
}) {
  const [playerName, setPlayerName] = useState("TuiStarter");
  const [deviceClass, setDeviceClass] = useState("Android demo phone");

  const clearedModels = gameState.modelBoxes.filter((box) => box.isCleared).map((box) => box.modelTier);
  const avgSpeed = gameState.benchmarkLog.length
    ? gameState.benchmarkLog.reduce((sum, item) => sum + item.result.decodeTokPerSec, 0) / gameState.benchmarkLog.length
    : 0;

  return (
    <div className="upload-grid">
      <section className="result-panel">
        <span className="section-kicker">Result Upload</span>
        <h2 className="section-title">Challenge Complete</h2>
        <div className="stat-grid">
          <div className="metric">
            <span>Total Score</span>
            <strong>{score.totalScore.toLocaleString()}</strong>
          </div>
          <div className="metric">
            <span>Avg Speed</span>
            <strong>{avgSpeed.toFixed(1)} tok/s</strong>
          </div>
          <div className="metric">
            <span>Best Model</span>
            <strong>{clearedModels[clearedModels.length - 1] ?? "None"}</strong>
          </div>
        </div>
        <p className="privacy-note">
          <ShieldCheck size={18} /> Share your result, not your data. Only device class, score, average speed, and cleared models are saved locally in this MVP.
        </p>
      </section>
      <aside className="tool-panel">
        <div className="form-grid">
          <label>
            <span className="label">Player</span>
            <input value={playerName} maxLength={32} onChange={(event) => setPlayerName(event.target.value)} />
          </label>
          <label>
            <span className="label">Device class</span>
            <input value={deviceClass} maxLength={64} onChange={(event) => setDeviceClass(event.target.value)} />
          </label>
          <button
            className="primary-button"
            onClick={() => onSave(buildSubmission(gameState, score, playerName, deviceClass))}
            type="button"
          >
            <Send size={18} /> Save to Local Leaderboard
          </button>
          <button className="ghost-button" onClick={onBack} type="button">
            Back to Challenge
          </button>
        </div>
      </aside>
    </div>
  );
}
