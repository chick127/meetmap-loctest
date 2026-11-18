package com.meetmap.loctest;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 갤러리의 최근 사진에서 위치(GPS)를 읽을 수 있는지 시험하는 플러그인.
 * - "일반 방식": 웹 사진 선택창과 비슷하게, 위치가 가려진 상태로 읽기
 * - "앱 방식": ACCESS_MEDIA_LOCATION 권한을 받고 원본을 요청해서 읽기
 */
@CapacitorPlugin(
    name = "MediaLoc",
    permissions = {
        @Permission(alias = "images", strings = { Manifest.permission.READ_MEDIA_IMAGES }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "medialoc", strings = { Manifest.permission.ACCESS_MEDIA_LOCATION })
    }
)
public class MediaLocPlugin extends Plugin {

    private String[] needed() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] { "images", "medialoc" };
        }
        return new String[] { "storage", "medialoc" };
    }

    private boolean allGranted() {
        for (String alias : needed()) {
            if (getPermissionState(alias) != PermissionState.GRANTED) {
                return false;
            }
        }
        return true;
    }

    @PluginMethod
    public void latest(PluginCall call) {
        if (allGranted()) {
            read(call);
        } else {
            requestPermissionForAliases(needed(), call, "permsDone");
        }
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        if (allGranted()) {
            read(call);
        } else {
            call.reject("권한이 허용되지 않았어요. 사진 접근을 '모두 허용'으로, 사진 위치 접근도 허용해 주세요.");
        }
    }

    private float[] gpsOf(ContentResolver cr, Uri uri) throws Exception {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ExifInterface exif = new ExifInterface(in);
            float[] ll = new float[2];
            return exif.getLatLong(ll) ? ll : null;
        }
    }

    private void read(PluginCall call) {
        int limit = call.getInt("limit", 12);
        ContentResolver cr = getContext().getContentResolver();
        JSArray items = new JSArray();
        String[] cols = new String[] {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        };
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
        try (Cursor c = cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            cols,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )) {
            int n = 0;
            while (c != null && c.moveToNext() && n < limit) {
                n++;
                long id = c.getLong(0);
                String name = c.getString(1);
                long taken = c.getLong(2);
                long added = c.getLong(3);
                long when = taken > 0 ? taken : added * 1000L;
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                JSObject o = new JSObject();
                o.put("name", name);
                o.put("date", fmt.format(new Date(when)));
                StringBuilder err = new StringBuilder();

                boolean plainGps = false;
                try {
                    plainGps = gpsOf(cr, uri) != null;
                } catch (Exception e) {
                    err.append("일반 방식 오류: ").append(e.getMessage()).append("\n");
                }

                boolean originalGps = false;
                try {
                    float[] ll = gpsOf(cr, MediaStore.setRequireOriginal(uri));
                    if (ll != null) {
                        originalGps = true;
                        o.put("lat", (double) ll[0]);
                        o.put("lng", (double) ll[1]);
                    }
                } catch (Exception e) {
                    err.append("앱 방식 오류: ").append(e.getMessage()).append("\n");
                }

                o.put("plainGps", plainGps);
                o.put("originalGps", originalGps);
                if (err.length() > 0) {
                    o.put("error", err.toString());
                }
                items.put(o);
            }
        } catch (Exception e) {
            call.reject("갤러리를 읽지 못했어요: " + e.getMessage());
            return;
        }
        JSObject ret = new JSObject();
        ret.put("items", items);
        call.resolve(ret);
    }
}
