/***
  Copyright (c) 2013 CommonsWare, LLC
  Portions Copyright (C) 2007 The Android Open Source Project
  
  Licensed under the Apache License, Version 2.0 (the "License"); you may
  not use this file except in compliance with the License. You may obtain
  a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.commonsware.cwac.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import com.commonsware.cwac.camera.CameraHost.FailureReason;

import java.io.IOException;

public class CameraView extends ViewGroup implements
    Camera.PictureCallback, AutoFocusCallback {
  static final String TAG="CWAC-Camera";
  private PreviewStrategy previewStrategy;
  private Camera.Size previewSize;
  private Camera camera=null;
  private boolean inPreview=false;
  private CameraHost host=null;
  private OnOrientationChange onOrientationChange=null;
  private int displayOrientation=-1;
  private int outputOrientation=-1;
  private int cameraId=-1;
  private MediaRecorder recorder=null;
  private Camera.Parameters previewParams=null;
  private boolean needBitmap=false;
  private boolean needByteArray=false;
  private boolean isDetectingFaces=false;
  private boolean isAutoFocusing=false;
  private int lastPictureOrientation=-1;

  public CameraView(Context context) {
    super(context);

    onOrientationChange=new OnOrientationChange(context);
  }

  public CameraView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public CameraView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    onOrientationChange=new OnOrientationChange(context);

    if (context instanceof CameraHostProvider) {
      setHost(((CameraHostProvider)context).getCameraHost());
    }
    else {
      throw new IllegalArgumentException("To use the two- or "
          + "three-parameter constructors on CameraView, "
          + "your activity needs to implement the "
          + "CameraHostProvider interface");
    }
  }

  public CameraHost getHost() {
    return(host);
  }

  // must call this after constructor, before onResume()

  public void setHost(CameraHost host) {
    this.host=host;
    if (camera != null) {
        this.host.onCheckFlashSupport(CameraUtils.deviceSupportsFlash(camera.getParameters()));
    }

    if (host.getDeviceProfile().useTextureView()) {
      previewStrategy=new TexturePreviewStrategy(this);
    }
    else {
      previewStrategy=new SurfacePreviewStrategy(this);
    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void onResume() {
    addView(previewStrategy.getWidget());

    initCamera();
  }

  public void onPause() {
    if (camera != null) {
      previewDestroyed();
      removeView(previewStrategy.getWidget());
    }
  }

  // based on CameraPreview.java from ApiDemos

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    final int width=
        resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
    final int height=
        resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
    setMeasuredDimension(width, height);

    if (width > 0 && height > 0) {
      if (camera != null) {
        Camera.Size newSize=null;

        try {
          if (getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY) {
            Camera.Size deviceHint=
                DeviceProfile.getInstance()
                             .getPreferredPreviewSizeForVideo(getDisplayOrientation(),
                                                              width,
                                                              height,
                                                              camera.getParameters());

            newSize=
                getHost().getPreferredPreviewSizeForVideo(getDisplayOrientation(),
                                                          width,
                                                          height,
                                                          camera.getParameters(),
                                                          deviceHint);
          }

          if (newSize == null || newSize.width * newSize.height < 65536) {
            newSize=
                getHost().getPreviewSize(getDisplayOrientation(),
                                         width, height,
                                         camera.getParameters());
          }
        }
        catch (Exception e) {
          android.util.Log.e(getClass().getSimpleName(),
                             "Could not work with camera parameters?",
                             e);
          // TODO get this out to library clients
        }

        if (newSize != null) {
          if (previewSize == null) {
            previewSize=newSize;
          }
          else if (previewSize.width != newSize.width
              || previewSize.height != newSize.height) {
            if (inPreview) {
              stopPreview();
            }

            previewSize=newSize;
            initPreview(width, height, false);
          }
        }
      }
    }
  }

  // based on CameraPreview.java from ApiDemos

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    if (changed && getChildCount() > 0) {
      final View child=getChildAt(0);
      final int width=r - l;
      final int height=b - t;
      int previewWidth=width;
      int previewHeight=height;

      // handle orientation

      if (previewSize != null) {
        if (getDisplayOrientation() == 90
            || getDisplayOrientation() == 270) {
          previewWidth=previewSize.height;
          previewHeight=previewSize.width;
        }
        else {
          previewWidth=previewSize.width;
          previewHeight=previewSize.height;
        }
      }

      boolean useFirstStrategy=
          (width * previewHeight > height * previewWidth);
      boolean useFullBleed=getHost().useFullBleedPreview();

      if ((useFirstStrategy && !useFullBleed)
          || (!useFirstStrategy && useFullBleed)) {
        final int scaledChildWidth=
            previewWidth * height / previewHeight;
        child.layout((width - scaledChildWidth) / 2, 0,
                     (width + scaledChildWidth) / 2, height);
      }
      else {
        final int scaledChildHeight=
            previewHeight * width / previewWidth;
        child.layout(0, (height - scaledChildHeight) / 2, width,
                     (height + scaledChildHeight) / 2);
      }
    }
  }

  public int getDisplayOrientation() {
    return(displayOrientation);
  }

  public void lockToLandscape(boolean enable) {
    if (enable) {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
      onOrientationChange.enable();
    }
    else {
      getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      onOrientationChange.disable();
    }

    post(new Runnable() {
      @Override
      public void run() {
        setCameraDisplayOrientation(cameraId, camera);        
      }
    });
  }

  @Override
  public void onPictureTaken(byte[] data, Camera camera) {
    camera.setParameters(previewParams);

    if (data != null) {
      new ImageCleanupTask(data, cameraId, getHost(),
                           getContext().getCacheDir(), needBitmap,
                           needByteArray, displayOrientation).execute();
    }

    if (!getHost().useSingleShotMode()) {
      startPreview();
    }
  }

  private void initCamera() {
      if (camera == null) {
          cameraId=getHost().getCameraId();

          if (cameraId >= 0) {
              try {
                  camera=Camera.open(cameraId);

                  if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                      onOrientationChange.enable();
                  }

                  setCameraDisplayOrientation(cameraId, camera);

                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
                          && getHost() instanceof Camera.FaceDetectionListener) {
                      camera.setFaceDetectionListener((Camera.FaceDetectionListener)getHost());
                  }
                  getHost().onCheckFlashSupport(CameraUtils.deviceSupportsFlash(camera.getParameters()));
              }
              catch (Exception e) {
                  getHost().onCameraFail(FailureReason.UNKNOWN);
              }
          }
          else {
              getHost().onCameraFail(FailureReason.NO_CAMERAS_REPORTED);
          }
      }
  }

  public void reinitializePreview() {
    // Release the camera and stop the preview
    previewDestroyed();
    // Get a new reference to the camera
    initCamera();
    // Rebind the camera to the rendering surface
    previewCreated();
    // Reset the camera's parameters and start the preview
    previewReset(this.getWidth(), this.getHeight());
  }

  public void restartPreview() {
    if (!inPreview) {
      startPreview();
    }
  }

  public void setFlashMode(String flashMode) {
      if (camera != null) {
          Camera.Parameters pictureParams=camera.getParameters();
          pictureParams.setFlashMode(flashMode);
          camera.setParameters(getHost().adjustPictureParameters(pictureParams));
      }
  }

  public void takePicture(boolean needBitmap, boolean needByteArray) {
    if (inPreview) {
      if (isAutoFocusing) {
        throw new IllegalStateException(
                                        "Camera cannot take a picture while auto-focusing");
      }
      else {
        this.needBitmap=needBitmap;
        this.needByteArray=needByteArray;

        previewParams=camera.getParameters();

        Camera.Parameters pictureParams=camera.getParameters();
        Camera.Size pictureSize=getHost().getPictureSize(pictureParams);

        pictureParams.setPictureSize(pictureSize.width,
                                     pictureSize.height);
        pictureParams.setPictureFormat(ImageFormat.JPEG);
        camera.setParameters(getHost().adjustPictureParameters(pictureParams));

        setCameraPictureOrientation();

        camera.takePicture(getHost().getShutterCallback(), null, this);
        inPreview=false;
      }
    }
    else {
      throw new IllegalStateException(
                                      "Preview mode must have started before you can take a picture");
    }
  }

  public boolean isRecording() {
    return(recorder != null);
  }

  public void record() throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      throw new UnsupportedOperationException(
                                              "Video recording supported only on API Level 11+");
    }

    setCameraPictureOrientation();
    stopPreview();
    camera.unlock();

    try {
      recorder=new MediaRecorder();
      recorder.setCamera(camera);
      getHost().configureRecorderAudio(cameraId, recorder);
      recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
      getHost().configureRecorderProfile(cameraId, recorder);
      getHost().configureRecorderOutput(cameraId, recorder);
      recorder.setOrientationHint(outputOrientation);
      previewStrategy.attach(recorder);
      recorder.prepare();
      recorder.start();
    }
    catch (IOException e) {
      recorder.release();
      recorder=null;
      throw e;
    }
  }

  public void stopRecording() throws IOException {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
      throw new UnsupportedOperationException(
                                              "Video recording supported only on API Level 11+");
    }

    MediaRecorder tempRecorder=recorder;

    recorder=null;
    tempRecorder.stop();
    tempRecorder.release();
    camera.reconnect();
  }

  public void autoFocus() {
    if (inPreview) {
      camera.autoFocus(this);
      isAutoFocusing=true;
    }
  }

  public void cancelAutoFocus() {
    camera.cancelAutoFocus();
  }

  public boolean isAutoFocusAvailable() {
    return(inPreview);
  }

  @Override
  public void onAutoFocus(boolean success, Camera camera) {
    isAutoFocusing=false;

    if (getHost() instanceof AutoFocusCallback) {
      getHost().onAutoFocus(success, camera);
    }
  }

  public String getFlashMode() {
    return(camera.getParameters().getFlashMode());
  }

  public ZoomTransaction zoomTo(int level) {
    if (camera == null) {
      throw new IllegalStateException(
                                      "Yes, we have no camera, we have no camera today");
    }
    else {
      Camera.Parameters params=camera.getParameters();

      if (level >= 0 && level <= params.getMaxZoom()) {
        return(new ZoomTransaction(camera, level));
      }
      else {
        throw new IllegalArgumentException(
                                           String.format("Invalid zoom level: %d",
                                                         level));
      }
    }
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void startFaceDetection() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
        && camera != null && !isDetectingFaces
        && camera.getParameters().getMaxNumDetectedFaces() > 0) {
      camera.startFaceDetection();
      isDetectingFaces=true;
    }
  }

  public void stopFaceDetection() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
        && camera != null && isDetectingFaces) {
      camera.stopFaceDetection();
      isDetectingFaces=false;
    }
  }

  public boolean doesZoomReallyWork() {
    Camera.CameraInfo info=new Camera.CameraInfo();
    Camera.getCameraInfo(getHost().getCameraId(), info);

    return(getHost().getDeviceProfile().doesZoomActuallyWork(info.facing == CameraInfo.CAMERA_FACING_FRONT));
  }

  void previewCreated() {
    if (camera != null) {
      try {
        previewStrategy.attach(camera);
      }
      catch (IOException e) {
        getHost().handleException(e);
      }
    }
  }

  void previewDestroyed() {
    if (camera != null) {
      previewStopped();
      camera.release();
      camera=null;
    }
  }

  void previewReset(int width, int height) {
    previewStopped();
    initPreview(width, height);
  }

  private void previewStopped() {
    if (inPreview) {
      stopPreview();
    }
  }

  public void initPreview(int w, int h) {
    initPreview(w, h, true);
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  public void initPreview(int w, int h, boolean firstRun) {
    if (camera != null) {
      Camera.Parameters parameters=camera.getParameters();

      parameters.setPreviewSize(previewSize.width, previewSize.height);

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        parameters.setRecordingHint(getHost().getRecordingHint() != CameraHost.RecordingHint.STILL_ONLY);
      }

      requestLayout();

      camera.setParameters(getHost().adjustPreviewParameters(parameters));
      startPreview();
    }
  }

  private void startPreview() {
    camera.startPreview();
    inPreview=true;
    getHost().autoFocusAvailable();
  }

  private void stopPreview() {
    inPreview=false;
    getHost().autoFocusUnavailable();
    camera.stopPreview();
  }

  // based on
  // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
  // and http://stackoverflow.com/a/10383164/115145

  private void setCameraDisplayOrientation(int cameraId,
                                           android.hardware.Camera camera) {
    Camera.CameraInfo info=new Camera.CameraInfo();
    int rotation=
        getActivity().getWindowManager().getDefaultDisplay()
                     .getRotation();
    int degrees=0;
    DisplayMetrics dm=new DisplayMetrics();

    Camera.getCameraInfo(cameraId, info);
    getActivity().getWindowManager().getDefaultDisplay().getMetrics(dm);

    switch (rotation) {
      case Surface.ROTATION_0:
        degrees=0;
        break;
      case Surface.ROTATION_90:
        degrees=90;
        break;
      case Surface.ROTATION_180:
        degrees=180;
        break;
      case Surface.ROTATION_270:
        degrees=270;
        break;
    }

    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      displayOrientation=(info.orientation + degrees) % 360;
      displayOrientation=(360 - displayOrientation) % 360;
    }
    else {
      displayOrientation=(info.orientation - degrees + 360) % 360;
    }

    boolean wasInPreview=inPreview;

    if (inPreview) {
      stopPreview();
    }

    camera.setDisplayOrientation(displayOrientation);

    if (wasInPreview) {
      startPreview();
    }
  }

  private void setCameraPictureOrientation() {
    Camera.CameraInfo info=new Camera.CameraInfo();

    Camera.getCameraInfo(cameraId, info);

    if (getActivity().getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
      outputOrientation=
          getCameraPictureRotation(getActivity().getWindowManager()
                                                .getDefaultDisplay()
                                                .getOrientation());
    }
    else if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      outputOrientation=(360 - displayOrientation) % 360;
    }
    else {
      outputOrientation=displayOrientation;
    }

    if (lastPictureOrientation != outputOrientation) {
      Camera.Parameters params=camera.getParameters();

      params.setRotation(outputOrientation);
      camera.setParameters(params);
      lastPictureOrientation=outputOrientation;
    }
  }

  // based on:
  // http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)

  private int getCameraPictureRotation(int orientation) {
    Camera.CameraInfo info=new Camera.CameraInfo();
    Camera.getCameraInfo(cameraId, info);
    int rotation=0;

    orientation=(orientation + 45) / 90 * 90;

    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      rotation=(info.orientation - orientation + 360) % 360;
    }
    else { // back-facing camera
      rotation=(info.orientation + orientation) % 360;
    }

    return(rotation);
  }

  Activity getActivity() {
    return((Activity)getContext());
  }

  private class OnOrientationChange extends OrientationEventListener {
    public OnOrientationChange(Context context) {
      super(context);
      disable();
    }

    @Override
    public void onOrientationChanged(int orientation) {
      if (camera != null && orientation != ORIENTATION_UNKNOWN) {
        int newOutputOrientation=getCameraPictureRotation(orientation);

        if (newOutputOrientation != outputOrientation) {
          outputOrientation=newOutputOrientation;

          Camera.Parameters params=camera.getParameters();

          params.setRotation(outputOrientation);
          camera.setParameters(params);
          lastPictureOrientation=outputOrientation;
        }
      }
    }
  }
}