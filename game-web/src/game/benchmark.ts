import type { ModelTier } from "./board";

export type BenchmarkSource = "pending" | "mobilecore" | "demo-fallback" | "manual";

export interface BenchmarkSignature {
  readonly payload: string;
  readonly value: string;
  readonly verified: boolean;
}

export interface BenchmarkResult {
  readonly modelTier: ModelTier;
  readonly decodeTokPerSec: number;
  readonly firstTokenMs: number;
  readonly prefillTokensPerSecond: number;
  readonly completionTokens: number;
  readonly memoryPeakMb: number;
  readonly success: boolean;
  readonly signature?: BenchmarkSignature;
}

const demoSpeedByTier: Record<ModelTier, number> = {
  "0.5B": 90,
  "1.5B": 70,
  "3B": 45,
  "7B": 28,
  "14B": 12,
};

const memoryByTier: Record<ModelTier, number> = {
  "0.5B": 600,
  "1.5B": 900,
  "3B": 1400,
  "7B": 2400,
  "14B": 3400,
};

export const createPendingBenchmark = (modelTier: ModelTier): BenchmarkResult => ({
  modelTier,
  decodeTokPerSec: 0,
  firstTokenMs: 0,
  prefillTokensPerSecond: 0,
  completionTokens: 0,
  memoryPeakMb: 0,
  success: false,
});

export const runDemoBenchmark = (modelTier: ModelTier): BenchmarkResult => {
  const decodeTokPerSec = demoSpeedByTier[modelTier];
  return {
    modelTier,
    decodeTokPerSec,
    firstTokenMs: Math.max(120, 600 - decodeTokPerSec * 2),
    prefillTokensPerSecond: Math.max(20, decodeTokPerSec * 0.6),
    completionTokens: 240,
    memoryPeakMb: memoryByTier[modelTier],
    success: true,
  };
};

export const getDemoBenchmarkSpeedMap = (): Readonly<Record<ModelTier, number>> => ({
  ...demoSpeedByTier,
});
