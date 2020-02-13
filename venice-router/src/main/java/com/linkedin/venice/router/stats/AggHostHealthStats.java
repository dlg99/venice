package com.linkedin.venice.router.stats;

import com.linkedin.venice.stats.AbstractVeniceAggStats;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import io.tehuti.metrics.MetricsRepository;
import java.util.Map;


public class AggHostHealthStats extends AbstractVeniceAggStats<HostHealthStats> {
  private final Map<String, HostHealthStats> hostHealthStatsMap = new VeniceConcurrentHashMap<>();


  public AggHostHealthStats(MetricsRepository metricsRepository) {
    super(metricsRepository, (repo, hostName) -> new HostHealthStats(repo, StatsUtils.convertHostnameToMetricName(hostName)));
  }

  private HostHealthStats getHostStats(String hostName) {
    return getStoreStats(hostName);
  }

  public void recordUnhealthyHostOfflineInstance(String hostName) {
    totalStats.recordUnhealthyHostOfflineInstance();
    getHostStats(hostName).recordUnhealthyHostOfflineInstance();
  }

  public void recordUnhealthyHostSlowPartition(String hostName) {
    totalStats.recordUnhealthyHostSlowPartition();
    getHostStats(hostName).recordUnhealthyHostSlowPartition();
  }

  public void recordUnhealthyHostTooManyPendingRequest(String hostName) {
    totalStats.recordUnhealthyHostTooManyPendingRequest();
    getHostStats(hostName).recordUnhealthyHostTooManyPendingRequest();
  }

  public void recordUnhealthyHostHeartBeatFailure(String hostName) {
    totalStats.recordUnhealthyHostHeartBeatFailure();
    getHostStats(hostName).recordUnhealthyHostHeartBeatFailure();
  }

  public void recordPendingRequestCount(String hostName, long cnt) {
    totalStats.recordPendingRequestCount(cnt);
    getHostStats(hostName).recordPendingRequestCount(cnt);
  }
}