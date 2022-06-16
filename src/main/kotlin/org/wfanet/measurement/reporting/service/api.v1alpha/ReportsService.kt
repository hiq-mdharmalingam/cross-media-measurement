// Copyright 2022 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.reporting.service.api.v1alpha

import io.grpc.Status
import kotlin.math.min
import kotlinx.coroutines.flow.toList
import org.wfanet.measurement.api.v2.alpha.ListReportsPageToken
import org.wfanet.measurement.api.v2.alpha.copy
import org.wfanet.measurement.api.v2.alpha.listReportsPageToken
import org.wfanet.measurement.api.v2alpha.MeasurementConsumerKey
import org.wfanet.measurement.api.v2alpha.principalFromCurrentContext
import org.wfanet.measurement.common.base64UrlDecode
import org.wfanet.measurement.common.grpc.failGrpc
import org.wfanet.measurement.common.grpc.grpcRequire
import org.wfanet.measurement.common.grpc.grpcRequireNotNull
import org.wfanet.measurement.common.identity.externalIdToApiId
import org.wfanet.measurement.internal.reporting.Metric as InternalMetric
import org.wfanet.measurement.internal.reporting.PeriodicTimeInterval as InternalPeriodicTimeInterval
import org.wfanet.measurement.internal.reporting.Report as InternalReport
import org.wfanet.measurement.internal.reporting.ReportsGrpcKt.ReportsCoroutineStub
import org.wfanet.measurement.internal.reporting.StreamReportsRequest
import org.wfanet.measurement.internal.reporting.StreamReportsRequestKt.filter
import org.wfanet.measurement.internal.reporting.TimeIntervals as InternalTimeIntervals
import org.wfanet.measurement.internal.reporting.streamReportsRequest
import org.wfanet.measurement.reporting.v1alpha.ListReportsRequest
import org.wfanet.measurement.reporting.v1alpha.ListReportsResponse
import org.wfanet.measurement.reporting.v1alpha.Metric
import org.wfanet.measurement.reporting.v1alpha.PeriodicTimeInterval
import org.wfanet.measurement.reporting.v1alpha.Report
import org.wfanet.measurement.reporting.v1alpha.ReportKt.EventGroupUniverseKt.eventGroupEntry
import org.wfanet.measurement.reporting.v1alpha.ReportsGrpcKt.ReportsCoroutineImplBase
import org.wfanet.measurement.reporting.v1alpha.TimeIntervals
import org.wfanet.measurement.reporting.v1alpha.listReportsResponse
import org.wfanet.measurement.reporting.v1alpha.periodicTimeInterval
import org.wfanet.measurement.reporting.v1alpha.report
import org.wfanet.measurement.reporting.v1alpha.timeInterval
import org.wfanet.measurement.reporting.v1alpha.timeIntervals

private const val MIN_PAGE_SIZE = 1
private const val DEFAULT_PAGE_SIZE = 50
private const val MAX_PAGE_SIZE = 1000

class ReportsService(private val internalReportsStub: ReportsCoroutineStub) :
  ReportsCoroutineImplBase() {

  override suspend fun listReports(request: ListReportsRequest): ListReportsResponse {
    val principal = principalFromCurrentContext
    val listReportsPageToken = request.toListReportsPageToken()

    // Based on AIP-132#Errors
    when (val resourceKey = principal.resourceKey) {
      is MeasurementConsumerKey -> {
        if (request.parent != resourceKey.toName()) {
          failGrpc(Status.PERMISSION_DENIED) {
            "Cannot list ReportingSets belonging to other MeasurementConsumers."
          }
        }
      }
      else -> {
        failGrpc(Status.PERMISSION_DENIED) {
          "Caller does not have permission to list ReportingSets."
        }
      }
    }

    val results: List<InternalReport> =
      internalReportsStub.streamReports(listReportsPageToken.toStreamReportsRequest()).toList()

    if (results.isEmpty()) {
      return ListReportsResponse.getDefaultInstance()
    }

    return listReportsResponse {
      reports +=
        results
          .subList(0, min(results.size, listReportsPageToken.pageSize))
          .map(InternalReport::toReport)
    }
  }
}

