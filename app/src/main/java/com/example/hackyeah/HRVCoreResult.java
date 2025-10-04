package com.example.hackyeah;

class HRVCoreResult {
    int meanHr;        // bpm
    double rmssdMs;    // ms
    double sd1Ms;      // ms (RMSSD/√2)
    double pnn20;      // 0..1
    double baevskySI;  // ~stress index
}
