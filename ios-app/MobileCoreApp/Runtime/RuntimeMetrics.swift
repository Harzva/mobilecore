import Foundation

struct RuntimeMetrics: Equatable {
    var activeModel: String = "none"
    var backend: String = "llama.cpp"
    var promptEvalMs: Int = 0
    var firstTokenMs: Int = 0
    var decodeMs: Int = 0
    var totalMs: Int = 0
    var decodeTokensPerSecond: Double = 0
    var promptTokens: Int = 0
    var completionTokens: Int = 0
    var memoryPeakMb: Int = 0

    var totalTokens: Int {
        promptTokens + completionTokens
    }
}
