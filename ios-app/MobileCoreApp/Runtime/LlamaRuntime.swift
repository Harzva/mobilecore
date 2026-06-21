import Foundation

final class LlamaRuntime: MobileCoreRuntime {
    typealias StreamTokenHandler = (String, Int) -> Bool

    private let bridge = LlamaBridge()
    private var loadedModelPathValue: String?
    private var metricsValue = RuntimeMetrics()

    var loadedModelPath: String? {
        loadedModelPathValue
    }

    func backendInfo() -> BackendInfo {
        guard let data = bridge.backendInfo().data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return BackendInfo(id: "llama.cpp", name: "llama.cpp", version: "unknown", mode: "unavailable")
        }

        return BackendInfo(
            id: json["id"] as? String ?? "llama.cpp",
            name: json["name"] as? String ?? "llama.cpp",
            version: json["version"] as? String ?? "unknown",
            mode: json["mode"] as? String ?? "unavailable"
        )
    }

    func loadModel(path: String, options: LoadOptions) throws -> LoadResult {
        let startedAt = Date()
        try bridge.loadModel(atPath: path, contextLength: options.contextLength)
        loadedModelPathValue = path

        let result = LoadResult(
            ok: true,
            modelId: URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent,
            path: path,
            loadTimeMs: max(1, Int(Date().timeIntervalSince(startedAt) * 1000)),
            memoryUsedMb: 0,
            backend: backendInfo().id
        )
        metricsValue.activeModel = result.modelId
        metricsValue.backend = result.backend
        return result
    }

    func unloadModel() {
        bridge.unloadModel()
        loadedModelPathValue = nil
        metricsValue.activeModel = "none"
    }

    func isModelLoaded() -> Bool {
        loadedModelPathValue != nil
    }

    func chat(messages: [ChatMessage], options: ChatOptions) throws -> ChatResult {
        try chat(messages: messages, options: options, onToken: nil)
    }

    func chat(messages: [ChatMessage], options: ChatOptions, onToken: StreamTokenHandler?) throws -> ChatResult {
        let messagesJSON = try jsonString(
            messages.map { ["role": $0.role, "content": $0.content] }
        )
        let optionsJSON = try jsonString([
            "model": options.model,
            "max_tokens": options.maxTokens,
            "temperature": options.temperature,
            "stream": options.stream
        ])

        var bridgeError: NSError?
        let responseJSON = bridge.chat(
            withMessagesJSON: messagesJSON,
            optionsJSON: optionsJSON,
            tokenHandler: onToken.map { handler in
                { tokenText, tokenIndex in
                    handler(tokenText, tokenIndex)
                }
            },
            error: &bridgeError
        )
        if let bridgeError {
            throw bridgeError
        }
        guard let data = responseJSON.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LlamaRuntimeError.invalidBridgeResponse
        }

        let result = ChatResult(
            model: json["model"] as? String ?? options.model,
            message: json["message"] as? String ?? "",
            finishReason: json["finish_reason"] as? String ?? "stop",
            promptTokens: json["prompt_tokens"] as? Int ?? 0,
            completionTokens: json["completion_tokens"] as? Int ?? 0,
            promptEvalMs: json["prompt_eval_ms"] as? Int ?? 0,
            firstTokenMs: json["first_token_ms"] as? Int ?? 0,
            decodeMs: json["decode_ms"] as? Int ?? 0,
            totalMs: json["total_ms"] as? Int ?? 0,
            decodeTokensPerSecond: json["decode_tokens_per_second"] as? Double ?? 0,
            memoryPeakMb: json["memory_peak_mb"] as? Int ?? 0
        )
        metricsValue = RuntimeMetrics(
            activeModel: result.model,
            backend: backendInfo().id,
            promptEvalMs: result.promptEvalMs,
            firstTokenMs: result.firstTokenMs,
            decodeMs: result.decodeMs,
            totalMs: result.totalMs,
            decodeTokensPerSecond: result.decodeTokensPerSecond,
            promptTokens: result.promptTokens,
            completionTokens: result.completionTokens,
            memoryPeakMb: result.memoryPeakMb
        )
        return result
    }

    func cancelCurrentOperation() {
        bridge.cancelCurrentOperation()
    }

    func metrics() -> RuntimeMetrics {
        metricsValue
    }

    private func jsonString(_ object: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8) ?? "{}"
    }
}

enum LlamaRuntimeError: LocalizedError {
    case invalidBridgeResponse

    var errorDescription: String? {
        switch self {
        case .invalidBridgeResponse:
            return "Llama bridge returned invalid JSON."
        }
    }
}
