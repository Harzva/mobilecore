import SwiftUI

@main
struct MobileCoreiOSApp: App {
    @StateObject private var appState = MobileCoreAppState()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(appState)
        }
    }
}

enum MobileCoreOperation: Equatable {
    case idle
    case importing(String)
    case loading(String)
    case chatting(String)

    var isActive: Bool {
        self != .idle
    }

    var title: String {
        switch self {
        case .idle:
            return "Ready"
        case .importing:
            return "Importing model"
        case .loading:
            return "Loading model"
        case .chatting:
            return "Generating reply"
        }
    }

    var detail: String {
        switch self {
        case .idle:
            return "Local runtime is ready."
        case .importing(let fileName):
            return fileName
        case .loading(let modelId):
            return modelId
        case .chatting(let modelId):
            return modelId
        }
    }

    var systemImage: String {
        switch self {
        case .idle:
            return "checkmark.circle.fill"
        case .importing:
            return "square.and.arrow.down"
        case .loading:
            return "cpu"
        case .chatting:
            return "message.badge.waveform"
        }
    }
}

@MainActor
final class MobileCoreAppState: ObservableObject {
    private let modelManager = ModelManager()
    private let runtime = LlamaRuntime()
    private lazy var server = LocalAPIServer(runtime: runtime, modelManager: modelManager)
    private var chatTask: Task<Void, Never>?

    @Published var models: [ModelMetadata] = []
    @Published var isServerRunning = false
    @Published var activeModel = "none"
    @Published var statusMessage = "Ready"
    @Published var lastReply = ""
    @Published var lastMetrics = RuntimeMetrics()
    @Published var backendInfo = BackendInfo(id: "llama.cpp", name: "llama.cpp", version: "linked", mode: "objective-c++")
    @Published var operation = MobileCoreOperation.idle
    @Published var isChatCancelling = false

    var apiURL: String {
        "http://127.0.0.1:8080"
    }

    var isBusy: Bool {
        operation.isActive
    }

    var canCancelChat: Bool {
        if case .chatting = operation {
            return true
        }
        return false
    }

    var modelDirectory: String {
        modelManager.modelsDirectory.path
    }

    init() {
        refreshModels()
        lastMetrics = runtime.metrics()
    }

    func refreshModels() {
        backendInfo = runtime.backendInfo()
        models = modelManager.scanModels(loadedPath: runtime.loadedModelPath)
        lastMetrics = runtime.metrics()
        activeModel = lastMetrics.activeModel
    }

    func importModel(from url: URL) {
        guard begin(.importing(url.lastPathComponent)) else {
            return
        }

        let modelManager = self.modelManager
        Task {
            do {
                let model = try await Task.detached(priority: .userInitiated) {
                    try modelManager.importModel(from: url)
                }.value
                refreshModels()
                statusMessage = "Imported \(model.fileName)"
            } catch {
                statusMessage = error.localizedDescription
            }
            operation = .idle
        }
    }

    func loadModel(_ model: ModelMetadata) {
        guard begin(.loading(model.id)) else {
            return
        }

        let runtime = self.runtime
        let path = model.path
        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    try runtime.loadModel(path: path, options: LoadOptions())
                }.value
                refreshModels()
                statusMessage = "Loaded \(result.modelId)"
            } catch {
                statusMessage = error.localizedDescription
            }
            operation = .idle
        }
    }

    func loadFirstModel() {
        guard let model = modelManager.firstAvailableModel(loadedPath: runtime.loadedModelPath) else {
            statusMessage = "Import a GGUF model first."
            return
        }
        loadModel(model)
    }

    func unloadModel() {
        guard !isBusy else {
            statusMessage = "Wait for the current task to finish."
            return
        }
        runtime.unloadModel()
        refreshModels()
        statusMessage = "Model unloaded"
    }

    func startServer() {
        do {
            try server.start(port: 8080)
            isServerRunning = true
            statusMessage = "Local API running on \(apiURL)"
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func stopServer() {
        server.stop()
        isServerRunning = false
        statusMessage = "Local API stopped"
    }

    func runTestChat() {
        let metrics = runtime.metrics()
        let model = metrics.activeModel == "none"
            ? modelManager.defaultModelId(loadedPath: runtime.loadedModelPath)
            : metrics.activeModel

        guard begin(.chatting(model)) else {
            return
        }

        isChatCancelling = false
        lastReply = ""
        let runtime = self.runtime
        let onToken: LlamaRuntime.StreamTokenHandler = { [weak self] tokenText, _ in
            Task { @MainActor in
                self?.appendStreamToken(tokenText)
            }
            return true
        }

        chatTask = Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    try runtime.chat(
                        messages: [ChatMessage(role: "user", content: "Hello from MobileCore iOS")],
                        options: ChatOptions(model: model, maxTokens: 64),
                        onToken: onToken
                    )
                }.value
                if lastReply.isEmpty || lastReply != result.message {
                    lastReply = result.message
                }
                refreshModels()
                statusMessage = result.finishReason == "cancelled" ? "Reply cancelled" : "Reply ready"
            } catch {
                statusMessage = error.localizedDescription
            }
            isChatCancelling = false
            operation = .idle
            chatTask = nil
        }
    }

    func cancelTestChat() {
        guard canCancelChat else {
            return
        }

        isChatCancelling = true
        statusMessage = "Cancelling reply"
        runtime.cancelCurrentOperation()
    }

    private func begin(_ nextOperation: MobileCoreOperation) -> Bool {
        guard !operation.isActive else {
            statusMessage = "Wait for the current task to finish."
            return false
        }

        isChatCancelling = false
        operation = nextOperation
        statusMessage = nextOperation.title
        return true
    }

    private func appendStreamToken(_ tokenText: String) {
        guard canCancelChat else {
            return
        }

        lastReply += tokenText
        if !isChatCancelling {
            statusMessage = "Streaming reply"
        }
    }
}
