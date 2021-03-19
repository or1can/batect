/*
   Copyright 2017-2021 Charles Korn.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package batect.config

import batect.cli.CommandLineOptions
import batect.logging.Logger

class TaskSpecialisedConfigurationFactory(
    private val rawConfiguration: RawConfiguration,
    private val commandLineOptions: CommandLineOptions,
    private val logger: Logger
) {
    fun create(): TaskSpecialisedConfiguration {
        val overrides = commandLineOptions.imageOverrides.mapValues { PullImage(it.value) }
        val taskSpecialisedConfiguration = rawConfiguration.applyImageOverrides(overrides)

        logger.info {
            message("Created task-specialised configuration.")
            data("config", taskSpecialisedConfiguration, TaskSpecialisedConfiguration.serializer())
        }

        return taskSpecialisedConfiguration
    }
}
