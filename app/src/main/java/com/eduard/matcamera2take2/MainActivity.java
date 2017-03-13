package com.eduard.matcamera2take2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.CheckResult;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.StringDef;
import android.support.v4.content.ContextCompat;
import android.support.v4.util.SimpleArrayMap;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.view.menu.MenuBuilder;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.security.PrivateKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_PERMISSION_RESULT = 1;
    private TextureView mTexureView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //Toast.makeText(getApplicationContext(), "Texture is available!", Toast.LENGTH_SHORT).show();
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
            //Toast.makeText(getApplicationContext(),
            //        "We are now connected af homie!!",Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {

        }
    };

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private CaptureRequest.Builder mCaptureRequestBuilder;

    private ImageButton mRecordImageButton;
    private boolean mIsRecording = false;
    private File mVideoFolder;
    private String mVideoFileName;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0,0);
        ORIENTATIONS.append(Surface.ROTATION_90,90);
        ORIENTATIONS.append(Surface.ROTATION_180,180);
        ORIENTATIONS.append(Surface.ROTATION_270,270);
    }

    public static class CompareSizeByAre implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth()* lhs.getHeight()/
                    (long) rhs.getWidth()*rhs.getHeight());
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createVideoFolder();

        mTexureView = (TextureView)findViewById(R.id.textureView);
        mRecordImageButton = (ImageButton) findViewById(R.id.videoOnlineImageButton);
        mRecordImageButton.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                if(mIsRecording){
                    mIsRecording = false;
                    mRecordImageButton.setImageResource(R.mipmap.btn_video_online);
                }else{
                    checkWriteStoreagePermission();
                }
            }
        });
    }

    @Override
    protected void onResume(){
        super.onResume();

        startBackgroundThread();

        if(mTexureView.isAvailable()){
            setupCamera(mTexureView.getWidth(),mTexureView.getHeight());
            connectCamera();
        } else {
            mTexureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION_RESULT);{
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(getApplicationContext(),
                        "dont be silly, I need the camera!!",Toast.LENGTH_LONG).show();
            }
        }
        if(requestCode == REQUEST_WRITE_PERMISSION_RESULT){
            if(grantResults[0]==PackageManager.PERMISSION_GRANTED){
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.mipmap.btn_video_buisy);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Toast.makeText(this,"Whooo acess to external storage motherfucker!!!",Toast.LENGTH_LONG).show();
            }else{
                Toast.makeText(this,"Cmon homie, dont be weird",Toast.LENGTH_SHORT).show();
            }

        }
    }

    @Override
    protected void onPause(){
        closeCamera();

        super.onPause();

        startBackgroundThread();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if(hasFocus){
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    private void setupCamera(int width, int height){
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try{
            for(String cameraId : cameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT){
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if(swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth,rotatedHeight);
                mCameraId = cameraId;
                return;
            }
        } catch(CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void connectCamera(){
        CameraManager cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED){
                    cameraManager.openCamera(mCameraId,mCameraDeviceStateCallback,mBackgroundHandler);
                }else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
                        Toast.makeText(this,"Vid app requires access to camera",Toast.LENGTH_LONG).show();
                    }
                    requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_RESULT);
                }
            }else{
                cameraManager.openCamera(mCameraId,mCameraDeviceStateCallback,mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview(){
        SurfaceTexture surfaceTexture = mTexureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(mCaptureRequestBuilder.build(),null,mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(getApplicationContext(),
                                    "shit bro, unable to setup camera preview",
                                    Toast.LENGTH_SHORT).show();
                        }
                    },null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    // not really nessisary
    private void closeCamera(){
        if(mCameraDevice != null){
            try {
                mCameraDevice.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mCameraDevice = null;
        }
    }

    private void startBackgroundThread(){
        mBackgroundHandlerThread = new HandlerThread("Camera2VideoImage");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread(){
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation){
        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        return (sensorOrientation + deviceOrientation +360) % 360;
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height){
        List<Size> bigEnough = new ArrayList<Size>();
        for(Size option: choices){
            if(option.getHeight()==option.getWidth() * height/width &&
                    option.getWidth()>=width && option.getHeight()>=height){
                bigEnough.add(option);
            }
        }
        if(bigEnough.size()>0){
            return Collections.min(bigEnough, new CompareSizeByAre());
        }else{
            return choices[0];
        }
    }

    private void createVideoFolder(){
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        mVideoFolder = new File(movieFile,"camera2VideoImage");
        if(!mVideoFolder.exists()){
            mVideoFolder.mkdirs();
        }
    }

    private File createVideoFileName() throws IOException{
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "VIDEO_" + timeStamp + "_";
        File videoFile = File.createTempFile(prepend,".mp4",mVideoFolder);
        mVideoFileName = videoFile.getAbsolutePath();
        return videoFile;
    }

    private void checkWriteStoreagePermission(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED){
                mIsRecording = true;
                mRecordImageButton.setImageResource(R.mipmap.btn_video_buisy);
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }else{
                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this,"need to be able te save son",Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_PERMISSION_RESULT);
            }
        }else{
            mIsRecording = true;
            mRecordImageButton.setImageResource(R.mipmap.btn_video_buisy);
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

}
