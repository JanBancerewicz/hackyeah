package com.example.hackyeah;

import java.util.*;

import com.example.hackyeah.HRVCoreResult;

class HRVCoreUtils {

    /** RR z listy pików (ms). */
    static List<Integer> rrFromPeaks(List<Long> peaks) {
        List<Integer> rr = new ArrayList<>();
        if (peaks == null) return rr;
        for (int i = 1; i < peaks.size(); i++) {
            rr.add((int)(peaks.get(i) - peaks.get(i - 1)));
        }
        return rr;
    }

    /** RR z listy pików, ignorując pary przed minTs (warm-up). */
    static List<Integer> rrFromPeaks(List<Long> peaks, long minTs) {
        List<Integer> rr = new ArrayList<>();
        if (peaks == null) return rr;
        for (int i = 1; i < peaks.size(); i++) {
            long t0 = peaks.get(i - 1), t1 = peaks.get(i);
            if (t0 < minTs || t1 < minTs) continue;              // pomiń pierwsze 5 s
            rr.add((int)(t1 - t0));
        }
        return rr;
    }

    /** Oczyszczanie NN: zakres i odcięcie względem mediany (±120 ms) + bezpieczne fallbacki. */
    static List<Integer> filterRR(List<Integer> rrIn) {
        if (rrIn == null || rrIn.size() < 2) return rrIn == null ? Collections.emptyList() : rrIn;

        // mediana
        double med;
        {
            List<Integer> s = new ArrayList<>(rrIn);
            Collections.sort(s);
            int n = s.size();
            med = (n % 2 == 1) ? s.get(n/2) : (s.get(n/2 - 1) + s.get(n/2)) / 2.0;
        }

        // 300–2000 ms i |RR - med| <= 120 ms
        List<Integer> out = new ArrayList<>();
        for (int r : rrIn) {
            if (r >= 300 && r <= 2000 && Math.abs(r - med) <= 120) out.add(r);
        }

        // fallbacky
        if (out.size() < 2) {
            out.clear();
            for (int r : rrIn) if (r >= 300 && r <= 2000) out.add(r);
            if (out.size() < 2) return rrIn; // ostatecznie zwróć oryginał
        }
        return out;
    }

    /** Metryki HRV z oczyszczonych RR. */
    static HRVCoreResult compute(List<Integer> rr) {
        HRVCoreResult r = new HRVCoreResult();
        if (rr == null || rr.size() < 2) return r;

        // HR z meanRR
        double meanRR = rr.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        r.meanHr = meanRR > 0 ? (int)Math.round(60000.0 / meanRR) : 0;

        // RMSSD
        double sumSq = 0.0; int pairs = 0;
        for (int i = 1; i < rr.size(); i++) { double d = rr.get(i) - rr.get(i-1); sumSq += d*d; pairs++; }
        r.rmssdMs = pairs > 0 ? Math.sqrt(sumSq / pairs) : 0.0;

        // SDNN = odchylenie standardowe RR
        double mean = rr.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double var = 0.0;
        for (int g : rr) { double d = g - mean; var += d*d; }
        var = rr.size() > 1 ? var / (rr.size() - 1) : 0.0;
        r.sdnnMs = Math.sqrt(var);


        // SD1
        r.sd1Ms = r.rmssdMs / Math.sqrt(2.0);

        // pNN20
        int nn20 = 0;
        for (int i = 1; i < rr.size(); i++) if (Math.abs(rr.get(i) - rr.get(i-1)) > 20) nn20++;
        r.pnn20 = pairs > 0 ? (double)nn20 / pairs : 0.0;


        return r;
    }
}
