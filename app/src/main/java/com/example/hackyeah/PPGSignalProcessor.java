package com.example.hackyeah;

import java.util.*;

class PPGSignalProcessor {

    interface Listener {
        void onPeak(long ts);
        void onBpm(int bpm);
        void onSamplePlotted(float value);
    }

    private final Listener listener;

    // --- parametry filtrów (adaptowane do fps) ---
    private static final double TARGET_DETREND_SEC = 2.0;   // okno trendu ~2 s
    private static final double TARGET_SMOOTH_SEC  = 0.15;  // wygładzanie ~150 ms
    private static final int    DETREND_MIN = 15, DETREND_MAX = 300;
    private static final int    SMOOTH_MIN  = 3,  SMOOTH_MAX  = 15;

    // --- bufory do filtracji ---
    private final Deque<Double> detrendBuf = new ArrayDeque<>();
    private double detrendSum = 0.0;
    private int detrendWin = 60;

    private final Deque<Double> smoothBuf = new ArrayDeque<>();
    private double smoothSum = 0.0;
    private int smoothWin = 5;

    // --- do estymacji fps (dt) ---
    private long prevTs = -1;
    private double emaDtMs = 33.0; // start ~30 fps

    // --- detekcja pików na sygnale przefiltrowanym ---
    private final Deque<Double> recent3 = new ArrayDeque<>();
    private final Deque<Long>   recent3Ts = new ArrayDeque<>();
    private final List<Long>    peaks = new ArrayList<>();

    // --- BPM z okna 10 s (jak było) ---
    private int  lastBpm = 0;
    private long lastBpmAtSec = -1;

    PPGSignalProcessor(Listener l) { this.listener = l; }

    void reset() {
        detrendBuf.clear(); detrendSum = 0.0;
        smoothBuf.clear();  smoothSum  = 0.0;
        recent3.clear(); recent3Ts.clear();
        peaks.clear();
        lastBpm = 0; lastBpmAtSec = -1;
        prevTs = -1; emaDtMs = 33.0;
    }

    void onSample(double avg, long ts) {
        // --- estymacja kroku czasowego ---
        if (prevTs > 0) {
            double dt = ts - prevTs;
            emaDtMs = 0.9 * emaDtMs + 0.1 * dt; // łagodne śledzenie FPS
        }
        prevTs = ts;

        // --- dostosowanie rozmiarów okien do FPS ---
        double fs = 1000.0 / Math.max(1.0, emaDtMs);
        int newDetrend = clamp((int)Math.round(TARGET_DETREND_SEC * fs), DETREND_MIN, DETREND_MAX);
        int newSmooth  = clamp((int)Math.round(TARGET_SMOOTH_SEC  * fs), SMOOTH_MIN,  SMOOTH_MAX);
        detrendWin = newDetrend; smoothWin = newSmooth;

        // --- detrending: x - mean(x, okno~2s) ---
        detrendSum += avg;
        detrendBuf.addLast(avg);
        if (detrendBuf.size() > detrendWin) detrendSum -= detrendBuf.removeFirst();
        double baseline = detrendSum / detrendBuf.size();
        double detrended = avg - baseline;

        // --- wygładzanie MA (~0.15 s) ---
        smoothSum += detrended;
        smoothBuf.addLast(detrended);
        if (smoothBuf.size() > smoothWin) smoothSum -= smoothBuf.removeFirst();
        double filtered = smoothSum / smoothBuf.size();

        // do wykresu pokazujemy sygnał po filtracji
        listener.onSamplePlotted((float) filtered);

        // --- detekcja lokalnego maksimum z interpolacją paraboliczną ---
        recent3.addLast(filtered);
        recent3Ts.addLast(ts);
        if (recent3.size() == 3) {
            Double[] y = recent3.toArray(new Double[0]);
            Long[]   t = recent3Ts.toArray(new Long[0]);

            if (y[1] > y[0] && y[1] > y[2]) {
                // parabola przez punkty (-1,y0),(0,y1),(+1,y2)
                double denom = (y[0] - 2*y[1] + y[2]);
                double delta = 0.0;
                if (Math.abs(denom) > 1e-9) {
                    delta = 0.5 * (y[0] - y[2]) / denom; // przesunięcie w próbkach względem środkowej
                    // ogranicz, by nie „uciekało”
                    if (delta > 0.5) delta = 0.5;
                    if (delta < -0.5) delta = -0.5;
                }
                long refinedTs = t[1] + Math.round(delta * emaDtMs);

                // refrakcja 600 ms jak wcześniej
                if (peaks.isEmpty() || refinedTs - peaks.get(peaks.size()-1) > 600) {
                    peaks.add(refinedTs);
                    listener.onPeak(refinedTs);
                }
            }
            // przesuwamy okno
            recent3.removeFirst();
            recent3Ts.removeFirst();
        }

        // --- BPM z okna 10 s (bez zmian koncepcyjnych) ---
        int bpm = computeBpm(ts);
        if (bpm != lastBpm) {
            lastBpm = bpm;
            listener.onBpm(bpm);
        }
    }

    int getLastBpm() { return lastBpm; }

    boolean lastBpmComputedAtSecond(long sec) {
        if (sec != lastBpmAtSec) { lastBpmAtSec = sec; return true; }
        return false;
    }

    List<Long> getPeaksCopy() { return new ArrayList<>(peaks); }

    // --- pomocnicze ---
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int computeBpm(long nowTs) {
        // usuń piki starsze niż 10 s
        while (peaks.size() > 1 && nowTs - peaks.get(0) > 10_000) peaks.remove(0);
        if (peaks.size() < 2) return 0;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < peaks.size(); i++) intervals.add(peaks.get(i) - peaks.get(i - 1));
        if (intervals.isEmpty()) return 0;

        intervals.sort(Long::compare);
        int n = intervals.size();
        long med = (n % 2 == 0) ? (intervals.get(n/2 - 1) + intervals.get(n/2)) / 2 : intervals.get(n/2);

        int bpm = (int) (60000.0 / med);
        if (bpm < 45 || bpm > 180) return 0;
        return bpm;
    }
}
