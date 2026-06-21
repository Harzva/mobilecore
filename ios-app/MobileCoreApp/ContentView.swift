import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var appState: MobileCoreAppState
    @State private var isImporterPresented = false

    private let actionColumns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10)
    ]

    private var ggufTypes: [UTType] {
        [UTType(filenameExtension: "gguf") ?? .data]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    hero
                    metricStrip
                    controlsSection
                    modelsSection
                    inferenceSection
                    if !appState.lastReply.isEmpty {
                        replySection
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 16)
            }
            .background(Palette.background.ignoresSafeArea())
            .navigationTitle("TuiMa")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Palette.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        appState.refreshModels()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(appState.isBusy)
                    .accessibilityLabel("Refresh")
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

    private var hero: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 0) {
                        Text("Tui")
                            .foregroundStyle(Palette.ink)
                        Text("Ma")
                            .foregroundStyle(Palette.blue)
                    }
                    .font(.system(size: 34, weight: .black, design: .rounded))

                    Text("MobileCore Runtime")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Palette.muted)
                }

                Spacer(minLength: 8)

                StatusPill(
                    title: appState.isServerRunning ? "API online" : "API offline",
                    systemImage: appState.isServerRunning ? "bolt.horizontal.circle.fill" : "bolt.horizontal.circle",
                    tint: appState.isServerRunning ? Palette.mintDark : Palette.muted
                )
            }

            VStack(alignment: .leading, spacing: 7) {
                Text(activeModelTitle)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(Palette.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.72)

                Text(appState.statusMessage)
                    .font(.callout)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }

            if appState.operation.isActive {
                OperationBanner(operation: appState.operation)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Palette.mintWash, Palette.blueWash, Palette.lavenderWash],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Palette.stroke)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private var metricStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                MetricCard(
                    title: "Loaded model",
                    value: activeModelTitle,
                    caption: "\(appState.models.count) GGUF ready",
                    systemImage: "shippingbox",
                    tint: Palette.mintDark
                )
                MetricCard(
                    title: "Last speed",
                    value: speedLabel,
                    caption: "local decode",
                    systemImage: "speedometer",
                    tint: Palette.blue
                )
                MetricCard(
                    title: "API status",
                    value: appState.isServerRunning ? "Online" : "Offline",
                    caption: "Port 8080",
                    systemImage: "network",
                    tint: Palette.sky
                )
                MetricCard(
                    title: "Memory peak",
                    value: memoryLabel,
                    caption: appState.backendInfo.mode,
                    systemImage: "memorychip",
                    tint: Palette.lavender
                )
            }
            .padding(.vertical, 1)
        }
    }

    private var controlsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Controls", subtitle: "Local runtime", systemImage: "slider.horizontal.3")
            LazyVGrid(columns: actionColumns, spacing: 10) {
                ControlTile(
                    title: "Import GGUF",
                    subtitle: "Choose model",
                    systemImage: "square.and.arrow.down",
                    tint: Palette.mintDark,
                    isDisabled: appState.isBusy
                ) {
                    isImporterPresented = true
                }

                ControlTile(
                    title: "Load Model",
                    subtitle: appState.models.isEmpty ? "No GGUF yet" : "First available",
                    systemImage: "play.fill",
                    tint: Palette.blue,
                    isDisabled: appState.isBusy || appState.models.isEmpty
                ) {
                    appState.loadFirstModel()
                }

                ControlTile(
                    title: appState.isServerRunning ? "Stop API" : "Start API",
                    subtitle: "localhost:8080",
                    systemImage: appState.isServerRunning ? "stop.fill" : "network",
                    tint: appState.isServerRunning ? Palette.sky : Palette.lavender,
                    isDisabled: appState.isBusy
                ) {
                    appState.isServerRunning ? appState.stopServer() : appState.startServer()
                }

                ControlTile(
                    title: "Test Chat",
                    subtitle: appState.activeModel == "none" ? "Load first" : "64 tokens",
                    systemImage: "message.badge.waveform",
                    tint: Palette.mintDark,
                    isDisabled: appState.isBusy || appState.activeModel == "none"
                ) {
                    appState.runTestChat()
                }
            }
        }
        .sectionSurface()
    }

    private var modelsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Models", subtitle: "GGUF library", systemImage: "cube.box")
            if appState.models.isEmpty {
                EmptyState(
                    title: "No local model",
                    subtitle: "Import or push a GGUF file into the app model library.",
                    systemImage: "doc.badge.plus"
                )
            } else {
                VStack(spacing: 0) {
                    ForEach(appState.models) { model in
                        ModelRow(model: model, isBusy: appState.isBusy) {
                            appState.loadModel(model)
                        }
                        if model.id != appState.models.last?.id {
                            Divider()
                                .padding(.leading, 52)
                        }
                    }
                }
            }
        }
        .sectionSurface()
    }

    private var inferenceSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Inference", subtitle: "Last run", systemImage: "waveform.path.ecg")
            LazyVGrid(columns: actionColumns, spacing: 10) {
                StatTile(title: "Tok/s", value: String(format: "%.2f", appState.lastMetrics.decodeTokensPerSecond))
                StatTile(title: "TTFT", value: "\(appState.lastMetrics.firstTokenMs) ms")
                StatTile(title: "Decode", value: "\(appState.lastMetrics.decodeMs) ms")
                StatTile(title: "Total", value: "\(appState.lastMetrics.totalMs) ms")
            }
        }
        .sectionSurface()
    }

    private var replySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Reply", subtitle: "Generated text", systemImage: "text.bubble")
            Text(appState.lastReply)
                .font(.body)
                .foregroundStyle(Palette.ink)
                .lineSpacing(3)
                .textSelection(.enabled)
        }
        .sectionSurface()
    }

    private var activeModelTitle: String {
        appState.activeModel == "none" ? "No model loaded" : appState.activeModel
    }

    private var speedLabel: String {
        appState.lastMetrics.decodeTokensPerSecond > 0
            ? String(format: "%.2f tok/s", appState.lastMetrics.decodeTokensPerSecond)
            : "0.00 tok/s"
    }

    private var memoryLabel: String {
        appState.lastMetrics.memoryPeakMb > 0 ? "\(appState.lastMetrics.memoryPeakMb) MB" : "n/a"
    }
}

