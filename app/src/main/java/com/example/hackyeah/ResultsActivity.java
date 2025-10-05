package com.example.hackyeah;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log; // <— potrzebne do logowania
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import io.noties.markwon.Markwon;
import java.util.regex.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import androidx.annotation.Nullable;
import io.noties.markwon.Markwon;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.Locale;

public class ResultsActivity extends AppCompatActivity {

    private static final String TAG = "ReportPrompt";

    private TextView resultHr, resultHrv;
    private TextView resultRMSSD, resultSD1, resultPNN20, resultMeta;
    private TextView resultReport;
    private Markwon markwon;
    private EditText userNotes;
    private long selectedStartTs = -1L;

    public static final String EXTRA_SESSION_START_TS = "extra_session_start_ts";



    @Nullable private SessionSummary last;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        // refs
        resultHr    = findViewById(R.id.resultHr);
        resultHrv   = findViewById(R.id.resultHrv);
        resultRMSSD = findViewById(R.id.resultRMSSD);
        resultSD1   = findViewById(R.id.resultSD1);
        resultPNN20 = findViewById(R.id.resultPNN20);
        resultMeta  = findViewById(R.id.resultMeta);
        userNotes   = findViewById(R.id.userNotes);

        resultReport = findViewById(R.id.resultReport);
        markwon = Markwon.create(this);

        Button gen = findViewById(R.id.buttonGenerateReport);
        gen.setOnClickListener(v -> onGenerateReport());

