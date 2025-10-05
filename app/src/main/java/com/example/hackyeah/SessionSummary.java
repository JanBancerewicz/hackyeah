package com.example.hackyeah;

import androidx.annotation.Nullable;

import org.json.JSONObject;

public class SessionSummary {
    public long startTs, endTs;
    public int durationSec;

    // 5 kluczowych metryk
    public int meanHr;         // bpm
    public double rmssdMs;     // ms
    public double sd1Ms;       // ms
    public double pnn20;       // 0..1

    public double sdnnMs;

    @Nullable
    public Integer stressScore;   // null until LLM finishes
    @Nullable public String  llmReportMd;   // raw markdown from LLM


    public String note = "";

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("startTs", startTs);
            o.put("endTs", endTs);
            o.put("durationSec", durationSec);
            o.put("meanHr", meanHr);
            o.put("rmssdMs", rmssdMs);
            o.put("sd1Ms", sd1Ms);
            o.put("pnn20", pnn20);
            o.put("note", note == null ? "" : note);
        } catch (Exception ignored) {}
        return o;
    }

    public static SessionSummary fromJson(JSONObject o) {
        SessionSummary s = new SessionSummary();
        if (o == null) return s;
        s.startTs   = o.optLong("startTs", 0L);
        s.endTs     = o.optLong("endTs", 0L);
        s.durationSec = o.optInt("durationSec", 0);
        s.meanHr    = o.optInt("meanHr", 0);
        s.rmssdMs   = o.optDouble("rmssdMs", 0.0);
        s.sd1Ms     = o.optDouble("sd1Ms", 0.0);
        s.pnn20     = o.optDouble("pnn20", 0.0);
        s.note      = o.optString("note", "");
        return s;
    }
}