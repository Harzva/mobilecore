import { useState } from "react";
import { Send, ShieldCheck } from "lucide-react";
import { DeviceTelemetryCard } from "../components/DeviceTelemetryCard";
import type { DeviceTelemetry } from "../deviceTelemetry";
import type { GameState } from "../game/board";
import type { ScoreSummary } from "../game/scoring";
import { buildSubmission, type Submission } from "../storage";

export function ResultUpload({
  gameState,
  score,
  telemetry,
  onSave,
  onBack,
}: {
  gameState: GameState;
  score: ScoreSummary;
  telemetry: DeviceTelemetry;
  onSave: (submission: Submission) => void;
  onBack: () => void;
}) {
  const clearedModels = gameState.modelBoxes.filter((box) => box.isCleared).map((box) => box.modelTier);
  const avgSpeed = gameState.benchmarkLog.length
    ? gameState.benchmarkLog.reduce((sum, item) => sum + item.result.decodeTokPerSec, 0) / gameState.benchmarkLog.length
    : 0;
  const lastClearedModel = clearedModels[clearedModels.length - 1] ?? "7B";
  const [playerName, setPlayerName] = useState("TuiStarter");
  const [deviceClass, setDeviceClass] = useState(telemetry.platform || "Android demo phone");
  const [modelName, setModelName] = useState<string>(lastClearedModel);
  const [speedTokS, setSpeedTokS] = useState(avgSpeed > 0 ? avgSpeed.toFixed(1) : "28.0");
  const submittedSpeed = Number(speedTokS);
  const canSave = Number.isFinite(submittedSpeed) && submittedSpeed > 0;

  return (
    <div className="upload-grid">
      <section className="result-panel">
        <span className="section-kicker">Speed Entry</span>
        <h2 className="section-title">Submit inference speed</h2>
        <div className="stat-grid">
          <div className="metric">
            <span>Speed</span>
            <strong>{canSave ? submittedSpeed.toFixed(1) : "0.0"} tok/s</strong>
          </div>
          <div className="metric">
            <span>Model</span>
            <strong>{modelName || "Unknown"}</strong>
          </div>
          <div className="metric">
            <span>Score</span>
            <strong>{score.totalScore.toLocaleString()}</strong>
          </div>
        </div>
        <p className="privacy-note">
          <ShieldCheck size={18} /> This page does not start a benchmark. It only saves the speed, model, device class, and local game context you enter.
        </p>
        <DeviceTelemetryCard telemetry={telemetry} />
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
          <label>
            <span className="label">Model</span>
            <input value={modelName} maxLength={64} onChange={(event) => setModelName(event.target.value)} />
          </label>
          <label>
            <span className="label">Inference speed tok/s</span>
            <input
              inputMode="decimal"
              min="0"
              step="0.1"
              type="number"
              value={speedTokS}
              onChange={(event) => setSpeedTokS(event.target.value)}
            />
          </label>
          <button
            className="primary-button"
            disabled={!canSave}
            onClick={() => onSave(buildSubmission(gameState, score, { playerName, deviceClass, modelName, speedTokS: submittedSpeed, telemetry }))}
            type="button"
          >
            <Send size={18} /> Save to Speed Leaderboard
          </button>
          <button className="ghost-button" onClick={onBack} type="button">
            Back to Challenge
          </button>
        </div>
      </aside>
    </div>
  );
}
