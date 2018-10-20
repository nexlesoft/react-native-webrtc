
#import <Foundation/Foundation.h>
#import <WebRTC/RTCCameraVideoCapturer.h>

#import <React/RCTBridge.h>
#import <React/RCTEventDispatcher.h>
#import <React/RCTLog.h>
#import <React/RCTUtils.h>

@interface VideoCaptureController : NSObject

-(void)takePicture:(NSDictionary *)options
    successCallback:(RCTResponseSenderBlock)successCallback
     errorCallback: (RCTResponseSenderBlock)errorCallback;

-(instancetype)initWithCapturer:(RTCCameraVideoCapturer *)capturer
                 andConstraints:(NSDictionary *)constraints;
-(void)startCapture;
-(void)stopCapture;
-(void)switchCamera;

@end
