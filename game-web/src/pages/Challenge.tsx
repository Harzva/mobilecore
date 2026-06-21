import { useCallback, useEffect, useState } from "react";
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Lightbulb, RotateCcw, Undo2, Upload } from "lucide-react";
import { BoardGrid } from "../components/BoardGrid";
import { ScoreCard } from "../components/ScoreCard";
import {
  getProgressSummary,
  movePlayer,
  resetGame,
  undoMove,
  type Direction,
  type GameState,
  type MoveEvent,
} from "../game/board";
import type { ScoreSummary } from "../game/scoring";

const keyToDirection: Record<string, Direction> = {
  ArrowUp: "up",
  w: "up",
  W: "up",
  ArrowDown: "down",
  s: "down",
  S: "down",
  ArrowLeft: "left",
  a: "left",
  A: "left",
  ArrowRight: "right",
  d: "right",
  D: "right",
};

const eventText = (event: MoveEvent | null) => {
  if (!event) return "Use arrow keys or WASD. Push each model box into a matching phone tile.";
  if (!event.moved) return `Blocked by ${event.blockedBy}. Try another path.`;
  if (event.type === "box-cleared") return `${event.clearedModelTier} cleared · ${event.benchmarkResult?.decodeTokPerSec} tok/s mock benchmark.`;
  if (event.type === "box-push") return "Model pushed. Keep lining it up with the phone tile.";
  return "TuiMa moved.";
};

export function Challenge({
  gameState,
  score,
  onGameStateChange,
  onUpload,
}: {
  gameState: GameState;
  score: ScoreSummary;
  onGameStateChange: (state: GameState) => void;
  onUpload: () => void;
}) {
  const [lastEvent, setLastEvent] = useState<MoveEvent | null>(null);
  const [hint, setHint] = useState(false);
  const progress = getProgressSummary(gameState);

  const move = useCallback((direction: Direction) => {
    const result = movePlayer(gameState, direction);
    onGameStateChange(result.state);
    setLastEvent(result.event);
  }, [gameState, onGameStateChange]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const direction = keyToDirection[event.key];
      if (!direction) return;
      event.preventDefault();
      move(direction);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [move]);

  return (
    <div className="challenge-layout">
      <section className="surface board-wrap">
        <div>
          <span className="section-kicker">Device Challenge 04</span>
          <h2 className="section-title">Push the model boxes</h2>
          <p className="section-copy">{eventText(lastEvent)}</p>
        </div>
        <BoardGrid state={gameState} />
        <div className="inline-actions">
          <button className="ghost-button" onClick={() => onGameStateChange(undoMove(gameState))} type="button">
            <Undo2 size={18} /> Undo
          </button>
          <button className="ghost-button" onClick={() => { onGameStateChange(resetGame()); setLastEvent(null); }} type="button">
            <RotateCcw size={18} /> Reset
          </button>
          <button className="ghost-button" onClick={() => setHint((value) => !value)} type="button">
            <Lightbulb size={18} /> Hint
          </button>
          <button className="primary-button" disabled={progress.clearedCount === 0} onClick={onUpload} type="button">
            <Upload size={18} /> Upload Result
          </button>
        </div>
        {hint && (
          <p className="privacy-note">
            Hint: this starter board is a straight push challenge. Stand left of each model box, then push right until it reaches its matching phone target.
          </p>
        )}
      </section>

      <aside className="tool-panel">
        <ScoreCard state={gameState} score={score} />
        <div className="control-pad" aria-label="Movement controls">
          <button className="icon-button up" onClick={() => move("up")} type="button" title="Move up">
            <ArrowUp size={18} />
          </button>
          <button className="icon-button left" onClick={() => move("left")} type="button" title="Move left">
            <ArrowLeft size={18} />
          </button>
          <button className="icon-button down" onClick={() => move("down")} type="button" title="Move down">
            <ArrowDown size={18} />
          </button>
          <button className="icon-button right" onClick={() => move("right")} type="button" title="Move right">
            <ArrowRight size={18} />
          </button>
        </div>
      </aside>
    </div>
  );
}
