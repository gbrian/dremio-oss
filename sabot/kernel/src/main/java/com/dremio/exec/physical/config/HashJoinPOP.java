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

package com.dremio.exec.physical.config;

import java.util.Iterator;
import java.util.List;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.rel.core.JoinRelType;

import com.dremio.common.logical.data.JoinCondition;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.physical.base.AbstractBase;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.PhysicalVisitor;
import com.dremio.exec.proto.UserBitShared.CoreOperatorType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;

@JsonTypeName("hash-join")
public class HashJoinPOP extends AbstractBase {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HashJoinPOP.class);


  private final PhysicalOperator left;
  private final PhysicalOperator right;
  private final List<JoinCondition> conditions;
  private final JoinRelType joinType;
  private final boolean vectorize;

  @JsonCreator
  public HashJoinPOP(
          @JsonProperty("left") PhysicalOperator left,
          @JsonProperty("right") PhysicalOperator right,
          @JsonProperty("conditions") List<JoinCondition> conditions,
          @JsonProperty("joinType") JoinRelType joinType,
          @JsonProperty("vectorize") Boolean vectorize
  ) {
      this.left = left;
      this.right = right;
      this.conditions = conditions;
      Preconditions.checkArgument(joinType != null, "Join type is missing!");
      this.joinType = joinType;
      this.vectorize = vectorize == null ? false : vectorize;
  }

  @Override
  public <T, X, E extends Throwable> T accept(PhysicalVisitor<T, X, E> physicalVisitor, X value) throws E {
      return physicalVisitor.visitHashJoin(this, value);
  }

  @Override
  public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children) {
      Preconditions.checkArgument(children.size() == 2);
      return new HashJoinPOP(children.get(0), children.get(1), conditions, joinType, vectorize);
  }

  @Override
  public Iterator<PhysicalOperator> iterator() {
      return Iterators.forArray(left, right);
  }

  public PhysicalOperator getLeft() {
      return left;
  }

  public PhysicalOperator getRight() {
      return right;
  }

  public JoinRelType getJoinType() {
      return joinType;
  }

  public List<JoinCondition> getConditions() {
      return conditions;
  }

  public boolean isVectorize() {
    return vectorize;
  }

  @Override
  protected BatchSchema constructSchema(FunctionLookupContext context) {
    SchemaBuilder b = BatchSchema.newBuilder();
    for (Field f : getRight().getSchema(context)) {
      b.addField(f);
    }
    for (Field f : getLeft().getSchema(context)) {
      b.addField(f);
    }
    return b.build();
  }

  @Override
  public int getOperatorType() {
    return CoreOperatorType.HASH_JOIN_VALUE;
  }
}
