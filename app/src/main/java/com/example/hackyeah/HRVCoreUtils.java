package com.example.hackyeah;

import java.util.*;

class HRVCoreUtils {

    static List<Integer> rrFromPeaks(List<Long> peaks) {
        List<Integer> rr = new ArrayList<>();
        if (peaks == null) return rr;
        for (int i = 1; i < peaks.size(); i++) {
            rr.add((int)(peaks.get(i) - peaks.get(i - 1)));
        }
        return rr;
    }

    static List<Integer> filterRR(List<Integer> rr) {
        // prosta filtracja artefaktów: zakres 300..2000 ms
        List<Integer> out = new ArrayList<>();
        for (int v : rr) if (v >= 300 && v <= 2000) out.add(v);
        return out;
    }

    static HRVCoreResult compute(List<Integer> rr) {
        HRVCoreResult r = new HRVCoreResult();
        if (rr == null || rr.isEmpty()) return r;

        // mean RR / HR
        double sum = 0;
        int minRR = Integer.MAX_VALUE, maxRR = Integer.MIN_VALUE;
        for (int v : rr) { sum += v; if (v<minRR) minRR=v; if (v>maxRR) maxRR=v; }
        double meanRR = sum / rr.size();
        r.meanHr = meanRR > 0 ? (int)Math.round(60000.0 / meanRR) : 0;

        // ΔRR
        List<Double> diffs = new ArrayList<>();
        for (int i = 1; i < rr.size(); i++) diffs.add((double)(rr.get(i) - rr.get(i - 1)));

        // RMSSD, pNN20
        if (!diffs.isEmpty()) {
            double sumSq = 0; int over20 = 0;
            for (double d : diffs) { sumSq += d*d; if (Math.abs(d) > 20) over20++; }
            r.rmssdMs = Math.sqrt(sumSq / diffs.size());
            r.pnn20 = over20 / (double)diffs.size();
        } else {
            r.rmssdMs = 0; r.pnn20 = 0;
        }

        // SD1
        r.sd1Ms = r.rmssdMs / Math.sqrt(2.0);

        // Baevsky SI
        double mxDmn = (minRR == Integer.MAX_VALUE || maxRR == Integer.MIN_VALUE) ? 0 : (maxRR - minRR);
        Hist h = histogram(rr, 50); // bin 50 ms
        double Mo = h.modeCenterMs;           // modal RR [ms]
        double AMo = rr.isEmpty() ? 0 : (h.modeCount * 100.0 / rr.size()); // %
        r.baevskySI = (Mo > 0 && mxDmn > 0) ? (AMo / (2.0 * Mo * mxDmn)) : 0;

        return r;
    }

    // --- helpers ---
    private static class Hist { double modeCenterMs = 0; int modeCount = 0; }
    private static Hist histogram(List<Integer> rr, int binMs) {
        Hist res = new Hist();
        if (rr.isEmpty()) return res;
        int min = Collections.min(rr), max = Collections.max(rr);
        if (min == max) { res.modeCenterMs = min; res.modeCount = rr.size(); return res; }

        int bins = Math.max(1, ((max - min) / binMs) + 1);
        int[] cnt = new int[bins];
        for (int v : rr) {
            int idx = Math.min(bins - 1, Math.max(0, (v - min) / binMs));
            cnt[idx]++;
        }
        int bestIdx = 0, best = 0;
        for (int i = 0; i < bins; i++) if (cnt[i] > best) { best = cnt[i]; bestIdx = i; }
        res.modeCount = best;
        res.modeCenterMs = min + bestIdx * binMs + binMs / 2.0;
        return res;
    }
}
