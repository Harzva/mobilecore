#import "LlamaBridge.h"

#include <string>

static NSString *JSONStringFromObject(id object) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:object options:0 error:nil];
    if (data == nil) {
        return @"{}";
    }
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"{}";
}

static std::string MobileCoreStubVersion() {
    return "llama.cpp-objective-cpp-stub";
}

@implementation LlamaBridge {
    NSString *_activeModelPath;
    NSInteger _contextLength;
}

- (NSString *)backendInfo {
    return JSONStringFromObject(@{
        @"id": @"llama.cpp",
        @"name": @"llama.cpp",
        @"version": [NSString stringWithUTF8String:MobileCoreStubVersion().c_str()],
        @"mode": @"objective-c++-stub"
    });
}

- (BOOL)loadModelAtPath:(NSString *)path contextLength:(NSInteger)contextLength error:(NSError **)error {
    if (path.length == 0 || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        if (error != nil) {
            *error = [NSError errorWithDomain:@"ai.mobilecore.ios.llama"
                                         code:1001
                                     userInfo:@{NSLocalizedDescriptionKey: @"GGUF model file does not exist."}];
        }
        return NO;
    }

    _activeModelPath = [path copy];
    _contextLength = contextLength;
    return YES;
}

- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON optionsJSON:(NSString *)optionsJSON error:(NSError **)error {
    NSData *messagesData = [messagesJSON dataUsingEncoding:NSUTF8StringEncoding];
    NSData *optionsData = [optionsJSON dataUsingEncoding:NSUTF8StringEncoding];
    NSArray *messages = @[];
    NSDictionary *options = @{};

    if (messagesData != nil) {
        id parsedMessages = [NSJSONSerialization JSONObjectWithData:messagesData options:0 error:nil];
        if ([parsedMessages isKindOfClass:[NSArray class]]) {
            messages = parsedMessages;
        }
    }
    if (optionsData != nil) {
        id parsedOptions = [NSJSONSerialization JSONObjectWithData:optionsData options:0 error:nil];
        if ([parsedOptions isKindOfClass:[NSDictionary class]]) {
            options = parsedOptions;
        }
    }

    NSString *model = options[@"model"] ?: @"local-model";
    NSString *lastPrompt = @"";
    for (NSDictionary *message in messages) {
        if (![message isKindOfClass:[NSDictionary class]]) {
            continue;
        }
        NSString *role = message[@"role"];
        NSString *content = message[@"content"];
        if ([role isEqualToString:@"user"] && [content isKindOfClass:[NSString class]]) {
            lastPrompt = content;
        }
    }

    NSInteger maxTokens = [options[@"max_tokens"] integerValue];
    if (maxTokens <= 0) {
        maxTokens = 128;
    }

    NSInteger promptTokens = MAX((NSInteger)ceil((double)lastPrompt.length / 4.0), 1);
    NSInteger completionTokens = MIN(MAX(maxTokens / 8, 8), 32);
    NSInteger promptEvalMs = 18 + promptTokens;
    NSInteger firstTokenMs = promptEvalMs + 22;
    NSInteger decodeMs = completionTokens * 9;
    NSInteger totalMs = firstTokenMs + decodeMs;
    double tokensPerSecond = completionTokens > 0 ? ((double)completionTokens / ((double)decodeMs / 1000.0)) : 0;

    NSString *loadedName = _activeModelPath.length > 0
        ? [[_activeModelPath lastPathComponent] stringByDeletingPathExtension]
        : model;
    NSString *reply = [NSString stringWithFormat:
        @"MobileCore iOS bridge stub is alive. Loaded model: %@. Prompt preview: %@",
        loadedName,
        lastPrompt.length > 80 ? [[lastPrompt substringToIndex:80] stringByAppendingString:@"..."] : lastPrompt
    ];

    return JSONStringFromObject(@{
        @"model": loadedName,
        @"message": reply,
        @"finish_reason": @"stop",
        @"prompt_tokens": @(promptTokens),
        @"completion_tokens": @(completionTokens),
        @"prompt_eval_ms": @(promptEvalMs),
        @"first_token_ms": @(firstTokenMs),
        @"decode_ms": @(decodeMs),
        @"total_ms": @(totalMs),
        @"decode_tokens_per_second": @(tokensPerSecond),
        @"memory_peak_mb": @(0),
        @"context_length": @(_contextLength)
    });
}

- (void)unloadModel {
    _activeModelPath = nil;
    _contextLength = 0;
}

@end
