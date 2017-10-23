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
package com.dremio.sabot.op.join.vhash;

import static com.dremio.sabot.op.common.hashtable.HashTable.BUILD_RECORD_LINK_SIZE;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.calcite.rel.core.JoinRelType;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.record.ExpandableHyperContainer;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.sabot.op.common.hashtable.HashTable;
import com.dremio.sabot.op.common.ht2.PivotDef;
import com.dremio.sabot.op.copier.ConditionalFieldBufferCopier6;
import com.dremio.sabot.op.copier.FieldBufferCopier;
import com.dremio.sabot.op.copier.FieldBufferCopier6;
import com.dremio.sabot.op.join.hash.BuildInfo;
import com.google.common.base.Stopwatch;

import io.netty.buffer.ArrowBuf;
import io.netty.util.internal.PlatformDependent;

public class VectorizedProbe implements AutoCloseable {

  private static final int SHIFT_SIZE = 16;

  public static final int SKIP = -1;

  private final BufferAllocator allocator;
  private final ArrowBuf[] links;
  private final ArrowBuf[] starts;
  private final BitSet[] matches;
  private final int[] matchMaxes;
  private ArrowBuf projectProbeSv2;
  private ArrowBuf projectBuildOffsetBuf;
  private final long projectBuildOffsetAddr;
  private final long probeSv2Addr;

  private final JoinTable table;
  private final List<FieldBufferCopier> buildCopiers;
  private final List<FieldBufferCopier> probeCopiers;
  private final int targetRecordsPerBatch;
  private final boolean projectUnmatchedProbe;
  private final boolean projectUnmatchedBuild;
  private final Stopwatch probeFind2Watch = Stopwatch.createUnstarted();
  private final Stopwatch buildCopyWatch = Stopwatch.createUnstarted();
  private final Stopwatch probeCopyWatch = Stopwatch.createUnstarted();
  private final Stopwatch projectBuildNonMatchesWatch = Stopwatch.createUnstarted();
  private final NullComparator nullMask;

  private ArrowBuf probed;
  private final PivotDef pivot;
  private int remainderBuildSetIndex = -1;
  private int remainderBuildElementIndex = -1;
  private int nextProbeIndex = 0;
  private long remainderBuildCompositeIndex = -1;

  public VectorizedProbe(
      BufferAllocator allocator,
      final ExpandableHyperContainer buildBatch,
      final VectorAccessible probeBatch,
      final List<FieldVector> probeOutputs,
      final List<FieldVector> buildOutputs,
      JoinRelType joinRelType,
      List<BuildInfo> buildInfos,
      List<ArrowBuf> startIndices,
      JoinTable table,
      PivotDef pivot,
      int targetRecordsPerBatch,
      final NullComparator nullMask){

    this.nullMask = nullMask;
    this.pivot = pivot;
    this.allocator = allocator;
    this.table = table;
    this.links = new ArrowBuf[buildInfos.size()];
    this.matches = new BitSet[buildInfos.size()];
    this.matchMaxes = new int[buildInfos.size()];

    for (int i =0; i < links.length; i++) {
      links[i] = buildInfos.get(i).getLinks();
      matches[i] = buildInfos.get(i).getKeyMatchBitVector();
      matchMaxes[i] = buildInfos.get(i).getRecordCount();
    }

    this.starts = new ArrowBuf[startIndices.size()];
    for (int i = 0; i < starts.length; i++) {
      starts[i] = startIndices.get(i);
    }

    this.projectUnmatchedBuild = joinRelType == JoinRelType.RIGHT || joinRelType == JoinRelType.FULL;
    this.projectUnmatchedProbe = joinRelType == JoinRelType.LEFT || joinRelType == JoinRelType.FULL;
    this.targetRecordsPerBatch = targetRecordsPerBatch;
    this.projectProbeSv2 = allocator.buffer(targetRecordsPerBatch * 2);
    this.probeSv2Addr = projectProbeSv2.memoryAddress();
    // first 4 bytes (int) are for batch index and rest 2 bytes are offset within the batch
    this.projectBuildOffsetBuf = allocator.buffer(targetRecordsPerBatch * BUILD_RECORD_LINK_SIZE);
    this.projectBuildOffsetAddr = projectBuildOffsetBuf.memoryAddress();

    if(table.size() > 0){
      this.buildCopiers = projectUnmatchedProbe  ?
          ConditionalFieldBufferCopier6.getFourByteCopiers(VectorContainer.getHyperFieldVectors(buildBatch), buildOutputs) :
          FieldBufferCopier6.getFourByteCopiers(VectorContainer.getHyperFieldVectors(buildBatch), buildOutputs);
    }else {
      this.buildCopiers = Collections.emptyList();
    }

    this.probeCopiers = FieldBufferCopier.getCopiers(VectorContainer.getFieldVectors(probeBatch), probeOutputs);
  }

