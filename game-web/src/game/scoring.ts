import type { GameState, ModelTier } from "./board";
import { getClearedModels } from "./board";
import type { BenchmarkResult } from "./benchmark";

export interface ScoreContribution {
  readonly modelTier: ModelTier;
  readonly baseScore: number;
  readonly speedScore: number;
}

export interface ScoreSummary {
  readonly baseScore: number;
  readonly speedScore: number;
  readonly completionBonus: number;
  readonly moveCountPenalty: number;
  readonly totalScore: number;
  readonly details: readonly ScoreContribution[];
}

const BASE_SCORE_BY_TIER: Record<ModelTier, number> = {
  "0.5B": 500,
  "1.5B": 1000,
  "3B": 2000,
  "7B": 4000,
  "14B": 8000,
};

const COMPLETION_BONUS = [0, 100, 300, 700, 1500, 3000] as const;
const SPEED_WEIGHT = 2;

const completionBonusForCount = (count: number): number =>
  COMPLETION_BONUS[Math.min(count, COMPLETION_BONUS.length - 1)];

const movesPenalty = (moves: number): number => Math.max(0, moves - 60);

const sortedTiers = (tiers: readonly ModelTier[]): readonly ModelTier[] =>
  [...tiers].sort((a, b) => {
    const order: Record<ModelTier, number> = { "0.5B": 1, "1.5B": 2, "3B": 3, "7B": 4, "14B": 5 };
    return order[a] - order[b];
  });

export const calculateScore = (
  state: GameState,
  benchmarkMap: Record<string, BenchmarkResult> = {},
): ScoreSummary => {
  const clearedModels = sortedTiers(getClearedModels(state));
  const details: ScoreContribution[] = clearedModels.map((modelTier) => {
    const baseScore = BASE_SCORE_BY_TIER[modelTier];
    const benchmark = benchmarkMap[modelTier] ?? null;
    const speedScore = benchmark ? Math.round(benchmark.decodeTokPerSec * SPEED_WEIGHT) : 0;
    return { modelTier, baseScore, speedScore };
  });

  const baseScore = details.reduce((sum, item) => sum + item.baseScore, 0);
  const speedScore = details.reduce((sum, item) => sum + item.speedScore, 0);
  const completionBonus = completionBonusForCount(details.length);
  const movePenalty = movesPenalty(state.moveCount);

  return {
    baseScore,
    speedScore,
    completionBonus,
    moveCountPenalty: movePenalty,
    totalScore: Math.max(0, baseScore + speedScore + completionBonus - movePenalty),
    details,
  };
};
