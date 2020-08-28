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

import com.google.cloud.spanner.Mutation
import com.google.cloud.spanner.TransactionContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.db.gcp.appendClause
import org.wfanet.measurement.db.gcp.spannerDispatcher
import org.wfanet.measurement.db.gcp.toProtoEnum
import org.wfanet.measurement.internal.kingdom.Report
import org.wfanet.measurement.internal.kingdom.Requisition.RequisitionState

class AssociateRequisitionAndReportTransaction {
  fun execute(
    transactionContext: TransactionContext,
    externalRequisitionId: ExternalId,
    externalReportId: ExternalId
  ) = runBlocking(spannerDispatcher()) {
    val reportDeferred = async {
      ReportReader().readExternalId(transactionContext, externalReportId)
    }
    val requisitionDeferred = async { readRequisition(transactionContext, externalRequisitionId) }

    val reportReadResult = reportDeferred.await()
    val requisitionReadResult = requisitionDeferred.await()

    // This uses an InsertOrUpdate to avoid crashing if it already exists. This can't actually
    // update the row because the entire thing is part of the PK.
    transactionContext.buffer(
      Mutation.newInsertOrUpdateBuilder("ReportRequisitions")
        .set("AdvertiserId").to(reportReadResult.advertiserId)
        .set("ReportConfigId").to(reportReadResult.reportConfigId)
        .set("ScheduleId").to(reportReadResult.scheduleId)
        .set("ReportId").to(reportReadResult.reportId)
        .set("DataProviderId").to(requisitionReadResult.dataProviderId)
        .set("CampaignId").to(requisitionReadResult.campaignId)
        .set("RequisitionId").to(requisitionReadResult.requisitionId)
        .build()
    )

    if (requisitionReadResult.requisition.state == RequisitionState.PERMANENTLY_UNAVAILABLE) {
      transactionContext.buffer(
        Mutation.newUpdateBuilder("Reports")
          .set("AdvertiserId").to(reportReadResult.advertiserId)
          .set("ReportConfigId").to(reportReadResult.reportConfigId)
          .set("ScheduleId").to(reportReadResult.scheduleId)
          .set("ReportId").to(reportReadResult.reportId)
          .set("State").toProtoEnum(Report.ReportState.FAILED)
          .build()
      )
    }
  }

  private suspend fun readRequisition(
    transactionContext: TransactionContext,
    externalRequisitionId: ExternalId
  ): RequisitionReader.Result =
    RequisitionReader()
      .withBuilder {
        appendClause("WHERE Requisitions.ExternalRequisitionId = @external_requisition_id")
        bind("external_requisition_id").to(externalRequisitionId.value)
      }
      .execute(transactionContext)
      .single()
}
