package com.linkedin.venice.controller;

import com.linkedin.venice.SSLConfig;
import com.linkedin.venice.common.VeniceSystemStoreUtils;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controller.kafka.AdminTopicUtils;
import com.linkedin.venice.controller.kafka.consumer.AdminConsumerService;
import com.linkedin.venice.controller.kafka.consumer.AdminConsumptionTask;
import com.linkedin.venice.controller.kafka.consumer.VeniceControllerConsumerFactory;
import com.linkedin.venice.controller.kafka.protocol.admin.AbortMigration;
import com.linkedin.venice.controller.kafka.protocol.admin.AddVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.AdminOperation;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteAllVersions;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteOldVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteStore;
import com.linkedin.venice.controller.kafka.protocol.admin.DerivedSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.DisableStoreRead;
import com.linkedin.venice.controller.kafka.protocol.admin.ETLStoreConfigRecord;
import com.linkedin.venice.controller.kafka.protocol.admin.EnableStoreRead;
import com.linkedin.venice.controller.kafka.protocol.admin.HybridStoreConfigRecord;
import com.linkedin.venice.controller.kafka.protocol.admin.KillOfflinePushJob;
import com.linkedin.venice.controller.kafka.protocol.admin.MigrateStore;
import com.linkedin.venice.controller.kafka.protocol.admin.PartitionerConfigRecord;
import com.linkedin.venice.controller.kafka.protocol.admin.PauseStore;
import com.linkedin.venice.controller.kafka.protocol.admin.ResumeStore;
import com.linkedin.venice.controller.kafka.protocol.admin.SchemaMeta;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStoreCurrentVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStoreOwner;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStorePartitionCount;
import com.linkedin.venice.controller.kafka.protocol.admin.StoreCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.SupersetSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.UpdateStore;
import com.linkedin.venice.controller.kafka.protocol.admin.ValueSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.enums.AdminMessageType;
import com.linkedin.venice.controller.kafka.protocol.enums.SchemaType;
import com.linkedin.venice.controller.kafka.protocol.serializer.AdminOperationSerializer;
import com.linkedin.venice.controller.migration.MigrationPushStrategyZKAccessor;
import com.linkedin.venice.controller.stats.ZkAdminTopicMetadataAccessor;
import com.linkedin.venice.controllerapi.AdminCommandExecution;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.D2ControllerClient;
import com.linkedin.venice.controllerapi.JobStatusQueryResponse;
import com.linkedin.venice.controllerapi.MultiSchemaResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceHttpException;
import com.linkedin.venice.exceptions.VeniceNoStoreException;
import com.linkedin.venice.exceptions.VeniceUnsupportedOperationException;
import com.linkedin.venice.helix.HelixReadWriteStoreRepository;
import com.linkedin.venice.helix.ParentHelixOfflinePushAccessor;
import com.linkedin.venice.helix.Replica;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.meta.BackupStrategy;
import com.linkedin.venice.meta.ETLStoreConfig;
import com.linkedin.venice.meta.HybridStoreConfig;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.RoutersClusterConfig;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreConfig;
import com.linkedin.venice.meta.StoreInfo;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.participant.protocol.ParticipantMessageKey;
import com.linkedin.venice.participant.protocol.ParticipantMessageValue;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.schema.DerivedSchemaEntry;
import com.linkedin.venice.schema.SchemaData;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.avro.DirectionalSchemaCompatibilityType;
import com.linkedin.venice.security.SSLFactory;
import com.linkedin.venice.status.protocol.PushJobDetails;
import com.linkedin.venice.status.protocol.PushJobStatusRecordKey;
import com.linkedin.venice.status.protocol.PushJobStatusRecordValue;
import com.linkedin.venice.store.rocksdb.RocksDBUtils;
import com.linkedin.venice.utils.AvroSchemaUtils;
import com.linkedin.venice.utils.Pair;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.SystemTime;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.http.HttpStatus;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.log4j.Logger;


/**
 * This class is a wrapper of {@link VeniceHelixAdmin}, which will be used in parent controller.
 * There should be only one single Parent Controller, which is the endpoint for all the admin data
 * update.
 * For every admin update operation, it will first push admin operation messages to Kafka,
 * then wait for the admin consumer to consume the message.
 */
public class VeniceParentHelixAdmin implements Admin {
  // Latest value schema id for push job status
  public static final int LATEST_PUSH_JOB_STATUS_VALUE_SCHEMA_ID = 2;

  private static final long SLEEP_INTERVAL_FOR_DATA_CONSUMPTION_IN_MS = 1000;
  private static final long SLEEP_INTERVAL_FOR_ASYNC_SETUP_MS = 3000;
  private static final int MAX_ASYNC_SETUP_RETRY_COUNT = 10;
  private static final Logger logger = Logger.getLogger(VeniceParentHelixAdmin.class);
  private static final String VENICE_INTERNAL_STORE_OWNER = "venice-internal";
  private static final String PUSH_JOB_STATUS_STORE_DESCRIPTOR = "push job status store: ";
  private static final String PUSH_JOB_DETAILS_STORE_DESCRIPTOR = "push job details store: ";
  private static final String PARTICIPANT_MESSAGE_STORE_DESCRIPTOR = "participant message store: ";
  //Store version number to retain in Parent Controller to limit 'Store' ZNode size.
  protected static final int STORE_VERSION_RETENTION_COUNT = 5;

  protected final Map<String, Boolean> asyncSetupEnabledMap;
  private final VeniceHelixAdmin veniceHelixAdmin;
  private final Map<String, VeniceWriter<byte[], byte[], byte[]>> veniceWriterMap;
  private final AdminTopicMetadataAccessor adminTopicMetadataAccessor;
  private final byte[] emptyKeyByteArr = new byte[0];
  private final AdminOperationSerializer adminOperationSerializer = new AdminOperationSerializer();
  private final VeniceControllerMultiClusterConfig multiClusterConfigs;
  private final Map<String, Lock> perClusterAdminLocks = new ConcurrentHashMap<>();
  private final Map<String, AdminCommandExecutionTracker> adminCommandExecutionTrackers;
  private final Set<String> executionIdValidatedClusters = new HashSet<>();
  // Only used for setup work which are intended to be short lived and is bounded by the number of venice clusters.
  // Based on JavaDoc "Threads that have not been used for sixty seconds are terminated and removed from the cache."
  private final ExecutorService asyncSetupExecutor = Executors.newCachedThreadPool();
  private Time timer = new SystemTime();
  private Optional<SSLFactory> sslFactory = Optional.empty();

  private final MigrationPushStrategyZKAccessor pushStrategyZKAccessor;

  private ParentHelixOfflinePushAccessor offlinePushAccessor;

  /**
   * Here is the way how Parent Controller is keeping errored topics when {@link #maxErroredTopicNumToKeep} > 0:
   * 1. For errored topics, {@link #getOfflineJobProgress(String, String, Map)} won't truncate them;
   * 2. For errored topics, {@link #killOfflinePush(String, String)} won't truncate them;
   * 3. {@link #getTopicForCurrentPushJob(String, String, boolean)} will truncate the errored topics based on
   * {@link #maxErroredTopicNumToKeep};
   *
   * It means error topic retiring is only be triggered by next push.
   *
   * When {@link #maxErroredTopicNumToKeep} is 0, errored topics will be truncated right away when job is finished.
   */
  private int maxErroredTopicNumToKeep;

  private final int waitingTimeForConsumptionMs;

  public VeniceParentHelixAdmin(VeniceHelixAdmin veniceHelixAdmin, VeniceControllerMultiClusterConfig multiClusterConfigs) {
    this(veniceHelixAdmin, multiClusterConfigs, false, Optional.empty());
  }

  public VeniceParentHelixAdmin(VeniceHelixAdmin veniceHelixAdmin, VeniceControllerMultiClusterConfig multiClusterConfigs,
      boolean sslEnabled, Optional<SSLConfig> sslConfig) {
    this.veniceHelixAdmin = veniceHelixAdmin;
    this.multiClusterConfigs = multiClusterConfigs;
    this.waitingTimeForConsumptionMs = multiClusterConfigs.getParentControllerWaitingTimeForConsumptionMs();
    this.veniceWriterMap = new ConcurrentHashMap<>();
    this.adminTopicMetadataAccessor = new ZkAdminTopicMetadataAccessor(this.veniceHelixAdmin.getZkClient(),
        this.veniceHelixAdmin.getAdapterSerializer());
    this.adminCommandExecutionTrackers = new HashMap<>();
    this.asyncSetupEnabledMap = new VeniceConcurrentHashMap<>();
    if (sslEnabled) {
      try {
        String sslFactoryClassName = multiClusterConfigs.getSslFactoryClassName();
        Properties sslProperties = sslConfig.get().getSslProperties();
        sslFactory = Optional.of(SslUtils.getSSLFactory(sslProperties, sslFactoryClassName));
      } catch (Exception e) {
        logger.error("Failed to create SSL engine", e);
        throw new VeniceException(e);
      }
    }
    for (String cluster : multiClusterConfigs.getClusters()) {
      VeniceControllerConfig config = multiClusterConfigs.getConfigForCluster(cluster);
      adminCommandExecutionTrackers.put(cluster,
          new AdminCommandExecutionTracker(config.getClusterName(), veniceHelixAdmin.getExecutionIdAccessor(),
              getControllerClientMap(config.getClusterName())));
      perClusterAdminLocks.put(cluster, new ReentrantLock());
    }
    this.pushStrategyZKAccessor = new MigrationPushStrategyZKAccessor(veniceHelixAdmin.getZkClient(),
        veniceHelixAdmin.getAdapterSerializer());
    this.maxErroredTopicNumToKeep = multiClusterConfigs.getParentControllerMaxErroredTopicNumToKeep();
    this.offlinePushAccessor =
        new ParentHelixOfflinePushAccessor(veniceHelixAdmin.getZkClient(), veniceHelixAdmin.getAdapterSerializer());
    // Start store migration monitor background thread
    startStoreMigrationMonitor();
  }

  // For testing purpose
  protected void setMaxErroredTopicNumToKeep(int maxErroredTopicNumToKeep) {
    this.maxErroredTopicNumToKeep = maxErroredTopicNumToKeep;
  }

  public void setVeniceWriterForCluster(String clusterName, VeniceWriter writer) {
    veniceWriterMap.putIfAbsent(clusterName, writer);
  }

  @Override
  public synchronized void start(String clusterName) {
    veniceHelixAdmin.start(clusterName);
    asyncSetupEnabledMap.put(clusterName, true);
    // We might not be able to call a lot of functions of veniceHelixAdmin since
    // current controller might not be the master controller for the given clusterName
    // Even current controller is master controller, it will take some time to become 'master'
    // since VeniceHelixAdmin.start won't wait for state becomes 'Master', but a lot of
    // VeniceHelixAdmin functions have 'mastership' check.

    // Check whether the admin topic exists or not
    String topicName = AdminTopicUtils.getTopicNameFromClusterName(clusterName);
    TopicManager topicManager = getTopicManager();
    if (topicManager.containsTopic(topicName)) {
      logger.info("Admin topic: " + topicName + " for cluster: " + clusterName + " already exists.");
    } else {
      // Create Kafka topic
      topicManager.createTopic(topicName, AdminTopicUtils.PARTITION_NUM_FOR_ADMIN_TOPIC, multiClusterConfigs.getKafkaReplicaFactor());
      logger.info("Created admin topic: " + topicName + " for cluster: " + clusterName);
    }

    // Initialize producer
    veniceWriterMap.computeIfAbsent(clusterName, (key) -> {
      /**
       * Venice just needs to check seq id in {@link com.linkedin.venice.controller.kafka.consumer.AdminConsumptionTask} to catch the following scenarios:
       * 1. Data missing;
       * 2. Data out of order;
       * 3. Data duplication;
       */
      return getVeniceWriterFactory().createBasicVeniceWriter(topicName, getTimer());
    });

    if (!multiClusterConfigs.getPushJobStatusStoreClusterName().isEmpty()
        && clusterName.equals(multiClusterConfigs.getPushJobStatusStoreClusterName())) {
      if (!multiClusterConfigs.getPushJobStatusStoreName().isEmpty()) {
        String storeName = multiClusterConfigs.getPushJobStatusStoreName();
        asyncSetupForInternalRTStore(multiClusterConfigs.getPushJobStatusStoreClusterName(),
            storeName, PUSH_JOB_STATUS_STORE_DESCRIPTOR + storeName,
            PushJobStatusRecordKey.SCHEMA$.toString(), PushJobStatusRecordValue.SCHEMA$.toString(),
            multiClusterConfigs.getConfigForCluster(clusterName).getNumberOfPartition());
      }

      asyncSetupForInternalRTStore(
          multiClusterConfigs.getPushJobStatusStoreClusterName(),
          VeniceSystemStoreUtils.getPushJobDetailsStoreName(),
          PUSH_JOB_DETAILS_STORE_DESCRIPTOR + VeniceSystemStoreUtils.getPushJobDetailsStoreName(),
          PushJobStatusRecordKey.SCHEMA$.toString(), PushJobDetails.SCHEMA$.toString(),
          multiClusterConfigs.getConfigForCluster(clusterName).getNumberOfPartition());
    }

    if (multiClusterConfigs.getConfigForCluster(clusterName).isParticipantMessageStoreEnabled()) {
      String storeName = VeniceSystemStoreUtils.getParticipantStoreNameForCluster(clusterName);
      asyncSetupForInternalRTStore(clusterName, storeName, PARTICIPANT_MESSAGE_STORE_DESCRIPTOR + storeName,
          ParticipantMessageKey.SCHEMA$.toString(), ParticipantMessageValue.SCHEMA$.toString(),
          multiClusterConfigs.getConfigForCluster(clusterName).getNumberOfPartition());
    }
  }

