package com.example.hackyeah;

import java.util.*;

class PPGSignalProcessor {

    interface Listener {
        void onPeak(long ts);
        void onBpm(int bpm);
        void onSamplePlotted(float value);
    }

    private final Listener listener;

    // smoothing
    private static final double ALPHA = 0.2;
    private Double filtered = null;

    // peak detection
    private final Deque<Double> recent3 = new ArrayDeque<>();
    private final List<Long> peaks = new ArrayList<>();

    // bpm window
    private int lastBpm = 0;
    private long lastBpmAtSec = -1;

    PPGSignalProcessor(Listener l) { this.listener = l; }

    void reset() {
        filtered = null;
        recent3.clear();
        peaks.clear();
        lastBpm = 0;
        lastBpmAtSec = -1;
    }

    void onSample(double avg, long ts) {
        // smoothing
        filtered = (filtered == null) ? avg : ALPHA * avg + (1 - ALPHA) * filtered;

        // plot
        listener.onSamplePlotted((float) avg);

        // peak detection on raw avg (jak dotąd)
        if (recent3.size() == 3) recent3.removeFirst();
        recent3.addLast(avg);

        if (recent3.size() == 3) {
            Double[] a = recent3.toArray(new Double[0]);
            if (a[1] > a[0] && a[1] > a[2]) {
                if (peaks.isEmpty() || ts - peaks.get(peaks.size() - 1) > 600) {
                    peaks.add(ts);
                    listener.onPeak(ts);
                }
            }
        }

        // BPM co próbkę na podstawie ostatnich 10 s okna
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

    private int computeBpm(long nowTs) {
        // usuń piki starsze niż 10 s
        while (peaks.size() > 1 && nowTs - peaks.get(0) > 10_000) peaks.remove(0);
        if (peaks.size() < 2) return 0;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < peaks.size(); i++) intervals.add(peaks.get(i) - peaks.get(i - 1));
        if (intervals.isEmpty()) return 0;

        intervals.sort(Long::compare);
        long median;
        int n = intervals.size();
        median = (n % 2 == 0) ? (intervals.get(n/2 - 1) + intervals.get(n/2)) / 2 : intervals.get(n/2);

        int bpm = (int) (60000.0 / median);
        if (bpm < 45 || bpm > 180) return 0;
        return bpm;
    }
}