/** Converts an internal [InternalReport] to a public [Report] */
private fun InternalReport.toReport(): Report {
  val source = this
  return report {
    name =
      ReportKey(
          measurementConsumerId = source.measurementConsumerReferenceId,
          reportId = externalIdToApiId(source.externalReportId)
        )
        .toName()
    reportIdempotencyKey = externalIdToApiId(source.externalReportId)
    measurementConsumer = source.measurementConsumerReferenceId

    for (eventGroupFilter in source.details.eventGroupFiltersMap) {
      eventGroupUniverse.eventGroupEntriesList += eventGroupEntry {
        key = eventGroupFilter.key
        value = eventGroupFilter.value
      }
    }

    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA") // Proto enum fields are never null.
    when (source.timeCase) {
      InternalReport.TimeCase.TIME_INTERVALS ->
        this.timeIntervals = source.timeIntervals.toTimeIntervals()
      InternalReport.TimeCase.PERIODIC_TIME_INTERVAL ->
        this.periodicTimeInterval = source.periodicTimeInterval.toPeriodicTimeInterval()
      InternalReport.TimeCase.TIME_NOT_SET ->
        error("The time in the internal report should be set.")
    }

    for (metric in source.metricsList) {
      this.metrics += metric.toMetric()
    }
  }
}

private fun InternalMetric.toMetric(): Metric {
  val source = this

  when (source.details.metricTypeCase) {
    InternalMetric.Details.MetricTypeCase.REACH -> source.details.reach.toReach()
    InternalMetric.Details.MetricTypeCase.FREQUENCY_HISTOGRAM ->
      source.details.frequencyHistogram.toFrequencyHistogram()
    InternalMetric.Details.MetricTypeCase.IMPRESSION_COUNT ->
      source.details.impressionCount.toImpressionCount()
    InternalMetric.Details.MetricTypeCase.WATCH_DURATION ->
      source.details.watchDuration.toWatchDuration()
  }
}

private fun InternalPeriodicTimeInterval.toPeriodicTimeInterval(): PeriodicTimeInterval {
  val source = this
  return periodicTimeInterval {
    startTime = source.startTime
    increment = source.increment
    intervalCount = source.intervalCount
  }
}

private fun InternalTimeIntervals.toTimeIntervals(): TimeIntervals {
  val source = this
  return timeIntervals {
    for (internalTimeInternal in source.timeIntervalsList) {
      this.timeIntervals += timeInterval {
        startTime = internalTimeInternal.startTime
        endTime = internalTimeInternal.endTime
      }
    }
  }
}

/** Converts an internal [ListReportsPageToken] to an internal [StreamReportsRequest]. */
private fun ListReportsPageToken.toStreamReportsRequest(): StreamReportsRequest {
  val source = this
  return streamReportsRequest {
    // get 1 more than the actual page size for deciding whether or not to set page token
    limit = pageSize + 1
    filter = filter {
      measurementConsumerReferenceId = source.measurementConsumerReferenceId
      externalReportIdAfter = source.lastReport.externalReportId
    }
  }
}

/** Converts a public [ListReportsRequest] to an internal [ListReportsPageToken]. */
private fun ListReportsRequest.toListReportsPageToken(): ListReportsPageToken {
  grpcRequire(pageSize >= 0) { "Page size cannot be less than 0" }

  val source = this
  val parentKey: MeasurementConsumerKey =
    grpcRequireNotNull(MeasurementConsumerKey.fromName(parent)) {
      "Parent is either unspecified or invalid."
    }
  val measurementConsumerReferenceId = parentKey.measurementConsumerId

  return if (pageToken.isNotBlank()) {
    ListReportsPageToken.parseFrom(pageToken.base64UrlDecode()).copy {
      grpcRequire(this.measurementConsumerReferenceId == measurementConsumerReferenceId) {
        "Arguments must be kept the same when using a page token"
      }

      if (
        source.pageSize != 0 && source.pageSize >= MIN_PAGE_SIZE && source.pageSize <= MAX_PAGE_SIZE
      ) {
        pageSize = source.pageSize
      }
    }
  } else {
    listReportsPageToken {
      pageSize =
        when {
          source.pageSize < MIN_PAGE_SIZE -> DEFAULT_PAGE_SIZE
          source.pageSize > MAX_PAGE_SIZE -> MAX_PAGE_SIZE
          else -> source.pageSize
        }
      this.measurementConsumerReferenceId = measurementConsumerReferenceId
    }
  }
}
