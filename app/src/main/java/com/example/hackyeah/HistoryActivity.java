package com.example.hackyeah;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends AppCompatActivity {

    private LinearLayout historyList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        historyList = findViewById(R.id.historyList);
        renderHistoryList();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final View header = findViewById(R.id.headerBar);
        final View bottom = findViewById(R.id.bottomNav);
        final View root   = findViewById(R.id.root);
        final View scroll = findViewById(R.id.rootScroll);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            header.setPadding(
                    header.getPaddingLeft(),
                    header.getPaddingTop() + bars.top,
                    header.getPaddingRight(),
                    header.getPaddingBottom()
            );

            bottom.setPadding(
                    bottom.getPaddingLeft(),
                    bottom.getPaddingTop(),
                    bottom.getPaddingRight(),
                    bottom.getPaddingBottom() + bars.bottom
            );

            if (scroll != null) {
                scroll.setPadding(
                        scroll.getPaddingLeft(),
                        scroll.getPaddingTop(),
                        scroll.getPaddingRight(),
                        scroll.getPaddingBottom() + bars.bottom
                );
            }
            return insets; // nie konsumujemy – pozwól innym listenerom zadziałać
        });
    }

    private void renderHistoryList() {
        if (historyList == null) return;
        historyList.removeAllViews();

        List<SessionSummary> items = LocalStore.readAll(this);
        if (items == null || items.isEmpty()) {
            items = LocalStore.getLast(this, 50);
        }
        if (items == null || items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No measurements yet");
            empty.setTextSize(14f);
            empty.setTextColor(0xFF888888);
            historyList.addView(empty);
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());

        for (SessionSummary s : items) {
            // Card container
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

            // Title line
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
                // Label
                TextView label = new TextView(this);
                label.setText("Stress");
                label.setTextColor(0xFF666666);
                label.setTextSize(13f);
                card.addView(label);

                // Progress bar (1..10), color → stress level
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                LinearLayout.LayoutParams lpPb = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
                pb.setLayoutParams(lpPb);
                pb.setIndeterminate(false);
                pb.setMax(10);
                int cl = colorForStress(s.stressScore);
                pb.setProgress(Math.max(0, Math.min(10, s.stressScore)));
                pb.setProgressTintList(ColorStateList.valueOf(cl));
                pb.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(cl, 60)));
                card.addView(pb);

                TextView score = new TextView(this);
                score.setText(s.stressScore + "/10");
                score.setTextColor(0xFF444444);
                score.setTextSize(13f);
                score.setPadding(0, dp(4), 0, 0);
                card.addView(score);
            } else {
                // Go to Results to generate report for this session
                Button go = new Button(this);
                go.setAllCaps(true);
                go.setText("Inspect results");
                go.setTypeface(null, Typeface.BOLD);
                go.setTextColor(0xFFFFFFFF);
                go.setBackgroundTintList(ColorStateList.valueOf(0xFF3F51B5));
                go.setBackgroundResource(R.drawable.bg_btn_green);
                go.setOnClickListener(v -> {
                    Intent i = new Intent(HistoryActivity.this, ResultsActivity.class);
                    i.putExtra("session_start_ts", s.startTs); // explicit key to avoid dependency
                    startActivity(i);
                });
                card.addView(go);
            }

            // Tap the whole card → open Results for this session (read-only unless user taps generate there)
            card.setOnClickListener(v -> {
                Intent i = new Intent(HistoryActivity.this, ResultsActivity.class);
                i.putExtra("session_start_ts", s.startTs);
                startActivity(i);
            });

            historyList.addView(card);
        }
    }

    // == helpers ==
    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private Drawable roundedCardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(16));
        d.setStroke(dp(1), 0xFFE0E0E0);
        return d;
    }

    private int colorForStress(int score) {
        // Clamp 1..10 (treat 0 as 1)
        int s = Math.max(1, Math.min(10, score));
        float t = (s - 1f) / 9f; // 0..1
        // green -> red linear blend
        int r = (int) (0x3C + (0xE9 - 0x3C) * t); // 0x3C..0xE9
        int g = (int) (0xB3 + (0x1E - 0xB3) * t); // 0xB3..0x1E
        int b = (int) (0x41 + (0x63 - 0x41) * t); // 0x41..0x63
        return Color.rgb(r, g, b);
    }

    private int withAlpha(int color, int alpha /*0..255*/) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
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
        startActivity(new Intent(this, ResultsActivity.class));
        finish();
    }

    public void onNavHistory(View v) {
        // already here
    }

    public void onNavSettings(View v) {
        Toast.makeText(this, "Settings: TODO", Toast.LENGTH_SHORT).show();
    }
}
