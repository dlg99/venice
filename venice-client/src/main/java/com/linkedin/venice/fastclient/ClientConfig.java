package com.linkedin.venice.fastclient;

import com.linkedin.r2.transport.common.Client;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.AvroSpecificStoreClient;
import com.linkedin.venice.fastclient.stats.ClientStats;
import com.linkedin.venice.fastclient.stats.ClusterStats;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import io.tehuti.metrics.MetricsRepository;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;


public class ClientConfig<K, V, T extends SpecificRecord> {
  private final MetricsRepository metricsRepository;
  private final Client r2Client;
  private final String statsPrefix;
  private final boolean speculativeQueryEnabled;
  private final Class<T> specificValueClass;
  private final String storeName;
  private final Map<RequestType, ClientStats> clientStatsMap = new VeniceConcurrentHashMap<>();
  private final ClusterStats clusterStats;
  private final Executor deserializationExecutor;
  /**
   * For dual-read support.
   */
  private final boolean dualReadEnabled;
  private final AvroGenericStoreClient<K, V> genericThinClient;
  private final AvroSpecificStoreClient<K, T> specificThinClient;

  /**
   * For Client Routing.
   * Please check {@link com.linkedin.venice.fastclient.meta.InstanceHealthMonitor} to find more details.
   */
  private final long routingLeakedRequestCleanupThresholdMS;
  private final long routingQuotaExceededRequestCounterResetDelayMS;
  private final long routingErrorRequestCounterResetDelayMS;
  private final long routingUnavailableRequestCounterResetDelayMS;
  private final int routingPendingRequestCounterInstanceBlockThreshold;

  private ClientConfig(String storeName,
      Client r2Client,
      MetricsRepository metricsRepository,
      String statsPrefix,
      boolean speculativeQueryEnabled,
      Class<T> specificValueClass,
      Executor deserializationExecutor,
      boolean dualReadEnabled,
      AvroGenericStoreClient<K, V> genericThinClient,
      AvroSpecificStoreClient<K, T> specificThinClient,
      long routingLeakedRequestCleanupThresholdMS,
      long routingQuotaExceededRequestCounterResetDelayMS,
      long routingErrorRequestCounterResetDelayMS,
      long routingUnavailableRequestCounterResetDelayMS,
      int routingPendingRequestCounterInstanceBlockThreshold) {
    if (storeName == null || storeName.isEmpty()) {
      throw new VeniceClientException("storeName param shouldn't be empty");
    }
    if (r2Client == null) {
      throw new VeniceClientException("r2Client param shouldn't be null");
    }
    this.r2Client = r2Client;
    this.storeName = storeName;
    this.statsPrefix = (statsPrefix == null ? "" : statsPrefix);
    this.metricsRepository = (metricsRepository == null ? new MetricsRepository() : metricsRepository);
    for (RequestType requestType : RequestType.values()) {
      clientStatsMap.put(requestType, ClientStats.getClientStats(this.metricsRepository, this.statsPrefix, storeName, requestType));
    }
    this.clusterStats = new ClusterStats(metricsRepository, storeName);
    this.speculativeQueryEnabled = speculativeQueryEnabled;
    this.specificValueClass = specificValueClass;
    this.deserializationExecutor = deserializationExecutor;
    this.dualReadEnabled = dualReadEnabled;
    this.genericThinClient = genericThinClient;
    this.specificThinClient = specificThinClient;
    if (this.dualReadEnabled && this.specificThinClient == null && this.genericThinClient == null) {
      throw new VeniceClientException("Either param: specificThinClient or param: genericThinClient"
          + " should be specified when dual read is enabled");
    }

    this.routingLeakedRequestCleanupThresholdMS = routingLeakedRequestCleanupThresholdMS > 0 ?
        routingLeakedRequestCleanupThresholdMS : TimeUnit.SECONDS.toMillis(30); // 30 seconds by default
    this.routingQuotaExceededRequestCounterResetDelayMS = routingQuotaExceededRequestCounterResetDelayMS > 0 ?
        routingQuotaExceededRequestCounterResetDelayMS : 50; // 50 ms by default
    this.routingErrorRequestCounterResetDelayMS = routingErrorRequestCounterResetDelayMS > 0 ?
        routingErrorRequestCounterResetDelayMS : TimeUnit.SECONDS.toMillis(10); // 10 seconds
    this.routingUnavailableRequestCounterResetDelayMS = routingUnavailableRequestCounterResetDelayMS > 0 ?
        routingUnavailableRequestCounterResetDelayMS : TimeUnit.MINUTES.toMicros(1); // 1 min
    this.routingPendingRequestCounterInstanceBlockThreshold = routingPendingRequestCounterInstanceBlockThreshold > 0 ?
        routingPendingRequestCounterInstanceBlockThreshold : 50;
  }

  public String getStoreName() {
    return storeName;
  }

  public MetricsRepository getMetricsRepository() {
    return metricsRepository;
  }

  public Client getR2Client() {
    return r2Client;
  }

  public ClientStats getStats(RequestType requestType) {
    return clientStatsMap.get(requestType);
  }

