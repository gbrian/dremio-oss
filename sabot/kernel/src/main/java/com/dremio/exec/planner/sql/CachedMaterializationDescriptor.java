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
package com.dremio.exec.planner.sql;

import org.apache.calcite.plan.CopyWithCluster;
import org.apache.calcite.plan.RelOptCluster;

import com.google.common.base.Preconditions;


/**
 * Cached implementation that deserializes the rel tree once, then copies over the materialization
 * to the target {@link RelOptCluster}
 */
public class CachedMaterializationDescriptor extends MaterializationDescriptor {

  private final DremioRelOptMaterialization materialization;

  public CachedMaterializationDescriptor(MaterializationDescriptor descriptor, DremioRelOptMaterialization materialization) {
    super(descriptor.getAccelerationId(),
          descriptor.getLayoutInfo(),
          descriptor.getMaterializationId(),
          descriptor.getUpdateId(),
          descriptor.getExpirationTimestamp(),
          descriptor.getPlan(),
          descriptor.getPath(),
          descriptor.getOriginalCost(),
          descriptor.getIncrementalUpdateSettings(),
          descriptor.isComplete());
    this.materialization = Preconditions.checkNotNull(materialization, "materialization is required");
  }

  @Override
  public DremioRelOptMaterialization getMaterializationFor(SqlConverter converter) {
    final CopyWithCluster copier = new CopyWithCluster(converter.getCluster());
    final DremioRelOptMaterialization copied = materialization.accept(copier);
    copier.validate();
    return copied;
  }

  public DremioRelOptMaterialization getMaterialization() {
    return materialization;
  }
}