  /**
   * Setup the venice RT store used internally for hosting push job status records or participant messages.
   * If the store already exists and is in the correct state then only verification is performed.
   * TODO replace this with {@link com.linkedin.venice.controller.init.ClusterLeaderInitializationRoutine}
   */
  private void asyncSetupForInternalRTStore(String clusterName, String storeName, String storeDescriptor,
      String keySchema, String valueSchema, int partitionCount) {
    asyncSetupExecutor.submit(() -> {
      int retryCount = 0;
      boolean isStoreReady = false;
      while (!isStoreReady && asyncSetupEnabledMap.get(clusterName) && retryCount < MAX_ASYNC_SETUP_RETRY_COUNT) {
        try {
          if (retryCount > 0) {
            timer.sleep(SLEEP_INTERVAL_FOR_ASYNC_SETUP_MS);
          }
          isStoreReady = verifyAndCreateInternalStore(clusterName, storeName, storeDescriptor, keySchema, valueSchema,
              partitionCount);
        } catch (VeniceException e) {
          // Verification attempts (i.e. a controller running this routine but is not the master of the cluster) do not
          // count towards the retry count.
          retryCount++;
          logger.info("VeniceException occurred during " + storeDescriptor + " setup with store " + storeName
              + " in cluster " + clusterName, e);
          logger.info("Async setup for " + storeDescriptor + " attempts: " + retryCount + "/" + MAX_ASYNC_SETUP_RETRY_COUNT);
        } catch (Exception e) {
          logger.warn(
              "Exception occurred aborting " + storeDescriptor + " setup with store " + storeName + " in cluster " + clusterName, e);
          break;
        }
      }
      if (isStoreReady) {
        logger.info(storeDescriptor + " has been successfully created or it already exists");
      } else {
        logger.error("Unable to create or verify the " + storeDescriptor);
      }
    });
  }

  /**
   * Verify the state of the system store. The master controller will also create and configure the store if the
   * desired state is not met.
   * @param clusterName the name of the cluster that push status store belongs to.
   * @param storeName the name of the push status store.
   * @return {@code true} if the push status store is ready, {@code false} otherwise.
   */
  private boolean verifyAndCreateInternalStore(String clusterName, String storeName, String storeDescriptor,
      String keySchema, String valueSchema, int partitionCount) {
    boolean storeReady = false;
    UpdateStoreQueryParams updateStoreQueryParams;
    if (isMasterController(clusterName)) {
      // We should only perform the store validation if the current controller is the master controller of the requested cluster.
      Store store = getStore(clusterName, storeName);
      if (store == null) {
        addStore(clusterName, storeName, VENICE_INTERNAL_STORE_OWNER, keySchema, valueSchema);
        store = getStore(clusterName, storeName);
        if (store == null) {
          throw new VeniceException("Unable to create or fetch the " + storeDescriptor);
        }
      }

      if (!store.isHybrid()) {
        updateStoreQueryParams = new UpdateStoreQueryParams();
        updateStoreQueryParams.setHybridOffsetLagThreshold(100L);
        updateStoreQueryParams.setHybridRewindSeconds(TimeUnit.DAYS.toSeconds(7));
        updateStore(clusterName, storeName, updateStoreQueryParams);
        store = getStore(clusterName, storeName);
        if (!store.isHybrid()) {
          throw new VeniceException("Unable to update the " + storeDescriptor + " to a hybrid store");
        }
      }
      if (store.getVersions().isEmpty()) {
        int replicationFactor = getReplicationFactor(clusterName, storeName);
        Version version =
            incrementVersionIdempotent(clusterName, storeName, Version.guidBasedDummyPushId(), partitionCount, replicationFactor);
        writeEndOfPush(clusterName, storeName, version.getNumber(), true);
        store = getStore(clusterName, storeName);
        if (store.getVersions().isEmpty()) {
          throw new VeniceException("Unable to initialize a version for the " + storeDescriptor);
        }
      }
      String pushJobStatusRtTopic = getRealTimeTopic(clusterName, storeName);
      if (!pushJobStatusRtTopic.equals(Version.composeRealTimeTopic(storeName))) {
        throw new VeniceException("Unexpected real time topic name for the " + storeDescriptor);
      }
      storeReady = true;
    } else {
      // Verify that the store is indeed created by another controller. This is to prevent if the initial master fails
      // or when the cluster happens to be leaderless for a bit.
      try (ControllerClient controllerClient =
          new ControllerClient(clusterName, getMasterController(clusterName).getUrl(false), sslFactory)) {
        StoreResponse storeResponse = controllerClient.getStore(storeName);
        if (storeResponse.isError()) {
          logger.info("Failed to verify " + storeDescriptor + " from the controller with URL: " +
              controllerClient.getControllerDiscoveryUrls());
          return false;
        }
        StoreInfo storeInfo = storeResponse.getStore();

        if (storeInfo.getHybridStoreConfig() != null
            && !storeInfo.getVersions().isEmpty()
            && storeInfo.getVersions().get(storeInfo.getLargestUsedVersionNumber()).getPartitionCount() == partitionCount
            && getTopicManager().containsTopic(Version.composeRealTimeTopic(storeName))) {
          storeReady = true;
        }
      }
    }
    return storeReady;
  }

  @Override
  public boolean isClusterValid(String clusterName) {
    return veniceHelixAdmin.isClusterValid(clusterName);
  }

  private void sendAdminMessageAndWaitForConsumed(String clusterName, String storeName, AdminOperation message) {
    if (!veniceWriterMap.containsKey(clusterName)) {
      throw new VeniceException("Cluster: " + clusterName + " is not started yet!");
    }
    if (!executionIdValidatedClusters.contains(clusterName)) {
      ExecutionIdAccessor executionIdAccessor = veniceHelixAdmin.getExecutionIdAccessor();
      long lastGeneratedExecutionId = executionIdAccessor.getLastGeneratedExecutionId(clusterName);
      long lastConsumedExecutionId =
          AdminTopicMetadataAccessor.getExecutionId(adminTopicMetadataAccessor.getMetadata(clusterName));
      if (lastGeneratedExecutionId < lastConsumedExecutionId) {
        // Invalid state, resetting the last generated execution id to last consumed execution id.
        logger.warn("Invalid executionId state detected, last generated execution id: " + lastGeneratedExecutionId
            + ", last consumed execution id: " + lastConsumedExecutionId
            + ". Resetting last generated execution id to: " + lastConsumedExecutionId);
        executionIdAccessor.updateLastGeneratedExecutionId(clusterName, lastConsumedExecutionId);
      }
      executionIdValidatedClusters.add(clusterName);
    }
    AdminCommandExecutionTracker adminCommandExecutionTracker = adminCommandExecutionTrackers.get(clusterName);
    AdminCommandExecution execution =
        adminCommandExecutionTracker.createExecution(AdminMessageType.valueOf(message).name());
    message.executionId = execution.getExecutionId();
    VeniceWriter<byte[], byte[], byte[]> veniceWriter = veniceWriterMap.get(clusterName);
    byte[] serializedValue = adminOperationSerializer.serialize(message);
    try {
      Future<RecordMetadata> future = veniceWriter.put(emptyKeyByteArr, serializedValue, AdminOperationSerializer.LATEST_SCHEMA_ID_FOR_ADMIN_OPERATION);
      RecordMetadata meta = future.get();

      logger.info("Sent message: " + message + " to kafka, offset: " + meta.offset());
      waitingMessageToBeConsumed(clusterName, storeName, message.executionId);
      adminCommandExecutionTracker.startTrackingExecution(execution);
    } catch (Exception e) {
      throw new VeniceException("Got exception during sending message to Kafka -- " + e.getMessage(), e);
    }
  }

  private void waitingMessageToBeConsumed(String clusterName, String storeName, long executionId) {
    // Blocking until consumer consumes the new message or timeout
    long startTime = SystemTime.INSTANCE.getMilliseconds();
    while (true) {
      Long consumedExecutionId = veniceHelixAdmin.getLastSucceededExecutionId(clusterName, storeName);
      if (consumedExecutionId != null && consumedExecutionId >= executionId) {
        break;
      }
      // Check whether timeout
      long currentTime = SystemTime.INSTANCE.getMilliseconds();
      if (currentTime - startTime > waitingTimeForConsumptionMs) {
        Exception lastException = veniceHelixAdmin.getLastExceptionForStore(clusterName, storeName);
        String exceptionMsg = null == lastException ? "null" : lastException.getMessage();
        String errMsg = "Timed out after waiting for " + waitingTimeForConsumptionMs + "ms for admin consumption to catch up.";
        errMsg += " Consumed execution id: " + consumedExecutionId + ", waiting to be consumed id: " + executionId;
        errMsg += " Last exception: " + exceptionMsg;
        throw new VeniceException(errMsg, lastException);
      }

      logger.info("Waiting execution id: " + executionId + " to be consumed, currently at " + consumedExecutionId);
      Utils.sleep(SLEEP_INTERVAL_FOR_DATA_CONSUMPTION_IN_MS);
    }
    logger.info("The message has been consumed, execution id: " + executionId);
  }

  private void acquireLock(String clusterName, String storeName) {
    try {
      // First check whether an exception already exist in the admin channel for the given store
      Exception lastException = veniceHelixAdmin.getLastExceptionForStore(clusterName, storeName);
      if (lastException != null) {
        throw new VeniceException("Unable to start new admin operations for store: " + storeName + " in cluster: "
            + clusterName + " due to existing exception: " + lastException.getMessage(), lastException);
      }
      boolean acquired = perClusterAdminLocks.get(clusterName).tryLock(waitingTimeForConsumptionMs, TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new VeniceException("Failed to acquire lock after waiting for " + waitingTimeForConsumptionMs
            + "ms. Another ongoing admin operation might be holding up the lock");
      }
    } catch (InterruptedException e) {
      throw new VeniceException("Got interrupted during acquiring lock", e);
    }
  }

  private void releaseLock(String clusterName) {
    perClusterAdminLocks.get(clusterName).unlock();
  }

  @Override
  public void addStore(String clusterName, String storeName, String owner, String keySchema, String valueSchema) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForAddStore(clusterName, storeName, keySchema, valueSchema);
      logger.info("Adding store: " + storeName + " to cluster: " + clusterName);

