import Foundation

struct ModelMetadata: Identifiable, Equatable {
    var id: String
    var path: String
    var fileName: String
    var format: String
    var backend: String
    var quantization: String
    var contextLength: Int
    var sizeBytes: Int64
    var loaded: Bool
    var architecture: String
    var parameterCountB: Double
    var parameterLabel: String
    var metadataSource: String

    var sizeLabel: String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: sizeBytes)
    }

    static func from(url: URL, loadedPath: String?) -> ModelMetadata {
        let fileName = url.lastPathComponent
        let id = url.deletingPathExtension().lastPathComponent
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        let size = attributes?[.size] as? NSNumber
        let normalized = fileName.uppercased()

        return ModelMetadata(
            id: id,
            path: url.path,
            fileName: fileName,
            format: "gguf",
            backend: "llama.cpp",
            quantization: Self.quantization(from: normalized),
            contextLength: 4096,
            sizeBytes: size?.int64Value ?? 0,
            loaded: loadedPath == url.path,
            architecture: Self.architecture(from: fileName),
            parameterCountB: Self.parameterCount(from: fileName),
            parameterLabel: Self.parameterLabel(from: fileName),
            metadataSource: "filename"
        )
    }

    private static func quantization(from normalizedFileName: String) -> String {
        let pattern = #"Q[0-9]+(?:_[A-Z0-9]+)+"#
        if let match = normalizedFileName.range(of: pattern, options: .regularExpression) {
            return String(normalizedFileName[match])
        }
        if normalizedFileName.contains("Q8") {
            return "Q8"
        }
        if normalizedFileName.contains("Q6") {
            return "Q6"
        }
        if normalizedFileName.contains("Q5") {
            return "Q5"
        }
        if normalizedFileName.contains("Q4") {
            return "Q4"
        }
        return "unknown"
    }

    private static func architecture(from fileName: String) -> String {
        let normalized = fileName.lowercased()
        if normalized.contains("qwen") {
            return "qwen"
        }
        if normalized.contains("llama") {
            return "llama"
        }
        if normalized.contains("gemma") {
            return "gemma"
        }
        if normalized.contains("phi") {
            return "phi"
        }
        if normalized.contains("smollm") {
            return "smollm"
        }
        if normalized.contains("deepseek") {
            return "deepseek"
        }
        return "unknown"
    }

    private static func parameterCount(from fileName: String) -> Double {
        let normalized = fileName.replacingOccurrences(of: "_", with: "-")
        let pattern = #"([0-9]+(?:\.[0-9]+)?)[ -]?B"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return 0
        }
        let range = NSRange(normalized.startIndex..<normalized.endIndex, in: normalized)
        guard let match = regex.firstMatch(in: normalized, options: [], range: range),
              let valueRange = Range(match.range(at: 1), in: normalized) else {
            return 0
        }
        return Double(normalized[valueRange]) ?? 0
    }

    private static func parameterLabel(from fileName: String) -> String {
        let value = parameterCount(from: fileName)
        guard value > 0 else {
            return "unknown"
        }
        if value.rounded() == value {
            return "\(Int(value))B"
        }
        return String(format: "%.1fB", value)
    }
}
