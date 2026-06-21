import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var appState: MobileCoreAppState
    @State private var isImporterPresented = false

    private var ggufTypes: [UTType] {
        [UTType(filenameExtension: "gguf") ?? .data]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    runtimeCard
                    controlsCard
                    modelsCard
                    metricsCard
                    if !appState.lastReply.isEmpty {
                        replyCard
                    }
                }
                .padding(20)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("MobileCore iOS")
            .toolbar {
                Button {
                    appState.refreshModels()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }
            .fileImporter(
                isPresented: $isImporterPresented,
                allowedContentTypes: ggufTypes,
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        appState.importModel(from: url)
                    }
                case .failure(let error):
                    appState.statusMessage = error.localizedDescription
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(appState.isServerRunning ? "Local API online" : "Local API offline", systemImage: appState.isServerRunning ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(appState.isServerRunning ? .green : .secondary)

            Text(appState.activeModel == "none" ? "No model loaded" : appState.activeModel)
                .font(.system(.largeTitle, design: .rounded).weight(.bold))
                .lineLimit(2)
                .minimumScaleFactor(0.7)

            Text(appState.statusMessage)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(.background)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var runtimeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Runtime", symbol: "cpu")
            HStack(spacing: 10) {
                StatTile(title: "Backend", value: "llama.cpp")
                StatTile(title: "Mode", value: "stub")
                StatTile(title: "Port", value: "8080")
            }
            Text(appState.apiURL)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
        }
        .cardStyle()
    }

    private var controlsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Controls", symbol: "slider.horizontal.3")
            FlowLayout {
                Button {
                    isImporterPresented = true
                } label: {
                    Label("Import GGUF", systemImage: "square.and.arrow.down")
                }
                .buttonStyle(.borderedProminent)

                Button {
                    appState.loadFirstModel()
                } label: {
                    Label("Load First", systemImage: "play.fill")
                }
                .buttonStyle(.bordered)

                Button {
                    appState.unloadModel()
                } label: {
                    Label("Unload", systemImage: "xmark.circle")
                }
                .buttonStyle(.bordered)

                Button {
                    appState.isServerRunning ? appState.stopServer() : appState.startServer()
                } label: {
                    Label(appState.isServerRunning ? "Stop API" : "Start API", systemImage: appState.isServerRunning ? "stop.fill" : "network")
                }
                .buttonStyle(.borderedProminent)

                Button {
                    appState.runTestChat()
                } label: {
                    Label("Test Chat", systemImage: "message")
                }
                .buttonStyle(.bordered)
            }
        }
        .cardStyle()
    }

    private var modelsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Models", symbol: "shippingbox")
            if appState.models.isEmpty {
                ContentUnavailableView("No GGUF models", systemImage: "doc.badge.plus")
            } else {
                ForEach(appState.models) { model in
                    ModelRow(model: model) {
                        appState.loadModel(model)
                    }
                    if model.id != appState.models.last?.id {
                        Divider()
                    }
                }
            }
            Text(appState.modelDirectory)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
        }
        .cardStyle()
    }

    private var metricsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Metrics", symbol: "speedometer")
            HStack(spacing: 10) {
                StatTile(title: "Tok/s", value: String(format: "%.2f", appState.lastMetrics.decodeTokensPerSecond))
                StatTile(title: "TTFT", value: "\(appState.lastMetrics.firstTokenMs) ms")
                StatTile(title: "Total", value: "\(appState.lastMetrics.totalMs) ms")
            }
        }
        .cardStyle()
    }

    private var replyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(title: "Reply", symbol: "text.bubble")
            Text(appState.lastReply)
                .font(.body)
                .textSelection(.enabled)
        }
        .cardStyle()
    }
}

private struct SectionTitle: View {
    var title: String
    var symbol: String

    var body: some View {
        Label(title, systemImage: symbol)
            .font(.headline)
            .foregroundStyle(.primary)
    }
}

private struct ModelRow: View {
    var model: ModelMetadata
    var onLoad: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(model.fileName)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(2)
                    if model.loaded {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }
                HStack(spacing: 8) {
                    Tag(text: model.parameterLabel)
                    Tag(text: model.quantization)
                    Tag(text: model.sizeLabel)
                }
            }
            Spacer(minLength: 8)
            Button("Load", action: onLoad)
                .buttonStyle(.bordered)
        }
    }
}

private struct StatTile: View {
    var title: String
    var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption2.weight(.bold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct Tag: View {
    var text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color(.tertiarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

private struct FlowLayout<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) {
                content
            }
            VStack(alignment: .leading, spacing: 10) {
                content
            }
        }
    }
}

private extension View {
    func cardStyle() -> some View {
        self
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.background)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}
