package com.oney.WebRTCModule;

import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;

import android.os.HandlerThread;
import java.util.Base64;
import android.os.Handler;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.HandlerThread;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

import org.webrtc.*;

/**
 * The implementation of {@code getUserMedia} extracted into a separate file in
 * order to reduce complexity and to (somewhat) separate concerns.
 */
class GetUserMediaImpl {
    /**
     * The {@link Log} tag with which {@code GetUserMediaImpl} is to log.
     */
    private static final String TAG = WebRTCModule.TAG;

    private final CameraEnumerator cameraEnumerator;
    private final ReactApplicationContext reactContext;

    /**
     * FIXME: To add doc
     */
    private final HandlerThread imageProcessingThread;
    private Handler imageProcessingHandler;

    /**
     * The application/library-specific private members of local
     * {@link MediaStreamTrack}s created by {@code GetUserMediaImpl} mapped by
     * track ID.
     */
    private final Map<String, TrackPrivate> tracks = new HashMap<>();

    private final WebRTCModule webRTCModule;

    GetUserMediaImpl(
            WebRTCModule webRTCModule,
            ReactApplicationContext reactContext) {
        this.webRTCModule = webRTCModule;
        this.reactContext = reactContext;

        // NOTE: to support Camera2, the device should:
        //   1. Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        //   2. all camera support level should greater than LEGACY
        //   see: https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics.html#INFO_SUPPORTED_HARDWARE_LEVEL
        if (Camera2Enumerator.isSupported(reactContext)) {
            Log.d(TAG, "Creating video capturer using Camera2 API.");
            cameraEnumerator = new Camera2Enumerator(reactContext);
        } else {
            Log.d(TAG, "Creating video capturer using Camera1 API.");
            cameraEnumerator = new Camera1Enumerator(false);
        }

        imageProcessingThread = new HandlerThread("SnapshotThread");
        imageProcessingThread.start();
        imageProcessingHandler = new Handler(imageProcessingThread.getLooper());
    }

    private AudioTrack createAudioTrack(ReadableMap constraints) {
        MediaConstraints audioConstraints
            = webRTCModule.parseMediaConstraints(constraints.getMap("audio"));

        Log.d(TAG, "getUserMedia(audio): " + audioConstraints);

        String id = UUID.randomUUID().toString();
        PeerConnectionFactory pcFactory = webRTCModule.mFactory;
        AudioSource audioSource = pcFactory.createAudioSource(audioConstraints);
        AudioTrack track = pcFactory.createAudioTrack(id, audioSource);
        tracks.put(
            id,
            new TrackPrivate(track, audioSource, /* videoCapturer */ null));

        return track;
    }

    private VideoTrack createVideoTrack(ReadableMap constraints) {
        ReadableMap videoConstraintsMap = constraints.getMap("video");

        Log.d(TAG, "getUserMedia(video): " + videoConstraintsMap);

        VideoCaptureController videoCaptureController
            = new VideoCaptureController(cameraEnumerator, videoConstraintsMap);
        VideoCapturer videoCapturer = videoCaptureController.getVideoCapturer();
        if (videoCapturer == null) {
            return null;
        }

        PeerConnectionFactory pcFactory = webRTCModule.mFactory;
        VideoSource videoSource = pcFactory.createVideoSource(videoCapturer);

        String id = UUID.randomUUID().toString();
        VideoTrack track = pcFactory.createVideoTrack(id, videoSource);

        track.setEnabled(true);
        videoCaptureController.startCapture();

        tracks.put(id, new TrackPrivate(track, videoSource, videoCaptureController));

        return track;
    }

    ReadableArray enumerateDevices() {
        WritableArray array = Arguments.createArray();
        String[] devices = cameraEnumerator.getDeviceNames();

        for(int i = 0; i < devices.length; ++i) {
            WritableMap params = Arguments.createMap();
            params.putString("deviceId", "" + i);
            params.putString("groupId", "");
            params.putString("label", devices[i]);
            params.putString("kind", "videoinput");
            array.pushMap(params);
        }

        WritableMap audio = Arguments.createMap();
        audio.putString("deviceId", "audio-1");
        audio.putString("groupId", "");
        audio.putString("label", "Audio");
        audio.putString("kind", "audioinput");
        array.pushMap(audio);

        return array;
    }

    private ReactApplicationContext getReactApplicationContext() {
        return reactContext;
    }

    MediaStreamTrack getTrack(String id) {
        TrackPrivate private_ = tracks.get(id);

        return private_ == null ? null : private_.track;
    }

