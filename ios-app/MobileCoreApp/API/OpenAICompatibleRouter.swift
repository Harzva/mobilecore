import Foundation

final class OpenAICompatibleRouter {
    private let runtime: LlamaRuntime
    private let modelManager: ModelManager
    private let apiKey: String

    init(runtime: LlamaRuntime, modelManager: ModelManager, apiKey: String = "local") {
        self.runtime = runtime
        self.modelManager = modelManager
        self.apiKey = apiKey
    }

    func route(_ request: HTTPRequest) -> HTTPResponsePayload {
        if request.method == "OPTIONS" {
            return .json(statusCode: 200, object: [:])
        }

        if request.path == "/health" && request.method == "GET" {
            return health()
        }

        guard hasAuth(request) else {
            return .json(statusCode: 401, object: [
                "error": ["message": "unauthorized"]
            ])
        }

        switch (request.method, request.path) {
        case ("GET", "/v1/models"):
            return models()
        case ("POST", "/v1/chat/completions"):
            return chatCompletions(request)
        case ("GET", "/metrics"):
            return metrics()
        case ("GET", "/mobilecore/models/dirs"):
            return .json(statusCode: 200, object: ["dirs": modelManager.modelDirectories()])
        default:
            return .json(statusCode: 404, object: [
                "error": ["message": "not found"]
            ])
        }
    }

    private func hasAuth(_ request: HTTPRequest) -> Bool {
        request.headers["authorization"] == "Bearer \(apiKey)"
    }

    private func models() -> HTTPResponsePayload {
        let now = Int(Date().timeIntervalSince1970)
        let models = modelManager.scanModels(loadedPath: runtime.loadedModelPath).map { model in
            [
                "id": model.id,
                "object": "model",
                "created": now,
                "owned_by": "mobilecore",
                "mobilecore": [
                    "path": model.path,
                    "format": model.format,
                    "backend": model.backend,
                    "quantization": model.quantization,
                    "context_length": model.contextLength,
                    "size_bytes": model.sizeBytes,
                    "loaded": model.loaded,
                    "architecture": model.architecture,
                    "parameter_count_b": model.parameterCountB,
                    "parameter_label": model.parameterLabel,
                    "metadata_source": model.metadataSource
                ] as [String: Any]
            ] as [String: Any]
        }

        return .json(statusCode: 200, object: [
            "object": "list",
            "data": models
        ])
    }

    private func chatCompletions(_ request: HTTPRequest) -> HTTPResponsePayload {
        do {
            let body = try parseJSONBody(request)
            let model = body["model"] as? String ?? modelManager.defaultModelId(loadedPath: runtime.loadedModelPath)
            let maxTokens = body["max_tokens"] as? Int ?? 128
            let temperature = body["temperature"] as? Double ?? 0.7
            let stream = body["stream"] as? Bool ?? false
            let messages = (body["messages"] as? [[String: Any]] ?? []).map {
                ChatMessage(
                    role: $0["role"] as? String ?? "user",
                    content: $0["content"] as? String ?? ""
                )
            }

            if stream {
                return .json(statusCode: 400, object: [
                    "error": ["message": "streaming is not implemented in the iOS skeleton yet"]
                ])
            }

            let result = try runtime.chat(
                messages: messages,
                options: ChatOptions(model: model, maxTokens: maxTokens, temperature: temperature, stream: false)
            )
            let created = Int(Date().timeIntervalSince1970)

            return .json(statusCode: 200, object: [
                "id": "chatcmpl-ios-local-0001",
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [[
                    "index": 0,
                    "message": [
                        "role": "assistant",
                        "content": result.message
                    ],
                    "finish_reason": result.finishReason
                ]],
                "usage": [
                    "prompt_tokens": result.promptTokens,
                    "completion_tokens": result.completionTokens,
                    "total_tokens": result.totalTokens
                ],
                "mobilecore": [
                    "backend": runtime.backendInfo().id,
                    "mode": runtime.backendInfo().mode,
                    "prompt_eval_ms": result.promptEvalMs,
                    "decode_tokens_per_second": result.decodeTokensPerSecond,
                    "first_token_ms": result.firstTokenMs,
                    "decode_ms": result.decodeMs,
                    "total_ms": result.totalMs,
                    "memory_peak_mb": result.memoryPeakMb
                ]
            ])
        } catch {
            return .json(statusCode: 400, object: [
                "error": ["message": error.localizedDescription]
            ])
        }
    }

    private func metrics() -> HTTPResponsePayload {
        let metrics = runtime.metrics()
        return .json(statusCode: 200, object: [
            "active_model": metrics.activeModel,
            "backend": metrics.backend,
            "uptime_seconds": 0,
            "requests_total": 0,
            "requests_failed": 0,
            "last_prompt_eval_ms": metrics.promptEvalMs,
            "last_decode_tokens_per_second": metrics.decodeTokensPerSecond,
            "average_decode_tokens_per_second": metrics.decodeTokensPerSecond,
            "last_first_token_ms": metrics.firstTokenMs,
            "last_decode_ms": metrics.decodeMs,
            "last_total_ms": metrics.totalMs,
            "last_prompt_tokens": metrics.promptTokens,
            "last_completion_tokens": metrics.completionTokens,
            "last_total_tokens": metrics.totalTokens,
            "memory_peak_mb": metrics.memoryPeakMb
        ])
    }

    private func health() -> HTTPResponsePayload {
        let info = runtime.backendInfo()
        return .json(statusCode: 200, object: [
            "status": "ok",
            "service": "mobilecore-ios",
            "version": "0.1.0",
            "model_loaded": runtime.isModelLoaded(),
            "active_model": runtime.metrics().activeModel,
            "backend": info.id,
            "mode": info.mode
        ])
    }

    private func parseJSONBody(_ request: HTTPRequest) throws -> [String: Any] {
        guard !request.body.isEmpty else {
            return [:]
        }
        let object = try JSONSerialization.jsonObject(with: request.body)
        guard let dict = object as? [String: Any] else {
            throw RouterError.invalidJSONBody
        }
        return dict
    }
}

enum RouterError: LocalizedError {
    case invalidJSONBody

    var errorDescription: String? {
        switch self {
        case .invalidJSONBody:
            return "Request body must be a JSON object."
        }
    }
}

struct HTTPResponsePayload {
    var statusCode: Int
    var reason: String
    var body: Data
    var headers: [String: String]

    static func json(statusCode: Int, object: Any) -> HTTPResponsePayload {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]))
            ?? Data("{}".utf8)
        return HTTPResponsePayload(
            statusCode: statusCode,
            reason: reasonPhrase(for: statusCode),
            body: data,
            headers: ["Content-Type": "application/json; charset=utf-8"]
        )
    }

    private static func reasonPhrase(for statusCode: Int) -> String {
        switch statusCode {
        case 200:
            return "OK"
        case 400:
            return "Bad Request"
        case 401:
            return "Unauthorized"
        case 404:
            return "Not Found"
        default:
            return "OK"
        }
    }
}
