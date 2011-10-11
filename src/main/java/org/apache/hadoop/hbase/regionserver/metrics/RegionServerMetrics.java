/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver.metrics;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.metrics.HBaseInfo;
import org.apache.hadoop.hbase.metrics.MetricsRate;
import org.apache.hadoop.hbase.metrics.PersistentMetricsTimeVaryingRate;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Strings;
import org.apache.hadoop.metrics.ContextFactory;
import org.apache.hadoop.metrics.MetricsContext;
import org.apache.hadoop.metrics.MetricsRecord;
import org.apache.hadoop.metrics.MetricsUtil;
import org.apache.hadoop.metrics.Updater;
import org.apache.hadoop.metrics.jvm.JvmMetrics;
import org.apache.hadoop.metrics.util.MetricsIntValue;
import org.apache.hadoop.metrics.util.MetricsLongValue;
import org.apache.hadoop.metrics.util.MetricsRegistry;
import org.apache.hadoop.metrics.util.MetricsTimeVaryingRate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is for maintaining the various regionserver statistics
 * and publishing them through the metrics interfaces.
 * <p>
 * This class has a number of metrics variables that are publicly accessible;
 * these variables (objects) have methods to update their values.
 */
public class RegionServerMetrics implements Updater {
  @SuppressWarnings({"FieldCanBeLocal"})
  private final Log LOG = LogFactory.getLog(this.getClass());
  private final MetricsRecord metricsRecord;
  private long lastUpdate = System.currentTimeMillis();
  private long lastExtUpdate = System.currentTimeMillis();
  private long extendedPeriod = 0;
  private static final int MB = 1024*1024;
  private MetricsRegistry registry = new MetricsRegistry();
  private final RegionServerStatistics statistics;

  public final MetricsTimeVaryingRate atomicIncrementTime =
      new MetricsTimeVaryingRate("atomicIncrementTime", registry);

  /**
   * Count of regions carried by this regionserver
   */
  public final MetricsIntValue regions =
    new MetricsIntValue("regions", registry);

  /**
   * Block cache size.
   */
  public final MetricsLongValue blockCacheSize = new MetricsLongValue("blockCacheSize", registry);

  /**
   * Block cache free size.
   */
  public final MetricsLongValue blockCacheFree = new MetricsLongValue("blockCacheFree", registry);

  /**
   * Block cache item count.
   */
  public final MetricsLongValue blockCacheCount = new MetricsLongValue("blockCacheCount", registry);

  /**
   * Block hit ratio.
   */
  public final MetricsIntValue blockCacheHitRatio = new MetricsIntValue("blockCacheHitRatio", registry);

  /*
   * Count of requests to the regionservers since last call to metrics update
   */
  private final MetricsRate requests = new MetricsRate("requests", registry);

  /**
   * Count of stores open on the regionserver.
   */
  public final MetricsIntValue stores = new MetricsIntValue("stores", registry);

  /**
   * Count of storefiles open on the regionserver.
   */
  public final MetricsIntValue storefiles = new MetricsIntValue("storefiles", registry);

  /**
   * Sum of all the storefile index sizes in this regionserver in MB. This is
   * a legacy metric to be phased out as we fully transition to multi-level
   * block indexes.
   */
  public final MetricsIntValue storefileIndexSizeMB =
    new MetricsIntValue("storefileIndexSizeMB", registry);

  /** The total size of block index root levels in this regionserver in KB. */
  public final MetricsIntValue rootIndexSizeKB =
    new MetricsIntValue("rootIndexSizeKB", registry);

  /** Total size of all block indexes (not necessarily loaded in memory) */
  public final MetricsIntValue totalStaticIndexSizeKB =
    new MetricsIntValue("totalStaticIndexSizeKB", registry);

  /** Total size of all Bloom filters (not necessarily loaded in memory) */
  public final MetricsIntValue totalStaticBloomSizeKB =
    new MetricsIntValue("totalStaticBloomSizeKB", registry);

