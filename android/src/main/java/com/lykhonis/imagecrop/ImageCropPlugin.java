package com.lykhonis.imagecrop;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.Build;
import android.util.Log;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public final class ImageCropPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, RequestPermissionsResultListener {
    private static final int PERMISSION_REQUEST_CODE = 13094;

    private MethodChannel channel;
    private ActivityPluginBinding binding;
    private Activity activity;
    private Result permissionRequestResult;
    private ExecutorService executor;

    public ImageCropPlugin() {}

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        setup(binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding activityPluginBinding) {
        binding = activityPluginBinding;
        activity = activityPluginBinding.getActivity();
        activityPluginBinding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
        if (binding != null) {
            binding.removeRequestPermissionsResultListener(this);
        }
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding activityPluginBinding) {
        onAttachedToActivity(activityPluginBinding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    private void setup(BinaryMessenger messenger) {
        channel = new MethodChannel(messenger, "plugins.lykhonis.com/image_crop");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (activity == null) {
            result.error("NO_ACTIVITY", "Plugin requires a foreground activity.", null);
            return;
        }

        switch (call.method) {
            case "cropImage":
                String path = call.argument("path");
                double scale = call.argument("scale");
                double left = call.argument("left");
                double top = call.argument("top");
                double right = call.argument("right");
                double bottom = call.argument("bottom");
                RectF area = new RectF((float) left, (float) top, (float) right, (float) bottom);
                cropImage(path, area, (float) scale, result);
                break;
            case "sampleImage":
                sampleImage(
                        call.argument("path"),
                        call.argument("maximumWidth"),
                        call.argument("maximumHeight"),
                        result
                );
                break;
            case "getImageOptions":
                getImageOptions(call.argument("path"), result);
                break;
            case "requestPermissions":
                requestPermissions(result);
                break;
            default:
                result.notImplemented();
        }
    }

    private synchronized void io(@NonNull Runnable runnable) {
        if (executor == null) {
            executor = Executors.newCachedThreadPool();
        }
        executor.execute(runnable);
    }

    private void ui(@NonNull Runnable runnable) {
        if (activity != null) {
            activity.runOnUiThread(runnable);
        }
    }

    private void cropImage(final String path, final RectF area, final float scale, final Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            Bitmap srcBitmap = BitmapFactory.decodeFile(path, null);
            if (srcBitmap == null) {
                ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            if (options.isFlippedDimensions()) {
                Matrix transformations = new Matrix();
                transformations.postRotate(options.getDegrees());
                Bitmap oldBitmap = srcBitmap;
                srcBitmap = Bitmap.createBitmap(oldBitmap, 0, 0, oldBitmap.getWidth(), oldBitmap.getHeight(), transformations, true);
                oldBitmap.recycle();
            }

            int width = (int) (options.getWidth() * area.width() * scale);
            int height = (int) (options.getHeight() * area.height() * scale);

            Bitmap dstBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(dstBitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);

            Rect srcRect = new Rect((int) (srcBitmap.getWidth() * area.left),
                    (int) (srcBitmap.getHeight() * area.top),
                    (int) (srcBitmap.getWidth() * area.right),
                    (int) (srcBitmap.getHeight() * area.bottom));
            Rect dstRect = new Rect(0, 0, width, height);
            canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint);

            try {
                File dstFile = createTemporaryImageFile();
                compressBitmap(dstBitmap, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Image could not be saved", e));
            } finally {
                canvas.setBitmap(null);
                dstBitmap.recycle();
                srcBitmap.recycle();
            }
        });
    }

    private void sampleImage(String path, int maximumWidth, int maximumHeight, Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            BitmapFactory.Options bitmapOptions = new BitmapFactory.Options();
            bitmapOptions.inSampleSize = calculateInSampleSize(options.getWidth(), options.getHeight(), maximumWidth, maximumHeight);

            Bitmap bitmap = BitmapFactory.decodeFile(path, bitmapOptions);
            if (bitmap == null) {
                ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                return;
            }

            if (options.getWidth() > maximumWidth && options.getHeight() > maximumHeight) {
                float ratio = Math.max(maximumWidth / (float) options.getWidth(), maximumHeight / (float) options.getHeight());
                Bitmap sample = bitmap;
                bitmap = Bitmap.createScaledBitmap(sample, Math.round(bitmap.getWidth() * ratio), Math.round(bitmap.getHeight() * ratio), true);
                sample.recycle();
            }

            try {
                File dstFile = createTemporaryImageFile();
                compressBitmap(bitmap, dstFile);
                copyExif(srcFile, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Image could not be saved", e));
            } finally {
                bitmap.recycle();
            }
        });
    }

    private void getImageOptions(String path, Result result) {
        io(() -> {
            File file = new File(path);
            if (!file.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            Map<String, Object> properties = new HashMap<>();
            properties.put("width", options.getWidth());
            properties.put("height", options.getHeight());
            ui(() -> result.success(properties));
        });
    }

    private void requestPermissions(Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    activity.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                result.success(true);
            } else {
                permissionRequestResult = result;
                activity.requestPermissions(new String[]{READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            int read = getPermissionGrantResult(READ_EXTERNAL_STORAGE, permissions, grantResults);
            int write = getPermissionGrantResult(WRITE_EXTERNAL_STORAGE, permissions, grantResults);
            permissionRequestResult.success(read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED);
            permissionRequestResult = null;
        }
        return false;
    }

    private int getPermissionGrantResult(String permission, String[] permissions, int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {
            if (permission.equals(permissions[i])) {
                return grantResults[i];
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    private File createTemporaryImageFile() throws IOException {
        File directory = activity.getCacheDir();
        String name = "image_crop_" + UUID.randomUUID().toString();
        return File.createTempFile(name, ".jpg", directory);
    }

    private void compressBitmap(Bitmap bitmap, File file) throws IOException {
        OutputStream outputStream = new FileOutputStream(file);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)) {
                throw new IOException("Failed to compress bitmap");
            }
        } finally {
            outputStream.close();
        }
    }

    private int calculateInSampleSize(int width, int height, int maxWidth, int maxHeight) {
        int inSampleSize = 1;

        if (height > maxHeight || width > maxWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= maxHeight &&
                    (halfWidth / inSampleSize) >= maxWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    private ImageOptions decodeImageOptions(String path) {
        int rotationDegrees = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            rotationDegrees = exif.getRotationDegrees();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to read file " + path, e);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return new ImageOptions(options.outWidth, options.outHeight, rotationDegrees);
    }

    private void copyExif(File source, File destination) {
        try {
            ExifInterface srcExif = new ExifInterface(source.getAbsolutePath());
            ExifInterface dstExif = new ExifInterface(destination.getAbsolutePath());

            List<String> tags = Arrays.asList(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL
            );

            for (String tag : tags) {
                String value = srcExif.getAttribute(tag);
                if (value != null) dstExif.setAttribute(tag, value);
            }

            dstExif.saveAttributes();
        } catch (IOException e) {
            Log.e("ImageCrop", "Failed to copy Exif", e);
        }
    }

    private static final class ImageOptions {
        private final int width;
        private final int height;
        private final int degrees;

        ImageOptions(int width, int height, int degrees) {
            this.width = width;
            this.height = height;
            this.degrees = degrees;
        }

        int getWidth() {
            return (isFlippedDimensions() && degrees != 180) ? height : width;
        }

        int getHeight() {
            return (isFlippedDimensions() && degrees != 180) ? width : height;
        }

        int getDegrees() {
            return degrees;
        }

        boolean isFlippedDimensions() {
            return degrees == 90 || degrees == 270 || degrees == 180;
        }
    }
}
