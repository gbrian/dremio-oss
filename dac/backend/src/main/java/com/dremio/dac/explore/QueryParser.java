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
package com.dremio.dac.explore;

import static com.dremio.common.perf.Timer.time;

import java.security.AccessControlException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.perf.Timer.TimedBlock;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.PhysicalPlan;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.RootSchemaFinder;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.observer.AbstractAttemptObserver;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.planner.sql.SqlExceptionHelper;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.query.NormalHandler;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.exec.proto.UserProtos.UserProperties;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.work.foreman.ExecutionPlan;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.jobs.metadata.QueryMetadata;
import com.dremio.service.jobs.metadata.QueryMetadata.Builder;
import com.google.common.base.Throwables;

/**
 * Query Parsing capabilities
 */
public final class QueryParser {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(QueryParser.class);
  private static final long ID_MAJOR = 3221355214L;
  private static final AtomicLong ID_MINOR = new AtomicLong();

  private final SabotContext sabotContext;

  private QueryParser(SabotContext sabotContext) {
    this.sabotContext = sabotContext;
  }

  // We should try to avoid parsing the query more than once in a request, there is one remaining
  // use of this outside of test code that we should get rid of. Marking deprecated to avoid accumulating
  // new uses of this method.
  @Deprecated
  public static QueryMetadata extract(SqlQuery query, SabotContext context){
    QueryParser parser = new QueryParser(context);
    return parser.extract(query);
  }

  private QueryContext newQueryContext(SqlQuery query) {
    try (TimedBlock b = time("initParser")) {
      QueryId queryId = QueryId.newBuilder().setPart1(ID_MAJOR).setPart2(ID_MINOR.incrementAndGet()).build();
      UserSession session = UserSession.Builder.newBuilder()
          .withCredentials(UserCredentials.newBuilder()
              .setUserName(query.getUsername())
              .build())
          .withUserProperties(UserProperties.getDefaultInstance())
          .withOptionManager(sabotContext.getOptionManager())
          .build();
      return new QueryContext(session, sabotContext, queryId);
    }
  }

  private SqlConverter getNewConverter(QueryContext context, SqlQuery query, AttemptObserver observerForSubstitution) {

    SchemaPlus defaultSchema = context.getRootSchema();
    final List<String> sqlContext = query.getContext();
    if(sqlContext != null){
      for(String schema : sqlContext){
        defaultSchema = defaultSchema.getSubSchema(schema);
        if(defaultSchema == null){
          throw UserException.validationError()
            .message("Unable to resolve schema path [%s]. Failure resolving [%s] portion of path.", sqlContext, schema)
            .build(logger);
        }
      }
    }

    return new SqlConverter(
        context.getPlannerSettings(),
        defaultSchema,
        context.getOperatorTable(),
        context,
        context.getMaterializationProvider(),
        context.getFunctionRegistry(),
        context.getSession(),
        observerForSubstitution,
        context.getStorage(),
        context.getSubstitutionProviderFactory());
  }

  /**
   * @param query the query to parse
   * @return view fields and ancestors
   */
  private QueryMetadata extract(SqlQuery query) {

    try{
      // inner try to make sure query context is closed.
      try(QueryContext context = newQueryContext(query)) {

        QueryMetadata.Builder builder = QueryMetadata.builder(sabotContext.getNamespaceService(query.getUsername()));
        AttemptObserver observer = new MetadataCollectingObserver(builder);

        final SqlConverter converter = getNewConverter(context, query, observer);
        final SqlNode sqlNode = parseQueryInternal(converter, query.getSql());
        final SqlHandlerConfig config = new SqlHandlerConfig(context, converter, observer, null);
        NormalHandler handler = new NormalHandler();
        PhysicalPlan pp = handler.getPlan(config, query.getSql(), sqlNode);
        builder.addBatchSchema(pp.getRoot().getSchema(sabotContext.getFunctionImplementationRegistry()));
        return builder.build();

      } catch(ValidationException e) {
        throw SqlExceptionHelper.validationError(query.getSql(), e)
          .build(logger);
      } catch (AccessControlException e) {
        throw UserException
          .permissionError(e)
          .build(logger);
      } catch(SqlUnsupportedException e) {
        throw UserException.unsupportedError(e)
          .build(logger);
      } catch (RelConversionException e) {
        throw new RuntimeException("Failure handling SQL.", e);
      }
    }catch(Exception ex){
      throw Throwables.propagate(ex);
    }

  }

  private SqlNode parseQueryInternal(SqlConverter converter, String sql) {
    try (TimedBlock b = time("parse")) {
      return converter.parse(sql);
    }
  }

  private class MetadataCollectingObserver extends AbstractAttemptObserver {
    private final QueryMetadata.Builder builder;

    public MetadataCollectingObserver(Builder builder) {
      super();
      this.builder = builder;
    }

    @Override
    public void planCompleted(ExecutionPlan plan) {
      if (plan != null) {
        try {
          builder.addBatchSchema(RootSchemaFinder.getSchema(plan.getRootOperator(),
              sabotContext.getFunctionImplementationRegistry()));
        } catch (Exception e) {
          throw new RuntimeException("Failure in finding schema", e);
        }
      }
    }

    @Override
    public void planRelTransform(PlannerPhase phase, RelOptPlanner planner, RelNode before, RelNode after, long millisTaken) {
      switch(phase){
      case JOIN_PLANNING:
        builder.addPreJoinPlan(before);
        break;
      case LOGICAL:
        builder.addLogicalPlan(before);
        // Always use metadataQuery from the cluster (do not use calcite's default CALCITE_INSTANCE)
        final RelOptCost cost = before.getCluster().getMetadataQuery().getCumulativeCost(after);
        // set final pre-accelerated cost
        builder.addCost(cost);
        break;
      default:
        // noop.
      }
    }

    @Override
    public void planParallelized(final PlanningSet planningSet) {
      builder.setPlanningSet(planningSet);
    }

    @Override
    public void planValidated(RelDataType rowType, SqlNode node, long millisTaken) {
      builder.addRowType(rowType);
      builder.addParsedSql(node);
    }

  }
}
