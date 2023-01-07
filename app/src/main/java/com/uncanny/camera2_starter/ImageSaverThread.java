package com.uncanny.camera2_starter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageSaverThread implements Runnable {
    private final Image mImage;
    private final String cameraId;
    private final ContentResolver contentResolver;
    private Uri uri;
    private final Context context;

    public ImageSaverThread(Context context, Image image, String cameraId, ContentResolver contentResolver) {
        this.context = context;
        this.mImage = image;
        this.cameraId = cameraId;
        this.contentResolver = contentResolver;
    }

    @Override
    public void run() {
        ContentValues values = new ContentValues();
        File DIR_DCIM = new File(Environment.getExternalStorageDirectory()+"//DCIM//Camera//");
        if(!DIR_DCIM.exists()){
            DIR_DCIM.mkdirs();
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String currentDateandTime = sdf.format(new Date());

        File file = new File(Environment.getExternalStorageDirectory()+"//DCIM//Camera//"
                ,"Camera2_starter_"+currentDateandTime+cameraId+".jpg");


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/");
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "Camera2_starter_"+currentDateandTime+cameraId+".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpg");
            values.put( MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis() );
            values.put(MediaStore.Images.Media.TITLE, "Image.jpg");

            Uri external = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            uri = contentResolver.insert(external, values);
        }

        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        saveByteBuffer(bytes,file);
    }

    private void saveByteBuffer(byte[] bytes, File file) {
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream outputStream = contentResolver.openOutputStream(uri);
                outputStream.write(bytes);
                outputStream.close();
            }
            else {
                try{
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            mImage.close();
        }
    }

}