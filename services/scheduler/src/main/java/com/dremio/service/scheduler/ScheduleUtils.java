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
package com.dremio.service.scheduler;

import java.util.Collections;
import java.util.Iterator;

import org.threeten.bp.Instant;
import org.threeten.bp.temporal.TemporalAmount;

/**
 *
 * Utility functions for Scheduled tasks
 */
public final class ScheduleUtils {

  /**
   * cancel the list of cancellables
   * @param cancellables
   */
  public static void cancel(Cancellable... cancellables) {
    for (Cancellable cancellable:cancellables) {
      if (cancellable != null && !cancellable.isCancelled()) {
        cancellable.cancel();
      }
    }
  }

  /**
   * Create a schedule to run a task exactly once at the given time.
   *
   * @param timeInMillis time in millis
   * @return schedule with the time in millis
   */
  public static Schedule scheduleForRunningOnceAt(final long timeInMillis) {
    return new Schedule() {
      @Override
      public TemporalAmount getPeriod() {
        return null;
      }

      @Override
      public Iterator<Instant> iterator() {
        return Collections.singletonList(Instant.ofEpochMilli(timeInMillis))
            .iterator();
      }
    };
  }

  private ScheduleUtils() {
  }
}
