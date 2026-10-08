#import "LyricsPiPControlsBridge.h"

#if DEBUG
@implementation LyricsPiPControlsBridge
+ (LyricsPiPControlsApplyResult)applyToObject:(NSObject *)object {
    // controlsStyle is undocumented. Probe before using KVC and catch
    // Objective-C exceptions here; Swift's do/catch cannot catch them.
    if (![object respondsToSelector:NSSelectorFromString(@"setControlsStyle:")] ||
        ![object respondsToSelector:NSSelectorFromString(@"controlsStyle")]) {
        return LyricsPiPControlsApplyResultUnsupported;
    }
    id previous = nil;
    @try {
        previous = [object valueForKey:@"controlsStyle"];
        // Style 1 hides playback/seek/progress UI while keeping close/restore.
        // Style 2 also hides those window actions and must not be used here.
        [object setValue:@1 forKey:@"controlsStyle"];
        id value = [object valueForKey:@"controlsStyle"];
        if ([value isKindOfClass:NSNumber.class] && [value integerValue] == 1) {
            return LyricsPiPControlsApplyResultApplied;
        }
    } @catch (NSException *exception) {
        // Unsupported system behavior must leave lyric display usable.
    }
    if (previous != nil) {
        @try {
            [object setValue:previous forKey:@"controlsStyle"];
        } @catch (NSException *exception) {
            // Best effort only: never propagate a private API exception.
        }
    }
    return LyricsPiPControlsApplyResultFailed;
}
@end
#endif
