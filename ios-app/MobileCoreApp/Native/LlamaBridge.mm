#import "LlamaBridge.h"

#include "llama.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

static NSString *JSONStringFromObject(id object) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:object options:0 error:nil];
    if (data == nil) {
        return @"{}";
    }
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"{}";
}

static std::string UTF8String(NSString *value) {
    if (value == nil) {
        return "";
    }
    const char *utf8 = [value UTF8String];
    return utf8 == nullptr ? "" : utf8;
}

static NSString *NSStringFromStdString(const std::string &value) {
    return [[NSString alloc] initWithBytes:value.data()
                                    length:value.size()
                                  encoding:NSUTF8StringEncoding] ?: @"";
}

static NSString *ModelIdFromPath(NSString *path) {
    NSString *fileName = [[path lastPathComponent] stringByDeletingPathExtension];
    return fileName.length > 0 ? fileName : @"local-model";
}

static NSError *BridgeError(NSInteger code, NSString *message) {
    return [NSError errorWithDomain:@"ai.mobilecore.ios.llama"
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: message}];
}

static long long ElapsedMs(std::chrono::steady_clock::time_point start,
                           std::chrono::steady_clock::time_point end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

static NSString *PromptFromMessages(NSArray *messages) {
    NSMutableString *prompt = [NSMutableString string];
    for (NSDictionary *message in messages) {
        if (![message isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        NSString *role = message[@"role"];
        NSString *content = message[@"content"];
        if (![content isKindOfClass:[NSString class]] || content.length == 0) {
            continue;
        }

        if ([role isKindOfClass:[NSString class]] && role.length > 0) {
            [prompt appendFormat:@"%@: %@\n", role, content];
        } else {
            [prompt appendFormat:@"%@\n", content];
        }
    }

    if (prompt.length == 0) {
        return @"user: Hello\nassistant:";
    }
    [prompt appendString:@"assistant:"];
    return prompt;
}

static NSArray *MessagesFromJSON(NSString *messagesJSON) {
    NSData *data = [messagesJSON dataUsingEncoding:NSUTF8StringEncoding];
    if (data == nil) {
        return @[];
    }

    id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [parsed isKindOfClass:[NSArray class]] ? parsed : @[];
}

static NSDictionary *OptionsFromJSON(NSString *optionsJSON) {
    NSData *data = [optionsJSON dataUsingEncoding:NSUTF8StringEncoding];
    if (data == nil) {
        return @{};
    }

    id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [parsed isKindOfClass:[NSDictionary class]] ? parsed : @{};
}

@implementation LlamaBridge {
    NSString *_activeModelPath;
    NSString *_activeModelId;
    NSInteger _contextLength;
    BOOL _backendInitialized;
    llama_model *_model;
    llama_context *_context;
    long long _lastPromptEvalMs;
    long long _lastFirstTokenMs;
    long long _lastDecodeMs;
    long long _lastTotalMs;
    double _lastDecodeTokensPerSecond;
    int32_t _lastPromptTokens;
    int32_t _lastCompletionTokens;
    long long _memoryPeakMb;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _activeModelId = @"none";
    }
    return self;
}

- (void)dealloc {
    [self unloadModel];
    if (_backendInitialized) {
        llama_backend_free();
    }
}

- (void)ensureBackendInitialized {
    if (!_backendInitialized) {
        llama_backend_init();
        _backendInitialized = YES;
    }
}

- (NSString *)backendInfo {
    return JSONStringFromObject(@{
        @"id": @"llama.cpp",
        @"name": @"llama.cpp",
        @"version": @"linked",
        @"mode": @"objective-c++",
        @"status": @"linked",
        @"llama_linked": @YES,
        @"model_loaded": @(_model != nullptr && _context != nullptr),
        @"active_model": _activeModelId ?: @"none",
        @"active_model_path": _activeModelPath ?: @"",
        @"context_length": @(_contextLength),
        @"last_prompt_eval_ms": @(_lastPromptEvalMs),
        @"last_first_token_ms": @(_lastFirstTokenMs),
        @"last_decode_ms": @(_lastDecodeMs),
        @"last_total_ms": @(_lastTotalMs),
        @"last_decode_tokens_per_second": @(_lastDecodeTokensPerSecond),
        @"last_prompt_tokens": @(_lastPromptTokens),
        @"last_completion_tokens": @(_lastCompletionTokens),
        @"memory_peak_mb": @(_memoryPeakMb),
        @"system_info": [NSString stringWithUTF8String:llama_print_system_info()]
    });
}

- (BOOL)loadModelAtPath:(NSString *)path contextLength:(NSInteger)contextLength error:(NSError **)error {
    @synchronized (self) {
        if (path.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
            if (error != nil) {
                *error = BridgeError(1001, @"GGUF model file does not exist.");
            }
            return NO;
        }

        [self ensureBackendInitialized];
        [self unloadModel];

        const auto startedAt = std::chrono::steady_clock::now();
        const std::string modelPath = UTF8String(path);

        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        _model = llama_model_load_from_file(modelPath.c_str(), modelParams);
        if (_model == nullptr) {
            if (error != nil) {
                *error = BridgeError(1002, @"llama_model_load_from_file failed.");
            }
            return NO;
        }

        const uint32_t nCtx = static_cast<uint32_t>(std::max<NSInteger>(128, contextLength));
        const int threadCount = static_cast<int>(std::max<NSInteger>(
            1,
            std::min<NSInteger>([NSProcessInfo processInfo].processorCount, 6)
        ));

        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = nCtx;
        contextParams.n_batch = nCtx;
        contextParams.n_ubatch = std::min<uint32_t>(nCtx, 512);
        contextParams.n_threads = threadCount;
        contextParams.n_threads_batch = threadCount;

        _context = llama_init_from_model(_model, contextParams);
        if (_context == nullptr) {
            llama_model_free(_model);
            _model = nullptr;
            if (error != nil) {
                *error = BridgeError(1003, @"llama_init_from_model failed.");
            }
            return NO;
        }

        _activeModelPath = [path copy];
        _activeModelId = ModelIdFromPath(path);
        _contextLength = contextLength;
        _memoryPeakMb = static_cast<long long>(llama_model_size(_model) / (1024 * 1024));
        _lastTotalMs = ElapsedMs(startedAt, std::chrono::steady_clock::now());
        return YES;
    }
}

- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON optionsJSON:(NSString *)optionsJSON error:(NSError **)error {
    @synchronized (self) {
        if (_model == nullptr || _context == nullptr) {
            if (error != nil) {
                *error = BridgeError(2001, @"Load a GGUF model before calling chat.");
            }
            return @"{}";
        }

        NSDictionary *options = OptionsFromJSON(optionsJSON);
        NSArray *messages = MessagesFromJSON(messagesJSON);
        NSString *requestedModel = [options[@"model"] isKindOfClass:[NSString class]]
            ? options[@"model"]
            : (_activeModelId ?: @"local-model");
        NSString *prompt = PromptFromMessages(messages);
        NSInteger requestedMaxTokens = [options[@"max_tokens"] respondsToSelector:@selector(integerValue)]
            ? [options[@"max_tokens"] integerValue]
            : 128;
        const int32_t maxTokens = static_cast<int32_t>(std::max<NSInteger>(1, std::min<NSInteger>(requestedMaxTokens, 256)));

        const auto totalStart = std::chrono::steady_clock::now();
        const llama_vocab *vocab = llama_model_get_vocab(_model);
        const std::string promptText = UTF8String(prompt);
        const int32_t promptTokenCount = -llama_tokenize(
            vocab,
            promptText.c_str(),
            static_cast<int32_t>(promptText.size()),
            nullptr,
            0,
            true,
            true
        );

        if (promptTokenCount <= 0) {
            if (error != nil) {
                *error = BridgeError(2002, @"llama_tokenize failed.");
            }
            return @"{}";
        }

        std::vector<llama_token> promptTokens(promptTokenCount);
        const int32_t tokenized = llama_tokenize(
            vocab,
            promptText.c_str(),
            static_cast<int32_t>(promptText.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            true,
            true
        );
        if (tokenized < 0) {
            if (error != nil) {
                *error = BridgeError(2003, @"Prompt tokenization overflowed.");
            }
            return @"{}";
        }

        llama_memory_clear(llama_get_memory(_context), true);

        llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        llama_batch batch = llama_batch_get_one(promptTokens.data(), static_cast<int32_t>(promptTokens.size()));
        const auto promptEvalStart = std::chrono::steady_clock::now();
        if (llama_model_has_encoder(_model)) {
            if (llama_encode(_context, batch) != 0) {
                llama_sampler_free(sampler);
                if (error != nil) {
                    *error = BridgeError(2004, @"llama_encode failed.");
                }
                return @"{}";
            }

            llama_token decoderStartToken = llama_model_decoder_start_token(_model);
            if (decoderStartToken == LLAMA_TOKEN_NULL) {
                decoderStartToken = llama_vocab_bos(vocab);
            }
            batch = llama_batch_get_one(&decoderStartToken, 1);
            if (llama_decode(_context, batch) != 0) {
                llama_sampler_free(sampler);
                if (error != nil) {
                    *error = BridgeError(2005, @"llama_decode decoder start failed.");
                }
                return @"{}";
            }
        } else if (llama_decode(_context, batch) != 0) {
            llama_sampler_free(sampler);
            if (error != nil) {
                *error = BridgeError(2006, @"llama_decode prompt eval failed.");
            }
            return @"{}";
        }
        const auto promptEvalEnd = std::chrono::steady_clock::now();

        std::string generated;
        int32_t decodedTokens = 0;
        bool stoppedByLength = false;
        long long firstTokenMs = 0;
        const auto decodeStart = std::chrono::steady_clock::now();

        while (decodedTokens < maxTokens) {
            llama_token token = llama_sampler_sample(sampler, _context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }

            std::vector<char> piece(256);
            int32_t pieceSize = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            if (pieceSize < 0) {
                piece.resize(static_cast<size_t>(-pieceSize));
                pieceSize = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
            }
            if (pieceSize > 0) {
                generated.append(piece.data(), static_cast<size_t>(pieceSize));
            }

            batch = llama_batch_get_one(&token, 1);
            llama_sampler_accept(sampler, token);
            decodedTokens += 1;
            if (decodedTokens == 1) {
                firstTokenMs = ElapsedMs(totalStart, std::chrono::steady_clock::now());
            }
            if (decodedTokens >= maxTokens) {
                stoppedByLength = true;
                break;
            }
            if (llama_decode(_context, batch) != 0) {
                llama_sampler_free(sampler);
                if (error != nil) {
                    *error = BridgeError(2007, @"llama_decode token step failed.");
                }
                return @"{}";
            }
        }

        const auto decodeEnd = std::chrono::steady_clock::now();
        llama_sampler_free(sampler);

        const long long promptEvalMs = ElapsedMs(promptEvalStart, promptEvalEnd);
        const long long decodeMs = ElapsedMs(decodeStart, decodeEnd);
        const long long totalMs = ElapsedMs(totalStart, decodeEnd);
        const double decodeTokensPerSecond = decodeMs > 0
            ? static_cast<double>(decodedTokens) * 1000.0 / static_cast<double>(decodeMs)
            : 0.0;

        _lastPromptEvalMs = promptEvalMs;
        _lastFirstTokenMs = firstTokenMs;
        _lastDecodeMs = decodeMs;
        _lastTotalMs = totalMs;
        _lastDecodeTokensPerSecond = decodeTokensPerSecond;
        _lastPromptTokens = promptTokenCount;
        _lastCompletionTokens = decodedTokens;
        _memoryPeakMb = static_cast<long long>(llama_model_size(_model) / (1024 * 1024));

        return JSONStringFromObject(@{
            @"model": _activeModelId ?: requestedModel,
            @"message": NSStringFromStdString(generated),
            @"finish_reason": stoppedByLength ? @"length" : @"stop",
            @"prompt_tokens": @(promptTokenCount),
            @"completion_tokens": @(decodedTokens),
            @"prompt_eval_ms": @(promptEvalMs),
            @"first_token_ms": @(firstTokenMs),
            @"decode_ms": @(decodeMs),
            @"total_ms": @(totalMs),
            @"decode_tokens_per_second": @(decodeTokensPerSecond),
            @"memory_peak_mb": @(_memoryPeakMb),
            @"context_length": @(_contextLength)
        });
    }
}

- (void)unloadModel {
    @synchronized (self) {
        if (_context != nullptr) {
            llama_free(_context);
            _context = nullptr;
        }
        if (_model != nullptr) {
            llama_model_free(_model);
            _model = nullptr;
        }
        _activeModelPath = nil;
        _activeModelId = @"none";
        _contextLength = 0;
    }
}

@end
