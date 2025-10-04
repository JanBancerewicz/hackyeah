package com.example.hackyeah;

import android.graphics.Color;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.*;

import java.util.ArrayList;

class ChartController {
    private final LineChart chart;
    private final LineDataSet dataSet;
    private final LineData data;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private float t = 0f;

    ChartController(LineChart chart) {
        this.chart = chart;
        dataSet = new LineDataSet(entries, "Heart Rate Signal");
        dataSet.setColor(Color.RED);
        dataSet.setDrawCircles(false);
        dataSet.setLineWidth(2f);

        data = new LineData(dataSet);
        this.chart.setData(data);
        this.chart.getDescription().setEnabled(false);
        this.chart.getAxisRight().setEnabled(false);
        this.chart.getXAxis().setDrawLabels(false);
        this.chart.getLegend().setEnabled(false);
    }

    void add(float v) {
        t += 0.1f;
        if (entries.size() > 100) entries.remove(0);
        entries.add(new Entry(t, v));
        dataSet.notifyDataSetChanged();
        data.notifyDataChanged();
        chart.notifyDataSetChanged();
        chart.invalidate();
    }
}
