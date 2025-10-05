package com.example.hackyeah;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.Protocol;

public class GeminiClient {

    public interface Listener {
        void onSuccess(String text);
        void onError(String message, Throwable t);
    }

    private static final String TAG = "Gemini";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Najpierw próbujemy v1beta, potem v1
    private static final String[] API_VERSIONS = new String[]{"v1beta", "v1"};

    // Podpowiedzi modeli (fallback, gdy ListModels nie działa)
    private static final String[] MODEL_HINTS = new String[]{
            "models/gemini-2.5-flash", "models/gemini-2.5-pro",
            "models/gemini-2.0-flash", "models/gemini-2.0-pro",
            "models/gemini-1.5-flash", "models/gemini-1.5-pro"
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)) // wyłącz HTTP/2
            .build();
    private final String apiKey;

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    // Public
    public void generateReport(String prompt, Listener cb) {
        resolveModel(new ModelResolvedCallback() {
            @Override public void onResolved(String apiVersion, String modelName) {
                callGenerateContent(apiVersion, modelName, prompt, cb);
            }
            @Override public void onError(String msg, Throwable t) {
                cb.onError(msg, t);
            }
        });
    }

    // ===== Model discovery =====
    private interface ModelResolvedCallback {
        void onResolved(String apiVersion, String modelName);
        void onError(String msg, Throwable t);
    }

    private void resolveModel(ModelResolvedCallback cb) {
        tryListModels(0, new ModelResolvedCallback() {
            @Override public void onResolved(String apiVersion, String modelName) {
                cb.onResolved(apiVersion, modelName);
            }
            @Override public void onError(String msg, Throwable t) {
                tryHints(0, cb);
            }
        });
    }

    private void tryListModels(int verIndex, ModelResolvedCallback cb) {
        if (verIndex >= API_VERSIONS.length) {
            cb.onError("list_models_failed", null);
            return;
        }
        final String ver = API_VERSIONS[verIndex];
        String url = "https://generativelanguage.googleapis.com/" + ver + "/models?key=" + apiKey;

        Log.i(TAG, "Calling: " + url);
        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "ListModels " + ver + " fail", e);
                tryListModels(verIndex + 1, cb);
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.e(TAG, "ListModels " + ver + " HTTP " + resp.code() + " " + body);
                    tryListModels(verIndex + 1, cb);
                    return;
                }
                try {
                    JSONObject j = new JSONObject(body);
                    JSONArray models = j.optJSONArray("models");
                    if (models == null || models.length() == 0) {
                        tryListModels(verIndex + 1, cb);
                        return;
                    }
                    String best = pickBestModel(models);
                    if (best == null) { tryListModels(verIndex + 1, cb); return; }
                    cb.onResolved(ver, best);
                } catch (JSONException e) {
                    Log.e(TAG, "ListModels parse error", e);
                    tryListModels(verIndex + 1, cb);
                }
            }
        });
    }

    private String pickBestModel(JSONArray models) {
        String bestName = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) continue;
            final String name = m.optString("name", "");
            if (name.isEmpty()) continue;
            if (!supportsGenerateContent(m)) continue;

            int score = 0;
            String n = name.toLowerCase();
            if (n.contains("2.5")) score += 300;
            else if (n.contains("2.0")) score += 200;
            else if (n.contains("1.5")) score += 100;

            if (n.contains("flash")) score += 20;
            if (n.contains("pro"))   score += 10;

            if (score > bestScore) {
                bestScore = score;
                bestName = name;
            }
        }
        return bestName;
    }

    private boolean supportsGenerateContent(JSONObject model) {
        JSONArray a = model.optJSONArray("supportedGenerationMethods");
        if (a == null) a = model.optJSONArray("generationMethods");
        if (a == null) return true; // brak pola – próbujemy
        for (int i = 0; i < a.length(); i++) {
            String m = a.optString(i, "");
            if ("generateContent".equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    private void tryHints(int idx, ModelResolvedCallback cb) {
        if (idx >= MODEL_HINTS.length) {
            cb.onError("no_supported_model", null);
            return;
        }
        tryModelOnVersions(MODEL_HINTS[idx], 0, new TryModelCallback() {
            @Override public void ok(String ver, String name) { cb.onResolved(ver, name); }
            @Override public void fail() { tryHints(idx + 1, cb); }
        });
    }

    private interface TryModelCallback { void ok(String ver, String name); void fail(); }

    private void tryModelOnVersions(String modelName, int verIndex, TryModelCallback cb) {
        if (verIndex >= API_VERSIONS.length) { cb.fail(); return; }
        final String ver = API_VERSIONS[verIndex];
        String url = "https://generativelanguage.googleapis.com/" + ver + "/" + modelName + ":generateContent?key=" + apiKey;

        JSONObject payload = new JSONObject();
        try {
            JSONObject part = new JSONObject().put("text", "ping");
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            payload.put("contents", new JSONArray().put(content));
        } catch (JSONException ignored) {}

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                tryModelOnVersions(modelName, verIndex + 1, cb);
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful() || resp.code() == 400) { // 400 => endpoint istnieje
                    cb.ok(ver, modelName);
                } else {
                    Log.e(TAG, ver + " " + modelName + " NOT OK: " + resp.code() + " " + body);
                    tryModelOnVersions(modelName, verIndex + 1, cb);
                }
            }
        });
    }

    // ===== GenerateContent =====
    private void callGenerateContent(String apiVersion, String modelName, String prompt, Listener cb) {
        String url = "https://generativelanguage.googleapis.com/" + apiVersion + "/" + modelName + ":generateContent?key=" + apiKey;

        JSONObject payload = new JSONObject();
        try {
            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            payload.put("contents", new JSONArray().put(content));
        } catch (JSONException e) {
            cb.onError("payload_build_fail", e);
            return;
        }

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        http.newCall(req).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "network_fail", e);
                cb.onError("network_fail", e);
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.e(TAG, "HTTP " + resp.code() + " " + body);
                    cb.onError("http_" + resp.code(), null);
                    return;
                }
                try {
                    String text = extractText(body);
                    if (text == null || text.isEmpty()) text = body;
                    cb.onSuccess(text);
                } catch (Exception e) {
                    cb.onError("parse_fail", e);
                }
            }
        });
    }

    private String extractText(String respBody) throws JSONException {
        JSONObject j = new JSONObject(respBody);
        JSONArray cand = j.optJSONArray("candidates");
        if (cand == null || cand.length() == 0) return null;
        JSONObject c0 = cand.optJSONObject(0);
        if (c0 == null) return null;

        JSONObject content = c0.optJSONObject("content");
        if (content != null) {
            JSONArray parts = content.optJSONArray("parts");
            if (parts != null && parts.length() > 0) {
                JSONObject p0 = parts.optJSONObject(0);
                if (p0 != null) return p0.optString("text", null);
            }
        }
        // starszy fallback
        return c0.optString("output_text", null);
    }
}
