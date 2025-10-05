package com.example.hackyeah;

import java.util.*;

class PPGSignalProcessor {

    interface Listener {
        void onPeak(long ts);
        void onBpm(int bpm);
        void onSamplePlotted(float value);
    }

    private final Listener listener;

    // --- parametry filtrów (konserwatywne) ---
    private static final int   ROBUST_WIN   = 31;     // okno do median/MAD (≈ 1 s przy 30 FPS)
    private static final double WINSOR_K    = 3.5;    // winsoryzacja: median ± K*MAD
    private static final int   TREND_WIN    = 60;     // okno trendu (≈ 2 s)
    private static final int   SMOOTH_WIN   = 5;      // lekkie wygładzanie (≈ 0.17 s)
    private static final long  REFRACT_MS   = 600;    // martwy czas między pikami (jak wcześniej)
    private static final long  BPM_WIN_MS   = 10_000; // okno dla BPM (jak wcześniej)
    private static final int   MIN_BPM      = 45, MAX_BPM = 180;

    // --- bufory do filtrów ---
    private final Deque<Double> robustBuf = new ArrayDeque<>();
    private final Deque<Double> trendBuf  = new ArrayDeque<>();
    private final Deque<Double> smoothBuf = new ArrayDeque<>();

    // --- detekcja lokalnych maksimów: 3 próbki, ale na sygnale oczyszczonym ---
    private final Deque<Double> recent3 = new ArrayDeque<>();

    // --- piki i BPM ---
    private final List<Long> peaks = new ArrayList<>();
    private int  lastBpm = 0;
    private long lastBpmAtSec = -1;
    private long lastPeakTs = 0L;

    PPGSignalProcessor(Listener l) { this.listener = l; }

    void reset() {
        robustBuf.clear(); trendBuf.clear(); smoothBuf.clear();
        recent3.clear(); peaks.clear();
        lastBpm = 0; lastBpmAtSec = -1; lastPeakTs = 0L;
    }

    void onSample(double avg, long ts) {
        // 1) Winsoryzacja względem mediany i MAD z krótkiego okna
        double w = winsorize(avg);

        // 2) Detrending: odejmij średnią z okna trendu (wycina powolne zmiany/ściemnianie)
        push(trendBuf, w, TREND_WIN);
        double trendMean = mean(trendBuf);
        double detrended = w - trendMean;

        // 3) Lekkie wygładzanie MA, by ograniczyć szum wysokoczęstotliwościowy
        push(smoothBuf, detrended, SMOOTH_WIN);
        double clean = mean(smoothBuf);

        // wykres pokazuje sygnał oczyszczony (czytelniejszy)
        listener.onSamplePlotted((float) clean);

        // --- detekcja piku: lokalne maksimum/minimum na oczyszczonym sygnale ---
        // (logika jak wcześniej: porównanie 3 próbek; polaryzacja nieistotna dzięki detrendingowi)
        if (recent3.size() == 3) recent3.removeFirst();
        recent3.addLast(clean);

        if (recent3.size() == 3) {
            Double[] a = recent3.toArray(new Double[0]);
            boolean localMax = a[1] > a[0] && a[1] > a[2];
            boolean localMin = a[1] < a[0] && a[1] < a[2]; // gdy odwrócone sprzętowo
            if (localMax || localMin) {
                long now = ts;
                if (lastPeakTs == 0 || now - lastPeakTs >= REFRACT_MS) {
                    // RR filtrujemy tylko granicami BPM przy liczeniu BPM
                    peaks.add(now);
                    lastPeakTs = now;
                    listener.onPeak(now);
                }
            }
        }

        // --- BPM z mediany interwałów w ostatnich ~10 s (jak było) ---
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

    // ================== pomocnicze ==================
    private static void push(Deque<Double> q, double v, int max) {
        q.addLast(v);
        if (q.size() > max) q.removeFirst();
    }

    private static double mean(Deque<Double> q) {
        if (q.isEmpty()) return 0.0;
        double s = 0; for (double x : q) s += x;
        return s / q.size();
    }

    private double winsorize(double x) {
        // oblicz medianę i MAD na podstawie dotychczasowego okna
        // dodaj próbkę do okna na końcu (winsoryzujemy względem okna *sprzed* tej próbki nie jest krytyczne)
        robustBuf.addLast(x);
        if (robustBuf.size() > ROBUST_WIN) robustBuf.removeFirst();

        if (robustBuf.size() < 5) return x; // za małe okno na sensowną estymację

        ArrayList<Double> vals = new ArrayList<>(robustBuf);
        double med = median(vals);
        double mad = mad(vals, med);
        if (mad <= 0) return x;

        double lo = med - WINSOR_K * mad;
        double hi = med + WINSOR_K * mad;
        if (x < lo) return lo;
        if (x > hi) return hi;
        return x;
    }

    private static double median(List<Double> v) {
        Collections.sort(v);
        int n = v.size();
        if (n % 2 == 0) return 0.5 * (v.get(n/2 - 1) + v.get(n/2));
        return v.get(n/2);
    }

    private static double mad(List<Double> v, double med) {
        double[] dev = new double[v.size()];
        for (int i = 0; i < v.size(); i++) dev[i] = Math.abs(v.get(i) - med);
        Arrays.sort(dev);
        int n = dev.length;
        double medDev = (n % 2 == 0) ? 0.5 * (dev[n/2 - 1] + dev[n/2]) : dev[n/2];
        // stała 1.4826 aby MAD ~ sigma dla Gaussa
        return 1.4826 * medDev;
    }

    private int computeBpm(long nowTs) {
        // usuń stare piki, zostaw ~10 s
        while (peaks.size() > 1 && nowTs - peaks.get(0) > BPM_WIN_MS) peaks.remove(0);
        if (peaks.size() < 2) return 0;

        ArrayList<Long> ints = new ArrayList<>();
        for (int i = 1; i < peaks.size(); i++) {
            long rr = peaks.get(i) - peaks.get(i - 1);
            // zachowaj tylko RR dające BPM w zakresie
            if (rr > 0) {
                int bpm = (int) Math.round(60000.0 / rr);
                if (bpm >= MIN_BPM && bpm <= MAX_BPM) ints.add(rr);
            }
        }
        if (ints.isEmpty()) return 0;

        Collections.sort(ints);
        long medRR = (ints.size() % 2 == 0)
                ? (ints.get(ints.size()/2 - 1) + ints.get(ints.size()/2)) / 2
                : ints.get(ints.size()/2);

        int bpm = (int) Math.round(60000.0 / (double) medRR);
        if (bpm < MIN_BPM || bpm > MAX_BPM) return 0;
        return bpm;
    }
}
