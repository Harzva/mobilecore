import Foundation
import Network

final class LocalAPIServer {
    private let router: OpenAICompatibleRouter
    private let queue = DispatchQueue(label: "ai.mobilecore.ios.local-api")
    private var listener: NWListener?
    private let allowedCorsOrigins = Set([
        "https://harzva.github.io",
        "http://localhost:5173",
        "http://127.0.0.1:5173"
    ])

    private(set) var port: UInt16 = 8080
    private(set) var isRunning = false

    init(runtime: LlamaRuntime, modelManager: ModelManager) {
        self.router = OpenAICompatibleRouter(runtime: runtime, modelManager: modelManager)
    }

    func start(port: UInt16 = 8080) throws {
        guard listener == nil else {
            return
        }

        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw LocalAPIServerError.invalidPort
        }

        let listener = try NWListener(using: .tcp, on: nwPort)
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.isRunning = true
            case .failed, .cancelled:
                self?.isRunning = false
            default:
                break
            }
        }
        listener.start(queue: queue)
        self.listener = listener
        self.port = port
        isRunning = true
    }

    func stop() {
        listener?.cancel()
        listener = nil
        isRunning = false
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else {
                connection.cancel()
                return
            }

            var nextBuffer = buffer
            if let data {
                nextBuffer.append(data)
            }

            if let request = HTTPRequest.parse(nextBuffer) {
                let response = self.router.route(request)
                self.send(response, for: request, on: connection)
                return
            }

            if error != nil || isComplete {
                self.send(.json(statusCode: 400, object: ["error": ["message": "bad request"]]), for: nil, on: connection)
                return
            }

            self.receive(connection, buffer: nextBuffer)
        }
    }

    private func send(_ response: HTTPResponsePayload, for request: HTTPRequest?, on connection: NWConnection) {
        let data = serialize(response, origin: request?.headers["origin"])
        connection.send(content: data, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func serialize(_ response: HTTPResponsePayload, origin: String?) -> Data {
        var headers = response.headers
        headers["Content-Length"] = "\(response.body.count)"
        headers["Connection"] = "close"
        headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-MobileCore-Client"
        headers["Access-Control-Allow-Private-Network"] = "true"
        headers["Access-Control-Max-Age"] = "86400"
        if let origin, allowedCorsOrigins.contains(origin) {
            headers["Access-Control-Allow-Origin"] = origin
            headers["Vary"] = "Origin"
        }

        var head = "HTTP/1.1 \(response.statusCode) \(response.reason)\r\n"
        for key in headers.keys.sorted() {
            head += "\(key): \(headers[key] ?? "")\r\n"
        }
        head += "\r\n"

        var data = Data(head.utf8)
        data.append(response.body)
        return data
    }
}

struct HTTPRequest {
    var method: String
    var path: String
    var pathWithQuery: String
    var headers: [String: String]
    var body: Data

    static func parse(_ data: Data) -> HTTPRequest? {
        let separator = Data("\r\n\r\n".utf8)
        guard let headerRange = data.range(of: separator) else {
            return nil
        }

        let headerData = data.subdata(in: data.startIndex..<headerRange.lowerBound)
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            return nil
        }

        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            return nil
        }
        let parts = requestLine.split(separator: " ", maxSplits: 2).map(String.init)
        guard parts.count >= 2 else {
            return nil
        }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let separatorIndex = line.firstIndex(of: ":") else {
                continue
            }
            let key = line[..<separatorIndex].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let value = line[line.index(after: separatorIndex)...].trimmingCharacters(in: .whitespacesAndNewlines)
            headers[key] = value
        }

        let bodyStart = headerRange.upperBound
        let contentLength = Int(headers["content-length"] ?? "0") ?? 0
        guard data.count >= bodyStart + contentLength else {
            return nil
        }

        let body = contentLength > 0
            ? data.subdata(in: bodyStart..<(bodyStart + contentLength))
            : Data()
        let pathWithQuery = parts[1]
        let path = pathWithQuery.split(separator: "?", maxSplits: 1).first.map(String.init) ?? pathWithQuery

        return HTTPRequest(
            method: parts[0].uppercased(),
            path: path,
            pathWithQuery: pathWithQuery,
            headers: headers,
            body: body
        )
    }
}

enum LocalAPIServerError: LocalizedError {
    case invalidPort

    var errorDescription: String? {
        switch self {
        case .invalidPort:
            return "Invalid local API port."
        }
    }
}