  /**
   * Sum of all the memstore sizes in this regionserver in MB
   */
  public final MetricsIntValue memstoreSizeMB =
    new MetricsIntValue("memstoreSizeMB", registry);

  /**
   * Size of the compaction queue.
   */
  public final MetricsIntValue compactionQueueSize =
    new MetricsIntValue("compactionQueueSize", registry);

  /**
   * filesystem read latency
   */
  public final MetricsTimeVaryingRate fsReadLatency =
    new MetricsTimeVaryingRate("fsReadLatency", registry);

  /**
   * filesystem write latency
   */
  public final MetricsTimeVaryingRate fsWriteLatency =
    new MetricsTimeVaryingRate("fsWriteLatency", registry);

  /**
   * size (in bytes) of data in HLog append calls
   */
  public final MetricsTimeVaryingRate fsWriteSize =
    new MetricsTimeVaryingRate("fsWriteSize", registry);

  /**
   * filesystem sync latency
   */
  public final MetricsTimeVaryingRate fsSyncLatency =
    new MetricsTimeVaryingRate("fsSyncLatency", registry);

  /**
   * filesystem group sync latency
   */
  public final MetricsTimeVaryingRate fsGroupSyncLatency =
    new MetricsTimeVaryingRate("fsGroupSyncLatency", registry);

  /**
   * Memstore Insert time (in ms).
   */
  public final MetricsTimeVaryingRate memstoreInsertTime =
    new MetricsTimeVaryingRate("memstoreInsert", registry);

  public final MetricsTimeVaryingRate rowLockTime =
    new MetricsTimeVaryingRate("rowLock", registry);

  public final MetricsTimeVaryingRate rwccWaitTime =
    new MetricsTimeVaryingRate("rwccWait", registry);

  /**
   * time each scheduled compaction takes
   */
  protected final PersistentMetricsTimeVaryingRate compactionTime =
    new PersistentMetricsTimeVaryingRate("compactionTime", registry);

  protected final PersistentMetricsTimeVaryingRate compactionSize =
    new PersistentMetricsTimeVaryingRate("compactionSize", registry);

  /**
   * time each scheduled flush takes
   */
  protected final PersistentMetricsTimeVaryingRate flushTime =
    new PersistentMetricsTimeVaryingRate("flushTime", registry);

  protected final PersistentMetricsTimeVaryingRate flushSize =
    new PersistentMetricsTimeVaryingRate("flushSize", registry);

  public RegionServerMetrics() {
    MetricsContext context = MetricsUtil.getContext("hbase");
    metricsRecord = MetricsUtil.createRecord(context, "regionserver");
    String name = Thread.currentThread().getName();
    metricsRecord.setTag("RegionServer", name);
    context.registerUpdater(this);
    // Add jvmmetrics.
    JvmMetrics.init("RegionServer", name);
    // Add Hbase Info metrics
    HBaseInfo.init();

    // export for JMX
    statistics = new RegionServerStatistics(this.registry, name);

    // get custom attributes
    try {
      Object m = ContextFactory.getFactory().getAttribute("hbase.extendedperiod");
      if (m instanceof String) {
        this.extendedPeriod = Long.parseLong((String) m)*1000;
      }
    } catch (IOException ioe) {
      LOG.info("Couldn't load ContextFactory for Metrics config info");
    }

    LOG.info("Initialized");
  }

  public void shutdown() {
    if (statistics != null)
      statistics.shutdown();
  }