    /**
     * Implements {@code getUserMedia}. Note that at this point constraints have
     * been normalized and permissions have been granted. The constraints only
     * contain keys for which permissions have already been granted, that is,
     * if audio permission was not granted, there will be no "audio" key in
     * the constraints map.
     */
    void getUserMedia(
            final ReadableMap constraints,
            final Callback successCallback,
            final Callback errorCallback) {
        // TODO: change getUserMedia constraints format to support new syntax
        //   constraint format seems changed, and there is no mandatory any more.
        //   and has a new syntax/attrs to specify resolution
        //   should change `parseConstraints()` according
        //   see: https://www.w3.org/TR/mediacapture-streams/#idl-def-MediaTrackConstraints

        AudioTrack audioTrack = null;
        VideoTrack videoTrack = null;

        if (constraints.hasKey("audio")) {
            audioTrack = createAudioTrack(constraints);
        }

        if (constraints.hasKey("video")) {
            videoTrack = createVideoTrack(constraints);
        }

        if (audioTrack == null && videoTrack == null) {
             // Fail with DOMException with name AbortError as per:
             // https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-getusermedia
             errorCallback.invoke("DOMException","AbortError");
             return;
        }

        String streamId = UUID.randomUUID().toString();
        MediaStream mediaStream
            = webRTCModule.mFactory.createLocalMediaStream(streamId);
        WritableArray tracks = Arguments.createArray();

        for (MediaStreamTrack track : new MediaStreamTrack[]{audioTrack, videoTrack}) {
            if (track == null) {
                continue;
            }

            if (track instanceof AudioTrack) {
                mediaStream.addTrack((AudioTrack) track);
            } else {
                mediaStream.addTrack((VideoTrack) track);
            }

            WritableMap track_ = Arguments.createMap();
            String trackId = track.id();

            track_.putBoolean("enabled", track.enabled());
            track_.putString("id", trackId);
            track_.putString("kind", track.kind());
            track_.putString("label", trackId);
            track_.putString("readyState", track.state().toString());
            track_.putBoolean("remote", false);
            tracks.pushMap(track_);
        }

        Log.d(TAG, "MediaStream id: " + streamId);
        webRTCModule.localStreams.put(streamId, mediaStream);

        successCallback.invoke(streamId, tracks);
    }

    void mediaStreamTrackSetEnabled(String trackId, final boolean enabled) {
        TrackPrivate track = tracks.get(trackId);
        if (track != null && track.videoCaptureController != null) {
            if (enabled) {
                track.videoCaptureController.startCapture();
            } else {
                track.videoCaptureController.stopCapture();
            }
        }
    }

    void mediaStreamTrackStop(String id) {
        MediaStreamTrack track = getTrack(id);
        if (track == null) {
            Log.d(
                TAG,
                "mediaStreamTrackStop() No local MediaStreamTrack with id "
                    + id);
            return;
        }
        track.setEnabled(false);
        removeTrack(id);
    }

    private void removeTrack(String id) {
        TrackPrivate track = tracks.remove(id);
        if (track != null) {
            VideoCaptureController videoCaptureController
                = track.videoCaptureController;
            if (videoCaptureController != null) {
                if (videoCaptureController.stopCapture()) {
                    videoCaptureController.dispose();
                }
            }
            track.mediaSource.dispose();
        }
    }

    void switchCamera(String trackId) {
        TrackPrivate track = tracks.get(trackId);
        if (track != null && track.videoCaptureController != null) {
            track.videoCaptureController.switchCamera();
        }
    }

    /**
     * Application/library-specific private members of local
     * {@code MediaStreamTrack}s created by {@code GetUserMediaImpl}.
     */
    private static class TrackPrivate {
        /**
         * The {@code MediaSource} from which {@link #track} was created.
         */
        public final MediaSource mediaSource;

        public final MediaStreamTrack track;

        /**
         * The {@code VideoCapturer} from which {@link #mediaSource} was created
         * if {@link #track} is a {@link VideoTrack}.
         */
        public final VideoCaptureController videoCaptureController;

        /**
         * Initializes a new {@code TrackPrivate} instance.
         *
         * @param track
         * @param mediaSource the {@code MediaSource} from which the specified
         * {@code code} was created
         * @param videoCapturer the {@code VideoCapturer} from which the
         * specified {@code mediaSource} was created if the specified
         * {@code track} is a {@link VideoTrack}
         */
        public TrackPrivate(
                MediaStreamTrack track,
                MediaSource mediaSource,
                VideoCaptureController videoCaptureController) {
            this.track = track;
            this.mediaSource = mediaSource;
            this.videoCaptureController = videoCaptureController;
        }
    }

    private synchronized String savePicture(byte[] jpeg, int captureTarget, double maxJpegQuality, int maxSize) throws IOException {
        // TODO: check if rotation is needed
        //        int rotationAngle = currentFrame.rotationDegree;
        String filename = UUID.randomUUID().toString();
        File file = null;
        switch (captureTarget) {
            case WebRTCModule.RCT_CAMERA_CAPTURE_TARGET_CAMERA_ROLL: {
                file = getOutputCameraRollFile(filename);
                writePictureToFile(jpeg, file, maxSize, maxJpegQuality);
                addToMediaStore(file.getAbsolutePath());
                break;
            }
            case WebRTCModule.RCT_CAMERA_CAPTURE_TARGET_DISK: {
                file = getOutputMediaFile(filename);
                writePictureToFile(jpeg, file, maxSize, maxJpegQuality);
                break;
            }
            case WebRTCModule.RCT_CAMERA_CAPTURE_TARGET_TEMP: {
                file = getTempMediaFile(filename);
                writePictureToFile(jpeg, file, maxSize, maxJpegQuality);
                break;
            }
        }
        return Uri.fromFile(file).toString();
    }

