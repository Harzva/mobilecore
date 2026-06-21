import { Gauge, Trophy } from "lucide-react";
import type { GameState } from "../game/board";
import type { ScoreSummary } from "../game/scoring";

export function ScoreCard({ state, score }: { state: GameState; score: ScoreSummary }) {
  return (
    <section className="score-card">
      <div>
        <span className="section-kicker">Score</span>
        <div className="score-number">{score.totalScore.toLocaleString()}</div>
      </div>
      <div className="score-list">
        <div className="score-line">
          <span>Base score</span>
          <strong>{score.baseScore.toLocaleString()}</strong>
        </div>
        <div className="score-line">
          <span>Speed score</span>
          <strong>{score.speedScore.toLocaleString()}</strong>
        </div>
        <div className="score-line">
          <span>Completion bonus</span>
          <strong>{score.completionBonus.toLocaleString()}</strong>
        </div>
        <div className="score-line">
          <span>Moves</span>
          <strong>{state.moveCount}</strong>
        </div>
      </div>
      <div className="benchmark-list">
        {state.benchmarkLog.length === 0 && (
          <p className="section-copy">Push a model box onto a compatible phone tile to run the mock benchmark.</p>
        )}
        {state.benchmarkLog.map((record, index) => (
          <div className="benchmark-line" key={`${record.modelTier}-${index}`}>
            <span>
              <Trophy size={16} /> {record.modelTier}
            </span>
            <strong>
              <Gauge size={16} /> {record.result.decodeTokPerSec} tok/s
            </strong>
          </div>
        ))}
      </div>
    </section>
  );
}
