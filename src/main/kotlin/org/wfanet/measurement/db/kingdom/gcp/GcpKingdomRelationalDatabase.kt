// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.wfanet.measurement.db.kingdom.gcp

import com.google.cloud.Timestamp
import com.google.cloud.spanner.DatabaseClient
import com.google.cloud.spanner.TimestampBound
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.common.IdGenerator
import org.wfanet.measurement.db.gcp.runReadWriteTransaction
import org.wfanet.measurement.db.kingdom.KingdomRelationalDatabase
import org.wfanet.measurement.db.kingdom.StreamReportsFilter
import org.wfanet.measurement.db.kingdom.StreamRequisitionsFilter
import org.wfanet.measurement.internal.kingdom.Advertiser
import org.wfanet.measurement.internal.kingdom.Campaign
import org.wfanet.measurement.internal.kingdom.DataProvider
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.Report.ReportState
import org.wfanet.measurement.internal.kingdom.ReportConfigSchedule
import org.wfanet.measurement.internal.kingdom.ReportLogEntry
import org.wfanet.measurement.internal.kingdom.Requisition
import org.wfanet.measurement.internal.kingdom.RequisitionTemplate

class GcpKingdomRelationalDatabase(
  clock: Clock,
  idGenerator: IdGenerator,
  lazyClient: () -> DatabaseClient
) : KingdomRelationalDatabase {
  private val client: DatabaseClient by lazy { lazyClient() }
  private val createRequisitionTransaction = CreateRequisitionTransaction(idGenerator)
  private val createNextReportTransaction = CreateNextReportTransaction(clock, idGenerator)
  private val createAdvertiserTransaction = CreateAdvertiserTransaction(idGenerator)
  private val createCampaignTransaction = CreateCampaignTransaction(idGenerator)
  private val createDataProviderTransaction = CreateDataProviderTransaction(idGenerator)

  constructor(
    clock: Clock,
    idGenerator: IdGenerator,
    client: DatabaseClient
  ) : this(clock, idGenerator, { client })

  override suspend fun writeNewRequisition(requisition: Requisition): Requisition {
    val result = client.runReadWriteTransaction { transactionContext ->
      createRequisitionTransaction.execute(transactionContext, requisition)
    }
    return when (result) {
      is CreateRequisitionTransaction.Result.ExistingRequisition -> result.requisition
      is CreateRequisitionTransaction.Result.NewRequisitionId ->
        RequisitionReader()
          .readExternalId(client.singleUse(TimestampBound.strong()), result.externalRequisitionId)
          .requisition
    }
  }

  override suspend fun fulfillRequisition(
    externalRequisitionId: ExternalId,
    duchyId: String
  ): Requisition =
    client.runReadWriteTransaction { transactionContext ->
      FulfillRequisitionTransaction().execute(transactionContext, externalRequisitionId, duchyId)
    }

  override fun streamRequisitions(
    filter: StreamRequisitionsFilter,
    limit: Long
  ): Flow<Requisition> =
    StreamRequisitionsQuery().execute(
      client.singleUse(),
      filter,
      limit
    )

  override fun getReport(externalId: ExternalId): Report =
    GetReportQuery().execute(client.singleUse(), externalId)

  override fun createNextReport(externalScheduleId: ExternalId): Report {
    val runner = client.readWriteTransaction()
    runner.run { transactionContext ->
      createNextReportTransaction.execute(transactionContext, externalScheduleId)
    }
    val commitTimestamp: Timestamp = runner.commitTimestamp

    return ReadLatestReportByScheduleQuery().execute(
      client.singleUse(TimestampBound.ofMinReadTimestamp(commitTimestamp)),
      externalScheduleId
    )
  }

  override fun updateReportState(externalReportId: ExternalId, state: ReportState) =
    client.runReadWriteTransaction { transactionContext ->
      UpdateReportStateTransaction().execute(transactionContext, externalReportId, state)
    }

  override fun streamReports(filter: StreamReportsFilter, limit: Long): Flow<Report> =
    StreamReportsQuery().execute(client.singleUse(), filter, limit)

  override fun streamReadyReports(limit: Long): Flow<Report> =
    StreamReadyReportsQuery().execute(client.singleUse(), limit)

  override fun associateRequisitionToReport(
    externalRequisitionId: ExternalId,
    externalReportId: ExternalId
  ) {
    client.runReadWriteTransaction { transactionContext ->
      AssociateRequisitionAndReportTransaction()
        .execute(transactionContext, externalRequisitionId, externalReportId)
    }
  }

  override fun listRequisitionTemplates(reportConfigId: ExternalId): Iterable<RequisitionTemplate> =
    ReadRequisitionTemplatesQuery().execute(client.singleUse(), reportConfigId)

  override fun streamReadySchedules(limit: Long): Flow<ReportConfigSchedule> =
    StreamReadySchedulesQuery().execute(client.singleUse(), limit)

  override fun addReportLogEntry(reportLogEntry: ReportLogEntry): ReportLogEntry {
    val runner = client.readWriteTransaction()
    runner.run { transactionContext ->
      CreateReportLogEntryTransaction().execute(transactionContext, reportLogEntry)
    }
    val commitTimestamp: Timestamp = runner.commitTimestamp
    return reportLogEntry.toBuilder().apply {
      createTime = commitTimestamp.toProto()
    }.build()
  }

  override suspend fun confirmDuchyReadiness(
    externalReportId: ExternalId,
    duchyId: String,
    externalRequisitionIds: Set<ExternalId>
  ): Report {
    // TODO: this uses two reads that could be collapsed into one.
    val runner = client.readWriteTransaction()
    runner.run { transactionContext ->
      ConfirmDuchyReadinessTransaction()
        .execute(transactionContext, externalReportId, duchyId, externalRequisitionIds)
    }
    val commitTimestamp: Timestamp = runner.commitTimestamp
    val readContext = client.singleUse(TimestampBound.ofMinReadTimestamp(commitTimestamp))
    val reportReadResult = ReportReader().readExternalId(readContext, externalReportId)
    return reportReadResult.report
  }

  override fun createDataProvider(): DataProvider {
    return client.runReadWriteTransaction { transactionContext ->
      createDataProviderTransaction.execute(transactionContext)
    }
  }

  override fun createAdvertiser(): Advertiser {
    return client.runReadWriteTransaction { transactionContext ->
      createAdvertiserTransaction.execute(transactionContext)
    }
  }

  override fun createCampaign(
    externalDataProviderId: ExternalId,
    externalAdvertiserId: ExternalId,
    providedCampaignId: String
  ): Campaign {
    return client.runReadWriteTransaction { transactionContext ->
      createCampaignTransaction.execute(
        transactionContext,
        externalDataProviderId,
        externalAdvertiserId,
        providedCampaignId
      )
    }
  }
}
