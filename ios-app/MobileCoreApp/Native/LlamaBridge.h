#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef BOOL (^LlamaBridgeTokenHandler)(NSString *tokenText, NSInteger tokenIndex);

@interface LlamaBridge : NSObject
- (NSString *)backendInfo;
- (BOOL)loadModelAtPath:(NSString *)path contextLength:(NSInteger)contextLength error:(NSError **)error;
- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON optionsJSON:(NSString *)optionsJSON error:(NSError **)error;
- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON
                        optionsJSON:(NSString *)optionsJSON
                       tokenHandler:(nullable LlamaBridgeTokenHandler)tokenHandler
                              error:(NSError **)error;
- (void)cancelCurrentOperation;
- (void)unloadModel;
@end

NS_ASSUME_NONNULL_END
