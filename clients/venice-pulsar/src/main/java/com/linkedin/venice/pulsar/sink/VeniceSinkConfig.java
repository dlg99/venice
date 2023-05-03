/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.venice.pulsar.sink;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pulsar.io.common.IOConfigUtils;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.core.annotations.FieldDoc;


/**
 * Configuration class for the Sink Connector.
 */
public class VeniceSinkConfig implements Serializable {
  private static final Logger LOGGER = LogManager.getLogger(VeniceSinkConfig.class);

  private static final long serialVersionUID = 1L;

  @FieldDoc(defaultValue = "", help = "The url of the Venice controller")
  private String veniceDiscoveryUrl = "http://venice-controller:7777";

  @FieldDoc(defaultValue = "", help = "SASL configuration for Kafka. See Kafka client documentation for details.")
  private String kafkaSaslConfig = "";

  @FieldDoc(defaultValue = "", help = "SASL configuration for Kafka. See Kafka client documentation for details.")
  private String kafkaSaslMechanism = "PLAIN";

  @FieldDoc(defaultValue = "", help = "TLS/SASL configuration for Kafka. See Kafka client documentation for details.")
  private String kafkaSecurityProtocol = "PLAINTEXT";

  @FieldDoc(defaultValue = "", help = "The name of the Venice store")
  private String storeName = "test-store";

  @FieldDoc(defaultValue = "500", help = "Interval in milliseconds to flush data to Venice")
  private long flushIntervalMs = 500L;

  @FieldDoc(defaultValue = "10", help = "Max number of buffered records before flushing to Venice")
  private int maxNumberUnflushedRecords = 10;

  public static VeniceSinkConfig load(Map<String, Object> map, SinkContext sinkContext) throws IOException {
    LOGGER.info("Loading config {}", map);
    return IOConfigUtils.loadWithSecrets(map, VeniceSinkConfig.class, sinkContext);
  }

  // Generated by delombok
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig() {
  }

  @java.lang.SuppressWarnings("all")
  public String getVeniceDiscoveryUrl() {
    return this.veniceDiscoveryUrl;
  }

  @java.lang.SuppressWarnings("all")
  public String getKafkaSaslConfig() {
    return this.kafkaSaslConfig;
  }

  @java.lang.SuppressWarnings("all")
  public String getKafkaSaslMechanism() {
    return this.kafkaSaslMechanism;
  }

  @java.lang.SuppressWarnings("all")
  public String getKafkaSecurityProtocol() {
    return this.kafkaSecurityProtocol;
  }

  @java.lang.SuppressWarnings("all")
  public String getStoreName() {
    return this.storeName;
  }

  @java.lang.SuppressWarnings("all")
  public long getFlushIntervalMs() {
    return this.flushIntervalMs;
  }

