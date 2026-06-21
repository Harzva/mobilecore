import Foundation

struct BackendInfo: Equatable {
    var id: String
    var name: String
    var version: String
    var mode: String
}

struct LoadOptions: Equatable {
    var contextLength: Int = 4096
    var threads: Int = 4
    var gpuLayers: Int = 0
}

struct LoadResult: Equatable {
    var ok: Bool
    var modelId: String
    var path: String
    var loadTimeMs: Int
    var memoryUsedMb: Int
    var backend: String
}

struct ChatMessage: Equatable {
    var role: String
    var content: String
}

struct ChatOptions: Equatable {
    var model: String
    var maxTokens: Int = 128
    var temperature: Double = 0.7
    var stream: Bool = false
}

struct ChatResult: Equatable {
    var model: String
    var message: String
    var finishReason: String
    var promptTokens: Int
    var completionTokens: Int
    var promptEvalMs: Int
    var firstTokenMs: Int
    var decodeMs: Int
    var totalMs: Int
    var decodeTokensPerSecond: Double
    var memoryPeakMb: Int

    var totalTokens: Int {
        promptTokens + completionTokens
    }
}

protocol MobileCoreRuntime {
    func backendInfo() -> BackendInfo
    func loadModel(path: String, options: LoadOptions) throws -> LoadResult
    func unloadModel()
    func isModelLoaded() -> Bool
    func chat(messages: [ChatMessage], options: ChatOptions) throws -> ChatResult
    func metrics() -> RuntimeMetrics
}
