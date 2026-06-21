#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LlamaBridge : NSObject
- (NSString *)backendInfo;
- (BOOL)loadModelAtPath:(NSString *)path contextLength:(NSInteger)contextLength error:(NSError **)error;
- (NSString *)chatWithMessagesJSON:(NSString *)messagesJSON optionsJSON:(NSString *)optionsJSON error:(NSError **)error;
- (void)unloadModel;
@end

NS_ASSUME_NONNULL_END
