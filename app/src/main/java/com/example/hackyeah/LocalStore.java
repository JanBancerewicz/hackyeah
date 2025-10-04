package com.example.hackyeah;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LocalStore {
    private static final String FILE_NAME = "sessions.json";

    public static synchronized void appendSession(Context ctx, SessionSummary s) {
        try {
            JSONArray arr = readArray(ctx);
            arr.put(s.toJson());
            writeArray(ctx, arr);
        } catch (Exception ignored) {}
    }

    public static synchronized List<SessionSummary> getLast(Context ctx, int n) {
        List<SessionSummary> out = new ArrayList<>();
        try {
            JSONArray arr = readArray(ctx);
            int len = arr.length();
            int from = Math.max(0, len - n);
            for (int i = from; i < len; i++) {
                JSONObject o = arr.optJSONObject(i);
                out.add(SessionSummary.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static synchronized SessionSummary getLatest(Context ctx) {
        try {
            JSONArray arr = readArray(ctx);
            int len = arr.length();
            if (len == 0) return null;
            return SessionSummary.fromJson(arr.optJSONObject(len - 1));
        } catch (Exception e) {
            return null;
        }
    }

    // --- I/O ---
    private static JSONArray readArray(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
            String json = bos.toString(StandardCharsets.UTF_8.name()).trim();
            if (json.isEmpty()) return new JSONArray();
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeArray(Context ctx, JSONArray arr) {
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception ignored) {}
    }
}
