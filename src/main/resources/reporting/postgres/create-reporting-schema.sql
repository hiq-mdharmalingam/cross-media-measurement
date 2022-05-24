-- liquibase formatted sql

-- Copyright 2022 The Cross-Media Measurement Authors
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Postgres database schema for the Reporting server.
--
-- Table hierarchy:
--   Root
--   ├── Reports
--   │   ├── TimeIntervals
--   │   ├── PeriodicTimeIntervals
--   │   ├── Metrics
--   │       └── SetOperations
--   │   └── ReportMeasurements
--   ├── Measurements
--   └── ReportingSets
--       └── ReportingSetEventGroups

-- changeset tristanvuong2021:create-reports-table dbms:postgresql
CREATE TABLE Reports (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportId bigint NOT NULL,

  ExternalReportId bigint NOT NULL,

  -- org.wfanet.measurement.internal.reporting.Report.State
  -- protobuf enum encoded as an integer.
  State smallint NOT NULL,

  -- Serialized org.wfanet.measurement.internal.reporting.Report.Details
  -- protobuf message.
  ReportDetails bytea NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, ReportId),
  UNIQUE (MeasurementConsumerReferenceId, ExternalReportId)
);

-- changeset tristanvuong2021:create-reports-by-external-report-id-index dbms:postgresql
CREATE INDEX ReportsByExternalReportId
  ON Reports(MeasurementConsumerReferenceId, ExternalReportId);

-- changeset tristanvuong2021:create-time-intervals-table dbms:postgresql
CREATE TABLE TimeIntervals (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportId bigint NOT NULL,

  StartSeconds bigint,
  StartNanos integer,

  EndSeconds bigint,
  EndNanos integer,

  FOREIGN KEY(MeasurementConsumerReferenceId, ReportId)
    REFERENCES Reports(MeasurementConsumerReferenceId, ReportId)
);

-- changeset tristanvuong2021:create-periodic-time-intervals-table dbms:postgresql
CREATE TABLE PeriodicTimeIntervals (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportId bigint NOT NULL,

  StartSeconds bigint,
  StartNanos integer,

  IncrementSeconds bigint,
  IncrementNanos integer,

  IntervalCount integer,

  FOREIGN KEY(MeasurementConsumerReferenceId, ReportId)
    REFERENCES Reports(MeasurementConsumerReferenceId, ReportId)
);

-- changeset tristanvuong2021:create-metrics-table dbms:postgresql
CREATE TABLE Metrics (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportId bigint NOT NULL,
  MetricId bigint NOT NULL,

  -- Serialized org.wfanet.measurement.internal.reporting.Metric.Details
  -- protobuf message.
  MetricDetails bytea NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, ReportId, MetricId),
  FOREIGN KEY(MeasurementConsumerReferenceId, ReportId)
    REFERENCES Reports(MeasurementConsumerReferenceId, ReportId)
);

-- changeset tristanvuong2021:create-measurements-table dbms:postgresql
CREATE TABLE Measurements (
  MeasurementConsumerReferenceId text NOT NULL,
  MeasurementReferenceId text NOT NULL,

  -- org.wfanet.measurement.internal.reporting.Report.MeasurementInfo.State
  -- protobuf enum encoded as an integer.
  State smallint NOT NULL,
  -- Serialized org.wfanet.measurement.internal.reporting.Report.MeasurementInfo.Result
  -- protobuf message.
  Result bytea,
  -- Timestamp in UTC.
  UpdateTime timestamp NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, MeasurementReferenceId)
);

-- changeset tristanvuong2021:create-report-measurements-table dbms:postgresql
CREATE TABLE ReportMeasurements (
  MeasurementConsumerReferenceId text NOT NULL,
  MeasurementReferenceId text NOT NULL,
  ReportId bigint NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, MeasurementReferenceId, ReportId),
  FOREIGN KEY(MeasurementConsumerReferenceId, ReportId)
      REFERENCES Reports(MeasurementConsumerReferenceId, ReportId)
);

-- changeset tristanvuong2021:create-reporting-sets-table dbms:postgresql
CREATE TABLE ReportingSets (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportingSetId bigint NOT NULL,

  ExternalReportingSetId bigint NOT NULL,

  Filter text NOT NULL,
  DisplayName text NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, ReportingSetId),
  UNIQUE (MeasurementConsumerReferenceId, ExternalReportingSetId)
);

-- changeset tristanvuong2021:create-reporting-set-event-groups-table dbms:postgresql
CREATE TABLE ReportingSetEventGroups (
  MeasurementConsumerReferenceId text NOT NULL,
  DataProviderReferenceId text NOT NULL,
  EventGroupReferenceId text NOT NULL,
  ReportingSetId bigint NOT NULL,

  PRIMARY KEY(MeasurementConsumerReferenceId, DataProviderReferenceId, EventGroupReferenceId, ReportingSetId),
  FOREIGN KEY(MeasurementConsumerReferenceId, ReportingSetId)
    REFERENCES ReportingSets(MeasurementConsumerReferenceId, ReportingSetId)
);

-- changeset tristanvuong2021:create-reporting-sets-by-external-reporting-set-id-index dbms:postgresql
CREATE INDEX ReportingSetsByExternalReportingSetId
  ON ReportingSets(MeasurementConsumerReferenceId, ExternalReportingSetId);

-- changeset tristanvuong2021:create-set-operations-table dbms:postgresql
CREATE TABLE SetOperations (
  MeasurementConsumerReferenceId text NOT NULL,
  ReportId bigint NOT NULL,
  MetricId bigint NOT NULL,
  SetOperationId bigint NOT NULL,

  -- Whether SetOperation is a root SetOperation of a Metric.
  Root boolean NOT NULL,

  -- org.wfanet.measurement.internal.reporting.Metric.SetOperation.Type
  -- protobuf enum encoded as an integer.
  Type smallint NOT NULL,
  DisplayName text NOT NULL,

  LeftHandSetOperationId bigint,
  RightHandSetOperationId bigint,

  LeftHandReportingSetId bigint,
  RightHandReportingSetId bigint,


  PRIMARY KEY(MeasurementConsumerReferenceId, ReportId, MetricId, SetOperationId),
  FOREIGN KEY(MeasurementConsumerReferenceId, LeftHandReportingSetId)
    REFERENCES ReportingSets(MeasurementConsumerReferenceId, ReportingSetId),
  FOREIGN KEY(MeasurementConsumerReferenceId, RightHandReportingSetId)
    REFERENCES ReportingSets(MeasurementConsumerReferenceId, ReportingSetId),
  FOREIGN KEY(MeasurementConsumerReferenceId, ReportId, MetricId)
    REFERENCES Metrics(MeasurementConsumerReferenceId, ReportId, MetricId)
);
