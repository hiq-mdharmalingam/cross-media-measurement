// Copyright 2021 The Cross-Media Measurement Authors
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

package org.wfanet.measurement.loadtest.dataprovider

import kotlin.properties.Delegates
import org.wfanet.measurement.common.commandLineMain
import org.wfanet.measurement.storage.forwarded.ForwardedStorageFromFlags
import picocli.CommandLine

@CommandLine.Command(
  name = "ForwardStorageEdpSimulatorRunner",
  description = ["EdpSimulator Daemon"],
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
/** Implementation of [EdpSimulator] using ForwardStorage. */
class ForwardStorageEdpSimulatorRunner : EdpSimulatorRunner() {
  @CommandLine.Mixin private lateinit var forwardedStorageFlags: ForwardedStorageFromFlags.Flags

  @set:CommandLine.Option(
    names = ["--edp-sketch-reach"],
    description = ["The reach for sketches generated by this EDP Simulator"],
    defaultValue = "10000"
  )
  var edpSketchReach by Delegates.notNull<Int>()
    private set

  @set:CommandLine.Option(
    names = ["--edp-sketch-universe-size"],
    description = ["The size of the universe for sketches generated by this EDP Simulator"],
    defaultValue = "1000000"
  )
  var edpUniverseSize by Delegates.notNull<Int>()
    private set

  override fun run() {
    // val randomEventQuery =
    //   RandomEventQuery(
    //     SketchGenerationParams(reach = edpSketchReach, universeSize = edpUniverseSize)
    //   )
    val csvEventQuery = CsvEventQuery(flags.dataProviderDisplayName)
    run(
      ForwardedStorageFromFlags(forwardedStorageFlags, flags.tlsFlags).storageClient,
      csvEventQuery
    )
  }
}

fun main(args: Array<String>) = commandLineMain(ForwardStorageEdpSimulatorRunner(), args)