private struct SectionHeader: View {
    var title: String
    var subtitle: String
    var systemImage: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Palette.blue)
                .frame(width: 28, height: 28)
                .background(Palette.blueWash)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(Palette.ink)
                Text(subtitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Palette.muted)
            }
            Spacer()
        }
    }
}

private struct StatusPill: View {
    var title: String
    var systemImage: String
    var tint: Color

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.caption.weight(.bold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(tint.opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct OperationBanner: View {
    var operation: MobileCoreOperation

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Palette.surface)
                ProgressView()
                    .tint(Palette.blue)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 3) {
                Label(operation.title, systemImage: operation.systemImage)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Palette.ink)
                Text(operation.detail)
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(Palette.surface.opacity(0.86))
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Palette.stroke)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct MetricCard: View {
    var title: String
    var value: String
    var caption: String
    var systemImage: String
    var tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            IconBadge(systemImage: systemImage, tint: tint)
            VStack(alignment: .leading, spacing: 4) {
                Text(title.uppercased())
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Palette.muted)
                Text(value)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(Palette.ink)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
            }
        }
        .frame(width: 140, height: 154, alignment: .topLeading)
        .padding(14)
        .background(Palette.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Palette.stroke)
        )
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct ControlTile: View {
    var title: String
    var subtitle: String
    var systemImage: String
    var tint: Color
    var isDisabled: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                IconBadge(systemImage: systemImage, tint: tint)
                Spacer(minLength: 4)
                Text(title)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Palette.ink)
                    .lineLimit(1)
                    .minimumScaleFactor(0.76)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.76)
            }
            .frame(maxWidth: .infinity, minHeight: 112, alignment: .topLeading)
            .padding(14)
            .background(Palette.surface)
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(Palette.stroke)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.55 : 1)
    }
}

private struct ModelRow: View {
    var model: ModelMetadata
    var isBusy: Bool
    var onLoad: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            IconBadge(
                systemImage: model.loaded ? "checkmark.circle.fill" : "cube",
                tint: model.loaded ? Palette.mintDark : Palette.blue
            )

            VStack(alignment: .leading, spacing: 7) {
                Text(model.fileName)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Palette.ink)
                    .lineLimit(2)
                    .truncationMode(.middle)

                FlowLayout {
                    Tag(text: model.parameterLabel)
                    Tag(text: model.quantization)
                    Tag(text: model.sizeLabel)
                    Tag(text: model.architecture)
                }
            }

            Spacer(minLength: 8)

            Button {
                onLoad()
            } label: {
                Label(model.loaded ? "Loaded" : "Load", systemImage: model.loaded ? "checkmark" : "arrow.right")
                    .font(.caption.weight(.bold))
                    .labelStyle(.titleAndIcon)
            }
            .buttonStyle(.bordered)
            .tint(model.loaded ? Palette.mintDark : Palette.blue)
            .disabled(isBusy || model.loaded)
        }
        .padding(.vertical, 12)
    }
}

private struct StatTile: View {
    var title: String
    var value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption2.weight(.bold))
                .foregroundStyle(Palette.muted)
            Text(value)
                .font(.title3.weight(.bold))
                .foregroundStyle(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.64)
        }
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .padding(12)
        .background(Palette.blueWash.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct Tag: View {
    var text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(Palette.muted)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Palette.mintPale)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

private struct IconBadge: View {
    var systemImage: String
    var tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(tint)
            .frame(width: 34, height: 34)
            .background(tint.opacity(0.13))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct EmptyState: View {
    var title: String
    var subtitle: String
    var systemImage: String

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(systemImage: systemImage, tint: Palette.sky)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Palette.ink)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(14)
        .background(Palette.blueWash.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

private struct FlowLayout<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) {
                content
            }
            VStack(alignment: .leading, spacing: 6) {
                content
            }
        }
    }
}

private enum Palette {
    static let background = Color(red: 0.984, green: 0.992, blue: 1.000)
    static let surface = Color.white
    static let ink = Color(red: 0.078, green: 0.141, blue: 0.247)
    static let muted = Color(red: 0.412, green: 0.478, blue: 0.584)
    static let stroke = Color(red: 0.898, green: 0.933, blue: 0.969)
    static let mintDark = Color(red: 0.141, green: 0.667, blue: 0.541)
    static let mintPale = Color(red: 0.937, green: 1.000, blue: 0.976)
    static let sky = Color(red: 0.263, green: 0.820, blue: 0.910)
    static let blue = Color(red: 0.420, green: 0.549, blue: 1.000)
    static let lavender = Color(red: 0.714, green: 0.612, blue: 1.000)
    static let mintWash = Color(red: 0.941, green: 1.000, blue: 0.976)
    static let blueWash = Color(red: 0.933, green: 0.969, blue: 1.000)
    static let lavenderWash = Color(red: 0.957, green: 0.945, blue: 1.000)
}

private extension View {
    func sectionSurface() -> some View {
        self
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface)
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(Palette.stroke)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}
