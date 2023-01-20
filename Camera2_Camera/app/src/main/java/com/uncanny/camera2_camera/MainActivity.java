package com.uncanny.camera2_camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
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
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.HandlerCompat;

import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

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
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;
    private MediaActionSound sound = new MediaActionSound();

    private Handler mHandler;
    private Handler cameraHandler;
    private HandlerThread mBackgroundThread;
    private HandlerThread bBackgroundThread;
    private Executor bgExecutor = Executors.newSingleThreadExecutor();

    private boolean isLongPressed = false;
    private boolean resumed = false, hasSurface = false;
    private List<Surface> surfaceList = new ArrayList<>();
//    private BlockingQueue<CaptureRequest> captureResults = new LinkedBlockingQueue<>();

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandler = new Handler(getMainLooper());

        capture = findViewById(R.id.capture);
        thumbPreview = findViewById(R.id.thumbnail);
        previewView = findViewById(R.id.preview);
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                stPreview = surface;
                hasSurface = true;
                cameraHandler.post(()->openCamera());
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

        capture.setOnLongClickListener(v -> {
            isLongPressed = true;
            //Start Repeating Burst
            cameraHandler.post(this::captureBurstImage);
            Log.e(TAG, "onCreate: Start Repeating Burst");
            return false;
        });

        capture.setOnTouchListener((v, event) -> {
            if(event.getActionMasked() == MotionEvent.ACTION_UP && isLongPressed){
                //Stop Repeating Burst
                isLongPressed = false;
                cameraHandler.postAtFrontOfQueue(this::createPreview);
                cameraHandler.post(this::displayLatestImage);
                Log.e(TAG, "onLongPressedUp: Stop Repeating Burst");

                return true;
            }
            return false;
        });

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
            imageReader = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 3);
            imageReader.setOnImageAvailableListener(new OnJpegImageAvailableListener(), cameraHandler);

            surfaceList.clear();
            surfaceList.add(new Surface(stPreview));
            surfaceList.add(imageReader.getSurface());

            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions();
                    return;
                }
                cameraManager.openCamera("0", cameraDeviceStateCallback, mHandler);
            } catch(CameraAccessException e) {
                Log.e(TAG, "openCamera: open failed: " + e.getMessage());
            }
        }
        catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void createPreview(){
        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            previewRequestBuilder.addTarget(surfaceList.get(0));
            captureRequestBuilder.addTarget(surfaceList.get(0));

            cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, mHandler);

        } catch (CameraAccessException e) {
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

    private void captureBurstImage(){
        Log.e(TAG, "captureBurstImage: Capture");
        try {
//            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.set(CaptureRequest.JPEG_QUALITY,(byte) 100);
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,getJpegOrientation());
//            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            captureRequestBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
//            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
//            captureRequestBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);

            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, true);

            captureRequestBuilder.addTarget(surfaceList.get(1));

            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build()
                , new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber);
                        cameraHandler.post(() -> sound.play(MediaActionSound.SHUTTER_CLICK));
                    }

                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        super.onCaptureCompleted(session, request, result);
                    }
            }, mHandler);
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


    private class OnJpegImageAvailableListener implements ImageReader.OnImageAvailableListener {
        private DateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault());
        private String cameraDir = Environment.getExternalStorageDirectory()+"//DCIM//Camera//";

        @WorkerThread
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Log.e(TAG, "onImageAvailable: "+isLongPressed);
            try(Image image = imageReader.acquireNextImage()) {
                if (image != null ) {
                    ByteBuffer jpegByteBuffer = image.getPlanes()[0].getBuffer();
                    byte[] jpegByteArray = new byte[jpegByteBuffer.remaining()];
                    jpegByteBuffer.get(jpegByteArray);
                    bgExecutor.execute(() -> {
                        long date = System.currentTimeMillis();
                        String title = "Camera2_starter_" + dateFormat.format(date);
                        String displayName = title + ".jpeg";
                        String path = cameraDir + "/" + displayName;

                        File file = new File(path);

                        ContentValues values = new ContentValues();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/");
                        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg");
                        values.put(MediaStore.Images.ImageColumns.TITLE, title);
                        values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, displayName);
                        values.put(MediaStore.Images.ImageColumns.DATA, path);
                        values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, date);
                        Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        saveByteBuffer(jpegByteArray, file, u);
                    });
                }
            }
            catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void saveByteBuffer(byte[] bytes, File file, Uri uri) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try(OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                outputStream.write(bytes);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        else {
            try(FileOutputStream fos = new FileOutputStream(file)){
                fos.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

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
                        bgExecutor,
                        stateCallback);

                try {
                    cameraDevice.createCaptureSession(sessionConfiguration);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
            else{
                try {
                    cameraDevice.createCaptureSession(surfaceList,stateCallback, mHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            runOnUiThread(() -> previewView.setAspectRatio(1080,1440));
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
            createPreview();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "IMAGE CAPTURE CALLBACK: onConfigureFailed: configure failed");
        }
    };


    @WorkerThread
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
        bBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        bBackgroundThread.start();
        cameraHandler = new Handler(mBackgroundThread.getLooper());
        mHandler = new Handler(mBackgroundThread.getLooper());
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
            mHandler = null;
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
        // Handle before permission
        displayLatestImage();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if(imageReader != null) imageReader.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        stopBackgroundThread();
    }
}