    private String writePictureToFile(byte[] jpeg, File file, int maxSize, double jpegQuality) throws IOException {
        FileOutputStream output = new FileOutputStream(file);
        output.write(jpeg);
        output.close();
        Matrix matrix = new Matrix();

        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        // scale if needed
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // only resize if image larger than maxSize
        if (width > maxSize && width > maxSize) {
            Rect originalRect = new Rect(0, 0, width, height);
            Rect scaledRect = scaleDimension(originalRect, maxSize);
            Log.d(TAG, "scaled width = " + scaledRect.width() + ", scaled height = " + scaledRect.height());
            // calculate the scale
            float scaleWidth = ((float) scaledRect.width()) / width;
            float scaleHeight = ((float) scaledRect.height()) / height;
            matrix.postScale(scaleWidth, scaleHeight);
        }
        FileOutputStream finalOutput = new FileOutputStream(file, false);
        int compression = (int) (100 * jpegQuality);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, compression, finalOutput);
        finalOutput.close();
        return file.getAbsolutePath();
    }
    private File getOutputMediaFile(String fileName) {
        // Get environment directory type id from requested media type.
        String environmentDirectoryType;
        environmentDirectoryType = Environment.DIRECTORY_PICTURES;
        return getOutputFile(
                fileName + ".jpeg",
                Environment.getExternalStoragePublicDirectory(environmentDirectoryType)
        );
    }
    private File getOutputCameraRollFile(String fileName) {
        return getOutputFile(
                fileName + ".jpeg",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        );
    }
    private File getOutputFile(String fileName, File storageDir) {
        // Create the storage directory if it does not exist
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.e(TAG, "failed to create directory:" + storageDir.getAbsolutePath());
                return null;
            }
        }
        return new File(String.format("%s%s%s", storageDir.getPath(), File.separator, fileName));
    }
    private File getTempMediaFile(String fileName) {
        try {
            File outputDir = getReactApplicationContext().getCacheDir();
            File outputFile;
            outputFile = File.createTempFile(fileName, ".jpg", outputDir);
            return outputFile;
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
            return null;
        }
    }
    private void addToMediaStore(String path) {
        MediaScannerConnection.scanFile(getReactApplicationContext(), new String[]{path}, null, null);
    }

    private static Rect scaleDimension(Rect originalRect, int maxSize) {
        int originalWidth = originalRect.width();
        int originalHeight = originalRect.height();
        int newWidth = originalWidth;
        int newHeight = originalHeight;
        // first check if we need to scale width
        if (originalWidth > maxSize) {
            //scale width to fit
            newWidth = maxSize;
            //scale height to maintain aspect ratio
            newHeight = (newWidth * originalHeight) / originalWidth;
        }
        // then check if we need to scale even with the new height
        if (newHeight > maxSize) {
            //scale height to fit instead
            newHeight = maxSize;
            //scale width to maintain aspect ratio
            newWidth = (newHeight * originalWidth) / originalHeight;
        }
        return new Rect(0, 0, newWidth, newHeight);
    }

    public void takePicture(final ReadableMap options, final String trackId, final Callback successCallback, final Callback errorCallback) {
        final int captureTarget = options.getInt("captureTarget");
        final double maxJpegQuality = options.getDouble("maxJpegQuality");
        final int maxSize = options.getInt("maxSize");

        if (!tracks.containsKey(trackId)) {
            errorCallback.invoke("Invalid trackId " + trackId);
            return ;
        }

        VideoCapturer vc = tracks.get(trackId).videoCaptureController.getVideoCapturer();

        if ( !(vc instanceof CameraCapturer) ) {
            errorCallback.invoke("Wrong class in package");
        } else {
            CameraCapturer camCap = (CameraCapturer) vc;
            camCap.takeSnapshot(new CameraCapturer.SingleCaptureCallBack() {
                @Override
                public void captureSuccess(byte[] jpeg) {
                    if (captureTarget == WebRTCModule.RCT_CAMERA_CAPTURE_TARGET_MEMORY)
                        successCallback.invoke(Base64.getEncoder().encodeToString(jpeg));
                    else {
                        try {
                            String path = savePicture(jpeg, captureTarget, maxJpegQuality, maxSize);
                            successCallback.invoke(path);
                        } catch (IOException e){
                            String message = "Error saving picture";
                            Log.d(TAG, message, e);
                            errorCallback.invoke(message);
                        }
                    }
                }

                @Override
                public void captureFailed(String err) {
                    errorCallback.invoke(err);
                }
            }, this.imageProcessingHandler);
        }
    }
}