  @java.lang.SuppressWarnings("all")
  public int getMaxNumberUnflushedRecords() {
    return this.maxNumberUnflushedRecords;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setVeniceDiscoveryUrl(final String veniceDiscoveryUrl) {
    this.veniceDiscoveryUrl = veniceDiscoveryUrl;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setKafkaSaslConfig(final String kafkaSaslConfig) {
    this.kafkaSaslConfig = kafkaSaslConfig;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setKafkaSaslMechanism(final String kafkaSaslMechanism) {
    this.kafkaSaslMechanism = kafkaSaslMechanism;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setKafkaSecurityProtocol(final String kafkaSecurityProtocol) {
    this.kafkaSecurityProtocol = kafkaSecurityProtocol;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setStoreName(final String storeName) {
    this.storeName = storeName;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setFlushIntervalMs(final long flushIntervalMs) {
    this.flushIntervalMs = flushIntervalMs;
    return this;
  }

  /**
   * @return {@code this}.
   */
  @java.lang.SuppressWarnings("all")
  public VeniceSinkConfig setMaxNumberUnflushedRecords(final int maxNumberUnflushedRecords) {
    this.maxNumberUnflushedRecords = maxNumberUnflushedRecords;
    return this;
  }

  @java.lang.Override
  @java.lang.SuppressWarnings("all")
  public boolean equals(final java.lang.Object o) {
    if (o == this)
      return true;
    if (!(o instanceof VeniceSinkConfig))
      return false;
    final VeniceSinkConfig other = (VeniceSinkConfig) o;
    if (!other.canEqual((java.lang.Object) this))
      return false;
    if (this.getFlushIntervalMs() != other.getFlushIntervalMs())
      return false;
    if (this.getMaxNumberUnflushedRecords() != other.getMaxNumberUnflushedRecords())
      return false;
    final java.lang.Object this$veniceDiscoveryUrl = this.getVeniceDiscoveryUrl();
    final java.lang.Object other$veniceDiscoveryUrl = other.getVeniceDiscoveryUrl();
    if (this$veniceDiscoveryUrl == null
        ? other$veniceDiscoveryUrl != null
        : !this$veniceDiscoveryUrl.equals(other$veniceDiscoveryUrl))
      return false;
    final java.lang.Object this$kafkaSaslConfig = this.getKafkaSaslConfig();
    final java.lang.Object other$kafkaSaslConfig = other.getKafkaSaslConfig();
    if (this$kafkaSaslConfig == null
        ? other$kafkaSaslConfig != null
        : !this$kafkaSaslConfig.equals(other$kafkaSaslConfig))
      return false;
    final java.lang.Object this$kafkaSaslMechanism = this.getKafkaSaslMechanism();
    final java.lang.Object other$kafkaSaslMechanism = other.getKafkaSaslMechanism();
    if (this$kafkaSaslMechanism == null
        ? other$kafkaSaslMechanism != null
        : !this$kafkaSaslMechanism.equals(other$kafkaSaslMechanism))
      return false;
    final java.lang.Object this$kafkaSecurityProtocol = this.getKafkaSecurityProtocol();
    final java.lang.Object other$kafkaSecurityProtocol = other.getKafkaSecurityProtocol();
    if (this$kafkaSecurityProtocol == null
        ? other$kafkaSecurityProtocol != null
        : !this$kafkaSecurityProtocol.equals(other$kafkaSecurityProtocol))
      return false;
    final java.lang.Object this$storeName = this.getStoreName();
    final java.lang.Object other$storeName = other.getStoreName();
    if (this$storeName == null ? other$storeName != null : !this$storeName.equals(other$storeName))
      return false;
    return true;
  }

  @java.lang.SuppressWarnings("all")
  protected boolean canEqual(final java.lang.Object other) {
    return other instanceof VeniceSinkConfig;
  }

  @java.lang.Override
  @java.lang.SuppressWarnings("all")
  public int hashCode() {
    final int PRIME = 59;
    int result = 1;
    final long $flushIntervalMs = this.getFlushIntervalMs();
    result = result * PRIME + (int) ($flushIntervalMs >>> 32 ^ $flushIntervalMs);
    result = result * PRIME + this.getMaxNumberUnflushedRecords();
    final java.lang.Object $veniceDiscoveryUrl = this.getVeniceDiscoveryUrl();
    result = result * PRIME + ($veniceDiscoveryUrl == null ? 43 : $veniceDiscoveryUrl.hashCode());
    final java.lang.Object $kafkaSaslConfig = this.getKafkaSaslConfig();
    result = result * PRIME + ($kafkaSaslConfig == null ? 43 : $kafkaSaslConfig.hashCode());
    final java.lang.Object $kafkaSaslMechanism = this.getKafkaSaslMechanism();
    result = result * PRIME + ($kafkaSaslMechanism == null ? 43 : $kafkaSaslMechanism.hashCode());
    final java.lang.Object $kafkaSecurityProtocol = this.getKafkaSecurityProtocol();
    result = result * PRIME + ($kafkaSecurityProtocol == null ? 43 : $kafkaSecurityProtocol.hashCode());
    final java.lang.Object $storeName = this.getStoreName();
    result = result * PRIME + ($storeName == null ? 43 : $storeName.hashCode());
    return result;
  }

  @java.lang.Override
  @java.lang.SuppressWarnings("all")
  public java.lang.String toString() {
    return "VeniceSinkConfig(veniceDiscoveryUrl=" + this.getVeniceDiscoveryUrl() + ", kafkaSaslConfig="
        + this.getKafkaSaslConfig() + ", kafkaSaslMechanism=" + this.getKafkaSaslMechanism()
        + ", kafkaSecurityProtocol=" + this.getKafkaSecurityProtocol() + ", storeName=" + this.getStoreName()
        + ", flushIntervalMs=" + this.getFlushIntervalMs() + ", maxNumberUnflushedRecords="
        + this.getMaxNumberUnflushedRecords() + ")";
  }

}
