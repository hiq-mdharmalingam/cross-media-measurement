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

import com.google.cloud.spanner.ReadContext
import com.google.cloud.spanner.Statement
import com.google.cloud.spanner.Struct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.db.gcp.appendClause
import org.wfanet.measurement.db.gcp.asFlow

/**
 * Abstraction for reading rows from Spanner and translating into more expressive objects.
 */
abstract class SpannerReader<T : Any> {
  protected abstract val baseSql: String
  protected abstract val externalIdColumn: String

  protected abstract suspend fun translate(struct: Struct): T

  val builder: Statement.Builder by lazy {
    Statement.newBuilder(baseSql)
  }

  fun withBuilder(block: Statement.Builder.() -> Unit): SpannerReader<T> {
    builder.block()
    return this
  }

  fun execute(readContext: ReadContext): Flow<T> =
    readContext.executeQuery(builder.build()).asFlow().map(::translate)

  suspend fun readExternalIdOrNull(readContext: ReadContext, externalId: ExternalId): T? {
    return this
      .withBuilder {
        appendClause("WHERE $externalIdColumn = @external_id LIMIT 1")
        bind("external_id").to(externalId.value)
      }
      .execute(readContext)
      .singleOrNull()
  }

  suspend fun readExternalId(readContext: ReadContext, externalId: ExternalId): T {
    return requireNotNull(readExternalIdOrNull(readContext, externalId)) {
      "Not found: $externalId"
    }
  }
}
