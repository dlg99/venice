package com.linkedin.venice.tehuti;
import io.tehuti.TehutiException;
import io.tehuti.metrics.MetricsReporter;
import io.tehuti.metrics.TehutiMetric;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockTehutiReporter implements MetricsReporter{
    private Map<String, TehutiMetric> metrics;

    public MockTehutiReporter() {
        metrics = new HashMap<>();
    }
    @Override
    public void init(List<TehutiMetric> metrics) {}

    @Override
    public void metricChange(TehutiMetric metric) {
        metrics.put(metric.name(), metric);
    }

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> config) {}

    public TehutiMetric query(String name) {
        if (metrics.containsKey(name)) {
            return metrics.get(name);
        } else {
            throw new TehutiException("metrics: " + name + " does not exist.");
        }
    }
}
