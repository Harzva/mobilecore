import type { ModelTier } from "./board";

export interface MockBenchmarkResult {
  readonly modelTier: ModelTier;
  readonly decodeTokPerSec: number;
  readonly firstTokenMs: number;
  readonly prefillTokensPerSecond: number;
  readonly completionTokens: number;
  readonly memoryPeakMb: number;
  readonly success: boolean;
}

const benchmarkSpeedByTier: Record<ModelTier, number> = {
  "0.5B": 90,
  "1.5B": 70,
  "3B": 45,
  "7B": 28,
  "14B": 12,
};

export const runMockBenchmark = (modelTier: ModelTier): MockBenchmarkResult => {
  const decodeTokPerSec = benchmarkSpeedByTier[modelTier];
  return {
    modelTier,
    decodeTokPerSec,
    firstTokenMs: Math.max(120, 600 - decodeTokPerSec * 2),
    prefillTokensPerSecond: Math.max(20, decodeTokPerSec * 0.6),
    completionTokens: 240,
    memoryPeakMb: modelTier === "0.5B" ? 600 : modelTier === "1.5B" ? 900 : modelTier === "3B" ? 1400 : modelTier === "7B" ? 2400 : 3400,
    success: true,
  };
};

export const getMockBenchmarkSpeedMap = (): Readonly<Record<ModelTier, number>> => ({
  ...benchmarkSpeedByTier,
});