  /**
   * Since this object is a registered updater, this method will be called
   * periodically, e.g. every 5 seconds.
   * @param caller the metrics context that this responsible for calling us
   */
  public void doUpdates(MetricsContext caller) {
    synchronized (this) {
      this.lastUpdate = System.currentTimeMillis();

      // has the extended period for long-living stats elapsed?
      if (this.extendedPeriod > 0 &&
          this.lastUpdate - this.lastExtUpdate >= this.extendedPeriod) {
        this.lastExtUpdate = this.lastUpdate;
        this.compactionTime.resetMinMaxAvg();
        this.compactionSize.resetMinMaxAvg();
        this.flushTime.resetMinMaxAvg();
        this.flushSize.resetMinMaxAvg();
        this.resetAllMinMax();
      }

      this.stores.pushMetric(this.metricsRecord);
      this.storefiles.pushMetric(this.metricsRecord);
      this.storefileIndexSizeMB.pushMetric(this.metricsRecord);
      this.rootIndexSizeKB.pushMetric(this.metricsRecord);
      this.totalStaticIndexSizeKB.pushMetric(this.metricsRecord);
      this.totalStaticBloomSizeKB.pushMetric(this.metricsRecord);
      this.memstoreSizeMB.pushMetric(this.metricsRecord);
      this.regions.pushMetric(this.metricsRecord);
      this.requests.pushMetric(this.metricsRecord);
      this.compactionQueueSize.pushMetric(this.metricsRecord);
      this.blockCacheSize.pushMetric(this.metricsRecord);
      this.blockCacheFree.pushMetric(this.metricsRecord);
      this.blockCacheCount.pushMetric(this.metricsRecord);
      this.blockCacheHitRatio.pushMetric(this.metricsRecord);

      // Be careful. Here is code for MTVR from up in hadoop:
      // public synchronized void inc(final int numOps, final long time) {
      //   currentData.numOperations += numOps;
      //   currentData.time += time;
      //   long timePerOps = time/numOps;
      //    minMax.update(timePerOps);
      // }
      // Means you can't pass a numOps of zero or get a ArithmeticException / by zero.
      // HLog metrics
      addHLogMetric(HLog.getWriteTime(), this.fsWriteLatency);
      addHLogMetric(HLog.getWriteSize(), this.fsWriteSize);
      addHLogMetric(HLog.getSyncTime(), this.fsSyncLatency);
      addHLogMetric(HLog.getGSyncTime(), this.fsGroupSyncLatency);
      // HFile metrics
      int ops = HFile.getReadOps();
      if (ops != 0) this.fsReadLatency.inc(ops, HFile.getReadTime());
      /* NOTE: removed HFile write latency.  2 reasons:
       * 1) Mixing HLog latencies are far higher priority since they're
       *      on-demand and HFile is used in background (compact/flush)
       * 2) HFile metrics are being handled at a higher level
       *      by compaction & flush metrics.
       */

      int writeOps = (int)HRegion.getWriteOps();
      if (writeOps != 0) {
        this.memstoreInsertTime.inc(writeOps, HRegion.getMemstoreInsertTime());
        this.rwccWaitTime.inc(writeOps, HRegion.getRWCCWaitTime());
        this.rowLockTime.inc(writeOps, HRegion.getRowLockTime());
      }

      // push the result
      this.fsReadLatency.pushMetric(this.metricsRecord);
      this.fsWriteLatency.pushMetric(this.metricsRecord);
      this.fsWriteSize.pushMetric(this.metricsRecord);
      this.fsSyncLatency.pushMetric(this.metricsRecord);
      this.fsGroupSyncLatency.pushMetric(this.metricsRecord);
      this.memstoreInsertTime.pushMetric(this.metricsRecord);
      this.rowLockTime.pushMetric(this.metricsRecord);
      this.rwccWaitTime.pushMetric(this.metricsRecord);
      this.compactionTime.pushMetric(this.metricsRecord);
      this.compactionSize.pushMetric(this.metricsRecord);
      this.flushTime.pushMetric(this.metricsRecord);
      this.flushSize.pushMetric(this.metricsRecord);
    }
    this.metricsRecord.update();
  }

  private void addHLogMetric(HLog.Metric logMetric,
      MetricsTimeVaryingRate hadoopMetric) {
    if (logMetric.count > 0)
      hadoopMetric.inc(logMetric.min);
    if (logMetric.count > 1)
      hadoopMetric.inc(logMetric.max);
    if (logMetric.count > 2) {
      int ops = logMetric.count - 2;
      hadoopMetric.inc(ops, logMetric.total - logMetric.max - logMetric.min);
    }
  }

