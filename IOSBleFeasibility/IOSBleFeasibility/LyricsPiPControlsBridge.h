#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, LyricsPiPControlsApplyResult) {
    LyricsPiPControlsApplyResultApplied,
    LyricsPiPControlsApplyResultUnsupported,
    LyricsPiPControlsApplyResultFailed
};

/// Debug-only bridge for the explicitly requested local PiP appearance experiment.
@interface LyricsPiPControlsBridge : NSObject
+ (LyricsPiPControlsApplyResult)applyToObject:(NSObject *)object NS_SWIFT_NAME(apply(to:));
@end

NS_ASSUME_NONNULL_END
