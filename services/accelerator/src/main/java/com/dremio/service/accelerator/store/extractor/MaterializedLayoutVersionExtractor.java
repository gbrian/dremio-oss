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
package com.dremio.service.accelerator.store.extractor;

import com.dremio.datastore.VersionExtractor;
import com.dremio.service.accelerator.proto.MaterializedLayout;
import com.google.common.base.Optional;

/**
 * Extracts {@link MaterializedLayout} version
 */
public class MaterializedLayoutVersionExtractor implements VersionExtractor<MaterializedLayout> {

  @Override
  public Long getVersion(final MaterializedLayout value) {
    return value.getVersion();
  }

  @Override
  public Long incrementVersion(final MaterializedLayout value) {
    final Long current = value.getVersion();
    value.setVersion(Optional.fromNullable(value.getVersion()).or(-1L) + 1);
    return current;
  }

  @Override
  public void setVersion(final MaterializedLayout value, final Long version) {
    value.setVersion(version);
  }
}
