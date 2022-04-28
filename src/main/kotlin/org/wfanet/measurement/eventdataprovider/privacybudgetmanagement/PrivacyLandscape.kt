/**
 * Copyright 2022 The Cross-Media Measurement Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.wfanet.measurement.eventdataprovider.privacybudgetmanagement

import java.lang.Math
import java.time.LocalDate

object PrivacyLandscape {
  val dates: List<LocalDate> = (0..400).map { LocalDate.now().minusDays(it.toLong()) }
  val ageGroups: Set<AgeGroup> = AgeGroup.values().toSet()
  val genders = Gender.values().toSet()
  // There are 300 Vid intervals in the range [0, 1)
  val vids: List<Float> = (0..1).map { (Math.round((it / 300f) * 1000.0) / 1000.0).toFloat() }
}
