import { MOBILECORE_API_KEY, MOBILECORE_BASE_URL, MOBILECORE_SIGNATURE_SECRET } from "./config";
import type { BenchmarkResult, BenchmarkSignature } from "./game/benchmark";
import type { ModelTier } from "./game/board";

interface MobileCoreChatResponse {
  readonly model?: string;
  readonly usage?: {
    readonly prompt_tokens?: number;
    readonly completion_tokens?: number;
  };
  readonly mobilecore?: {
    readonly decode_tokens_per_second?: number;
    readonly first_token_ms?: number;
    readonly prompt_eval_ms?: number;
    readonly memory_peak_mb?: number;
    readonly benchmark_signature?: string;
    readonly signature_payload?: string;
  };
}

const encoder = new TextEncoder();

const sha256Hex = async (value: string) => {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
};

export const verifyBenchmarkSignature = async (
  signature: string | undefined,
  payload: string | undefined,
): Promise<BenchmarkSignature | undefined> => {
  if (!signature || !payload) return undefined;
  const expected = await sha256Hex(`${payload}|${MOBILECORE_SIGNATURE_SECRET}`);
  return {
    payload,
    value: signature,
    verified: expected === signature,
  };
};

export const runMobileCoreBenchmark = async (
  modelTier: ModelTier,
  modelId: string,
  signal?: AbortSignal,
): Promise<BenchmarkResult> => {
  const response = await fetch(`${MOBILECORE_BASE_URL}/chat/completions`, {
    method: "POST",
    signal,
    headers: {
      Authorization: `Bearer ${MOBILECORE_API_KEY}`,
      "Content-Type": "application/json",
      "X-MobileCore-Client": "tuima-push-game",
    },
    body: JSON.stringify({
      model: modelId,
      max_tokens: 96,
      temperature: 0.2,
      stream: false,
      messages: [
        {
          role: "user",
          content: `MobileCore benchmark probe for ${modelTier}. Reply with one compact sentence.`,
        },
      ],
    }),
  });

  if (!response.ok) {
    throw new Error(`MobileCore API ${response.status}`);
  }

  const payload = (await response.json()) as MobileCoreChatResponse;
  const metrics = payload.mobilecore;
  if (!metrics) {
    throw new Error("MobileCore response missing metrics");
  }

  const decodeTokPerSec = Number(metrics.decode_tokens_per_second ?? 0);
  if (!Number.isFinite(decodeTokPerSec) || decodeTokPerSec <= 0) {
    throw new Error("MobileCore response has no decode speed");
  }

  const signature = await verifyBenchmarkSignature(
    metrics.benchmark_signature,
    metrics.signature_payload,
  );

  return {
    modelTier,
    decodeTokPerSec,
    firstTokenMs: Number(metrics.first_token_ms ?? 0),
    prefillTokensPerSecond: Number(metrics.prompt_eval_ms ?? 0),
    completionTokens: Number(payload.usage?.completion_tokens ?? 0),
    memoryPeakMb: Number(metrics.memory_peak_mb ?? 0),
    success: true,
    signature,
  };
};
