/*
 * MIT License
 *
 * Copyright (c) 2020 Dataline
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.dataline.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataline.config.DestinationConnectionImplementation;
import io.dataline.config.JobCheckConnectionConfig;
import io.dataline.config.JobConfig;
import io.dataline.config.JobDiscoverSchemaConfig;
import io.dataline.config.JobOutput;
import io.dataline.config.JobSyncConfig;
import io.dataline.config.SourceConnectionImplementation;
import io.dataline.config.StandardSync;
import io.dataline.config.StandardSyncOutput;
import io.dataline.config.StandardSyncSummary;
import io.dataline.config.State;
import io.dataline.db.DatabaseHelper;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.dbcp2.BasicDataSource;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSchedulerPersistence implements SchedulerPersistence {
  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSchedulerPersistence.class);
  private final BasicDataSource connectionPool;

  public DefaultSchedulerPersistence(BasicDataSource connectionPool) {
    this.connectionPool = connectionPool;
  }

  @Override
  public long createSourceCheckConnectionJob(SourceConnectionImplementation sourceImplementation)
      throws IOException {
    final String scope =
        "checkConnection:source:" + sourceImplementation.getSourceImplementationId();

    final JobCheckConnectionConfig jobCheckConnectionConfig = new JobCheckConnectionConfig();
    jobCheckConnectionConfig.setConnectionConfiguration(sourceImplementation.getConfiguration());
    jobCheckConnectionConfig.setDockerImage(
        IntegrationConstants.SPEC_ID_TO_IMPL
            .get(sourceImplementation.getSourceSpecificationId())
            .getCheckConnection());

    final JobConfig jobConfig = new JobConfig();
    jobConfig.setConfigType(JobConfig.ConfigType.CHECK_CONNECTION);
    jobConfig.setCheckConnection(jobCheckConnectionConfig);

    return createPendingJob(scope, jobConfig);
  }

  @Override
  public long createDestinationCheckConnectionJob(
      DestinationConnectionImplementation destinationImplementation) throws IOException {
    final String scope =
        "checkConnection:destination:" + destinationImplementation.getDestinationImplementationId();

    final JobCheckConnectionConfig jobCheckConnectionConfig = new JobCheckConnectionConfig();
    jobCheckConnectionConfig.setConnectionConfiguration(
        destinationImplementation.getConfiguration());
    jobCheckConnectionConfig.setDockerImage(
        IntegrationConstants.SPEC_ID_TO_IMPL
            .get(destinationImplementation.getDestinationSpecificationId())
            .getCheckConnection());

    final JobConfig jobConfig = new JobConfig();
    jobConfig.setConfigType(JobConfig.ConfigType.CHECK_CONNECTION);
    jobConfig.setCheckConnection(jobCheckConnectionConfig);

    return createPendingJob(scope, jobConfig);
  }

  @Override
  public long createDiscoverSchemaJob(SourceConnectionImplementation sourceImplementation)
      throws IOException {

    final String scope = "discoverSchema:" + sourceImplementation.getSourceImplementationId();

    final JobDiscoverSchemaConfig jobDiscoverSchemaConfig = new JobDiscoverSchemaConfig();
    jobDiscoverSchemaConfig.setConnectionConfiguration(sourceImplementation.getConfiguration());
    jobDiscoverSchemaConfig.setDockerImage(
        IntegrationConstants.SPEC_ID_TO_IMPL
            .get(sourceImplementation.getSourceSpecificationId())
            .getDiscoverSchema());

    final JobConfig jobConfig = new JobConfig();
    jobConfig.setConfigType(JobConfig.ConfigType.DISCOVER_SCHEMA);
    jobConfig.setDiscoverSchema(jobDiscoverSchemaConfig);

    return createPendingJob(scope, jobConfig);
  }

  @Override
  public long createSyncJob(
      SourceConnectionImplementation sourceImplementation,
      DestinationConnectionImplementation destinationImplementation,
      StandardSync standardSync)
      throws IOException {
    final UUID connectionId = standardSync.getConnectionId();

    final String scope = "sync:" + connectionId;

    final JobSyncConfig jobSyncConfig = new JobSyncConfig();
    jobSyncConfig.setSourceConnectionImplementation(sourceImplementation);
    jobSyncConfig.setSourceDockerImage(
        IntegrationConstants.SPEC_ID_TO_IMPL
            .get(sourceImplementation.getSourceSpecificationId())
            .getSync());
    jobSyncConfig.setDestinationConnectionImplementation(destinationImplementation);
    jobSyncConfig.setDestinationDockerImage(
        IntegrationConstants.SPEC_ID_TO_IMPL
            .get(destinationImplementation.getDestinationSpecificationId())
            .getSync());
    jobSyncConfig.setStandardSync(standardSync);

    final Optional<Job> previousJobOptional = JobUtils.getLastSyncJobForConnectionId(connectionPool, connectionId);
    final Optional<StandardSyncOutput> standardSyncOutput =
        previousJobOptional.flatMap(Job::getOutput).map(JobOutput::getSync);

    standardSyncOutput
        .map(StandardSyncOutput::getStandardSyncSummary)
        .ifPresent(jobSyncConfig::setStandardSyncSummary);
    standardSyncOutput.map(StandardSyncOutput::getState).ifPresent(jobSyncConfig::setState);

    final JobConfig jobConfig = new JobConfig();
    jobConfig.setConfigType(JobConfig.ConfigType.SYNC);
    jobConfig.setSync(jobSyncConfig);
    return createPendingJob(scope, jobConfig);
  }

  // configJson is a oneOf checkConnection, discoverSchema, sync
  public long createPendingJob(String scope, JobConfig jobConfig) throws IOException {
    LOGGER.info("creating pending job for scope: " + scope);
    LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);

    final ObjectMapper objectMapper = new ObjectMapper();
    final String configJson = objectMapper.writeValueAsString(jobConfig);

    final Record record;
    try {
      record =
          DatabaseHelper.query(
                  connectionPool,
                  ctx ->
                      ctx.fetch(
                          "INSERT INTO jobs(scope, created_at, updated_at, status, config, output, stdout_path, stderr_path) VALUES(?, ?, ?, CAST(? AS JOB_STATUS), CAST(? as JSONB), ?, ?, ?) RETURNING id",
                          scope,
                          now,
                          now,
                          JobStatus.PENDING.toString().toLowerCase(),
                          configJson,
                          null,
                          JobLogs.getLogDirectory(scope),
                          JobLogs.getLogDirectory(scope)))
              .stream()
              .findFirst()
              .orElseThrow(() -> new RuntimeException("This should not happen"));
    } catch (SQLException e) {
      LOGGER.error("sql", e);
      throw new IOException(e);
    }
    return record.getValue("id", Long.class);
  }

  @Override
  public Job getJob(long jobId) throws IOException {
    try {
      return DatabaseHelper.query(
          connectionPool,
          ctx -> {
            Record jobEntry =
                ctx.fetch("SELECT * FROM jobs WHERE id = ?", jobId).stream()
                    .findFirst()
                    .orElseThrow(
                        () -> new RuntimeException("Could not find job with id: " + jobId));

            return getJobFromRecord(jobEntry);
          });
    } catch (SQLException e) {
      throw new IOException(e);
    }
  }

  public static Job getJobFromRecord(Record jobEntry) {
    final ObjectMapper objectMapper = new ObjectMapper();

    final JobConfig jobConfig;
    try {
      jobConfig = objectMapper.readValue(jobEntry.get("config", String.class), JobConfig.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    final JobOutput output;
    try {
      final String outputDb = jobEntry.get("output", String.class);
      if (outputDb == null) {
        output = null;
      } else {
        output = objectMapper.readValue(outputDb, JobOutput.class);
      }

    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return new Job(
        jobEntry.get("id", Long.class),
        jobEntry.getValue("scope", String.class),
        JobStatus.valueOf(jobEntry.getValue("status", String.class).toUpperCase()),
        jobConfig,
        output,
        jobEntry.get("stdout_path", String.class),
        jobEntry.get("stderr_path", String.class),
        getEpoch(jobEntry, "created_at"),
        Optional.ofNullable(jobEntry.get("started_at"))
            .map(value -> getEpoch(jobEntry, "started_at"))
            .orElse(null),
        getEpoch(jobEntry, "updated_at"));
  }

  private static long getEpoch(Record record, String fieldName) {
    return record.getValue(fieldName, LocalDateTime.class).toEpochSecond(ZoneOffset.UTC);
  }
}