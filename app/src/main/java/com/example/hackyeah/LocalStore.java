package com.example.hackyeah;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class LocalStore {

    private static final String FILE_NAME = "sessions.json";

    private LocalStore() {}

    // === Public API ===

    public static synchronized void appendSession(Context ctx, SessionSummary s) {
        File file = new File(ctx.getFilesDir(), FILE_NAME);
        JSONArray arr = readArray(file);
        arr.put(toJson(s));
        writeArray(file, arr);
    }

    /** Returns last N sessions sorted by startTs desc. */
    public static synchronized List<SessionSummary> getLast(Context ctx, int n) {
        File file = new File(ctx.getFilesDir(), FILE_NAME);
        JSONArray arr = readArray(file);

        List<SessionSummary> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            SessionSummary s = fromJson(o);
            if (s != null) list.add(s);
        }

        Collections.sort(list, new Comparator<SessionSummary>() {
            @Override public int compare(SessionSummary a, SessionSummary b) {
                long da = a.startTs;
                long db = b.startTs;
                // desc
                return (da == db) ? 0 : (db > da ? 1 : -1);
            }
        });

        if (n < list.size()) {
            return new ArrayList<>(list.subList(0, Math.max(0, n)));
        }
        return list;
    }

    /** Update LLM results (score + markdown) for the session identified by startTs. */
    public static synchronized void setLlmResult(Context ctx,
                                                 long startTs,
                                                 @Nullable Integer stressScore,
                                                 @Nullable String llmReportMd) {
        File file = new File(ctx.getFilesDir(), FILE_NAME);
        JSONArray arr = readArray(file);

        boolean updated = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optLong("startTs", -1L) == startTs) {
                try {
                    if (stressScore != null) o.put("stressScore", stressScore.intValue());
                    if (llmReportMd != null) o.put("llmReportMd", llmReportMd);
                } catch (JSONException ignored) {}
                updated = true;
                break;
            }
        }

        if (!updated) {
            JSONObject min = new JSONObject();
            try {
                min.put("startTs", startTs);
                if (stressScore != null) min.put("stressScore", stressScore.intValue());
                if (llmReportMd != null) min.put("llmReportMd", llmReportMd);
            } catch (JSONException ignored) {}
            arr.put(min);
        }

        writeArray(file, arr);
    }



    // === JSON mapping ===

    private static JSONObject toJson(SessionSummary s) {
        JSONObject o = new JSONObject();
        try {
            o.put("startTs", s.startTs);
            o.put("endTs", s.endTs);
            o.put("durationSec", s.durationSec);

            o.put("meanHr", s.meanHr);
            o.put("rmssdMs", s.rmssdMs);

            // Optional fields — write if present/non-zero
            o.put("sd1Ms", s.sd1Ms);
            o.put("sdnnMs", s.sdnnMs); // may be 0 if not computed
            o.put("pnn20", s.pnn20);

            o.put("note", s.note == null ? "" : s.note);

            // LLM fields (may be null)
            if (s.stressScore != null) o.put("stressScore", s.stressScore);
            if (s.llmReportMd != null) o.put("llmReportMd", s.llmReportMd);
        } catch (JSONException ignored) {}
        return o;
    }

    @Nullable
    private static SessionSummary fromJson(JSONObject o) {
        try {
            SessionSummary s = new SessionSummary();
            s.startTs = o.optLong("startTs", 0L);
            s.endTs = o.optLong("endTs", 0L);
            s.durationSec = o.optInt("durationSec", 0);

            s.meanHr = o.optInt("meanHr", 0);
            s.rmssdMs = o.optDouble("rmssdMs", 0.0);

            s.sd1Ms = o.optDouble("sd1Ms", 0.0);
            s.sdnnMs = o.optDouble("sdnnMs", 0.0);
            s.pnn20 = o.optDouble("pnn20", 0.0);

            s.note = o.optString("note", "");

            // LLM fields
            if (o.has("stressScore") && !o.isNull("stressScore")) {
                s.stressScore = o.optInt("stressScore");
            } else {
                s.stressScore = null;
            }
            if (o.has("llmReportMd") && !o.isNull("llmReportMd")) {
                s.llmReportMd = o.optString("llmReportMd", null);
            } else {
                s.llmReportMd = null;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    // === File I/O (SDK-29 friendly) ===

    private static JSONArray readArray(File f) {
        if (!f.exists()) return new JSONArray();
        FileInputStream in = null;
        ByteArrayOutputStream bos = null;
        try {
            in = new FileInputStream(f);
            bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            String json = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) return new JSONArray();
            return new JSONArray(json);
        } catch (Throwable ignored) {
            return new JSONArray();
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (bos != null) bos.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeArray(File f, JSONArray arr) {
        FileOutputStream fos = null;
        OutputStreamWriter osw = null;
        try {
            fos = new FileOutputStream(f, false);
            osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            osw.write(arr.toString());
            osw.flush();
            fos.getFD().sync(); // be robust
        } catch (Throwable ignored) {
        } finally {
            try { if (osw != null) osw.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
        }
    }

    // W LocalStore.java — wklej poniżej innych metod helperów

    public static List<SessionSummary> readAll(android.content.Context ctx) {
        File f = new File(ctx.getFilesDir(), "sessions.json"); // użyj tej samej nazwy pliku co w append/write
        List<SessionSummary> list = new ArrayList<>();
        if (!f.exists()) return list;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                SessionSummary s = new SessionSummary();
                s.startTs     = o.optLong("startTs", 0L);
                s.endTs       = o.optLong("endTs", 0L);
                s.durationSec = o.optInt("durationSec", 0);
                s.meanHr      = o.optInt("meanHr", 0);
                s.rmssdMs     = o.optDouble("rmssdMs", 0.0);
                s.sdnnMs      = o.optDouble("sdnnMs", 0.0);
                s.sd1Ms       = o.optDouble("sd1Ms", 0.0);
                s.pnn20       = o.optDouble("pnn20", 0.0);
                if (o.has("stressScore") && !o.isNull("stressScore")) {
                    s.stressScore = o.optInt("stressScore");
                }
                if (o.has("llmReportMd") && !o.isNull("llmReportMd")) {
                    s.llmReportMd = o.optString("llmReportMd", null);
                }
                s.note = o.optString("note", "");
                list.add(s);
            }
        } catch (Exception ignore) {}

        // sortuj malejąco po czasie startu
        Collections.sort(list, (a, b) -> Long.compare(b.startTs, a.startTs));
        return list;
    }

    @androidx.annotation.Nullable
    public static SessionSummary getByStartTs(android.content.Context ctx, long startTs) {
        List<SessionSummary> all = readAll(ctx);
        for (SessionSummary s : all) {
            if (s != null && s.startTs == startTs) return s;
        }
        return null;
    }


}