  public ClusterStats getClusterStats() {
    return clusterStats;
  }

  public boolean isSpeculativeQueryEnabled() {
    return speculativeQueryEnabled;
  }

  public Class<T> getSpecificValueClass() {
    return specificValueClass;
  }

  public Executor getDeserializationExecutor() {
    return deserializationExecutor;
  }

  public boolean isDualReadEnabled() {
    return dualReadEnabled;
  }

  public AvroGenericStoreClient<K, V> getGenericThinClient() {
    return genericThinClient;
  }

  public AvroSpecificStoreClient<K, T> getSpecificThinClient() {
    return specificThinClient;
  }

  public long getRoutingLeakedRequestCleanupThresholdMS() {
    return routingLeakedRequestCleanupThresholdMS;
  }

  public long getRoutingQuotaExceededRequestCounterResetDelayMS() {
    return routingQuotaExceededRequestCounterResetDelayMS;
  }

  public long getRoutingErrorRequestCounterResetDelayMS() {
    return routingErrorRequestCounterResetDelayMS;
  }

  public long getRoutingUnavailableRequestCounterResetDelayMS() {
    return routingUnavailableRequestCounterResetDelayMS;
  }

  public int getRoutingPendingRequestCounterInstanceBlockThreshold() {
    return routingPendingRequestCounterInstanceBlockThreshold;
  }

  public static class ClientConfigBuilder<K, V, T extends SpecificRecord> {
    private MetricsRepository metricsRepository;
    private String statsPrefix = "";
    private boolean speculativeQueryEnabled = false;
    private Class<T> specificValueClass;
    private String storeName;
    private Executor deserializationExecutor;
    private Client r2Client;
    private boolean dualReadEnabled = false;
    private AvroGenericStoreClient<K, V> genericThinClient;
    private AvroSpecificStoreClient<K, T> specificThinClient;

    private long routingLeakedRequestCleanupThresholdMS = -1;
    private long routingQuotaExceededRequestCounterResetDelayMS = -1;
    private long routingErrorRequestCounterResetDelayMS = -1;
    private long routingUnavailableRequestCounterResetDelayMS = -1;
    private int routingPendingRequestCounterInstanceBlockThreshold = -1;

    public ClientConfigBuilder<K, V, T> setStoreName(String storeName) {
      this.storeName = storeName;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setMetricsRepository(MetricsRepository metricsRepository) {
      this.metricsRepository = metricsRepository;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setStatsPrefix(String statsPrefix) {
      this.statsPrefix = statsPrefix;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpeculativeQueryEnabled(boolean speculativeQueryEnabled) {
      this.speculativeQueryEnabled = speculativeQueryEnabled;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpecificValueClass(Class<T> specificValueClass) {
      this.specificValueClass = specificValueClass;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setDeserializationExecutor(Executor deserializationExecutor) {
      this.deserializationExecutor = deserializationExecutor;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setR2Client(Client r2Client) {
      this.r2Client = r2Client;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setDualReadEnabled(boolean dualReadEnabled) {
      this.dualReadEnabled = dualReadEnabled;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setGenericThinClient(AvroGenericStoreClient<K, V> genericThinClient) {
      this.genericThinClient = genericThinClient;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpecificThinClient(AvroSpecificStoreClient<K, T> specificThinClient) {
      this.specificThinClient = specificThinClient;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingLeakedRequestCleanupThresholdMS(long routingLeakedRequestCleanupThresholdMS) {
      this.routingLeakedRequestCleanupThresholdMS = routingLeakedRequestCleanupThresholdMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingQuotaExceededRequestCounterResetDelayMS(long routingQuotaExceededRequestCounterResetDelayMS) {
      this.routingQuotaExceededRequestCounterResetDelayMS = routingQuotaExceededRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingErrorRequestCounterResetDelayMS(long routingErrorRequestCounterResetDelayMS) {
      this.routingErrorRequestCounterResetDelayMS = routingErrorRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingUnavailableRequestCounterResetDelayMS(long routingUnavailableRequestCounterResetDelayMS) {
      this.routingUnavailableRequestCounterResetDelayMS = routingUnavailableRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingPendingRequestCounterInstanceBlockThreshold(
        int routingPendingRequestCounterInstanceBlockThreshold) {
      this.routingPendingRequestCounterInstanceBlockThreshold = routingPendingRequestCounterInstanceBlockThreshold;
      return this;
    }

    public ClientConfig<K, V, T> build() {
      return new ClientConfig<>(storeName,
          r2Client,
          metricsRepository,
          statsPrefix,
          speculativeQueryEnabled,
          specificValueClass,
          deserializationExecutor,
          dualReadEnabled,
          genericThinClient,
          specificThinClient,
          routingLeakedRequestCleanupThresholdMS,
          routingQuotaExceededRequestCounterResetDelayMS,
          routingErrorRequestCounterResetDelayMS,
          routingUnavailableRequestCounterResetDelayMS,
          routingPendingRequestCounterInstanceBlockThreshold);
    }
  }
}