  /**
   * Find all the probe records that match the hash table.
   */
  private void findMatches(final int records){
    if(probed == null || probed.capacity() < records * 4){
      if(probed != null){
        probed.release();
        probed = null;
      }

      probed = allocator.buffer(records * 4);
    }
    long offsetAddr = probed.memoryAddress();

    this.table.find(offsetAddr, records);
  }

  /**
   * Probe with current batch. If we've run out of space, return a negative
   * record count. If we processed the entire incoming batch, return a positive
   * record count or zero.
   *
   * @return Negative if partial batch complete. Otherwise, all of probe batch
   *         is complete.
   */
  public int probeBatch(final int records) {
    final int targetRecordsPerBatch = this.targetRecordsPerBatch;
    final boolean projectUnmatchedProbe = this.projectUnmatchedProbe;
    final boolean projectUnmatchedBuild = this.projectUnmatchedBuild;
    final BitSet[] matches = this.matches;
    final ArrowBuf[] starts = this.starts;
    final ArrowBuf[] links = this.links;

    // we have two incoming options: we're starting on a new batch or we're picking up an existing batch.
    final int probeMax = records;
    int outputRecords = 0;
    int currentProbeIndex = this.nextProbeIndex;
    long currentCompositeBuildIdx = remainderBuildCompositeIndex;

    if(currentProbeIndex == 0){
      // when this is a new batch, we need to pivot the incoming data and then find all the matches.
      findMatches(records);
    }

    final long foundAddr = this.probed.memoryAddress();
    final long projectBuildOffsetAddr = this.projectBuildOffsetAddr;
    final long probeSv2Addr = this.probeSv2Addr;

    probeFind2Watch.start();
    while (outputRecords < targetRecordsPerBatch && currentProbeIndex < probeMax) {

      // If we don't have a composite index, we're done with the current probe record and need to get another.
      if (currentCompositeBuildIdx == -1) {
        final int indexInBuild = PlatformDependent.getInt(foundAddr + currentProbeIndex * 4);

        if (indexInBuild == -1) { // not a matching key.
          if (projectUnmatchedProbe) {
            PlatformDependent.putShort(probeSv2Addr + outputRecords * 2, (short) currentProbeIndex);
            PlatformDependent.putInt(projectBuildOffsetAddr + outputRecords * BUILD_RECORD_LINK_SIZE, SKIP);
            outputRecords++;
          }
          currentProbeIndex++;
          continue;

        } else { // matching key
          /* The current probe record has a key that matches. Get the index
           * of the first row in the build side that matches the current key
           */
          final long memStart = starts[indexInBuild >> SHIFT_SIZE].memoryAddress() +
              ((indexInBuild) % HashTable.BATCH_SIZE) * BUILD_RECORD_LINK_SIZE;

          currentCompositeBuildIdx = PlatformDependent.getInt(memStart);
          currentCompositeBuildIdx = currentCompositeBuildIdx << SHIFT_SIZE | PlatformDependent.getShort(memStart + 4);
        }
      }

      /* Record in the build side at currentCompositeBuildIdx has a matching record in the probe
       * side. Set the bit corresponding to this index so if we are doing a FULL or RIGHT
       * join we keep track of which records we need to project at the end
       */
      if(projectUnmatchedBuild){
        matches[(int)(currentCompositeBuildIdx >>> SHIFT_SIZE)].set((int)(currentCompositeBuildIdx & HashTable.BATCH_MASK));
      }
      PlatformDependent.putShort(probeSv2Addr + outputRecords * 2, (short) currentProbeIndex);
      final long projectBuildOffsetAddrStar = projectBuildOffsetAddr + outputRecords * BUILD_RECORD_LINK_SIZE;
      PlatformDependent.putInt(projectBuildOffsetAddrStar, (int) (currentCompositeBuildIdx >>> SHIFT_SIZE));
      PlatformDependent.putShort(projectBuildOffsetAddrStar + 4, (short) (currentCompositeBuildIdx & HashTable.BATCH_MASK));
      outputRecords++;

      /* Projected single row from the build side with matching key but there
       * may be more build rows with the same key. Check if that's the case
       */
      final long memStart = links[(int)(currentCompositeBuildIdx >>> SHIFT_SIZE)].memoryAddress() +
          ((int)(currentCompositeBuildIdx & HashTable.BATCH_MASK)) * BUILD_RECORD_LINK_SIZE;

      currentCompositeBuildIdx = PlatformDependent.getInt(memStart);
      if (currentCompositeBuildIdx == -1) {
        /* We only had one row in the build side that matched the current key
         * from the probe side. Drain the next row in the probe side.
         */
        currentProbeIndex++;
      } else {
        // read the rest of the index including offset in batch.
        currentCompositeBuildIdx = currentCompositeBuildIdx << SHIFT_SIZE | PlatformDependent.getShort(memStart + 4);
      }

    }
    probeFind2Watch.stop();

    projectProbe(probeSv2Addr, outputRecords);
    projectBuild(projectBuildOffsetAddr, outputRecords);

    if(outputRecords == targetRecordsPerBatch){ // batch was full
      if(currentProbeIndex < probeMax){
        // we have remaining records to process, need to save our position for when we return.
        this.nextProbeIndex = currentProbeIndex;
        this.remainderBuildCompositeIndex = currentCompositeBuildIdx;
        return -outputRecords;
      }
    }

    // we need to clear the last saved position and tell the driver that we completed consuming the current batch.
    this.nextProbeIndex = 0;
    this.remainderBuildCompositeIndex = -1;
    return outputRecords;
  }

