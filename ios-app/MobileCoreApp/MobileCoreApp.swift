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

@MainActor
final class MobileCoreAppState: ObservableObject {
    private let modelManager = ModelManager()
    private let runtime = LlamaRuntime()
    private lazy var server = LocalAPIServer(runtime: runtime, modelManager: modelManager)

    @Published var models: [ModelMetadata] = []
    @Published var isServerRunning = false
    @Published var activeModel = "none"
    @Published var statusMessage = "Ready"
    @Published var lastReply = ""
    @Published var lastMetrics = RuntimeMetrics()

    var apiURL: String {
        "http://127.0.0.1:8080"
    }

    var modelDirectory: String {
        modelManager.modelsDirectory.path
    }

    init() {
        refreshModels()
        lastMetrics = runtime.metrics()
    }

    func refreshModels() {
        models = modelManager.scanModels(loadedPath: runtime.loadedModelPath)
        lastMetrics = runtime.metrics()
        activeModel = lastMetrics.activeModel
    }

    func importModel(from url: URL) {
        do {
            let model = try modelManager.importModel(from: url)
            refreshModels()
            statusMessage = "Imported \(model.fileName)"
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func loadModel(_ model: ModelMetadata) {
        do {
            let result = try runtime.loadModel(path: model.path, options: LoadOptions())
            refreshModels()
            statusMessage = "Loaded \(result.modelId)"
        } catch {
            statusMessage = error.localizedDescription
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
        do {
            let model = runtime.metrics().activeModel == "none"
                ? modelManager.defaultModelId(loadedPath: runtime.loadedModelPath)
                : runtime.metrics().activeModel
            let result = try runtime.chat(
                messages: [ChatMessage(role: "user", content: "Hello from MobileCore iOS")],
                options: ChatOptions(model: model, maxTokens: 64)
            )
            lastReply = result.message
            refreshModels()
            statusMessage = "Stub chat completed"
        } catch {
            statusMessage = error.localizedDescription
        }
    }
}