  public void resetAllMinMax() {
    this.atomicIncrementTime.resetMinMax();
    this.fsReadLatency.resetMinMax();
    this.fsWriteLatency.resetMinMax();
    this.fsWriteSize.resetMinMax();
    this.fsSyncLatency.resetMinMax();
    this.fsGroupSyncLatency.resetMinMax();
    this.memstoreInsertTime.resetMinMax();
    this.rowLockTime.resetMinMax();
    this.rwccWaitTime.resetMinMax();
  }

  /**
   * @return Count of requests.
   */
  public float getRequests() {
    return this.requests.getPreviousIntervalValue();
  }

  /**
   * @param time time that compaction took
   * @param size bytesize of storefiles in the compaction
   */
  public synchronized void addCompaction(long time, long size) {
    this.compactionTime.inc(time);
    this.compactionSize.inc(size);
  }

  /**
   * @param flushes history in <time, size>
   */
  public synchronized void addFlush(final List<Pair<Long,Long>> flushes) {
    for (Pair<Long,Long> f : flushes) {
      this.flushTime.inc(f.getFirst());
      this.flushSize.inc(f.getSecond());
    }
  }

  /**
   * @param inc How much to add to requests.
   */
  public void incrementRequests(final int inc) {
    this.requests.inc(inc);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    int seconds = (int)((System.currentTimeMillis() - this.lastUpdate)/1000);
    if (seconds == 0) {
      seconds = 1;
    }
    sb = Strings.appendKeyValue(sb, "request",
      Float.valueOf(this.requests.getPreviousIntervalValue()));
    sb = Strings.appendKeyValue(sb, "regions",
      Integer.valueOf(this.regions.get()));
    sb = Strings.appendKeyValue(sb, "stores",
      Integer.valueOf(this.stores.get()));
    sb = Strings.appendKeyValue(sb, "storefiles",
      Integer.valueOf(this.storefiles.get()));
    sb = Strings.appendKeyValue(sb, "storefileIndexSize",
      Integer.valueOf(this.storefileIndexSizeMB.get()));
    sb = Strings.appendKeyValue(sb, "rootIndexSizeKB",
        Integer.valueOf(this.rootIndexSizeKB.get()));
    sb = Strings.appendKeyValue(sb, "totalStaticIndexSizeKB",
        Integer.valueOf(this.totalStaticIndexSizeKB.get()));
    sb = Strings.appendKeyValue(sb, "totalStaticBloomSizeKB",
        Integer.valueOf(this.totalStaticBloomSizeKB.get()));
    sb = Strings.appendKeyValue(sb, "memstoreSize",
      Integer.valueOf(this.memstoreSizeMB.get()));
    sb = Strings.appendKeyValue(sb, "compactionQueueSize",
      Integer.valueOf(this.compactionQueueSize.get()));
    // Duplicate from jvmmetrics because metrics are private there so
    // inaccessible.
    MemoryUsage memory =
      ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    sb = Strings.appendKeyValue(sb, "usedHeap",
      Long.valueOf(memory.getUsed()/MB));
    sb = Strings.appendKeyValue(sb, "maxHeap",
      Long.valueOf(memory.getMax()/MB));
    sb = Strings.appendKeyValue(sb, this.blockCacheSize.getName(),
        Long.valueOf(this.blockCacheSize.get()));
    sb = Strings.appendKeyValue(sb, this.blockCacheFree.getName(),
        Long.valueOf(this.blockCacheFree.get()));
    sb = Strings.appendKeyValue(sb, this.blockCacheCount.getName(),
        Long.valueOf(this.blockCacheCount.get()));
    sb = Strings.appendKeyValue(sb, this.blockCacheHitRatio.getName(),
        Long.valueOf(this.blockCacheHitRatio.get()));
    return sb.toString();
  }
}