  /**
   * Project any remaining build items that were not matched. Only used when doing a FULL or RIGHT join.
   * @return Negative output if records were output but batch wasn't completed. Positive output if batch was completed.
   */
  public int projectBuildNonMatches() {
    assert projectUnmatchedBuild;
    projectBuildNonMatchesWatch.start();

    final int targetRecordsPerBatch = this.targetRecordsPerBatch;

    int outputRecords = 0;
    int remainderBuildSetIndex = this.remainderBuildSetIndex;
    int nextClearIndex = remainderBuildElementIndex;

    BitSet currentBitset = remainderBuildSetIndex < 0 ? null : matches[remainderBuildSetIndex];

    final long projectBuildOffsetAddr = this.projectBuildOffsetAddr;
    // determine the next set of unmatched bits.
    while(outputRecords < targetRecordsPerBatch) {
      if(nextClearIndex == -1){
        // we need to move to the next bit set since the current one has no more matches.
        remainderBuildSetIndex++;
        if (remainderBuildSetIndex < matches.length) {

          currentBitset = matches[remainderBuildSetIndex];
          nextClearIndex = 0;
        } else {
          // no bitsets left.
          this.remainderBuildSetIndex = matches.length;
          remainderBuildSetIndex = -1;
          break;
        }
      }

      nextClearIndex = currentBitset.nextClearBit(nextClearIndex);
      if(nextClearIndex != -1){
        // the clear bit is only valid if it is within the batch it corresponds to.
        if(nextClearIndex >= matchMaxes[remainderBuildSetIndex]){
          nextClearIndex = -1;
        }else{
          final long projectBuildOffsetAddrStart = projectBuildOffsetAddr + outputRecords * BUILD_RECORD_LINK_SIZE;
          PlatformDependent.putInt(projectBuildOffsetAddrStart, remainderBuildSetIndex);
          PlatformDependent.putShort(projectBuildOffsetAddrStart + 4, (short)(nextClearIndex & HashTable.BATCH_MASK));
          outputRecords++;
          nextClearIndex++;
        }
      }
    }

    projectBuildNonMatchesWatch.stop();

    allocateOnlyProbe(outputRecords);
    projectBuild(projectBuildOffsetAddr, outputRecords);

    this.remainderBuildSetIndex = remainderBuildSetIndex;
    this.remainderBuildElementIndex = nextClearIndex;
    if(remainderBuildElementIndex == -1){
      return outputRecords;
    } else {
      return -outputRecords;
    }
  }

  private void allocateOnlyProbe(int records){
    for(FieldBufferCopier c : probeCopiers){
      c.allocate(records);
    }
  }

  /**
   * Project the build data (including keys from the probe)
   * @param offsetAddr
   * @param count
   */
  private void projectBuild(final long offsetAddr, final int count){
    buildCopyWatch.start();
    for(FieldBufferCopier c : buildCopiers){
      c.copy(offsetAddr, count);
    }
    buildCopyWatch.stop();
  }

  /**
   * Project the probe data
   * @param sv4Addr
   * @param count
   */
  private void projectProbe(final long sv4Addr, final int count){
    probeCopyWatch.start();
    for(FieldBufferCopier c : probeCopiers){
      c.copy(sv4Addr, count);
    }
    probeCopyWatch.stop();
  }

  public long getProbeListTime(){
    return probeFind2Watch.elapsed(TimeUnit.NANOSECONDS);
  }

  public long getProbeCopyTime(){
    return probeCopyWatch.elapsed(TimeUnit.NANOSECONDS);
  }

  public long getBuildCopyTime(){
    return buildCopyWatch.elapsed(TimeUnit.NANOSECONDS);
  }

  public long getBuildNonMatchCopyTime(){
    return projectBuildNonMatchesWatch.elapsed(TimeUnit.NANOSECONDS);
  }

  @Override
  public void close() throws Exception {
    try{
      AutoCloseables.close(projectBuildOffsetBuf, projectProbeSv2, probed);
    } finally {
      projectBuildOffsetBuf = null;
      projectProbeSv2 = null;
      probed = null;
    }
  }

}
