package com.uncanny.camera2_starter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

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

    private ShapeableImageView capture;
    private ShapeableImageView thumbPreview;
    private AutoFitPreviewView previewView;

    private SurfaceTexture stPreview;

    private CameraDevice cameraDevice;
    private CameraManager cameraManager;
    private CameraCaptureSession cameraCaptureSession;
    private CameraCharacteristics cameraCharacteristics;
    private CaptureRequest.Builder captureRequestBuilder;
    private CaptureRequest.Builder previewCaptureRequestBuilder;
    private ImageReader imageReader;

    private MediaActionSound sound = new MediaActionSound();

    private Handler cameraHandler;
    private Handler mHandler = new Handler();
    private HandlerThread mBackgroundThread;

    private boolean resumed = false, hasSurface = false;
    private List<Surface> surfaceList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        capture = findViewById(R.id.capture);
        thumbPreview = findViewById(R.id.thumbnail);
        previewView = findViewById(R.id.preview);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
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
        });

        capture.setOnClickListener(this);
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
            for(int i=0; i<permissions.length;i++){
                if(grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Grant Permission to continue", Toast.LENGTH_SHORT).show();
                    finishAffinity();
                }
            }
        }

    }



    private void openCamera(){
        if(!resumed || !hasSurface) return;

        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraCharacteristics = cameraManager.getCameraCharacteristics("0");

//        Don't need cause hardcoded the values
//        StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            //set preview resolution
            previewView.measure(1080, 1440);
            stPreview.setDefaultBufferSize(1440, 1080);

            //set capture resolution
            imageReader = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 5);
            imageReader.setOnImageAvailableListener(snapshotImageCallback, cameraHandler);

            surfaceList.clear();
            surfaceList.add(new Surface(stPreview));
            surfaceList.add(imageReader.getSurface());

            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions();
                    return;
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

    private void captureImage() {
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY,(byte) 100);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation());
            captureRequestBuilder.addTarget(surfaceList.get(1));

            cameraCaptureSession.capture(captureRequestBuilder.build(), null, mHandler);

            sound.play(MediaActionSound.SHUTTER_CLICK);
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
                        displayLatestImage();
                    }
                });

    };

    private CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                List<OutputConfiguration> outputs = new ArrayList<>();
                outputs.add(new OutputConfiguration(surfaceList.get(0)));
                outputs.add(new OutputConfiguration(surfaceList.get(1)));
                SessionConfiguration sessionConfiguration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        getMainExecutor(),
                        stateCallback);

                try {
                    cameraDevice.createCaptureSession(sessionConfiguration);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
            else{
                try {
                    cameraDevice.createCaptureSession(surfaceList,stateCallback, cameraHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            runOnUiThread(() -> {
                previewView.setAspectRatio(1080,1440);
            });
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

    private CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            cameraCaptureSession = session;

            try {
                previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewCaptureRequestBuilder.addTarget(surfaceList.get(0));
                previewCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(0));

                cameraCaptureSession.setRepeatingRequest(previewCaptureRequestBuilder.build(), null, null);

            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "IMAGE CAPTURE CALLBACK: onConfigureFailed: configure failed");
        }
    };


    private void displayLatestImage() {
        LatestThumbnailGeneratorThread ltg;
        Completable.fromRunnable(ltg = new LatestThumbnailGeneratorThread(this))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .andThen(Completable.fromRunnable(() -> {
                    thumbPreview.setImageBitmap(ltg.getBitmap());
                    Log.e(TAG, "displayLatestImage: Updated Thumbnail");
                })).subscribe();
    }

    private int getJpegOrientation() {
        int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();

        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return 0;
        int sensorOrientation =  cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int surfaceRotation = ORIENTATIONS.get(deviceOrientation);

        return (surfaceRotation + sensorOrientation + 270) % 360;
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


    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.capture) {
            captureImage();
        } else if (id == R.id.thumbnail) {
            if(ImageSaverThread.staticUri == null){
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg");
                startActivity(i);
            }
            else{
                Log.e(TAG, "onClick: uri : "+ImageSaverThread.staticUri);
                final String GALLERY_REVIEW = "com.android.camera.action.REVIEW";
                Intent i = new Intent(GALLERY_REVIEW);
                i.setData(ImageSaverThread.staticUri);
                startActivity(i);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startBackgroundThread();
        openCamera();
        displayLatestImage();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        stopBackgroundThread();
    }
}