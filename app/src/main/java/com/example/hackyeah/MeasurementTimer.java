package com.example.hackyeah;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

class MeasurementTimer {

    interface Callback {
        void onTick(long elapsedMs, int mm, int ss);
        void onFinished();
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Callback cb;

    private long startTs = 0L;
    private long maxMs = 0L;
    private boolean running = false;

    MeasurementTimer(Callback cb) { this.cb = cb; }

    void start(long startTs, long maxMs) {
        this.startTs = startTs;
        this.maxMs = maxMs;
        this.running = true;
        h.post(tick);
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long elapsed = System.currentTimeMillis() - startTs;
            int seconds = (int) (elapsed / 1000);
            int minutes = seconds / 60;
            seconds = seconds % 60;
            cb.onTick(elapsed, minutes, seconds);

            if (elapsed >= maxMs) {
                running = false;
                cb.onFinished();
                return;
            }
            h.postDelayed(this, 1000);
        }
    };
}
