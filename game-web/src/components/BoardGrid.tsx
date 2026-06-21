import { Bot, Sparkles, Upload, Zap } from "lucide-react";
import { ModelBox } from "./ModelBox";
import { TargetPhone } from "./TargetPhone";
import type { Coordinate, GameState } from "../game/board";

const same = (a: Coordinate, b: Coordinate) => a.x === b.x && a.y === b.y;

export function BoardGrid({ state }: { state: GameState }) {
  const cells = Array.from({ length: state.board.rows * state.board.cols }, (_, index) => ({
    x: index % state.board.cols,
    y: Math.floor(index / state.board.cols),
  }));

  return (
    <div className="board-grid" role="grid" aria-label="TuiMa push 8 by 8 board">
      {cells.map((cell) => {
        const wall = state.walls.some((item) => same(item.position, cell));
        const target = state.targetPhones.find((item) => same(item.position, cell));
        const model = state.modelBoxes.find((item) => same(item.position, cell));
        const isPlayer = same(state.player.position, cell);
        const bonus = state.bonusTiles.some((item) => same(item.position, cell));
        const speed = state.speedTiles.some((item) => same(item.position, cell));
        const upload = state.uploadTile ? same(state.uploadTile.position, cell) : false;
        const targetCleared = target
          ? state.modelBoxes.some((box) => box.isCleared && same(box.position, target.position))
          : false;
        const className = [
          "tile",
          wall ? "wall" : "",
          target ? "target" : "",
          bonus ? "bonus" : "",
          speed ? "speed" : "",
          upload ? "upload" : "",
        ]
          .filter(Boolean)
          .join(" ");

        return (
          <div className={className} key={`${cell.x}-${cell.y}`} role="gridcell">
            {wall && <span aria-hidden="true"> </span>}
            {target && !model && <TargetPhone cleared={targetCleared} />}
            {bonus && !model && !isPlayer && <Sparkles size={18} />}
            {speed && !model && !isPlayer && <Zap size={18} />}
            {upload && !model && !isPlayer && <Upload size={18} />}
            {model && <ModelBox cleared={model.isCleared} tier={model.modelTier} />}
            {isPlayer && (
              <div className="player-token" title="TuiMa helper">
                <Bot size={24} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