        // System bars / IME: header przypięty poniżej status bara, bottom nav nad paskiem gestów/IME
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final View headerBar = findViewById(R.id.headerBar);
        final int hStart = headerBar.getPaddingStart();
        final int hTop0  = headerBar.getPaddingTop();
        final int hEnd   = headerBar.getPaddingEnd();
        final int hBot   = headerBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(headerBar, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPaddingRelative(hStart, hTop0 + s.top, hEnd, hBot);
            return insets;
        });

        final View bottomNav = findViewById(R.id.bottomNav);
        final int bStart = bottomNav.getPaddingStart();
        final int bTop   = bottomNav.getPaddingTop();
        final int bEnd   = bottomNav.getPaddingEnd();
        final int bBot0  = bottomNav.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            Insets navIme = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPaddingRelative(bStart, bTop, bEnd, bBot0 + navIme.bottom);
            return insets;
        });
        selectedStartTs = getIntent().getLongExtra("session_start_ts", -1L);
        // ResultsActivity.java — onCreate(): after setContentView(...)
        long targetStartTs = getIntent().getLongExtra(EXTRA_SESSION_START_TS, -1L);
        if (targetStartTs > 0) {
            SessionSummary s = LocalStore.getByStartTs(this, targetStartTs);
            if (s != null) {
                last = s;
                bindSessionToUi(s);  // new small helper (below)
            } else {
                // fallback – not found
                loadLastSession();
            }
        } else {
            loadLastSession();
        }

    }

    // ResultsActivity.java — add this method
    private void bindSessionToUi(SessionSummary s) {
        // HR
        resultHr.setText(String.format(Locale.getDefault(), "%d bpm", Math.max(0, s.meanHr)));

        // HRV headline: prefer SDNN, fallback RMSSD
        double hrvMs = (s.sdnnMs > 0) ? s.sdnnMs : s.rmssdMs;
        resultHrv.setText(String.format(Locale.getDefault(), "HRV: %d ms", Math.round(hrvMs)));

        // Details
        resultRMSSD.setText(String.format(Locale.getDefault(), "RMSSD: %d ms", Math.round(s.rmssdMs)));
        resultSD1.setText(String.format(Locale.getDefault(), "SD1: %d ms", Math.round(s.sd1Ms)));
        resultPNN20.setText(String.format(Locale.getDefault(), "pNN20: %.2f", s.pnn20));

        String when = android.text.format.DateFormat.format("yyyy-MM-dd  HH:mm", s.startTs).toString();
        resultMeta.setText(String.format(Locale.getDefault(), "Duration: %d s  •  When: %s", s.durationSec, when));

        // keep `last` in sync
        last = s;
    }


    @Nullable
    private Integer parseStressScore(String md) {
        if (md == null) return null;
        String firstLine = md.split("\\R", 2)[0];
        // matches: "7", "7/10", "**7/10**", "7 – moderate", "Stress: 7/10", etc.
        Pattern p = Pattern.compile("(^|\\b)([1-9]|10)\\s*(?:/\\s*10)?\\b");
        Matcher m = p.matcher(firstLine);
        return m.find() ? Integer.parseInt(m.group(2)) : null;
    }


    private void loadLastSession() {
        if (selectedStartTs > 0) {
            last = LocalStore.getByStartTs(this, selectedStartTs);
            if (last == null) {
                // fallback to most recent
                List<SessionSummary> list = LocalStore.getLast(this, 1);
                if (list.isEmpty()) { setPlaceholders(); Toast.makeText(this, R.string.msg_no_last_session, Toast.LENGTH_SHORT).show(); return; }
                last = list.get(0);
            }
        } else {
            List<SessionSummary> list = LocalStore.getLast(this, 1);
            if (list.isEmpty()) { setPlaceholders(); Toast.makeText(this, R.string.msg_no_last_session, Toast.LENGTH_SHORT).show(); return; }
            last = list.get(0);
        }

        // HR
        resultHr.setText(String.format(Locale.getDefault(), "%d bpm", Math.max(0, last.meanHr)));

        // HRV w nagłówku: preferuj SDNN, fallback RMSSD
        double hrvMs = (last.sdnnMs > 0) ? last.sdnnMs : last.rmssdMs;
        resultHrv.setText(String.format(Locale.getDefault(), "HRV: %d ms", Math.round(hrvMs)));

        // Szczegóły
        resultRMSSD.setText(String.format(Locale.getDefault(), "RMSSD: %d ms", Math.round(last.rmssdMs)));
        resultSD1.setText(String.format(Locale.getDefault(), "SD1: %d ms", Math.round(last.sd1Ms)));
        resultPNN20.setText(String.format(Locale.getDefault(), "pNN20: %.2f", last.pnn20));

        String when = android.text.format.DateFormat.format("yyyy-MM-dd  HH:mm", last.startTs).toString();
        resultMeta.setText(String.format(Locale.getDefault(), "Duration: %d s  •  When: %s", last.durationSec, when));

        // Auto-generate if requested
        boolean auto = getIntent().getBooleanExtra("auto_generate", false);
        if (auto) {
            Button gen = findViewById(R.id.buttonGenerateReport);
            if (gen != null && gen.isEnabled()) onGenerateReport();
        }
    }

    private void setPlaceholders() {
        resultHr.setText(getString(R.string.value_hr_placeholder));
        resultHrv.setText(getString(R.string.value_hrv_placeholder));
        if (resultRMSSD != null) resultRMSSD.setText("RMSSD: -- ms");
        if (resultSD1   != null) resultSD1.setText("SD1: -- ms");
        if (resultPNN20 != null) resultPNN20.setText("pNN20: --");
        if (resultMeta  != null) resultMeta.setText("Duration: -- s  •  When: --");
        if (userNotes   != null) userNotes.setText("");
    }

    private void onGenerateReport() {
        final Button gen = findViewById(R.id.buttonGenerateReport);
        final String mood = (userNotes.getText() == null) ? "" : userNotes.getText().toString().trim();

        if (last == null) {
            Toast.makeText(this, R.string.msg_no_last_session, Toast.LENGTH_SHORT).show();
            android.util.Log.i("ReportPrompt", "Brak ostatniego pomiaru – nie generuję prompta.");
            return;
        }

        // HRV do raportu: preferuj SDNN, fallback na RMSSD
        final double hrvMs = (last.sdnnMs > 0) ? last.sdnnMs : last.rmssdMs;

        // Zbuduj prompt z hrvMs
        final String prompt = buildStressPrompt(mood, last, hrvMs);

        // Log prompta
        android.util.Log.i("ReportPrompt",
                "\n===== PROMPT DO LLM =====\n" + prompt + "\n=========================\n");

        // Zablokuj UI na czas generowania
        gen.setEnabled(false);
        gen.setAlpha(0.6f);
        userNotes.setEnabled(false);
        if (resultReport != null) markwon.setMarkdown(resultReport, "Generating report_");

        // Klucz API
        final String apiKey = getString(R.string.gemini_api_key);
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "Brak GEMINI_API_KEY (ustaw zmienną środowiskową / resources)", Toast.LENGTH_SHORT).show();
            gen.setEnabled(true); gen.setAlpha(1f);
            userNotes.setEnabled(true);
            if (resultReport != null) resultReport.setText("(Brak klucza API)");
            return;
        }

        // Wywołanie Gemini
        GeminiClient client = new GeminiClient(apiKey);
        client.generateReport(prompt, new GeminiClient.Listener() {
            @Override public void onSuccess(String text) {
                android.util.Log.i("Gemini",
                        "\n===== LLM RESPONSE =====\n" + text + "\n=======================\n");

                // 1) Parse stress score from the first non-empty line (1–10)
                @Nullable Integer stress = extractStressScore(text);
                android.util.Log.i("GeminiParse",
                        "Parsed stress score = " + (stress == null ? "null" : stress));

                // 2) Save into JSON (append/update last session)
                if (last != null) {
                    LocalStore.setLlmResult(ResultsActivity.this, last.startTs, stress, text);
                }

                // 3) Render Markdown in the resultReport TextView
                runOnUiThread(() -> {
                    if (resultReport != null) {
                        Markwon markwon = Markwon.create(ResultsActivity.this);
                        if (text == null || text.isEmpty()) {
                            resultReport.setText("(Empty LLM response)");
                        } else {
                            markwon.setMarkdown(resultReport, text);
                        }
                    }
                    Toast.makeText(ResultsActivity.this,
                            "Report generated (see Logcat)", Toast.LENGTH_SHORT).show();
                    // Keep button + mood field disabled as you wanted
                });
            }

            @Override public void onError(String message, Throwable t) {
                android.util.Log.e("Gemini", "Error: " + message, t);
                runOnUiThread(() -> {
                    if (resultReport != null) {
                        markwon.setMarkdown(resultReport, "**Błąd generowania raportu:** " + message);
                    }
                    gen.setEnabled(true); gen.setAlpha(1f);
                    userNotes.setEnabled(true);
                    Toast.makeText(ResultsActivity.this, "LLM error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Nullable
    private Integer extractStressScore(@Nullable String md) {
        if (md == null) return null;

        // First non-empty line only
        String first = null;
        for (String line : md.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) { first = t; break; }
        }
        if (first == null) return null;

        // Examples to match:
        // "7 – moderate", "Stress: 6/10", "8/10 (high)", "9"
        Pattern p = Pattern.compile(
                "^(?:\\**\\s*)?(?:stress\\s*[:=-]\\s*)?(\\d{1,2})(?:\\s*/\\s*10)?\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(first);
        if (!m.find()) {
            android.util.Log.i("GeminiParse", "No score found in first line: " + first);
            return null;
        }
        try {
            int v = Integer.parseInt(m.group(1));
            if (v >= 1 && v <= 10) return v;
            android.util.Log.i("GeminiParse", "Out-of-range score: " + v + " (line: " + first + ")");
            return null;
        } catch (NumberFormatException e) {
            android.util.Log.i("GeminiParse", "Parse error in line: " + first, e);
            return null;
        }
    }



    // Wersja preferowana – z jawnie przekazanym hrvMs
    // Replace your existing buildStressPrompt(...) with this:
    private String buildStressPrompt(String mood, SessionSummary s, double hrvMs) {
        double sdnn = (s.sdnnMs > 0) ? s.sdnnMs : ((hrvMs > 0) ? hrvMs : s.rmssdMs);
        String moodText = (mood == null || mood.trim().isEmpty()) ? "(no description)" : mood.trim();
        long ts = (s.startTs > 0) ? s.startTs : System.currentTimeMillis();
        String timeStr = formatLocalTime(ts);
        String daypart = daypartFromMillis(ts);
        String pnn20Str = String.format(java.util.Locale.US, "%.2f", s.pnn20);

        return ""
                + "You are an AI assistant analyzing stress and wellbeing. "
                + "Using the data below, estimate a **Stress Level (1–10)** and write a short **markdown-formatted** report addressed directly to the user.\n\n"

                + "**Input**\n"
                + "- Mood: " + moodText + "\n"
                + "- Heart rate (BPM): " + Math.max(0, s.meanHr) + "\n"
                + "- HRV (SDNN): " + Math.round(sdnn) + " ms\n"
                + "- RMSSD: " + Math.round(s.rmssdMs) + " ms\n"
                + "- SD1: " + Math.round(s.sd1Ms) + " ms\n"
                + "- pNN20: " + pnn20Str + "\n"
                + "- Measurement duration: " + s.durationSec + " s\n"
                + "- Time of measurement: " + timeStr + " (" + daypart + ")\n\n"

                + "**Instructions**\n"
                + "- First line: only the number 1–10 plus a label in parentheses (1 = very low … 5 = moderate … 10 = very high).\n"
                + "- Write 2–4 short paragraphs (no bullet lists). Interpret the data rather than repeating raw numbers.\n"
                + "- **Bold** key insights and user-relevant takeaways; use *italics* sparingly.\n"
                + "- Treat extreme values as potential artifacts; describe them neutrally as **elevated** or **reduced** (avoid exaggeration).\n"
                + "- Include practical tips (breathing, micro-breaks, light movement, sleep hygiene) and **one short fun fact** about stress/sleep/HRV.\n"
                + "- Reference the time of day if relevant (e.g., morning vs evening effects).\n"
                + "- Keep it concise, friendly, and end with a supportive closing.";
    }


    private String formatLocalTime(long tsMillis) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(tsMillis));
    }

    private String daypartFromMillis(long tsMillis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(tsMillis);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY);
        if (h >= 5  && h < 12) return "poranek";
        if (h >= 12 && h < 18) return "popołudnie";
        if (h >= 18 && h < 22) return "wieczór";
        return "noc";
    }






    // Header button
    public void onHeaderGoToMain(View v) {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // Bottom nav
    public void onNavHome(View v) {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
    public void onNavLast(View v) {
        loadLastSession();
        Toast.makeText(this, R.string.msg_refreshed, Toast.LENGTH_SHORT).show();
    }
    public void onNavHistory(View v) {
        Toast.makeText(this, R.string.todo_history, Toast.LENGTH_SHORT).show();
    }
    public void onNavSettings(View v) {
        Toast.makeText(this, R.string.todo_settings, Toast.LENGTH_SHORT).show();
    }
}
