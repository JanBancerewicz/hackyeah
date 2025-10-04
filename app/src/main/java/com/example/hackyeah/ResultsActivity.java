package com.example.hackyeah;

import android.content.Intent;
import android.os.Bundle;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ResultsActivity extends AppCompatActivity {

    private TextView resultHr, resultHrv;
    private TextView resultRMSSD, resultSD1, resultPNN20, resultSI, resultMeta;
    private EditText userNotes;
    @Nullable private SessionSummary last;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_results);

        // refs
        resultHr   = findViewById(R.id.resultHr);
        resultHrv  = findViewById(R.id.resultHrv);
        resultRMSSD= findViewById(R.id.resultRMSSD);
        resultSD1  = findViewById(R.id.resultSD1);
        resultPNN20= findViewById(R.id.resultPNN20);
        resultSI   = findViewById(R.id.resultSI);
        resultMeta = findViewById(R.id.resultMeta);
        userNotes  = findViewById(R.id.userNotes);

        Button gen = findViewById(R.id.buttonGenerateReport);
        gen.setOnClickListener(v -> onGenerateReport());

        // system bars insets: góra i dół
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final View bottomNav = findViewById(R.id.bottomNav);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom);
            return insets;
        });

        final View headerBar = findViewById(R.id.headerBar);
        ViewCompat.setOnApplyWindowInsetsListener(headerBar, (v, insets) -> {
            Insets s = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + s.top, v.getPaddingRight(), v.getPaddingBottom());
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

        // HRV (nagłówek + metryki szczegółowe)
        resultHrv.setText(String.format(Locale.getDefault(), "HRV: %d ms", Math.round(last.rmssdMs)));
        if (resultRMSSD != null) resultRMSSD.setText(String.format(Locale.getDefault(), "RMSSD: %d ms", Math.round(last.rmssdMs)));
        if (resultSD1   != null) resultSD1.setText(String.format(Locale.getDefault(), "SD1: %d ms", Math.round(last.sd1Ms)));
        if (resultPNN20 != null) resultPNN20.setText(String.format(Locale.getDefault(), "pNN20: %.2f", last.pnn20));
        if (resultSI    != null) resultSI.setText(String.format(Locale.getDefault(), "Baevsky SI: %.5f", last.baevskySI));

        // Meta
        if (resultMeta != null) {
            String when = (last.startTs > 0)
                    ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(last.startTs))
                    : "--";
            resultMeta.setText(String.format(Locale.getDefault(), "Duration: %d s  •  When: %s", last.durationSec, when));
        }

        // Notatka (prefill)
        if (last.note != null && !last.note.isEmpty()) {
            userNotes.setText(last.note);
        } else {
            userNotes.setText("");
        }
    }

    private void setPlaceholders() {
        resultHr.setText(getString(R.string.value_hr_placeholder));
        resultHrv.setText(getString(R.string.value_hrv_placeholder));
        if (resultRMSSD != null) resultRMSSD.setText("RMSSD: -- ms");
        if (resultSD1   != null) resultSD1.setText("SD1: -- ms");
        if (resultPNN20 != null) resultPNN20.setText("pNN20: --");
        if (resultSI    != null) resultSI.setText("Baevsky SI: --");
        if (resultMeta  != null) resultMeta.setText("Duration: -- s  •  When: --");
        userNotes.setText("");
    }

    private void onGenerateReport() {
        String note = userNotes.getText() == null ? "" : userNotes.getText().toString();
        Toast.makeText(this, R.string.msg_report_collected, Toast.LENGTH_SHORT).show();
        // TODO: WorkManager z payloadem (last + note) -> LLM
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
