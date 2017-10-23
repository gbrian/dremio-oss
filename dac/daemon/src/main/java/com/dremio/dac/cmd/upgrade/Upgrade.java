/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.cmd.upgrade;

import static com.dremio.dac.util.ClusterVersionUtils.fromClusterVersion;
import static com.dremio.dac.util.ClusterVersionUtils.toClusterVersion;

import java.io.File;

import javax.inject.Provider;

import com.dremio.common.Version;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.common.util.DremioVersionInfo;
import com.dremio.config.DremioConfig;
import com.dremio.dac.cmd.upgrade.namespace_canonicalize_keys.NormalizeNamespace;
import com.dremio.dac.cmd.upgrade.namespace_canonicalize_keys.ValidateNamespace;
import com.dremio.dac.proto.model.source.ClusterIdentity;
import com.dremio.dac.server.DACConfig;
import com.dremio.dac.support.SupportService;
import com.dremio.dac.support.SupportService.SupportStoreCreator;
import com.dremio.datastore.KVStore;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.LocalKVStoreProvider;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Ordering;

/**
 * Upgrade command.<br>
 * Extracts store version and uses it to decide if upgrade is possible and which tasks should be executed.
 * If no version is found, the tool assumes it's 1.0.6 as there is no way to identify versions prior to that anyway
 */
public class Upgrade {

  private static final UpgradeTask[] tasks = {
    new ValidateNamespace(),
    new SetLayoutVersion(),
    new FixAccelerationId(),
    new FixHiveMetadata(),
    new NormalizeNamespace(),
    new ReIndexStores(),
    new FixMySqlSourceConfig(),
    new ReplanAllAccelerations(),
    new FixAccelerationVersion(),
    new SetDatasetExpiry(),
    new SetAccelerationRefreshGrace(),
    new ReplanHomeBasedAccelerations()
  };

  private static Version retrieveStoreVersion(ClusterIdentity identity) {
    final Version storeVersion = fromClusterVersion(identity.getVersion());
    return storeVersion != null ? storeVersion : UpgradeTask.VERSION_106;
  }

  private static void updateStoreVersion(KVStore<String, ClusterIdentity> supportStore, ClusterIdentity identity) {
    identity.setVersion(toClusterVersion(DremioVersionInfo.VERSION));
    try {
      supportStore.put(SupportService.CLUSTER_ID, identity);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to update store version", e);
    }
  }

  private static void ensureUpgradeSupported(Version storeVersion) {
    //retrieve minimal KVStore version supported by the upgrade tool
    final Ordering<Version> versionOrdering = Ordering.natural();
    final Version minSupportedVersion = versionOrdering.min(
      FluentIterable.of(tasks).transform(UpgradeTask.TASK_MIN_VERSION));
    if (storeVersion.compareTo(minSupportedVersion) < 0) {
      throw new UnsupportedOperationException("Cannot run upgrade tool on versions below " + minSupportedVersion.getVersion());
    }
  }

  public static UpgradeStats upgrade(DACConfig dacConfig) throws Exception {
    final String dbDir = dacConfig.getConfig().getString(DremioConfig.DB_PATH_STRING);
    final File dbFile = new File(dbDir);

    if (!dbFile.exists()) {
      System.out.println("No database found. Skipping upgrade");
      return new UpgradeStats();
    }

    final SabotConfig sabotConfig = dacConfig.getConfig().getSabotConfig();
    final ScanResult classpathScan = ClassPathScanner.fromPrescan(sabotConfig);
    try (final KVStoreProvider storeProvider = new LocalKVStoreProvider(classpathScan, dbDir, false, true, false, true)) {
      storeProvider.start();

      final KVStore<String, ClusterIdentity> supportStore = storeProvider.getStore(SupportStoreCreator.class);
      final ClusterIdentity identity = Preconditions.checkNotNull(supportStore.get(SupportService.CLUSTER_ID), "No Cluster Identity found");

      final Version kvStoreVersion = retrieveStoreVersion(identity);
      System.out.println("KVStore version is " + kvStoreVersion.getVersion());
      ensureUpgradeSupported(kvStoreVersion);


      final LogicalPlanPersistence lpPersistence = new LogicalPlanPersistence(sabotConfig, classpathScan);
      final UpgradeContext context = new UpgradeContext(new Provider<KVStoreProvider>() {
        @Override
        public KVStoreProvider get() {
          return storeProvider;
        }
      }, lpPersistence);
      for (UpgradeTask task : tasks) {
        if (kvStoreVersion.compareTo(task.getMaxVersion()) < 0) {
          System.out.println(task);
          task.upgrade(context);
        } else {
          System.out.println("Skipping " + task);
        }
        // let exceptions propagate to main()
      }

      updateStoreVersion(supportStore, identity);

      return context.getUpgradeStats();
    }
  }

  public static void main(String[] args) {
    final DACConfig dacConfig = DACConfig.newConfig();
    try {
      UpgradeStats upgradeStats = upgrade(dacConfig);
      System.out.println(upgradeStats);
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("Upgrade failed " + e);
      System.exit(-1);
    }
  }
}
