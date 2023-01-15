package com.uncanny.camera2_slomo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.CamcorderProfile;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Chronometer;
import android.widget.Toast;

import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private final String TAG ="MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 2002;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private Chronometer chronometer;
    private ShapeableImageView capture;
    private ShapeableImageView pauseResume;
    private ShapeableImageView thumbPreview;
    private AutoFitPreviewView previewView;

    private Surface recordSurface,previewSurface,persistentSurface;
    private SurfaceTexture stPreview;

    private CameraDevice cameraDevice;
    private CameraManager cameraManager;
    private CameraCaptureSession cameraCaptureSession;
    private CameraCharacteristics cameraCharacteristics;
    private CaptureRequest.Builder captureRequestBuilder;
    private CaptureRequest.Builder previewCaptureRequestBuilder;
    private ImageReader imageReader;
    private MediaRecorder mMediaRecorder;
    private CamcorderProfile camcorderProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_1080P);

    private MediaActionSound sound = new MediaActionSound();

    private Handler cameraHandler;
    private Handler mHandler = new Handler();
    private HandlerThread mBackgroundThread;
    private Executor bgExecutor = Executors.newCachedThreadPool();

    private File videoFile;
    private boolean resumed = false, hasSurface = false;
    private boolean shouldDeleteEmptyFile;
    private boolean isVRecording = false;
    private Uri fileUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        capture = findViewById(R.id.capture);
        pauseResume = findViewById(R.id.pause_resume);
        thumbPreview = findViewById(R.id.thumbnail_snapshot);
        previewView = findViewById(R.id.preview);
        chronometer = findViewById(R.id.chronometer);

        capture.setOnClickListener(this);
        pauseResume.setOnClickListener(this);
        thumbPreview.setOnClickListener(this);

        requestPermissions();
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA
                    , Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION) {
            for(int i=0; i<permissions.length-1;i++){
                Log.e(TAG, "onRequestPermissionsResult: permissions : "+ Arrays.toString(permissions) +" results : "+ Arrays.toString(grantResults));
                if(grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Grant Permission to continue", Toast.LENGTH_SHORT).show();
                    finishAffinity();
                }
            }
        }

    }

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            stPreview = surface;
            hasSurface = true;
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            hasSurface = false;
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }
    };

    private void openCamera(){
        if(!resumed || !hasSurface) return;

        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraCharacteristics = cameraManager.getCameraCharacteristics("0");

            previewView.measure(1920, 1080);
            previewView.setAspectRatio(1080,1920);
            stPreview.setDefaultBufferSize(1920, 1080);

            //set capture resolution
            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 5);
            imageReader.setOnImageAvailableListener(snapshotImageCallback, cameraHandler);

            try {
                persistentSurface = MediaCodec.createPersistentInputSurface();
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions();
                }
                cameraManager.openCamera("0", cameraDeviceStateCallback, cameraHandler);
            } catch(CameraAccessException e) {
                Log.e(TAG, "openCamera: open failed: " + e.getMessage());
            }
        }
        catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void prepareMediaRecorder(){
        String mVideoLocation = "//storage//emulated//0//DCIM//Camera//";
        String mVideoSuffix = "Camera2_Video_" + System.currentTimeMillis() + ".mp4";

        if(resumed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) mMediaRecorder = new MediaRecorder(this);
            else mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setOrientationHint(getJpegOrientation());
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        mMediaRecorder.setAudioSamplingRate(camcorderProfile.audioSampleRate);
        mMediaRecorder.setAudioEncodingBitRate(camcorderProfile.audioBitRate);
        mMediaRecorder.setAudioChannels(camcorderProfile.audioChannels);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
//        mMediaRecorder.setInputSurface(persistentSurface);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
        mMediaRecorder.setVideoEncodingBitRate(camcorderProfile.videoBitRate);
        mMediaRecorder.setVideoSize(1920,1080);

        shouldDeleteEmptyFile = true;
        videoFile = new File(mVideoLocation+mVideoSuffix);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mMediaRecorder.setOutputFile(videoFile);
        } else {
            mMediaRecorder.setOutputFile(mVideoLocation+mVideoSuffix);
        }

        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private OutputConfiguration previewConfiguration;
    private OutputConfiguration recordConfiguration;
    private OutputConfiguration snapshotConfiguration;

    private void createVideoPreview()  {
        if(!resumed || !hasSurface) return;

        try {
            prepareMediaRecorder();
            previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewView.getSurfaceTexture().setDefaultBufferSize(1920, 1080);
            previewSurface = new Surface(previewView.getSurfaceTexture());

//            recordSurface = persistentSurface; // TODO: PersistentSurface not recording video in some devices
            recordSurface = mMediaRecorder.getSurface();

            previewCaptureRequestBuilder.addTarget(recordSurface);

            previewCaptureRequestBuilder.addTarget(previewSurface);

            previewCaptureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE
                    ,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                OutputConfiguration previewConfiguration  = new OutputConfiguration(previewSurface);
                OutputConfiguration recordConfiguration   = new OutputConfiguration(recordSurface);
                OutputConfiguration snapshotConfiguration = new OutputConfiguration(imageReader.getSurface());

//                previewConfiguration.enableSurfaceSharing();

                SessionConfiguration sessionConfiguration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR
                        , Arrays.asList(previewConfiguration,recordConfiguration,snapshotConfiguration)
                        , bgExecutor
                        , streamlineCaptureSessionCallback);

                cameraDevice.createCaptureSession(sessionConfiguration);
            }
            else{
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface, imageReader.getSurface())
                        ,streamlineCaptureSessionCallback,null);
            }

        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    CameraCaptureSession.StateCallback streamlineCaptureSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            cameraCaptureSession = session;
            try {
                cameraCaptureSession.setRepeatingRequest(previewCaptureRequestBuilder.build(), null,mHandler);
                if(isVRecording){
                    Log.e(TAG, "onConfigured: Preparing media Recorder");
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "onConfigureFailed: createVideoPreview()");
        }
    };

    private void startRecording(){
        shouldDeleteEmptyFile = false;
        mMediaRecorder.start();
    }

    private void captureImage() {
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY,(byte) 100);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation());
            captureRequestBuilder.addTarget(imageReader.getSurface());

            cameraCaptureSession.capture(captureRequestBuilder.build(), null, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }


    ImageReader.OnImageAvailableListener snapshotImageCallback = imageReader -> {
        Log.e(TAG, "onImageAvailable: received snapshot image data");
        Completable.fromRunnable(new ImageSaverThread(this,
                        imageReader.acquireLatestImage(), "0", getContentResolver()))
                .subscribeOn(Schedulers.computation())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Toast.makeText(MainActivity.this, "Could Not Save Image", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onComplete() {
//                        displayLatestThumbnail();
                    }
                });

    };

    private CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if(resumed) {
                Log.e(TAG, "onOpened: SURFACE ABANDONED");
                persistentSurface = MediaCodec.createPersistentInputSurface();
                mMediaRecorder = null;
            }
            createVideoPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice = null;
            Log.e(TAG, "onError: error int : "+error);
        }
    };

    private void displayLatestThumbnail() {
        LatestThumbnailGeneratorThread ltg;
        Completable.fromRunnable(ltg = new LatestThumbnailGeneratorThread(this))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .andThen(Completable.fromRunnable(() -> {
                    thumbPreview.setImageBitmap(ltg.getBitmap());
                    Log.e(TAG, "displayLatestImage: Updated Thumbnail");
                })).subscribe();
    }

    private void performMediaScan(String filename, String type){
        String mimeType = null;
        if(type.equals("image")) mimeType = "image/jpeg";
        else if(type.equals("video")) mimeType = "video/mp4";
        MediaScannerConnection.scanFile(this
                ,new String[] { filename }
                ,new String[] { mimeType }
                ,(path, uri) -> {
                    Log.i("TAG", "Scanned " + path + ":");
                    Log.i("TAG", "-> uri=" + uri);
                    fileUri = uri;
                });
    }

    private int getJpegOrientation() {
        int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();

        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
        int sensorOrientation =  cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int surfaceRotation = ORIENTATIONS.get(deviceOrientation);

        return (surfaceRotation + sensorOrientation + 270) % 360;
    }

    private void performFileCleanup() {
        Log.e(TAG, "performFileCleanup: shouldDeleteEmptyFile : "+shouldDeleteEmptyFile);
        boolean ds = false;
        if(shouldDeleteEmptyFile) {
            ds = videoFile.delete();
        }
        shouldDeleteEmptyFile = false;
        Log.e(TAG, "performFileCleanup: DELETED ?? "+ds);
    }

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        cameraHandler = HandlerCompat.createAsync(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        if(mBackgroundThread!=null) mBackgroundThread.quitSafely();
        else{
            finishAffinity();
            return;
        }
        try {
            mBackgroundThread.join();
            cameraHandler = null;
            mBackgroundThread = null;
            cameraManager = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    boolean paused = false;
    long pauseDuration;
    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.capture) {
            if(!isVRecording){
                chronometer.setBase(SystemClock.elapsedRealtime() - pauseDuration);
                chronometer.start();
                sound.play(MediaActionSound.START_VIDEO_RECORDING);
                startRecording();
            }
            else {
                chronometer.stop();
                pauseDuration = 0;
                mMediaRecorder.stop();
                mMediaRecorder.reset();
                mMediaRecorder.release();
                performMediaScan(videoFile.getAbsolutePath(),"video"); //TODO : Handle Efficiently
                createVideoPreview(); //without persistentSurface
//                prepareMediaRecorder(); //with persistentSurface
                sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
                displayLatestThumbnail(); //TODO : Handle Efficiently
            }
            runOnUiThread(()->{
                pauseResume.setVisibility(isVRecording ? View.INVISIBLE : View.VISIBLE);
                chronometer.setVisibility(isVRecording ? View.INVISIBLE : View.VISIBLE);
                thumbPreview.setImageDrawable(isVRecording ? null : ContextCompat.getDrawable(this,R.drawable.ic_capture_btn));
            });
            isVRecording = !isVRecording;
        }
        else if (id == R.id.thumbnail_snapshot) {
            if(isVRecording){
                captureImage();
            }
            else {
                if(fileUri == null){
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg");
                    startActivity(i);
                }
                else{
                    final String GALLERY_REVIEW = "com.android.camera.action.REVIEW";
                    Intent i = new Intent(GALLERY_REVIEW);
                    i.setData(fileUri);
                    startActivity(i);

                }
            }

        }
        else if (id == R.id.pause_resume){
            Log.e(TAG, "onClick: PAUSE /RESUME");
            if(paused) {
                mMediaRecorder.resume();
                chronometer.setBase(SystemClock.elapsedRealtime() - pauseDuration);
                chronometer.start();
                pauseResume.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_round_pause_24));
            }
            else {
                mMediaRecorder.pause();
                chronometer.stop();
                pauseDuration = SystemClock.elapsedRealtime() - chronometer.getBase();
                pauseResume.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_round_play_arrow_24));
            }
            paused = !paused;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startBackgroundThread();
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        displayLatestThumbnail();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if(mMediaRecorder != null) mMediaRecorder.release();
        performFileCleanup();
    }

    @Override
    protected void onStop() {
        super.onStop();
        closeCamera();
        performFileCleanup();
        stopBackgroundThread();
        if(mMediaRecorder != null) mMediaRecorder.release();
        persistentSurface.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        performFileCleanup();
        stopBackgroundThread();
        if(mMediaRecorder != null) mMediaRecorder.release();
        persistentSurface.release();
    }
}