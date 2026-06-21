import Foundation

final class ModelManager {
    private let fileManager: FileManager
    let modelsDirectory: URL

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.modelsDirectory = documents
            .appendingPathComponent("MobileCore", isDirectory: true)
            .appendingPathComponent("models", isDirectory: true)
        try? fileManager.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
    }

    func scanModels(loadedPath: String? = nil) -> [ModelMetadata] {
        let urls = (try? fileManager.contentsOfDirectory(
            at: modelsDirectory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        return urls
            .filter { $0.pathExtension.lowercased() == "gguf" }
            .sorted { $0.lastPathComponent.localizedCaseInsensitiveCompare($1.lastPathComponent) == .orderedAscending }
            .map { ModelMetadata.from(url: $0, loadedPath: loadedPath) }
    }

    func defaultModelId(loadedPath: String? = nil) -> String {
        scanModels(loadedPath: loadedPath).first?.id ?? "local-model"
    }

    func firstAvailableModel(loadedPath: String? = nil) -> ModelMetadata? {
        scanModels(loadedPath: loadedPath).first
    }

    func importModel(from sourceURL: URL) throws -> ModelMetadata {
        guard sourceURL.pathExtension.lowercased() == "gguf" else {
            throw ModelManagerError.invalidExtension
        }

        let didAccess = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        try fileManager.createDirectory(at: modelsDirectory, withIntermediateDirectories: true)
        let destination = uniqueDestination(for: sourceURL.lastPathComponent)
        try fileManager.copyItem(at: sourceURL, to: destination)
        return ModelMetadata.from(url: destination, loadedPath: nil)
    }

    func modelDirectories() -> [String] {
        [modelsDirectory.path]
    }

    private func uniqueDestination(for fileName: String) -> URL {
        let base = URL(fileURLWithPath: fileName).deletingPathExtension().lastPathComponent
        let ext = URL(fileURLWithPath: fileName).pathExtension
        var candidate = modelsDirectory.appendingPathComponent(fileName)
        var index = 2

        while fileManager.fileExists(atPath: candidate.path) {
            candidate = modelsDirectory.appendingPathComponent("\(base)-\(index).\(ext)")
            index += 1
        }

        return candidate
    }
}

enum ModelManagerError: LocalizedError {
    case invalidExtension

    var errorDescription: String? {
        switch self {
        case .invalidExtension:
            return "Choose a .gguf model file."
        }
    }
}
