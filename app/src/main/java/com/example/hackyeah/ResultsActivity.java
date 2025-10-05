package com.example.hackyeah;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log; // <— potrzebne do logowania
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

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
    private EditText userNotes;
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

        loadLastSession();
    }

    private void loadLastSession() {
        List<SessionSummary> list = LocalStore.getLast(this, 1);
        if (list.isEmpty()) {
            setPlaceholders();
            Toast.makeText(this, R.string.msg_no_last_session, Toast.LENGTH_SHORT).show();
            last = null;
            return;
        }
        last = list.get(0);

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
        if (resultReport != null) resultReport.setText("Generating report…");

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
                runOnUiThread(() -> {
                    if (resultReport != null) {
                        resultReport.setText((text == null || text.isEmpty())
                                ? "(Brak treści odpowiedzi)"
                                : text);
                    }
                    Toast.makeText(ResultsActivity.this,
                            "Report generated (zobacz Logcat)", Toast.LENGTH_SHORT).show();
                    // przycisk i pole nastroju zostają zablokowane (jak prosiłeś)
                });
            }

            @Override public void onError(String message, Throwable t) {
                android.util.Log.e("Gemini", "Error: " + message, t);
                runOnUiThread(() -> {
                    if (resultReport != null) resultReport.setText("LLM error: " + message);
                    gen.setEnabled(true); gen.setAlpha(1f);
                    userNotes.setEnabled(true);
                    Toast.makeText(ResultsActivity.this,
                            "LLM error: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }


    // Wersja preferowana – z jawnie przekazanym hrvMs
    private String buildStressPrompt(String mood, SessionSummary s, double hrvMs) {
        // HRV do raportu: preferuj SDNN, fallback na RMSSD
        double sdnn = (s.sdnnMs > 0) ? s.sdnnMs : ((hrvMs > 0) ? hrvMs : s.rmssdMs);

        String moodText = (mood == null || mood.trim().isEmpty()) ? "(brak opisu)" : mood.trim();
        long ts = (s.startTs > 0) ? s.startTs : System.currentTimeMillis();
        String timeStr = formatLocalTime(ts);
        String daypart = daypartFromMillis(ts);
        String pnn20Str = String.format(java.util.Locale.getDefault(), "%.2f", s.pnn20);

        return "Jesteś asystentem AI analizującym stres i samopoczucie.\n" +
                "Na podstawie poniższych danych określ Poziom Stresu (1–10) i napisz krótki raport w formie bezpośredniego zwrotu do użytkownika. " +
                "Raport ma być spójny, angażujący i przypominać opis, nie listę punktów. " +
                "Skrajne wartości traktuj jako potencjalne artefakty – opisuj je po prostu jako „podwyższone/obniżone”, bez dramatyzowania.\n\n" +

                "Dane wejściowe:\n" +
                "- Samopoczucie: " + moodText + "\n" +
                "- Tętno (BPM): " + Math.max(0, s.meanHr) + "\n" +
                "- HRV (SDNN): " + Math.round(sdnn) + " ms\n" +
                "- RMSSD: " + Math.round(s.rmssdMs) + " ms\n" +
                "- SD1: " + Math.round(s.sd1Ms) + " ms\n" +
                "- pNN20: " + pnn20Str + "\n" +
                "- Czas pomiaru: " + s.durationSec + " s\n" +
                "- Godzina pomiaru: " + timeStr + "\n" +
                "- Pora dnia: " + daypart + "\n\n" +

                "- Raport powinien zawierać:\n" +
                "- Pierwsza linia z liczbą w skali 1–10 oraz etykietą (1=„bardzo niski”… 5=„umiarkowany”… 10=„bardzo wysoki”).\n" +
                "- Obserwacje wynikające z danych (HR, HRV, RMSSD, SD1, pNN20, opis samopoczucia) – bez powtarzania samych liczb, tylko interpretacja.\n" +
                "- Odniesienie do godziny/pory dnia pomiaru i możliwego wpływu na wynik.\n" +
                "- Praktyczne wskazówki (oddech, mikroprzerwa, ruch, sen) – zwięźle.\n" +
                "- Jedna krótka ciekawostka o stresie/śnie/HRV w prostych słowach.\n" +
                "- Zakończenie w tonie wspierającym.\n" +
                "Pisz zwięźle: maksymalnie kilka krótkich akapitów.";
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