      // Write store creation message to Kafka
      StoreCreation storeCreation = (StoreCreation) AdminMessageType.STORE_CREATION.getNewInstance();
      storeCreation.clusterName = clusterName;
      storeCreation.storeName = storeName;
      storeCreation.owner = owner;
      storeCreation.keySchema = new SchemaMeta();
      storeCreation.keySchema.schemaType = SchemaType.AVRO_1_4.getValue();
      storeCreation.keySchema.definition = keySchema;
      storeCreation.valueSchema = new SchemaMeta();
      storeCreation.valueSchema.schemaType = SchemaType.AVRO_1_4.getValue();
      storeCreation.valueSchema.definition = valueSchema;

      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.STORE_CREATION.getValue();
      message.payloadUnion = storeCreation;
      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void deleteStore(String clusterName, String storeName, int largestUsedVersionNumber) {
    acquireLock(clusterName, storeName);
    try {
      Store store = veniceHelixAdmin.checkPreConditionForDeletion(clusterName, storeName);
      DeleteStore deleteStore = (DeleteStore) AdminMessageType.DELETE_STORE.getNewInstance();
      deleteStore.clusterName = clusterName;
      deleteStore.storeName = storeName;
      // Tell each prod colo the largest used version number in corp to make it consistent.
      deleteStore.largestUsedVersionNumber = store.getLargestUsedVersionNumber();
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.DELETE_STORE.getValue();
      message.payloadUnion = deleteStore;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void addVersionAndStartIngestion(String clusterName, String storeName, String pushJobId, int versionNumber,
      int numberOfPartitions, Version.PushType pushType) {
    throw new VeniceUnsupportedOperationException("addVersionAndStartIngestion");
  }

  /**
   * Since there is no offline push running in Parent Controller,
   * the old store versions won't be cleaned up by job completion action, so Parent Controller chooses
   * to clean it up when the new store version gets created.
   * It is OK to clean up the old store versions in Parent Controller without notifying Child Controller since
   * store version in Parent Controller doesn't maintain actual version status, and only for tracking
   * the store version creation history.
   */
  protected void cleanupHistoricalVersions(String clusterName, String storeName) {
    HelixReadWriteStoreRepository storeRepo = veniceHelixAdmin.getVeniceHelixResource(clusterName)
        .getMetadataRepository();
    storeRepo.lock();
    try {
      Store store = storeRepo.getStore(storeName);
      if (null == store) {
        logger.info("The store to clean up: " + storeName + " doesn't exist");
        return;
      }
      List<Version> versions = store.getVersions();
      final int versionCount = versions.size();
      if (versionCount <= STORE_VERSION_RETENTION_COUNT) {
        return;
      }
      List<Version> clonedVersions = new ArrayList<>(versions);
      clonedVersions.stream()
          .sorted()
          .limit(versionCount - STORE_VERSION_RETENTION_COUNT)
          .forEach(v -> store.deleteVersion(v.getNumber()));
      storeRepo.updateStore(store);
    } finally {
      storeRepo.unLock();
    }
  }

  /**
 * Check whether any topic for this store exists or not.
 * The existing topic could be introduced by two cases:
 * 1. The previous job push is still running;
 * 2. The previous job push fails to delete this topic;
 *
 * For the 1st case, it is expected to refuse the new data push,
 * and for the 2nd case, customer should reach out Venice team to fix this issue for now.
 **/
  protected List<String> existingTopicsForStore(String storeName) {
    List<String> outputList = new ArrayList<>();
    TopicManager topicManager = getTopicManager();
    Set<String> topics = topicManager.listTopics();
    String storeNameForCurrentTopic;
    for (String topic: topics) {
      if (AdminTopicUtils.isAdminTopic(topic) || AdminTopicUtils.isKafkaInternalTopic(topic) || Version.isRealTimeTopic(topic)) {
        continue;
      }
      try {
        storeNameForCurrentTopic = Version.parseStoreFromKafkaTopicName(topic);
      } catch (Exception e) {
        logger.warn("Failed to parse StoreName from topic: " + topic, e);
        continue;
      }
      if (storeNameForCurrentTopic.equals(storeName)) {
        outputList.add(topic);
      }
    }
    return outputList;
  }

  /**
   * Get the version topics list for the specified store in freshness order; the first
   * topic in the list is the latest topic and the last topic is the oldest one.
   * @param storeName
   * @return the version topics in freshness order
   */
  protected List<String> getKafkaTopicsByAge(String storeName) {
    List<String> existingTopics = existingTopicsForStore(storeName);
    if (!existingTopics.isEmpty()) {
      existingTopics.sort((t1, t2) -> {
        int v1 = Version.parseVersionFromKafkaTopicName(t1);
        int v2 = Version.parseVersionFromKafkaTopicName(t2);
        return v2 - v1;
      });
    }
    return existingTopics;
  }

  /**
   * If there is no ongoing push for specified store currently, this function will return {@link Optional#empty()},
   * else will return the ongoing Kafka topic. It will also try to clean up legacy topics.
   */
  protected Optional<String> getTopicForCurrentPushJob(String clusterName, String storeName, boolean isIncrementalPush) {
    // The first/last topic in the list is the latest/oldest version topic
    List<String> versionTopics = getKafkaTopicsByAge(storeName);
    Optional<String> latestKafkaTopic = Optional.empty();
    if (!versionTopics.isEmpty()) {
      latestKafkaTopic = Optional.of(versionTopics.get(0));
    }

    /**
     * Check current topic retention to decide whether the previous job is already done or not
     */
    if (latestKafkaTopic.isPresent()) {
      logger.debug("Latest kafka topic for store: " + storeName + " is " + latestKafkaTopic.get());


      if (!isTopicTruncated(latestKafkaTopic.get())) {
        /**
         * Check whether the corresponding version exists or not, since it is possible that last push
         * meets Kafka topic creation timeout.
         * When Kafka topic creation timeout happens, topic/job could be still running, but the version
         * should not exist according to the logic in {@link VeniceHelixAdmin#addVersion}.
         *
         * If the corresponding version doesn't exist, this function will issue command to kill job to deprecate
         * the incomplete topic/job.
         */
        Store store = getStore(clusterName, storeName);
        Optional<Version> version = store.getVersion(Version.parseVersionFromKafkaTopicName(latestKafkaTopic.get()));
        if (! version.isPresent()) {
          // The corresponding version doesn't exist.
          killOfflinePush(clusterName, latestKafkaTopic.get());
          logger.info("Found topic: " + latestKafkaTopic.get() + " without the corresponding version, will kill it");
          return Optional.empty();
        }

        /**
         * If Parent Controller could not infer the job status from topic retention policy, it will check the actual
         * job status by sending requests to each individual datacenter.
         * If the job is still running, Parent Controller will block current push.
         */
        final long SLEEP_MS_BETWEEN_RETRY = TimeUnit.SECONDS.toMillis(10);
        ExecutionStatus jobStatus = ExecutionStatus.PROGRESS;
        Map<String, String> extraInfo = new HashMap<>();

        int retryTimes = 5;
        int current = 0;
        while (current++ < retryTimes) {
          OfflinePushStatusInfo offlineJobStatus = getOffLinePushStatus(clusterName, latestKafkaTopic.get());
          jobStatus = offlineJobStatus.getExecutionStatus();
          extraInfo = offlineJobStatus.getExtraInfo();
          if (!extraInfo.containsValue(ExecutionStatus.UNKNOWN.toString())) {
            break;
          }
          // Retry since there is a connection failure when querying job status against each datacenter
          try {
            timer.sleep(SLEEP_MS_BETWEEN_RETRY);
          } catch (InterruptedException e) {
            throw new VeniceException("Received InterruptedException during sleep between 'getOffLinePushStatus' calls");
          }
        }
        if (extraInfo.containsValue(ExecutionStatus.UNKNOWN.toString())) {
          // TODO: Do we need to throw exception here??
          logger.error("Failed to get job status for topic: " + latestKafkaTopic.get() + " after retrying " + retryTimes
              + " times, extra info: " + extraInfo);
        }
        if (!jobStatus.isTerminal()) {
          logger.info(
              "Job status: " + jobStatus + " for Kafka topic: " + latestKafkaTopic.get() + " is not terminal, extra info: " + extraInfo);
          return latestKafkaTopic;
        } else {
          /**
           * If the job status of latestKafkaTopic is terminal and it is not an incremental push,
           * it will be truncated in {@link #getOffLinePushStatus(String, String)}.
           */
          if (!isIncrementalPush) {
            truncateTopicsBasedOnMaxErroredTopicNumToKeep(versionTopics);
          }
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Only keep {@link #maxErroredTopicNumToKeep} non-truncated topics ordered by version
   * N.B. This method was originally introduced to debug KMM issues. But now it works
   * as a general method for cleaning up leaking topics. ({@link #maxErroredTopicNumToKeep}
   * is always 0.)
   *
   * TODO: rename the method once we remove the rest of KMM debugging logic.
   */
  protected void truncateTopicsBasedOnMaxErroredTopicNumToKeep(List<String> topics) {
    // Based on current logic, only 'errored' topics were not truncated.
    List<String> sortedNonTruncatedTopics = topics.stream().filter(topic -> !isTopicTruncated(topic)).sorted((t1, t2) -> {
      int v1 = Version.parseVersionFromKafkaTopicName(t1);
      int v2 = Version.parseVersionFromKafkaTopicName(t2);
      return v1 - v2;
    }).collect(Collectors.toList());
    Set<String> grandfatheringTopics = sortedNonTruncatedTopics.stream().filter(Version::isStreamReprocessingTopic)
        .collect(Collectors.toSet());
    List<String> sortedNonTruncatedVersionTopics = sortedNonTruncatedTopics.stream().filter(topic ->
        !Version.isStreamReprocessingTopic(topic)).collect(Collectors.toList());
    if (sortedNonTruncatedVersionTopics.size() <= maxErroredTopicNumToKeep) {
      logger.info("Non-truncated version topics size: " + sortedNonTruncatedVersionTopics.size() +
          " isn't bigger than maxErroredTopicNumToKeep: " + maxErroredTopicNumToKeep + ", so no topic will be truncated this time");
      return;
    }
    int topicNumToTruncate = sortedNonTruncatedVersionTopics.size() - maxErroredTopicNumToKeep;
    int truncatedTopicCnt = 0;
    for (String topic: sortedNonTruncatedVersionTopics) {
      if (++truncatedTopicCnt > topicNumToTruncate) {
        break;
      }
      truncateKafkaTopic(topic);
      logger.info("Errored topic: " + topic + " got truncated");
      String correspondingStreamReprocessingTopic = Version.composeStreamReprocessingTopicFromVersionTopic(topic);
      if (grandfatheringTopics.contains(correspondingStreamReprocessingTopic)) {
        truncateKafkaTopic(correspondingStreamReprocessingTopic);
        logger.info("Corresponding grandfathering topic: " + correspondingStreamReprocessingTopic + " also got truncated.");
      }
    }
  }

  @Override
  public Version incrementVersionIdempotent(String clusterName, String storeName, String pushJobId,
      int numberOfPartitions, int replicationFactor, Version.PushType pushType, boolean sendStartOfPush,
      boolean sorted) {

    Optional<String> currentPushTopic = getTopicForCurrentPushJob(clusterName, storeName, pushType.isIncremental());
    if (currentPushTopic.isPresent()) {
      int currentPushVersion = Version.parseVersionFromKafkaTopicName(currentPushTopic.get());
      Store store = getStore(clusterName, storeName);
      Optional<Version> version = store.getVersion(currentPushVersion);
      if (!version.isPresent()) {
        throw new VeniceException("A corresponding version should exist with the ongoing push with topic "
            + currentPushTopic);
      }
      String existingPushJobId = version.get().getPushJobId();
      if (!existingPushJobId.equals(pushJobId)) {
        if (checkLingeringVersion(store, version.get())) {
          // Kill the lingering version and allow the new push to start.
          logger.info("Found lingering topic: " +  currentPushTopic.get() + " with push id: " + existingPushJobId
              + ". Killing the lingering version that was created at: " + version.get().getCreatedTime());
          killOfflinePush(clusterName, currentPushTopic.get());
        } else {
          throw new VeniceException("Unable to start the push with pushJobId " + pushJobId + " for store " + storeName
              + ". An ongoing push with pushJobId " + existingPushJobId + " and topic " + currentPushTopic
              + " is found and it must be terminated before another push can be started.");
        }
      }
    }
    Version newVersion = pushType.isIncremental() ? veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName)
        : veniceHelixAdmin.addVersionOnly(clusterName, storeName, pushJobId, numberOfPartitions, replicationFactor,
            sendStartOfPush, sorted, pushType);
    if (!pushType.isIncremental()) {
      acquireLock(clusterName, storeName);
      try {
        sendAddVersionAdminMessage(clusterName, storeName, pushJobId, newVersion.getNumber(), numberOfPartitions,
            pushType);
      } finally {
        releaseLock(clusterName);
      }
    }
    cleanupHistoricalVersions(clusterName, storeName);

    return newVersion;
  }

  protected void sendAddVersionAdminMessage(String clusterName, String storeName, String pushJobId, int versionNum,
      int numberOfPartitions, Version.PushType pushType) {
    AddVersion addVersion = (AddVersion) AdminMessageType.ADD_VERSION.getNewInstance();
    addVersion.clusterName = clusterName;
    addVersion.storeName = storeName;
    addVersion.pushJobId = pushJobId;
    addVersion.versionNum = versionNum;
    addVersion.numberOfPartitions = numberOfPartitions;
    addVersion.pushType = pushType.getValue();

    AdminOperation message = new AdminOperation();
    message.operationType = AdminMessageType.ADD_VERSION.getValue();
    message.payloadUnion = addVersion;

    sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
  }

  @Override
  public synchronized String getRealTimeTopic(String clusterName, String storeName){
    return veniceHelixAdmin.getRealTimeTopic(clusterName, storeName);
  }

  /**
   * A couple of extra checks are needed in parent controller
   * 1. check batch job statuses across child controllers. (We cannot only check the version status
   * in parent controller since they are marked as STARTED)
   * 2. check if the topic is marked to be truncated or not. (This could be removed if we don't
   * preserve incremental push topic in parent Kafka anymore
   */
  @Override
  public synchronized Version getIncrementalPushVersion(String clusterName, String storeName) {
    Version incrementalPushVersion = veniceHelixAdmin.getIncrementalPushVersion(clusterName, storeName);
    String incrementalPushTopic = incrementalPushVersion.kafkaTopicName();
    ExecutionStatus status = getOffLinePushStatus(clusterName, incrementalPushTopic, Optional.empty()).getExecutionStatus();

    return getIncrementalPushVersion(incrementalPushVersion, status);
  }

  //This method is only for internal / test use case
  Version getIncrementalPushVersion(Version incrementalPushVersion, ExecutionStatus status) {
    String incrementalPushTopic = incrementalPushVersion.kafkaTopicName();
    String storeName = incrementalPushVersion.getStoreName();

    if (!status.isTerminal()) {
      throw new VeniceException("Cannot start incremental push since batch push is on going." + " store: " + storeName);
    }

    if(status == ExecutionStatus.ERROR || veniceHelixAdmin.isTopicTruncated(incrementalPushTopic)) {
      throw new VeniceException("Cannot start incremental push since previous batch push has failed. Please run another bash job."
          + " store: " + storeName);
    }

    return incrementalPushVersion;
  }

  @Override
  public int getCurrentVersion(String clusterName, String storeName) {
    throw new VeniceUnsupportedOperationException("getCurrentVersion", "Please use getCurrentVersionsForMultiColos in Parent controller.");
  }

  /**
   * Query the current version for the given store. In parent colo, Venice do not update the current version because
   * there is not offline push monitor. So parent controller will query each prod controller and return the map.
   */
  @Override
  public Map<String, Integer> getCurrentVersionsForMultiColos(String clusterName, String storeName) {
    Map<String, ControllerClient> controllerClients = getControllerClientMap(clusterName);
    return getCurrentVersionForMultiColos(clusterName, storeName, controllerClients);
  }

  protected Map<String, Integer> getCurrentVersionForMultiColos(String clusterName, String storeName,
      Map<String, ControllerClient> controllerClients) {
    Set<String> prodColos = controllerClients.keySet();
    Map<String, Integer> result = new HashMap<>();
    for (String colo : prodColos) {
      StoreResponse response = controllerClients.get(colo).getStore(storeName);
      if (response.isError()) {
        logger.error(
            "Could not query store from colo: " + colo + " for cluster: " + clusterName + ". " + response.getError());
        result.put(colo, AdminConsumptionTask.IGNORED_CURRENT_VERSION);
      } else {
        result.put(colo,response.getStore().getCurrentVersion());
      }
    }
    return result;
  }

  @Override
  public Version peekNextVersion(String clusterName, String storeName) {
    throw new VeniceUnsupportedOperationException("peekNextVersion");
  }

  @Override
  public List<Version> deleteAllVersionsInStore(String clusterName, String storeName) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForDeletion(clusterName, storeName);

      DeleteAllVersions deleteAllVersions = (DeleteAllVersions) AdminMessageType.DELETE_ALL_VERSIONS.getNewInstance();
      deleteAllVersions.clusterName = clusterName;
      deleteAllVersions.storeName = storeName;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.DELETE_ALL_VERSIONS.getValue();
      message.payloadUnion = deleteAllVersions;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
      return Collections.emptyList();
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void deleteOldVersionInStore(String clusterName, String storeName, int versionNum) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForSingleVersionDeletion(clusterName, storeName, versionNum);

      DeleteOldVersion deleteOldVersion = (DeleteOldVersion) AdminMessageType.DELETE_OLD_VERSION.getNewInstance();
      deleteOldVersion.clusterName = clusterName;
      deleteOldVersion.storeName = storeName;
      deleteOldVersion.versionNum = versionNum;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.DELETE_OLD_VERSION.getValue();
      message.payloadUnion = deleteOldVersion;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public List<Version> versionsForStore(String clusterName, String storeName) {
    return veniceHelixAdmin.versionsForStore(clusterName, storeName);
  }

  @Override
  public List<Store> getAllStores(String clusterName) {
    return veniceHelixAdmin.getAllStores(clusterName);
  }

  @Override
  public Map<String, String> getAllStoreStatuses(String clusterName) {
    throw new VeniceUnsupportedOperationException("getAllStoreStatuses");
  }

  @Override
  public Store getStore(String clusterName, String storeName) {
    return veniceHelixAdmin.getStore(clusterName, storeName);
  }

  @Override
  public boolean hasStore(String clusterName, String storeName) {
    return veniceHelixAdmin.hasStore(clusterName, storeName);
  }

  @Override
  public void setStoreCurrentVersion(String clusterName,
                                String storeName,
                                int versionNumber) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForUpdateStoreMetadata(clusterName, storeName);

      SetStoreCurrentVersion setStoreCurrentVersion = (SetStoreCurrentVersion) AdminMessageType.SET_STORE_CURRENT_VERSION.getNewInstance();
      setStoreCurrentVersion.clusterName = clusterName;
      setStoreCurrentVersion.storeName = storeName;
      setStoreCurrentVersion.currentVersion = versionNumber;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.SET_STORE_CURRENT_VERSION.getValue();
      message.payloadUnion = setStoreCurrentVersion;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public synchronized void setStoreLargestUsedVersion(String clusterName, String storeName, int versionNumber) {
    throw new VeniceUnsupportedOperationException("setStoreLargestUsedVersion", "This is only supported in the Child Controller.");
  }


  @Override
  public void setStoreOwner(String clusterName, String storeName, String owner) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForUpdateStoreMetadata(clusterName, storeName);

      SetStoreOwner setStoreOwner = (SetStoreOwner) AdminMessageType.SET_STORE_OWNER.getNewInstance();
      setStoreOwner.clusterName = clusterName;
      setStoreOwner.storeName = storeName;
      setStoreOwner.owner = owner;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.SET_STORE_OWNER.getValue();
      message.payloadUnion = setStoreOwner;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void setStorePartitionCount(String clusterName, String storeName, int partitionCount) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForUpdateStoreMetadata(clusterName, storeName);

      SetStorePartitionCount setStorePartition = (SetStorePartitionCount) AdminMessageType.SET_STORE_PARTITION.getNewInstance();
      setStorePartition.clusterName = clusterName;
      setStorePartition.storeName = storeName;
      setStorePartition.partitionNum = partitionCount;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.SET_STORE_PARTITION.getValue();
      message.payloadUnion = setStorePartition;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void setStoreReadability(String clusterName, String storeName, boolean desiredReadability) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForUpdateStoreMetadata(clusterName, storeName);

      AdminOperation message = new AdminOperation();

      if (desiredReadability) {
        message.operationType = AdminMessageType.ENABLE_STORE_READ.getValue();
        EnableStoreRead enableStoreRead = (EnableStoreRead) AdminMessageType.ENABLE_STORE_READ.getNewInstance();
        enableStoreRead.clusterName = clusterName;
        enableStoreRead.storeName = storeName;
        message.payloadUnion = enableStoreRead;
      } else {
        message.operationType = AdminMessageType.DISABLE_STORE_READ.getValue();
        DisableStoreRead disableStoreRead = (DisableStoreRead) AdminMessageType.DISABLE_STORE_READ.getNewInstance();
        disableStoreRead.clusterName = clusterName;
        disableStoreRead.storeName = storeName;
        message.payloadUnion = disableStoreRead;
      }

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void setStoreWriteability(String clusterName, String storeName, boolean desiredWriteability) {
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForUpdateStoreMetadata(clusterName, storeName);

      AdminOperation message = new AdminOperation();

      if (desiredWriteability) {
        message.operationType = AdminMessageType.ENABLE_STORE_WRITE.getValue();
        ResumeStore resumeStore = (ResumeStore) AdminMessageType.ENABLE_STORE_WRITE.getNewInstance();
        resumeStore.clusterName = clusterName;
        resumeStore.storeName = storeName;
        message.payloadUnion = resumeStore;
      } else {
        message.operationType = AdminMessageType.DISABLE_STORE_WRITE.getValue();
        PauseStore pauseStore = (PauseStore) AdminMessageType.DISABLE_STORE_WRITE.getNewInstance();
        pauseStore.clusterName = clusterName;
        pauseStore.storeName = storeName;
        message.payloadUnion = pauseStore;
      }

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public void setStoreReadWriteability(String clusterName, String storeName, boolean isAccessible) {
    setStoreReadability(clusterName, storeName, isAccessible);
    setStoreWriteability(clusterName, storeName, isAccessible);
  }

  @Override
  public void setLeaderFollowerModelEnabled(String clusterName, String storeName, boolean leaderFollowerModelEnabled) {
    //place holder
    //will add it in the following RB
  }

  @Override
  public void updateStore(String clusterName,
      String storeName,
      Optional<String> owner,
      Optional<Boolean> readability,
      Optional<Boolean> writeability,
      Optional<Integer> partitionCount,
      Optional<String> partitionerClass,
      Optional<Map<String, String>> partitionerParams,
      Optional<Integer> amplificationFactor,
      Optional<Long> storageQuotaInByte,
      Optional<Boolean> hybridStoreDbOverheadBypass,
      Optional<Long> readQuotaInCU,
      Optional<Integer> currentVersion,
      Optional<Integer> largestUsedVersionNumber,
      Optional<Long> hybridRewindSeconds,
      Optional<Long> hybridOffsetLagThreshold,
      Optional<Boolean> accessControlled,
      Optional<CompressionStrategy> compressionStrategy,
      Optional<Boolean> clientDecompressionEnabled,
      Optional<Boolean> chunkingEnabled,
      Optional<Boolean> singleGetRouterCacheEnabled,
      Optional<Boolean> batchGetRouterCacheEnabled,
      Optional<Integer> batchGetLimit,
      Optional<Integer> numVersionsToPreserve,
      Optional<Boolean> incrementalPushEnabled,
      Optional<Boolean> storeMigration,
      Optional<Boolean> writeComputationEnabled,
      Optional<Boolean> readComputationEnabled,
      Optional<Integer> bootstrapToOnlineTimeoutInHours,
      Optional<Boolean> leaderFollowerModelEnabled,
      Optional<BackupStrategy> backupStrategy,
      Optional<Boolean> autoSchemaRegisterPushJobEnabled,
      Optional<Boolean> superSetSchemaAutoGenerationForReadComputeEnabled,
      Optional<Boolean> hybridStoreDiskQuotaEnabled,
      Optional<Boolean> regularVersionETLEnabled,
      Optional<Boolean> futureVersionETLEnabled,
      Optional<String> etledUserProxyAccount) {
    acquireLock(clusterName, storeName);

    try {
      Store store = veniceHelixAdmin.getStore(clusterName, storeName);

      if (store.isMigrating()) {
        if (!(storeMigration.isPresent() || readability.isPresent() || writeability.isPresent())) {
          String errMsg = "This update operation is not allowed during store migration!";
          logger.warn(errMsg + " Store name: " + storeName);
          throw new VeniceException(errMsg);
        }
      }

      UpdateStore setStore = (UpdateStore) AdminMessageType.UPDATE_STORE.getNewInstance();
      setStore.clusterName = clusterName;
      setStore.storeName = storeName;
      setStore.owner = owner.isPresent() ? owner.get() : store.getOwner();
      if (partitionCount.isPresent() && store.isHybrid()){
        throw new VeniceHttpException(HttpStatus.SC_BAD_REQUEST, "Cannot change partition count for hybrid stores");
      }
      setStore.partitionNum = partitionCount.isPresent() ? partitionCount.get() : store.getPartitionCount();

      /**
       * Prepare the PartitionerConfigRecord object for admin message queue. Only update fields that are set, other fields
       * will be read from the original store.
       */
      PartitionerConfigRecord partitionerConfigRecord = new PartitionerConfigRecord();
      partitionerConfigRecord.setPartitionerClass(partitionerClass.isPresent() ?  partitionerClass.get() : store.getPartitionerConfig().getPartitionerClass());
      partitionerConfigRecord.setPartitionerParams(partitionerParams.isPresent() ? partitionerParams.get() : store.getPartitionerConfig().getPartitionerParams());
      partitionerConfigRecord.setAmplificationFactor(amplificationFactor.isPresent() ? amplificationFactor.get() : store.getPartitionerConfig().getAmplificationFactor());
      setStore.setPartitionerConfig(partitionerConfigRecord);

      setStore.enableReads = readability.isPresent() ? readability.get() : store.isEnableReads();
      setStore.enableWrites = writeability.isPresent() ? writeability.get() : store.isEnableWrites();

      setStore.readQuotaInCU = readQuotaInCU.isPresent() ? readQuotaInCU.get() : store.getReadQuotaInCU();
      //We need to to be careful when handling currentVersion.
      //Since it is not synced between parent and local controller,
      //It is very likely to override local values unintentionally.
      setStore.currentVersion = currentVersion.isPresent()?currentVersion.get(): AdminConsumptionTask.IGNORED_CURRENT_VERSION;

      boolean oldStoreHybrid = store.isHybrid();

      HybridStoreConfig hybridStoreConfig = VeniceHelixAdmin.mergeNewSettingsIntoOldHybridStoreConfig(
          store, hybridRewindSeconds, hybridOffsetLagThreshold);
      if (null == hybridStoreConfig) {
        setStore.hybridStoreConfig = null;
      } else {
        HybridStoreConfigRecord hybridStoreConfigRecord = new HybridStoreConfigRecord();
        hybridStoreConfigRecord.offsetLagThresholdToGoOnline = hybridStoreConfig.getOffsetLagThresholdToGoOnline();
        hybridStoreConfigRecord.rewindTimeInSeconds = hybridStoreConfig.getRewindTimeInSeconds();
        setStore.hybridStoreConfig = hybridStoreConfigRecord;
      }

      /**
       * Set storage quota according to store properties. For hybrid stores, rocksDB has the overhead ratio as we
       * do append-only and compaction will happen later.
       * We need to multiply/divide the overhead ratio by situations
       */
      long setStoreQuota = storageQuotaInByte.orElse(store.getStorageQuotaInByte());
      // When hybridStoreOverheadBypass is true, we skip checking situations and simply set it to be the passed value.
      if (hybridStoreDbOverheadBypass.orElse(false) || setStoreQuota == Store.UNLIMITED_STORAGE_QUOTA) {
        setStore.storageQuotaInByte = setStoreQuota;
      } else {
        if (!oldStoreHybrid) {
          // convert from non-hybrid to hybrid store, needs to increase the storage quota accordingly
          if ((hybridRewindSeconds.isPresent() && hybridRewindSeconds.get() >= 0) &&
              (hybridOffsetLagThreshold.isPresent() && hybridOffsetLagThreshold.get() >= 0)) {
            setStore.storageQuotaInByte = Math.round(setStoreQuota * RocksDBUtils.ROCKSDB_OVERHEAD_RATIO_FOR_HYBRID_STORE);
          } else { // user updates storage quota for non-hybrid stores or just inherit the old value
            setStore.storageQuotaInByte = setStoreQuota;
          }
        } else {
          // convert from hybrid to non-hybrid store, needs to shrink the storage quota accordingly
          if ((hybridRewindSeconds.isPresent() && hybridRewindSeconds.get() < 0) ||
              (hybridOffsetLagThreshold.isPresent() && hybridOffsetLagThreshold.get() < 0)) {
            setStore.storageQuotaInByte = Math.round(setStoreQuota / RocksDBUtils.ROCKSDB_OVERHEAD_RATIO_FOR_HYBRID_STORE);
            // user updates storage quota for hybrid store
          } else if (storageQuotaInByte.isPresent()) {
            setStore.storageQuotaInByte = Math.round(setStoreQuota * RocksDBUtils.ROCKSDB_OVERHEAD_RATIO_FOR_HYBRID_STORE);
          } else { // inherit old value
            setStore.storageQuotaInByte = setStoreQuota;
          }
        }
      }

      setStore.accessControlled = accessControlled.isPresent() ? accessControlled.get() : store.isAccessControlled();
      setStore.compressionStrategy = compressionStrategy.isPresent()
          ? compressionStrategy.get().getValue() : store.getCompressionStrategy().getValue();
      setStore.clientDecompressionEnabled =  clientDecompressionEnabled.orElseGet(() -> store.getClientDecompressionEnabled());
      setStore.chunkingEnabled = chunkingEnabled.isPresent() ? chunkingEnabled.get() : store.isChunkingEnabled();
      setStore.singleGetRouterCacheEnabled = singleGetRouterCacheEnabled.isPresent() ? singleGetRouterCacheEnabled.get() : store.isSingleGetRouterCacheEnabled();
      setStore.batchGetRouterCacheEnabled =
          batchGetRouterCacheEnabled.isPresent() ? batchGetRouterCacheEnabled.get() : store.isBatchGetRouterCacheEnabled();

      veniceHelixAdmin.checkWhetherStoreWillHaveConflictConfigForCaching(store, incrementalPushEnabled,
          null == hybridStoreConfig ? Optional.empty() : Optional.of(hybridStoreConfig),
          singleGetRouterCacheEnabled, batchGetRouterCacheEnabled);
      setStore.batchGetLimit = batchGetLimit.isPresent() ? batchGetLimit.get() : store.getBatchGetLimit();
      setStore.numVersionsToPreserve =
          numVersionsToPreserve.isPresent() ? numVersionsToPreserve.get() : store.getNumVersionsToPreserve();
      setStore.incrementalPushEnabled =
          incrementalPushEnabled.isPresent() ? incrementalPushEnabled.get() : store.isIncrementalPushEnabled();
      setStore.isMigrating = storeMigration.isPresent() ? storeMigration.get() : store.isMigrating();
      setStore.writeComputationEnabled = writeComputationEnabled.isPresent() ? writeComputationEnabled.get() : store.isWriteComputationEnabled();
      setStore.readComputationEnabled = readComputationEnabled.isPresent() ? readComputationEnabled.get() : store.isReadComputationEnabled();
      setStore.bootstrapToOnlineTimeoutInHours = bootstrapToOnlineTimeoutInHours.isPresent() ?
          bootstrapToOnlineTimeoutInHours.get() : store.getBootstrapToOnlineTimeoutInHours();
      setStore.leaderFollowerModelEnabled = leaderFollowerModelEnabled.isPresent() ? leaderFollowerModelEnabled.get() : store.isLeaderFollowerModelEnabled();
      setStore.backupStrategy = (backupStrategy.orElse(store.getBackupStrategy())).ordinal();

      setStore.schemaAutoRegisterFromPushJobEnabled = autoSchemaRegisterPushJobEnabled.orElse(store.isSchemaAutoRegisterFromPushJobEnabled());
      if (superSetSchemaAutoGenerationForReadComputeEnabled.isPresent() && superSetSchemaAutoGenerationForReadComputeEnabled.get()) {
        if (!setStore.readComputationEnabled) {
          throw new VeniceException("Cannot set autoSchemaRegisterAdminEnabled to non-read-compute stores");
        }
        setStore.superSetSchemaAutoGenerationForReadComputeEnabled = superSetSchemaAutoGenerationForReadComputeEnabled.orElse(store.isSuperSetSchemaAutoGenerationForReadComputeEnabled());
      }
      setStore.hybridStoreDiskQuotaEnabled = hybridStoreDiskQuotaEnabled.orElse(store.isHybridStoreDiskQuotaEnabled());

      setStore.ETLStoreConfig = mergeNewSettingIntoOldETLStoreConfig(store, regularVersionETLEnabled, futureVersionETLEnabled, etledUserProxyAccount);

      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.UPDATE_STORE.getValue();
      message.payloadUnion = setStore;
      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public double getStorageEngineOverheadRatio(String clusterName) {
    return veniceHelixAdmin.getStorageEngineOverheadRatio(clusterName);
  }

  @Override
  public SchemaEntry getKeySchema(String clusterName, String storeName) {
    return veniceHelixAdmin.getKeySchema(clusterName, storeName);
  }

  @Override
  public Collection<SchemaEntry> getValueSchemas(String clusterName, String storeName) {
    return veniceHelixAdmin.getValueSchemas(clusterName, storeName);
  }

  @Override
  public Collection<DerivedSchemaEntry> getDerivedSchemas(String clusterName, String storeName) {
    return veniceHelixAdmin.getDerivedSchemas(clusterName, storeName);
  }

  @Override
  public int getValueSchemaId(String clusterName, String storeName, String valueSchemaStr) {
    return veniceHelixAdmin.getValueSchemaId(clusterName, storeName, valueSchemaStr);
  }

  @Override
  public Pair<Integer, Integer> getDerivedSchemaId(String clusterName, String storeName, String schemaStr) {
    return veniceHelixAdmin.getDerivedSchemaId(clusterName, storeName, schemaStr);
  }

  @Override
  public SchemaEntry getValueSchema(String clusterName, String storeName, int id) {
    return veniceHelixAdmin.getValueSchema(clusterName, storeName, id);
  }

  @Override
  public SchemaEntry addValueSchema(String clusterName, String storeName, String valueSchemaStr, DirectionalSchemaCompatibilityType expectedCompatibilityType) {
    acquireLock(clusterName, storeName);
    try {
      int newValueSchemaId = veniceHelixAdmin.checkPreConditionForAddValueSchemaAndGetNewSchemaId(
          clusterName, storeName, valueSchemaStr, expectedCompatibilityType);

      // if we find this is a exactly duplicate schema, return the existing schema id
      // else add the schema with possible doc field change
      if (newValueSchemaId == SchemaData.DUPLICATE_VALUE_SCHEMA_CODE) {
        return new SchemaEntry(veniceHelixAdmin.getValueSchemaId(clusterName, storeName, valueSchemaStr), valueSchemaStr);
      }

      Store store = veniceHelixAdmin.getStore(clusterName, storeName);
      Schema existingSchema = veniceHelixAdmin.getLatestValueSchema(clusterName, store);

      if (store.isSuperSetSchemaAutoGenerationForReadComputeEnabled() && existingSchema != null) {
        Schema upcomingSchema = Schema.parse(valueSchemaStr);
        Schema newSuperSetSchema = AvroSchemaUtils.generateSuperSetSchema(existingSchema, upcomingSchema);
        String newSuperSetSchemaStr = newSuperSetSchema.toString();

        // Register super-set schema only if it does not match with existing or upcoming schema
        if (!AvroSchemaUtils.compareSchemaIgnoreFieldOrder(newSuperSetSchema, upcomingSchema) &&
            !AvroSchemaUtils.compareSchemaIgnoreFieldOrder(newSuperSetSchema, existingSchema)) {
          // validate compatibility of the new superset schema
          veniceHelixAdmin.checkPreConditionForAddValueSchemaAndGetNewSchemaId(
              clusterName, storeName, newSuperSetSchemaStr, expectedCompatibilityType);
          // Check if the superset schema already exists or not. If exists use the same ID, else bump the ID by one
          int supersetSchemaId = veniceHelixAdmin.getValueSchemaIdIgnoreFieldOrder(clusterName, storeName, newSuperSetSchemaStr);
          if (supersetSchemaId == SchemaData.INVALID_VALUE_SCHEMA_ID) {
            supersetSchemaId = newValueSchemaId + 1;
          }
          return addSupersetValueSchemaEntry(clusterName, storeName, valueSchemaStr, newValueSchemaId,
              newSuperSetSchemaStr, supersetSchemaId);
        }
      }

      return addValueSchemaEntry(clusterName, storeName, valueSchemaStr, newValueSchemaId);
    } finally {
      releaseLock(clusterName);
    }
  }

  private SchemaEntry addSupersetValueSchemaEntry(String clusterName, String storeName, String valueSchemaStr,
      int valueSchemaId, String supersetSchemaStr, int supersetSchemaId) {
    logger.info("Adding value schema: " + valueSchemaStr + " and superset schema " + supersetSchemaStr + " to store: " + storeName + " in cluster: " + clusterName);

    SupersetSchemaCreation supersetSchemaCreation =
        (SupersetSchemaCreation) AdminMessageType.SUPERSET_SCHEMA_CREATION.getNewInstance();
    supersetSchemaCreation.clusterName = clusterName;
    supersetSchemaCreation.storeName = storeName;
    SchemaMeta schemaMeta = new SchemaMeta();
    schemaMeta.definition = valueSchemaStr;
    schemaMeta.schemaType = SchemaType.AVRO_1_4.getValue();
    supersetSchemaCreation.valueSchema = schemaMeta;
    supersetSchemaCreation.valueSchemaId = valueSchemaId;

    SchemaMeta supersetSchemaMeta = new SchemaMeta();
    supersetSchemaMeta.definition = supersetSchemaStr;
    supersetSchemaMeta.schemaType = SchemaType.AVRO_1_4.getValue();
    supersetSchemaCreation.supersetSchema = supersetSchemaMeta;
    supersetSchemaCreation.supersetSchemaId = supersetSchemaId;


    AdminOperation message = new AdminOperation();
    message.operationType = AdminMessageType.SUPERSET_SCHEMA_CREATION.getValue();
    message.payloadUnion = supersetSchemaCreation;

    sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    return new SchemaEntry(valueSchemaId, valueSchemaStr);
  }

  private SchemaEntry addValueSchemaEntry(String clusterName, String storeName, String valueSchemaStr,
      int newValueSchemaId) {
    logger.info("Adding value schema: " + valueSchemaStr + " to store: " + storeName + " in cluster: " + clusterName);

    ValueSchemaCreation valueSchemaCreation =
        (ValueSchemaCreation) AdminMessageType.VALUE_SCHEMA_CREATION.getNewInstance();
    valueSchemaCreation.clusterName = clusterName;
    valueSchemaCreation.storeName = storeName;
    SchemaMeta schemaMeta = new SchemaMeta();
    schemaMeta.definition = valueSchemaStr;
    schemaMeta.schemaType = SchemaType.AVRO_1_4.getValue();
    valueSchemaCreation.schema = schemaMeta;
    valueSchemaCreation.schemaId = newValueSchemaId;

    AdminOperation message = new AdminOperation();
    message.operationType = AdminMessageType.VALUE_SCHEMA_CREATION.getValue();
    message.payloadUnion = valueSchemaCreation;

    sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);

    //defensive code checking
    int actualValueSchemaId = getValueSchemaId(clusterName, storeName, valueSchemaStr);
    if (actualValueSchemaId != newValueSchemaId) {
      throw new VeniceException(
          "Something bad happens, the expected new value schema id is: " + newValueSchemaId + ", but got: "
              + actualValueSchemaId);
    }

    return new SchemaEntry(actualValueSchemaId, valueSchemaStr);
  }

  @Override
  public SchemaEntry addSupersetSchema(String clusterName, String storeName, String valueSchemaStr, int valueSchemaId,
      String supersetSchemaStr, int supersetSchemaId) {
    throw new VeniceUnsupportedOperationException("addValueSchema");
  }

  @Override
  public SchemaEntry addValueSchema(String clusterName, String storeName, String valueSchemaStr, int schemaId) {
    throw new VeniceUnsupportedOperationException("addValueSchema");
  }

  @Override
  public DerivedSchemaEntry addDerivedSchema(String clusterName, String storeName, int valueSchemaId, String derivedSchemaStr) {
    acquireLock(clusterName, storeName);
    try {
      int newDerivedSchemaId = veniceHelixAdmin
          .checkPreConditionForAddDerivedSchemaAndGetNewSchemaId(clusterName, storeName, valueSchemaId, derivedSchemaStr);

      //if we find this is a duplicate schema, return the existing schema id
      if (newDerivedSchemaId == SchemaData.DUPLICATE_VALUE_SCHEMA_CODE) {
        return new DerivedSchemaEntry(valueSchemaId,
            veniceHelixAdmin.getDerivedSchemaId(clusterName, storeName, derivedSchemaStr).getSecond(), derivedSchemaStr);
      }

      logger.info("Adding derived schema: " + derivedSchemaStr + " to store: " + storeName + ", version: " +
          valueSchemaId + " in cluster: " + clusterName);

      DerivedSchemaCreation derivedSchemaCreation = (DerivedSchemaCreation) AdminMessageType.DERIVED_SCHEMA_CREATION.getNewInstance();
      derivedSchemaCreation.clusterName = clusterName;
      derivedSchemaCreation.storeName = storeName;
      SchemaMeta schemaMeta = new SchemaMeta();
      schemaMeta.definition = derivedSchemaStr;
      schemaMeta.schemaType = SchemaType.AVRO_1_4.getValue();
      derivedSchemaCreation.schema = schemaMeta;
      derivedSchemaCreation.valueSchemaId = valueSchemaId;
      derivedSchemaCreation.derivedSchemaId = newDerivedSchemaId;

      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.DERIVED_SCHEMA_CREATION.getValue();
      message.payloadUnion = derivedSchemaCreation;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);

      //defensive code checking
      Pair<Integer, Integer> actualValueSchemaIdPair = getDerivedSchemaId(clusterName, storeName, derivedSchemaStr);
      if (actualValueSchemaIdPair.getFirst() != valueSchemaId || actualValueSchemaIdPair.getSecond() != newDerivedSchemaId) {
        throw new VeniceException(String.format("Something bad happens, the expected new value schema id pair is:"
            + "%d_%d, but got: %d_%d", valueSchemaId, newDerivedSchemaId, actualValueSchemaIdPair.getFirst(),
            actualValueSchemaIdPair.getSecond()));
      }

      return new DerivedSchemaEntry(valueSchemaId, newDerivedSchemaId, derivedSchemaStr);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public DerivedSchemaEntry addDerivedSchema(String clusterName, String storeName, int valueSchemaId, int derivedSchemaId, String derivedSchemaStr) {
    throw new VeniceUnsupportedOperationException("addDerivedSchema");
  }

  @Override
  public List<String> getStorageNodes(String clusterName) {
    throw new VeniceUnsupportedOperationException("getStorageNodes");
  }

  @Override
  public Map<String, String> getStorageNodesStatus(String clusterName) {
    throw new VeniceUnsupportedOperationException("getStorageNodesStatus");
  }

  @Override
  public void removeStorageNode(String clusterName, String instanceId) {
    throw new VeniceUnsupportedOperationException("removeStorageNode");
  }

  private Map<String, ControllerClient> getControllerClientMap(String clusterName){
    Map<String, ControllerClient> controllerClients = new HashMap<>();
    VeniceControllerConfig veniceControllerConfig = multiClusterConfigs.getConfigForCluster(clusterName);
    veniceControllerConfig.getChildClusterMap().entrySet().
      forEach(entry -> controllerClients.put(entry.getKey(), new ControllerClient(clusterName, entry.getValue(), sslFactory)));
    veniceControllerConfig.getChildClusterD2Map().entrySet().
      forEach(entry -> controllerClients.put(entry.getKey(),
          new D2ControllerClient(veniceControllerConfig.getD2ServiceName(), clusterName, entry.getValue(), sslFactory)));

    return controllerClients;
  }

  /**
   * Queries child clusters for status.
   * Of all responses, return highest of (in order) NOT_CREATED, NEW, STARTED, PROGRESS.
   * If all responses are COMPLETED, returns COMPLETED.
   * If any response is ERROR and all responses are terminal (COMPLETED or ERROR), returns ERROR
   * If any response is ERROR and any response is not terminal, returns PROGRESS
   * ARCHIVED is treated as NOT_CREATED
   *
   * If error in querying half or more of clusters, returns PROGRESS. (so that polling will continue)
   *
   * @param clusterName
   * @param kafkaTopic
   * @return
   */
  @Override
  public OfflinePushStatusInfo getOffLinePushStatus(String clusterName, String kafkaTopic) {
    Map<String, ControllerClient> controllerClients = getControllerClientMap(clusterName);
    return getOffLineJobStatus(clusterName, kafkaTopic, controllerClients);
  }

  @Override
  public OfflinePushStatusInfo getOffLinePushStatus(String clusterName, String kafkaTopic, Optional<String> incrementalPushVersion) {
    Map<String, ControllerClient> controllerClients = getControllerClientMap(clusterName);
    return getOffLineJobStatus(clusterName, kafkaTopic, controllerClients, incrementalPushVersion);
  }

  protected OfflinePushStatusInfo getOffLineJobStatus(String clusterName, String kafkaTopic,
    Map<String, ControllerClient> controllerClients) {
    return getOffLineJobStatus(clusterName, kafkaTopic, controllerClients, Optional.empty());
  }

  protected OfflinePushStatusInfo getOffLineJobStatus(String clusterName, String kafkaTopic,
      Map<String, ControllerClient> controllerClients, Optional<String> incrementalPushVersion) {
    Set<String> childClusters = controllerClients.keySet();
    ExecutionStatus currentReturnStatus = ExecutionStatus.NEW; // This status is not used for anything... Might make sense to remove it, but anyhow.
    Optional<String> currentReturnStatusDetails = Optional.empty();
    List<ExecutionStatus> statuses = new ArrayList<>();
    Map<String, String> extraInfo = new HashMap<>();
    Map<String, String> extraDetails = new HashMap<>();
    int failCount = 0;
    for (String cluster : childClusters) {
      ControllerClient controllerClient = controllerClients.get(cluster);
      String masterControllerUrl = controllerClient.getMasterControllerUrl();
      JobStatusQueryResponse response = controllerClient.queryJobStatus(kafkaTopic, incrementalPushVersion);
      if (response.isError()) {
        failCount += 1;
        logger.warn("Couldn't query " + cluster + " for job " + kafkaTopic + " status: " + response.getError());
        statuses.add(ExecutionStatus.UNKNOWN);
        extraInfo.put(cluster, ExecutionStatus.UNKNOWN.toString());
        extraDetails.put(cluster, masterControllerUrl + " " + response.getError());
      } else {
        ExecutionStatus status = ExecutionStatus.valueOf(response.getStatus());
        statuses.add(status);
        extraInfo.put(cluster, response.getStatus());
        Optional<String> statusDetails = response.getOptionalStatusDetails();
        if (statusDetails.isPresent()) {
          extraDetails.put(cluster, masterControllerUrl + " " + statusDetails.get());
        }
      }
    }

    /**
     * TODO: remove guava library dependency since it could cause a lot of indirect dependency conflicts.
     */
    // Sort the per-datacenter status in this order, and return the first one in the list
    // Edge case example: if one cluster is stuck in NOT_CREATED, then
    //   as another cluster goes from PROGRESS to COMPLETED
    //   the aggregate status will go from PROGRESS back down to NOT_CREATED.
    List<ExecutionStatus> priorityOrderList = Arrays.asList(
        ExecutionStatus.PROGRESS,
        ExecutionStatus.STARTED,
        ExecutionStatus.START_OF_INCREMENTAL_PUSH_RECEIVED,
        ExecutionStatus.UNKNOWN,
        ExecutionStatus.NEW,
        ExecutionStatus.NOT_CREATED,
        ExecutionStatus.END_OF_PUSH_RECEIVED,
        ExecutionStatus.ERROR,
        ExecutionStatus.WARNING,
        ExecutionStatus.COMPLETED,
        ExecutionStatus.END_OF_INCREMENTAL_PUSH_RECEIVED,
        ExecutionStatus.ARCHIVED);
    Collections.sort(statuses, Comparator.comparingInt(priorityOrderList::indexOf));
    if (statuses.size() > 0) {
      currentReturnStatus = statuses.get(0);
    }

    int successCount = childClusters.size() - failCount;
    if (! (successCount >= (childClusters.size() / 2) + 1)) { // Strict majority must be reachable, otherwise keep polling
      currentReturnStatus = ExecutionStatus.PROGRESS;
    }

    if (currentReturnStatus.isTerminal()) {
      // If there is a temporary datacenter connection failure, we want H2V to report failure while allowing the push
      // to succeed in remaining datacenters.  If we want to allow the push to succeed in asyc in the remaining datacenter
      // then put the topic delete into an else block under `if (failcount > 0)`
      if (failCount > 0) {
        currentReturnStatus = ExecutionStatus.ERROR;
        currentReturnStatusDetails = Optional.of(failCount + "/" + childClusters.size() + " DCs unreachable. ");
      }

      // TODO: Set parent controller's version status based on currentReturnStatus
      // COMPLETED -> ONLINE
      // ERROR -> ERROR
      //TODO: remove this if statement since it was only for debugging purpose
      if (maxErroredTopicNumToKeep > 0 && currentReturnStatus.equals(ExecutionStatus.ERROR)) {
        currentReturnStatusDetails = Optional.of(currentReturnStatusDetails.orElse("") + "Parent Kafka topic won't be truncated");
        logger.info("The errored kafka topic: " + kafkaTopic + " won't be truncated since it will be used to investigate"
            + "some Kafka related issue");
      } else {
        //truncate the topic if this is not an incremental push enabled store or this is a failed batch push
        Store store = veniceHelixAdmin.getStore(clusterName, Version.parseStoreFromKafkaTopicName(kafkaTopic));
        if ((!incrementalPushVersion.isPresent() && currentReturnStatus == ExecutionStatus.ERROR) ||
            !store.isIncrementalPushEnabled()) {
            logger.info("Truncating kafka topic: " + kafkaTopic + " with job status: " + currentReturnStatus);
            truncateKafkaTopic(kafkaTopic);
            Optional<Version> version = store.getVersion(Version.parseVersionFromKafkaTopicName(kafkaTopic));
            if (version.isPresent() && version.get().getPushType().isStreamReprocessing()) {
              truncateKafkaTopic(Version.composeStreamReprocessingTopic(store.getName(), version.get().getNumber()));
            }
            currentReturnStatusDetails = Optional.of(currentReturnStatusDetails.orElse("") + "Parent Kafka topic truncated");
          }
        }
    }

    return new OfflinePushStatusInfo(currentReturnStatus, extraInfo, currentReturnStatusDetails, extraDetails);
  }

  /**
   * Queries child clusters for job progress.  Prepends the cluster name to the task ID and provides an aggregate
   * Map of progress for all tasks.
   * @param clusterName
   * @param kafkaTopic
   * @return
   */
  @Override
  public Map<String, Long> getOfflinePushProgress(String clusterName, String kafkaTopic){
    Map<String, ControllerClient> controllerClients = getControllerClientMap(clusterName);
    return getOfflineJobProgress(clusterName, kafkaTopic, controllerClients);
  }

  protected static Map<String, Long> getOfflineJobProgress(String clusterName, String kafkaTopic, Map<String, ControllerClient> controllerClients){
    Map<String, Long> aggregateProgress = new HashMap<>();
    for (Map.Entry<String, ControllerClient> clientEntry : controllerClients.entrySet()){
      String childCluster = clientEntry.getKey();
      ControllerClient client = clientEntry.getValue();
      JobStatusQueryResponse statusResponse = client.queryJobStatus(kafkaTopic);
      if (statusResponse.isError()){
        logger.warn("Failed to query " + childCluster + " for job progress on topic " + kafkaTopic + ".  " + statusResponse.getError());
      } else {
        Map<String, Long> clusterProgress = statusResponse.getPerTaskProgress();
        for (String task : clusterProgress.keySet()){
          aggregateProgress.put(childCluster + "_" + task, clusterProgress.get(task));
        }
      }
    }
    return aggregateProgress;
  }

  @Override
  public String getKafkaBootstrapServers(boolean isSSL) {
    return veniceHelixAdmin.getKafkaBootstrapServers(isSSL);
  }

  @Override
  public boolean isSSLEnabledForPush(String clusterName, String storeName) {
    return veniceHelixAdmin.isSSLEnabledForPush(clusterName, storeName);
  }

  @Override
  public boolean isSslToKafka() {
    return veniceHelixAdmin.isSslToKafka();
  }

  @Override
  public TopicManager getTopicManager() {
    return veniceHelixAdmin.getTopicManager();
  }

  @Override
  public boolean isMasterController(String clusterName) {
    return veniceHelixAdmin.isMasterController(clusterName);
  }

  @Override
  public int calculateNumberOfPartitions(String clusterName, String storeName, long storeSize) {
    return veniceHelixAdmin.calculateNumberOfPartitions(clusterName, storeName, storeSize);
  }

  @Override
  public int getReplicationFactor(String clusterName, String storeName) {
    return veniceHelixAdmin.getReplicationFactor(clusterName, storeName);
  }

  @Override
  public int getDatacenterCount(String clusterName){
    return multiClusterConfigs.getConfigForCluster(clusterName).getChildClusterMap().size();
  }

  @Override
  public List<Replica> getReplicas(String clusterName, String kafkaTopic) {
    throw new VeniceException("getReplicas is not supported!");
  }

  @Override
  public List<Replica> getReplicasOfStorageNode(String clusterName, String instanceId) {
    throw new VeniceException("getReplicasOfStorageNode is not supported!");
  }

  @Override
  public NodeRemovableResult isInstanceRemovable(String clusterName, String instanceId, boolean isFromInstanceView) {
    throw new VeniceException("isInstanceRemovable is not supported!");
  }

  @Override
  public NodeRemovableResult isInstanceRemovable(String clusterName, String helixNodeId, int minActiveReplicas, boolean isInstanceView) {
    throw new VeniceException("isInstanceRemovable is not supported!");
  }

  @Override
  public Instance getMasterController(String clusterName) {
    return veniceHelixAdmin.getMasterController(clusterName);
  }

  @Override
  public void addInstanceToWhitelist(String clusterName, String helixNodeId) {
    throw new VeniceException("addInstanceToWhitelist is not supported!");
  }

  @Override
  public void removeInstanceFromWhiteList(String clusterName, String helixNodeId) {
    throw new VeniceException("removeInstanceFromWhiteList is not supported!");
  }

  @Override
  public Set<String> getWhitelist(String clusterName) {
    throw new VeniceException("getWhitelist is not supported!");
  }

  @Override
  public void killOfflinePush(String clusterName, String kafkaTopic) {
    String storeName = Version.parseStoreFromKafkaTopicName(kafkaTopic);
    if (getStore(clusterName, storeName) == null) {
      throw new VeniceNoStoreException(storeName, clusterName);
    }
    acquireLock(clusterName, storeName);
    try {
      veniceHelixAdmin.checkPreConditionForKillOfflinePush(clusterName, kafkaTopic);
      logger.info("Killing offline push job for topic: " + kafkaTopic + " in cluster: " + clusterName);
      /**
       * When parent controller wants to keep some errored topics, this function won't remove topic,
       * but relying on the next push to clean up this topic if it hasn't been removed by {@link #getOffLineJobStatus}.
       *
       * The reason is that every errored push will call this function.
       */
      if (0 == maxErroredTopicNumToKeep) {
        // Truncate Kafka topic
        logger.info("Truncating topic when kill offline push job, topic: " + kafkaTopic);
        truncateKafkaTopic(kafkaTopic);
        String correspondingStreamReprocessingTopic = Version.composeStreamReprocessingTopicFromVersionTopic(kafkaTopic);
        if (getTopicManager().containsTopicInKafkaZK(correspondingStreamReprocessingTopic)) {
          truncateKafkaTopic(correspondingStreamReprocessingTopic);
        }
      }

      // TODO: Set parent controller's version status (to ERROR, most likely?)

      KillOfflinePushJob killJob = (KillOfflinePushJob) AdminMessageType.KILL_OFFLINE_PUSH_JOB.getNewInstance();
      killJob.clusterName = clusterName;
      killJob.kafkaTopic = kafkaTopic;
      AdminOperation message = new AdminOperation();
      message.operationType = AdminMessageType.KILL_OFFLINE_PUSH_JOB.getValue();
      message.payloadUnion = killJob;

      sendAdminMessageAndWaitForConsumed(clusterName, storeName, message);
    } finally {
      releaseLock(clusterName);
    }
  }

  @Override
  public StorageNodeStatus getStorageNodesStatus(String clusterName, String instanceId) {
    throw new VeniceUnsupportedOperationException("getStorageNodesStatus");
  }

  @Override
  public boolean isStorageNodeNewerOrEqualTo(String clusterName, String instanceId,
                                             StorageNodeStatus oldServerStatus) {
    throw new VeniceUnsupportedOperationException("isStorageNodeNewerOrEqualTo");
  }

  @Override
  public void setDelayedRebalanceTime(String clusterName, long delayedTime) {
    throw new VeniceUnsupportedOperationException("setDelayedRebalanceTime");
  }

  @Override
  public long getDelayedRebalanceTime(String clusterName) {
    throw new VeniceUnsupportedOperationException("getDelayedRebalanceTime");
  }

  public void setAdminConsumerService(String clusterName, AdminConsumerService service){
    veniceHelixAdmin.setAdminConsumerService(clusterName, service);
  }

  @Override
  public void skipAdminMessage(String clusterName, long offset, boolean skipDIV){
    veniceHelixAdmin.skipAdminMessage(clusterName, offset, skipDIV);
  }

  @Override
  public Long getLastSucceedExecutionId(String clustername) {
    return veniceHelixAdmin.getLastSucceedExecutionId(clustername);
  }

  protected Time getTimer() {
    return timer;
  }

  protected void setTimer(Time timer) {
    this.timer = timer;
  }

  @Override
  public Optional<AdminCommandExecutionTracker> getAdminCommandExecutionTracker(String clusterName) {
    if(adminCommandExecutionTrackers.containsKey(clusterName)){
      return Optional.of(adminCommandExecutionTrackers.get(clusterName));
    }else{
      return Optional.empty();
    }
  }

  @Override
  public RoutersClusterConfig getRoutersClusterConfig(String clusterName) {
    throw new VeniceUnsupportedOperationException("getRoutersClusterConfig");
  }

  @Override
  public void updateRoutersClusterConfig(String clusterName, Optional<Boolean> isThrottlingEnable,
      Optional<Boolean> isQuotaRebalancedEnable, Optional<Boolean> isMaxCapaictyProtectionEnabled,
      Optional<Integer> expectedRouterCount) {
    throw new VeniceUnsupportedOperationException("updateRoutersClusterConfig");
  }

  @Override
  public Map<String, String> getAllStorePushStrategyForMigration() {
    return pushStrategyZKAccessor.getAllPushStrategies();
  }

  @Override
  public void setStorePushStrategyForMigration(String voldemortStoreName, String strategy) {
    pushStrategyZKAccessor.setPushStrategy(voldemortStoreName, strategy);
  }

  @Override
  public List<String> getClusterOfStoreInMasterController(String storeName) {
    return veniceHelixAdmin.getClusterOfStoreInMasterController(storeName);
  }

  @Override
  public Pair<String, String> discoverCluster(String storeName) {
    return veniceHelixAdmin.discoverCluster(storeName);
  }

  @Override
  public Map<String, String> findAllBootstrappingVersions(String clusterName) {
    throw new VeniceUnsupportedOperationException("findAllBootstrappingVersions");
  }

  public VeniceWriterFactory getVeniceWriterFactory() {
    return veniceHelixAdmin.getVeniceWriterFactory();
  }

  @Override
  public VeniceControllerConsumerFactory getVeniceConsumerFactory() {
    return veniceHelixAdmin.getVeniceConsumerFactory();
  }

  @Override
  public synchronized void stop(String clusterName) {
    veniceHelixAdmin.stop(clusterName);
    // Close the admin producer for this cluster
    VeniceWriter<byte[], byte[], byte[]> veniceWriter = veniceWriterMap.get(clusterName);
    if (null != veniceWriter) {
      veniceWriter.close();
    }
    asyncSetupEnabledMap.put(clusterName, false);
  }

  @Override
  public void stopVeniceController() {
    veniceHelixAdmin.stopVeniceController();
  }

  @Override
  public synchronized void close() {
    veniceWriterMap.keySet().forEach(this::stop);
    veniceHelixAdmin.close();
    asyncSetupExecutor.shutdownNow();
    try {
      asyncSetupExecutor.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isMasterControllerOfControllerCluster() {
    return veniceHelixAdmin.isMasterControllerOfControllerCluster();
  }

  @Override
  public boolean isTopicTruncated(String kafkaTopicName) {
    return veniceHelixAdmin.isTopicTruncated(kafkaTopicName);
  }

  @Override
  public boolean isTopicTruncatedBasedOnRetention(long retention) {
    return veniceHelixAdmin.isTopicTruncatedBasedOnRetention(retention);
  }

  public boolean truncateKafkaTopic(String kafkaTopicName) {
    return veniceHelixAdmin.truncateKafkaTopic(kafkaTopicName);
  }

  @Override
  public boolean isResourceStillAlive(String resourceName) {
    throw new VeniceException("VeniceParentHelixAdmin#isResourceStillAlive is not supported!");
  }

  public ParentHelixOfflinePushAccessor getOfflinePushAccessor() {
    return offlinePushAccessor;
  }

  /* Used by test only*/
  protected void setOfflinePushAccessor(ParentHelixOfflinePushAccessor offlinePushAccessor) {
    this.offlinePushAccessor = offlinePushAccessor;
  }

  @Override
  public void updateClusterDiscovery(String storeName, String oldCluster, String newCluster) {
    veniceHelixAdmin.updateClusterDiscovery(storeName, oldCluster, newCluster);
  }

  public void sendPushJobStatusMessage(PushJobStatusRecordKey key, PushJobStatusRecordValue value) {
    veniceHelixAdmin.sendPushJobStatusMessage(key, value);
  }

  public void sendPushJobDetails(PushJobStatusRecordKey key, PushJobDetails value) {
    veniceHelixAdmin.sendPushJobDetails(key, value);
  }

  public void writeEndOfPush(String clusterName, String storeName, int versionNumber, boolean alsoWriteStartOfPush) {
    veniceHelixAdmin.writeEndOfPush(clusterName, storeName, versionNumber, alsoWriteStartOfPush);
  }

  @Override
  public boolean whetherEnableBatchPushFromAdmin() {
    /**
     * Batch push to Parent Cluster is always enabled.
     */
    return true;
  }

  public void migrateStore(String srcClusterName, String destClusterName, String storeName) {
    if (srcClusterName.equals(destClusterName)) {
      throw new VeniceException("Source cluster and destination cluster cannot be the same!");
    }

    MigrateStore migrateStore = (MigrateStore) AdminMessageType.MIGRATE_STORE.getNewInstance();
    migrateStore.srcClusterName = srcClusterName;
    migrateStore.destClusterName = destClusterName;
    migrateStore.storeName = storeName;

    // Set src store migration flag
    String srcControllerUrl = this.getMasterController(srcClusterName).getUrl(false);
    ControllerClient srcControllerClient = new ControllerClient(srcClusterName, srcControllerUrl, sslFactory);
    UpdateStoreQueryParams params = new UpdateStoreQueryParams().setStoreMigration(true);
    srcControllerClient.updateStore(storeName, params);

    // Update migration src and dest cluster in storeConfig
    veniceHelixAdmin.setStoreConfigForMigration(storeName, srcClusterName, destClusterName);

    // Trigger store migration operation
    AdminOperation message = new AdminOperation();
    message.operationType = AdminMessageType.MIGRATE_STORE.getValue();
    message.payloadUnion = migrateStore;
    sendAdminMessageAndWaitForConsumed(destClusterName, storeName, message);
  }

  @Override
  public void abortMigration(String srcClusterName, String destClusterName, String storeName) {
    if (srcClusterName.equals(destClusterName)) {
      throw new VeniceException("Source cluster and destination cluster cannot be the same!");
    }

    AbortMigration abortMigration = (AbortMigration) AdminMessageType.ABORT_MIGRATION.getNewInstance();
    abortMigration.srcClusterName = srcClusterName;
    abortMigration.destClusterName = destClusterName;
    abortMigration.storeName = storeName;

    // Trigger store migration operation
    AdminOperation message = new AdminOperation();
    message.operationType = AdminMessageType.ABORT_MIGRATION.getValue();
    message.payloadUnion = abortMigration;
    sendAdminMessageAndWaitForConsumed(srcClusterName, storeName, message);
  }

  public List<String> getChildControllerUrls(String clusterName) {
    return new ArrayList<>(multiClusterConfigs.getConfigForCluster(clusterName).getChildClusterMap().values());
  }

  /**
   * This thread will run in the background and update cluster discovery information when necessary
   */
  private void startStoreMigrationMonitor() {
    Thread thread = new Thread(() -> {
      Map<String, ControllerClient> srcControllerClients = new HashMap<>();
      Map<String, List<ControllerClient>> childControllerClientsMap = new HashMap<>();

      while (true) {
        try {
          Utils.sleep(10000);

          // Get a list of clusters that this controller is responsible for
          List<String> activeClusters = this.multiClusterConfigs.getClusters()
              .stream()
              .filter(cluster -> this.isMasterController(cluster))
              .collect(Collectors.toList());

          // Get a list of stores from storeConfig that are migrating
          List<StoreConfig> allStoreConfigs = veniceHelixAdmin.getStoreConfigRepo().getAllStoreConfigs();
          List<String> migratingStores = allStoreConfigs.stream()
              .filter(storeConfig -> storeConfig.getMigrationSrcCluster() != null)  // Store might be migrating
              .filter(storeConfig -> storeConfig.getMigrationSrcCluster().equals(storeConfig.getCluster())) // Migration not complete
              .filter(storeConfig -> storeConfig.getMigrationDestCluster() != null)
              .filter(storeConfig -> activeClusters.contains(storeConfig.getMigrationDestCluster())) // This controller is eligible for this store
              .map(storeConfig -> storeConfig.getStoreName())
              .collect(Collectors.toList());

          // For each migrating stores, check if store migration is complete.
          // If so, update cluster discovery according to storeConfig
          for (String storeName : migratingStores) {
            StoreConfig storeConfig = veniceHelixAdmin.getStoreConfigRepo().getStoreConfig(storeName).get();
            String srcClusterName = storeConfig.getMigrationSrcCluster();
            String destClusterName = storeConfig.getMigrationDestCluster();
            String clusterDiscovered = storeConfig.getCluster();

            if (clusterDiscovered.equals(destClusterName)) {
              // Migration complete already
              continue;
            }

            List<ControllerClient> childControllerClients = childControllerClientsMap.computeIfAbsent(srcClusterName,
                sCN -> {
                  List<ControllerClient> child_controller_clients = new ArrayList<>();

                  for (String childControllerUrl : this.getChildControllerUrls(sCN)) {
                    ControllerClient childControllerClient = new ControllerClient(sCN, childControllerUrl, sslFactory);
                    child_controller_clients.add(childControllerClient);
                  }

                  return child_controller_clients;
                });


            // Check if latest version is online in each child cluster
            int readyCount = 0;

            for (ControllerClient childController : childControllerClients) {
              String childClusterDiscovered = childController.discoverCluster(storeName).getCluster();
              if (childClusterDiscovered.equals(destClusterName)) {
                // CLuster discovery information has been updated,
                // which means store migration in this particular cluster is successful
                readyCount++;
              }
            }

            if (readyCount == childControllerClients.size()) {
              // All child clusters have completed store migration
              // Finish off store migration in the parent cluster by creating a clone store

              // Get original store properties
              ControllerClient srcControllerClient = srcControllerClients.computeIfAbsent(srcClusterName,
                  src_cluster_name -> new ControllerClient(src_cluster_name,
                      this.getMasterController(src_cluster_name).getUrl(false), sslFactory));
              StoreInfo srcStore = srcControllerClient.getStore(storeName).getStore();
              String srcKeySchema = srcControllerClient.getKeySchema(storeName).getSchemaStr();
              MultiSchemaResponse.Schema[] srcValueSchemasResponse =
                  srcControllerClient.getAllValueSchema(storeName).getSchemas();

              // Finally finish off store migration in parent controller
              veniceHelixAdmin.cloneStore(srcClusterName, destClusterName, srcStore, srcKeySchema,
                  srcValueSchemasResponse);

              logger.info("All child clusters have " + storeName + " cloned store ready in " + destClusterName
                  + ". Will update cluster discovery in parent.");
              veniceHelixAdmin.updateClusterDiscovery(storeName, srcClusterName, destClusterName);
              continue;
            }
          }
        } catch (Exception e) {
          logger.error("Caught exception in store migration monitor", e);
        }
      }
    });

    thread.start();
  }

  /**
   * Check if a version has been lingering around for more than the bootstrap to online timeout because of bugs or other
   * unexpected events.
   * @param store of interest.
   * @param version of interest that may be lingering around.
   * @return true if the provided version is has been lingering around for long enough and is killed, otherwise false.
   */
  private boolean checkLingeringVersion(Store store, Version version) {
    long bootstrapTimeLimit = version.getCreatedTime() + store.getBootstrapToOnlineTimeoutInHours() * Time.MS_PER_HOUR;
    return timer.getMilliseconds() > bootstrapTimeLimit;
  }

  /**
   * Check if etled proxy account is set before enabling any ETL and return a {@link ETLStoreConfigRecord}
   */
  private ETLStoreConfigRecord mergeNewSettingIntoOldETLStoreConfig(Store store,
                                                                    Optional<Boolean> regularVersionETLEnabled,
                                                                    Optional<Boolean> futureVersionETLEnabled,
                                                                    Optional<String> etledUserProxyAccount) {
    ETLStoreConfig etlStoreConfig = store.getEtlStoreConfig();
    /**
     * If etl enabled is true (either current version or future version), then account name must be specified in the command
     * and it's not empty, or the store metadata already contains a non-empty account name.
     */
    if (regularVersionETLEnabled.orElse(false) || futureVersionETLEnabled.orElse(false)) {
      if ((!etledUserProxyAccount.isPresent() || etledUserProxyAccount.get().isEmpty()) &&
          (etlStoreConfig.getEtledUserProxyAccount() == null || etlStoreConfig.getEtledUserProxyAccount().isEmpty())) {
        throw new VeniceException("Cannot enable ETL for this store because etled user proxy account is not set");
      }
    }
    ETLStoreConfigRecord etlStoreConfigRecord = new ETLStoreConfigRecord();
    etlStoreConfigRecord.etledUserProxyAccount = etledUserProxyAccount.orElse(etlStoreConfig.getEtledUserProxyAccount());
    etlStoreConfigRecord.regularVersionETLEnabled = regularVersionETLEnabled.orElse(etlStoreConfig.isRegularVersionETLEnabled());
    etlStoreConfigRecord.futureVersionETLEnabled = futureVersionETLEnabled.orElse(etlStoreConfig.isFutureVersionETLEnabled());
    return etlStoreConfigRecord;
  }
}
