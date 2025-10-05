package com.example.hackyeah;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.res.ColorStateList;
import android.graphics.Color;


import com.github.mikephil.charting.charts.LineChart;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {

    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int MAX_MEASUREMENT_SECONDS = 20;
    private static final long MAX_MEASUREMENT_MS = MAX_MEASUREMENT_SECONDS * 1000L;

    // UI
    private TextView timerTextView, heartRateTextView;
    private ProgressBar measurementProgress;
    private ScrollView rootScroll;
    private View liveCard;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private LineChart lineChart;
    private LinearLayout historyList;

    // State
    private boolean isRecording = false;
    private boolean pendingStart = false;
    private long startTime = 0L;

    // Modules
    private CameraController cameraController;
    private PPGSignalProcessor signal;
    private ChartController chart;
    private MeasurementTimer measurementTimer;

    private HeartMaskView heartMask;

    private final List<Long> peakTimestamps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // refs
        rootScroll = findViewById(R.id.rootScroll);
        liveCard   = findViewById(R.id.liveCard);
        historyList = findViewById(R.id.historyList);
        timerTextView = findViewById(R.id.timerTextView);
        heartRateTextView = findViewById(R.id.heartRateTextView);
        measurementProgress = findViewById(R.id.measurementProgress);
        surfaceView = findViewById(R.id.surfaceView);
        lineChart = findViewById(R.id.lineChart);

        heartMask = findViewById(R.id.heartMask);
        if (heartMask != null) {
            heartMask.setHeartScale(1.0f); // even bigger than default 0.98 if you want
        }

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (!isRecording) {
                    cameraController.openPreviewIfReady();  // NOWE
                }
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {}
        });

        // live UI ukryte
        lineChart.setVisibility(View.GONE);

        // bottom nav nad paskiem systemowym
        final View bottomNav = findViewById(R.id.bottomNav);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom);
            return insets;
        });

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final View topBar = findViewById(R.id.headerBar);


        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(),
                    s.top + v.getPaddingTop(),
                    v.getPaddingRight(),
                    v.getPaddingBottom());
            return insets;
        });


        // chart
        chart = new ChartController(lineChart);

        // signal
        signal = new PPGSignalProcessor(new PPGSignalProcessor.Listener() {
            @Override public void onPeak(long ts) { peakTimestamps.add(ts); }

            @Override public void onBpm(int bpm) {
                runOnUiThread(() -> heartRateTextView.setText("HR: " + bpm + " BPM"));

                // realtime HRV (10 s okno) -> pokaż obok HR
                long now = System.currentTimeMillis();
                java.util.List<Long> peaks = signal.getPeaksCopy();
                java.util.List<Long> win = new java.util.ArrayList<>();
                for (Long t : peaks) if (now - t <= 10_000) win.add(t);

                java.util.List<Integer> rr = HRVCoreUtils.filterRR(HRVCoreUtils.rrFromPeaks(win));
                HRVCoreResult h = HRVCoreUtils.compute(rr);

                runOnUiThread(() -> {
                    TextView hrvTxt = findViewById(R.id.hrvTextView);
                    if (hrvTxt != null) {
                        hrvTxt.setText("RMSSD: " + Math.round(h.rmssdMs) + " ms   SD1: " + Math.round(h.sd1Ms) + " ms");
                    }
                });
            }

            @Override public void onSamplePlotted(float value) {
                runOnUiThread(() -> chart.add(value));
            }
        });


        // camera
        cameraController = new CameraController(
                this,
                surfaceHolder,
                new CameraController.Listener() {
                    @Override public void onSample(double avgY, long ts) {
                        signal.onSample(avgY, ts);
                    }
                    @Override public void onError(String message, Throwable t) {
                        Log.e("Camera", message, t);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                    }
                });

        // timer
        measurementProgress.setMax(MAX_MEASUREMENT_SECONDS);
        measurementTimer = new MeasurementTimer(new MeasurementTimer.Callback() {
            @Override public void onTick(long elapsedMs, int mm, int ss) {
                timerTextView.setText(String.format(Locale.getDefault(), "%02d:%02d", mm, ss));
                measurementProgress.setProgress(Math.min((int)(elapsedMs / 1000), MAX_MEASUREMENT_SECONDS));
                if (signal.lastBpmComputedAtSecond(elapsedMs/1000)) {
                    appendLogToFile(elapsedMs + "; " + signal.getLastBpm() + "\n", 2);
                }
            }
            @Override public void onFinished() { autoStopMeasurement(); }
        });

        // uprawnienia tylko prosimy; kamery nie otwieramy tutaj
        checkPermissions();

        // historia
        renderHistoryList();



    }

    // ===== UI actions =====
    public void onToggleRecording(View v) {
        Button button = (Button) v;
        if (!isRecording) {
            // pokaż live
            lineChart.setVisibility(View.VISIBLE);
            if (rootScroll != null && liveCard != null) {
                rootScroll.post(() -> rootScroll.smoothScrollTo(0, liveCard.getTop()));
            }

            startTime = System.currentTimeMillis();
            measurementProgress.setProgress(0);
            peakTimestamps.clear();
            signal.reset();

            // jeśli surface już gotowy -> od razu start; inaczej czekamy na surfaceCreated
            if (surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                startMeasurementNow();
            } else {
                pendingStart = true;
            }

            button.setText(R.string.measurement_button_end);
            isRecording = true;
        } else {
            manualStop();
        }
    }

    private void startMeasurementNow() {
        try {
            if (heartMask != null) heartMask.startPulse();
            cameraController.requestStartRecording();
            measurementTimer.start(startTime, MAX_MEASUREMENT_MS);
        } catch (Exception e) {
            if (heartMask != null) heartMask.stopPulse();
            Toast.makeText(this, "Błąd nagrywania", Toast.LENGTH_SHORT).show();
            Log.e("Start recording error", e.toString());
            isRecording = false;
            Button toggle = findViewById(R.id.buttonToggle);
            if (toggle != null) toggle.setText(R.string.measurement_button_start);
        }
    }

    private void manualStop() {
        stopAndSaveSession();
        if (heartMask != null) heartMask.stopPulse();
        measurementTimer.stop();
        measurementProgress.setProgress(0);
        Button toggle = findViewById(R.id.buttonToggle);
        if (toggle != null) toggle.setText(R.string.measurement_button_start);
        isRecording = false;
        startTime = 0L;

        surfaceView.setVisibility(View.GONE);
        lineChart.setVisibility(View.GONE);
    }

    private void autoStopMeasurement() {
        try {
            if (heartMask != null) heartMask.stopPulse();
            stopAndSaveSession();           // zapis ostatniego pomiaru do LocalStore
        } catch (Exception e) {
            Log.e("AutoStop", "stop error", e);
        }

        measurementTimer.stop();
        isRecording = false;
        startTime = 0L;

        runOnUiThread(() -> {
            if (measurementProgress != null) measurementProgress.setProgress(0);
            Button toggle = findViewById(R.id.buttonToggle);
            if (toggle != null) toggle.setText(R.string.measurement_button_start);
            // opcjonalnie: Toast o zakończeniu pomiaru
            // Toast.makeText(this, "Pomiar zakończony (20 s)", Toast.LENGTH_SHORT).show();

            // PRZEJŚCIE DO WIDOKU WYNIKÓW
            Intent i = new Intent(MainActivity.this, ResultsActivity.class);
            startActivity(i);
        });
    }


    // ===== Storage / history =====
    private void stopAndSaveSession() {
        long sessionEnd = System.currentTimeMillis();
        cameraController.stopRecordingAndReturnToPreview();

        List<Long> peaks = new ArrayList<>(peakTimestamps);
        List<Integer> rr = HRVCoreUtils.filterRR(
                HRVCoreUtils.rrFromPeaks(peaks, startTime + 5000L)
        );

        HRVCoreResult h = HRVCoreUtils.compute(rr);

        SessionSummary s = new SessionSummary();
        s.startTs = startTime;
        s.endTs   = sessionEnd;
        s.durationSec = (int)Math.max(0, (sessionEnd - startTime)/1000);

        s.meanHr    = h.meanHr;
        s.rmssdMs   = h.rmssdMs;
        s.sd1Ms     = h.sd1Ms;
        s.pnn20     = h.pnn20;
        s.sdnnMs   = h.sdnnMs;


        s.note = ""; // jeśli zbierasz notatki, uzupełnij

        LocalStore.appendSession(this, s);
        renderHistoryList();
    }


    private SessionSummary buildSummary(long startTs, long endTs, List<Long> peaks, String note) {
        SessionSummary s = new SessionSummary();
        s.startTs = startTs;
        s.endTs = endTs;
        s.durationSec = (int) Math.max(0, (endTs - startTs) / 1000);

        List<Integer> rr = new ArrayList<>();
        for (int i = 1; i < peaks.size(); i++) rr.add((int) (peaks.get(i) - peaks.get(i - 1)));

        if (!rr.isEmpty()) {
            double meanRR = 0;
            for (int v : rr) meanRR += v;
            meanRR /= rr.size();
            s.meanHr = (int) Math.round(60000.0 / meanRR);
        } else s.meanHr = 0;

        if (rr.size() >= 2) {
            double sumSq = 0;
            for (int i = 1; i < rr.size(); i++) {
                double d = rr.get(i) - rr.get(i - 1);
                sumSq += d * d;
            }
            s.rmssdMs = Math.sqrt(sumSq / (rr.size() - 1));
        } else s.rmssdMs = 0.0;

        s.note = note == null ? "" : note.trim();
        return s;
    }

    private void renderHistoryList() {
        if (historyList == null) return;
        historyList.removeAllViews();

        List<SessionSummary> items = LocalStore.getLast(this, 5);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No measurements yet");
            empty.setTextSize(14f);
            empty.setTextColor(0xFF888888);
            historyList.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());

        for (SessionSummary s : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lpCard.setMargins(0, dp(6), 0, dp(6));
            card.setLayoutParams(lpCard);
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            card.setBackground(roundedCardBg());

            TextView title = new TextView(this);
            title.setTextColor(0xFF222222);
            title.setTextSize(15f);
            String timeStr = (s.startTs > 0) ? fmt.format(new Date(s.startTs)) : "-- --, --:--";
            title.setText(timeStr + "  •  HR " + s.meanHr + " bpm");
            card.addView(title);

            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
            card.addView(spacer);

            if (s.stressScore != null) {
                TextView label = new TextView(this);
                label.setText("Stress");
                label.setTextColor(0xFF666666);
                label.setTextSize(13f);
                card.addView(label);

                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                LinearLayout.LayoutParams lpPb = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
                pb.setLayoutParams(lpPb);
                pb.setIndeterminate(false);
                pb.setMax(10);
                int cl = colorForStress(s.stressScore);
                pb.setProgress(Math.max(0, Math.min(10, s.stressScore)));
                pb.setProgressTintList(ColorStateList.valueOf(cl));
                pb.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(cl, 60))); // subtle track
                card.addView(pb);

                TextView score = new TextView(this);
                score.setText(s.stressScore + "/10");
                score.setTextColor(0xFF444444);
                score.setTextSize(13f);
                score.setPadding(0, dp(4), 0, 0);
                card.addView(score);
            } else {
                Button go = new Button(this);
                go.setAllCaps(false);
                go.setText("Generate report");
                go.setTextColor(0xFFFFFFFF);
                go.setBackgroundTintList(ColorStateList.valueOf(0xFF3F51B5));
                go.setOnClickListener(v -> {
                    Intent i = new Intent(MainActivity.this, ResultsActivity.class);
                    i.putExtra(ResultsActivity.EXTRA_SESSION_START_TS, s.startTs);
                    startActivity(i);
                });
                card.addView(go);
            }

            card.setOnClickListener(v -> {
                Intent i = new Intent(MainActivity.this, ResultsActivity.class);
                i.putExtra(ResultsActivity.EXTRA_SESSION_START_TS, s.startTs);
                startActivity(i);
            });

            historyList.addView(card);
        }
    }

    /** 1 -> green, 9 -> red; values between are interpolated. Null -> neutral gray. */
    private int colorForStress(@Nullable Integer score) {
        if (score == null) return 0xFFBDBDBD; // neutral
        int s = Math.max(1, Math.min(9, score));
        float t = (s - 1f) / 8f; // 0..1
        // Material-ish greens/reds
        final int start = 0xFF2E7D32; // green-800
        final int end   = 0xFFC62828; // red-800
        return lerpColor(start, end, t);
    }

    private int lerpColor(int start, int end, float t) {
        int a = Math.round(Color.alpha(start) + t * (Color.alpha(end) - Color.alpha(start)));
        int r = Math.round(Color.red(start)   + t * (Color.red(end)   - Color.red(start)));
        int g = Math.round(Color.green(start) + t * (Color.green(end) - Color.green(start)));
        int b = Math.round(Color.blue(start)  + t * (Color.blue(end)  - Color.blue(start)));
        return Color.argb(a, r, g, b);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }





    private void appendLogToFile(String text, int logtype) {
        String prefix = (logtype == 2) ? "pulse" : (logtype == 1) ? "breath" : null;
        if (prefix == null) return;
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date(startTime));
        String fileName = prefix + "_" + ts + ".txt";
        try (FileWriter writer = new FileWriter(new File(getFilesDir(), fileName), true)) {
            writer.append(text);
        } catch (IOException ignored) {}
    }

    // ===== Permissions =====
    private boolean checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CAMERA_PERMISSION);
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Brak uprawnień do kamery", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable roundedCardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);              // white
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0xFFE0E0E0);      // light gray border
        return d;
    }


    // bottom nav
    public void onNavHome(View v) { if (rootScroll != null) rootScroll.smoothScrollTo(0, 0); }
    public void onNavLast(View v) { startActivity(new Intent(this, ResultsActivity.class)); }
    public void onNavHistory(View v) { Toast.makeText(this, R.string.todo_history, Toast.LENGTH_SHORT).show(); }
    public void onNavSettings(View v) { Toast.makeText(this, R.string.todo_settings, Toast.LENGTH_SHORT).show(); }
}
