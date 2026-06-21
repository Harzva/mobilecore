import type { ModelTier } from "../game/board";

const classByTier: Record<ModelTier, string> = {
  "0.5B": "model-0-5b",
  "1.5B": "model-1-5b",
  "3B": "model-3b",
  "7B": "model-7b",
  "14B": "model-14b",
};

export function ModelBox({ tier, cleared }: { tier: ModelTier; cleared: boolean }) {
  return (
    <div className={`model-box ${classByTier[tier]} ${cleared ? "cleared" : ""}`} title={`${tier} model box`}>
      {tier}
    </div>
  );
}
