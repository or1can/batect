/*
    Copyright 2017-2022 Charles Korn.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package batect.cli

import batect.cli.commands.completion.Shell
import batect.cli.options.OptionGroup
import batect.cli.options.OptionParser
import batect.cli.options.OptionParserContainer
import batect.cli.options.OptionValueSource
import batect.cli.options.OptionsParsingResult
import batect.cli.options.ValueConverters
import batect.cli.options.defaultvalues.EnvironmentVariableDefaultValueProviderFactory
import batect.cli.options.defaultvalues.FileDefaultValueProvider
import batect.docker.DockerHttpConfigDefaults
import batect.dockerclient.DockerCLIContext
import batect.execution.CacheType
import batect.os.PathResolverFactory
import batect.os.SystemInfo
import batect.ui.OutputStyle
import okio.Path.Companion.toOkioPath
import java.nio.file.Path
import java.nio.file.Paths

class CommandLineOptionsParser(
    pathResolverFactory: PathResolverFactory,
    environmentVariableDefaultValueProviderFactory: EnvironmentVariableDefaultValueProviderFactory,
    dockerHttpConfigDefaults: DockerHttpConfigDefaults,
    systemInfo: SystemInfo,
    private val activeDockerContextResolver: ActiveDockerContextResolver = DockerCLIContext.Companion::getSelectedCLIContext,
) : OptionParserContainer {
    override val optionParser: OptionParser = OptionParser()

    companion object {
        const val disableCleanupAfterFailureFlagName = "no-cleanup-after-failure"
        const val disableCleanupAfterSuccessFlagName = "no-cleanup-after-success"
        const val disableCleanupFlagName = "no-cleanup"
        const val upgradeFlagName = "upgrade"
        const val configVariableOptionName = "config-var"
        const val permanentlyDisableTelemetryFlagName = "permanently-disable-telemetry"
        const val permanentlyEnableTelemetryFlagName = "permanently-enable-telemetry"
        const val enableBuildKitFlagName = "enable-buildkit"
        const val enableBuildKitEnvironmentVariableName = "DOCKER_BUILDKIT"
        const val imageTagsOptionName = "tag-image"
        const val helpBlurb = "For documentation and further information on Batect, visit https://github.com/batect/batect."
    }

    private val cacheOptionsGroup = OptionGroup("Cache management options")
    private val dockerConnectionOptionsGroup = OptionGroup("Docker connection options")
    private val executionOptionsGroup = OptionGroup("Execution options")
    private val outputOptionsGroup = OptionGroup("Output options")
    private val helpOptionsGroup = OptionGroup("Help options")
    private val telemetryOptionsGroup = OptionGroup("Telemetry options")
    private val hiddenOptionsGroup = OptionGroup("Hidden options")

    private val showHelp: Boolean by flagOption(helpOptionsGroup, "help", "Show this help information and exit.", 'h')
    private val showVersionInfo: Boolean by flagOption(helpOptionsGroup, "version", "Show Batect version information and exit.")
    private val runUpgrade: Boolean by flagOption(helpOptionsGroup, upgradeFlagName, "Upgrade Batect to the latest available version.")
    private val listTasks: Boolean by flagOption(helpOptionsGroup, "list-tasks", "List available tasks and exit.", 'T')
    private val runCleanup: Boolean by flagOption(cacheOptionsGroup, "clean", "Cleanup caches created on previous runs and exit.")

    private val disableColorOutput: Boolean by flagOption(outputOptionsGroup, "no-color", "Disable colored output from Batect. Does not affect task command output. Implies --output=simple unless overridden.")
    private val disableUpdateNotification: Boolean by flagOption(executionOptionsGroup, "no-update-notification", "Disable checking for updates to Batect and notifying you when a new version is available.")
    private val disableWrapperCacheCleanup: Boolean by flagOption(executionOptionsGroup, "no-wrapper-cache-cleanup", "Disable cleaning up downloaded versions of Batect that have not been used recently.")
    private val disablePortMappings: Boolean by flagOption(executionOptionsGroup, "disable-ports", "Disable binding of ports on the host.")
    private val existingNetworkToUse: String? by valueOption(executionOptionsGroup, "use-network", "Existing Docker network to use for all tasks. If not set, a new network is created for each task.")
    private val skipPrerequisites: Boolean by flagOption(executionOptionsGroup, "skip-prerequisites", "Don't run prerequisites for the named task.")
    private val maximumLevelOfParallelism: Int? by valueOption(executionOptionsGroup, "max-parallelism", "Maximum number of setup or cleanup steps to run in parallel when running a task", ValueConverters.positiveInteger)

    private val configurationFileName: Path by valueOption(
        executionOptionsGroup,
        "config-file",
        "The configuration file to use.",
        Paths.get("batect.yml"),
        ValueConverters.pathToFile(pathResolverFactory, mustExist = true),
        'f',
    )

    private val configVariablesSourceFileNameOption = valueOption(
        executionOptionsGroup,
        "config-vars-file",
        "YAML file containing values for config variables. Values in file take precedence over default values.",
        FileDefaultValueProvider("batect.local.yml", pathResolverFactory),
        ValueConverters.pathToFile(pathResolverFactory, mustExist = true),
    )

    private val configVariablesSourceFileName: Path? by configVariablesSourceFileNameOption

    private val configVariableOverrides: Map<String, String> by singleValueMapOption(
        executionOptionsGroup,
        configVariableOptionName,
        "Set a value for a config variable. Takes precedence over default values and values in file provided to ${configVariablesSourceFileNameOption.longOption}.",
    )

    private val imageOverrides: Map<String, String> by singleValueMapOption(
        executionOptionsGroup,
        "override-image",
        "Override the image used by a container.",
        "<container>=<image>",
    )

    private val imageTags: Map<String, Set<String>> by multiValueMapOption(
        executionOptionsGroup,
        imageTagsOptionName,
        "Tag the image built by a container during task execution.",
        "<container>=<image>",
    )

    private val logFileName: Path? by valueOption(
        helpOptionsGroup,
        "log-file",
        "Write internal Batect logs to file.",
        ValueConverters.pathToFile(pathResolverFactory),
    )

    private val requestedOutputStyle: OutputStyle? by valueOption<OutputStyle?, OutputStyle>(
        outputOptionsGroup,
        "output",
        "Force a particular style of output from Batect. Does not affect task command output. Valid values are: fancy (default value if your console supports this), simple (no updating text), all (interleaved output from all containers) or quiet (only error messages).",
        null,
        ValueConverters.enum(),
        'o',
    )

    private val disableCleanupAfterFailure: Boolean by flagOption(
        executionOptionsGroup,
        disableCleanupAfterFailureFlagName,
        "If an error occurs before any task can start, leave all containers created for that task running so that the issue can be investigated.",
    )
    private val disableCleanupAfterSuccess: Boolean by flagOption(executionOptionsGroup, disableCleanupAfterSuccessFlagName, "If the main task succeeds, leave all containers created for that task running.")
    private val disableCleanup: Boolean by flagOption(executionOptionsGroup, disableCleanupFlagName, "Equivalent to providing both --$disableCleanupAfterFailureFlagName and --$disableCleanupAfterSuccessFlagName.")
    private val dontPropagateProxyEnvironmentVariables: Boolean by flagOption(executionOptionsGroup, "no-proxy-vars", "Don't propagate proxy-related environment variables such as http_proxy and no_proxy to image builds or containers.")

    private val cacheType: CacheType by valueOption(
        cacheOptionsGroup,
        "cache-type",
        "Storage mechanism to use for caches. Valid values are: 'volume' (use Docker volumes) or 'directory' (use directories mounted from the host). Ignored for Windows containers (directory mounts are always used).",
        environmentVariableDefaultValueProviderFactory.create("BATECT_CACHE_TYPE", CacheType.Volume, CacheType.Volume.name.lowercase(), ValueConverters.enum()),
        ValueConverters.enum(),
    )

    val dockerHostOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-host",
        "Docker host to use, in the format 'unix:///var/run/docker.sock', 'npipe:////./pipe/docker_engine' or 'tcp://1.2.3.4:5678'.",
        environmentVariableDefaultValueProviderFactory.create("DOCKER_HOST", dockerHttpConfigDefaults.defaultDockerHost, ValueConverters.string),
    )

    private val dockerHost: String by dockerHostOption

    val dockerContextOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-context",
        "Docker CLI context to use.",
        environmentVariableDefaultValueProviderFactory.create("DOCKER_CONTEXT", null, "the active Docker context", ValueConverters.string),
        ValueConverters.string,
    )

    private val dockerContext: String? by dockerContextOption

    private val dockerUseTLSOption = flagOption(
        dockerConnectionOptionsGroup,
        "docker-tls",
        "Use TLS when communicating with the Docker host, but don't verify the certificate presented by the Docker host.",
    )

    private val dockerUseTLS: Boolean by dockerUseTLSOption

    private val dockerVerifyTLSOption = flagOption(
        dockerConnectionOptionsGroup,
        "docker-tls-verify",
        "Use TLS when communicating with the Docker host and verify the certificate presented by the Docker host. Implies ${dockerUseTLSOption.longOption}.",
        environmentVariableDefaultValueProviderFactory.create("DOCKER_TLS_VERIFY", false, ValueConverters.boolean),
    )

    private val dockerVerifyTLS: Boolean by dockerVerifyTLSOption

    private val defaultDockerConfigDirectory = systemInfo.homeDirectory.resolve(".docker")

    private val dockerConfigDirectoryOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-config",
        "Path to directory containing Docker client configuration files.",
        environmentVariableDefaultValueProviderFactory.create("DOCKER_CONFIG", defaultDockerConfigDirectory, ValueConverters.pathToDirectory(pathResolverFactory, mustExist = true)),
        ValueConverters.pathToDirectory(pathResolverFactory, mustExist = true),
    )

    private val dockerConfigDirectory: Path by dockerConfigDirectoryOption

    private val dockerCertificateDirectoryOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-cert-path",
        "Path to directory containing certificates to use to provide authentication to the Docker host and authenticate the Docker host. Has no effect if ${dockerUseTLSOption.longOption} is not set, and takes precedence over ${dockerConfigDirectoryOption.longOption}.",
        environmentVariableDefaultValueProviderFactory.create("DOCKER_CERT_PATH", defaultDockerConfigDirectory, ValueConverters.pathToDirectory(pathResolverFactory, mustExist = true)),
        ValueConverters.pathToDirectory(pathResolverFactory, mustExist = true),
    )

    private val dockerCertificateDirectory: Path by dockerCertificateDirectoryOption

    private val dockerTLSCACertificatePathOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-tls-ca-cert",
        "Path to TLS CA certificate file that should be used to verify certificates presented by the Docker host. Has no effect if ${dockerVerifyTLSOption.longOption} is not set, and takes precedence over ${dockerCertificateDirectoryOption.longOption}.",
        defaultDockerConfigDirectory.resolve("ca.pem"),
        ValueConverters.pathToFile(pathResolverFactory, mustExist = true),
    )

    private val dockerTlsCACertificatePath: Path by dockerTLSCACertificatePathOption

    private val dockerTLSCertificatePathOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-tls-cert",
        "Path to TLS certificate file to use to authenticate to the Docker host. Has no effect if ${dockerUseTLSOption.longOption} is not set, and takes precedence over ${dockerCertificateDirectoryOption.longOption}.",
        defaultDockerConfigDirectory.resolve("cert.pem"),
        ValueConverters.pathToFile(pathResolverFactory, mustExist = true),
    )

    private val dockerTLSCertificatePath: Path by dockerTLSCertificatePathOption

    private val dockerTLSKeyPathOption = valueOption(
        dockerConnectionOptionsGroup,
        "docker-tls-key",
        "Path to TLS key file to use to authenticate to the Docker host. Has no effect if ${dockerUseTLSOption.longOption} is not set, and takes precedence over ${dockerCertificateDirectoryOption.longOption}.",
        defaultDockerConfigDirectory.resolve("key.pem"),
        ValueConverters.pathToFile(pathResolverFactory, mustExist = true),
    )

    private val dockerTLSKeyPath: Path by dockerTLSKeyPathOption

    private val enableBuildKit: Boolean? by tristateFlagOption(
        dockerConnectionOptionsGroup,
        enableBuildKitFlagName,
        "Use BuildKit for image builds.",
        environmentVariableDefaultValueProviderFactory.create(enableBuildKitEnvironmentVariableName, null, ValueConverters.boolean),
    )

    private val permanentlyDisableTelemetry: Boolean by flagOption(telemetryOptionsGroup, permanentlyDisableTelemetryFlagName, "Permanently disable telemetry collection and uploading, and remove any telemetry data queued for upload.")
    private val permanentlyEnableTelemetry: Boolean by flagOption(telemetryOptionsGroup, permanentlyEnableTelemetryFlagName, "Permanently enable telemetry collection and uploading.")

    private val disableTelemetry: Boolean? by tristateFlagOption(
        telemetryOptionsGroup,
        "no-telemetry",
        "Disable telemetry for this command line invocation.",
        environmentVariableDefaultValueProviderFactory.create("BATECT_ENABLE_TELEMETRY", null, ValueConverters.invertingBoolean),
    )

    private val generateShellTabCompletionScript: Shell? by valueOption(hiddenOptionsGroup, "generate-completion-script", "Generate shell tab completion script for given shell.", ValueConverters.enum<Shell>(), showInHelp = false)
    private val generateShellTabCompletionTaskInformation: Shell? by valueOption(
        hiddenOptionsGroup,
        "generate-completion-task-info",
        "Generate shell tab completion task information for given shell.",
        ValueConverters.enum<Shell>(),
        showInHelp = false,
    )

    private val cleanCaches: Set<String> by setOption(
        group = cacheOptionsGroup,
        longName = "clean-cache",
        description = "Clean given cache(s) and exit.",
    )

    fun parse(args: Iterable<String>): CommandLineOptionsParsingResult {
        return when (val result = optionParser.parseOptions(args)) {
            is OptionsParsingResult.InvalidOptions -> CommandLineOptionsParsingResult.Failed(result.message)
            is OptionsParsingResult.ReadOptions -> {
                when (val error = validate()) {
                    null -> parseTaskName(args.drop(result.argumentsConsumed))
                    else -> error
                }
            }
        }
    }

    private fun validate(): CommandLineOptionsParsingResult.Failed? {
        if (requestedOutputStyle == OutputStyle.Fancy && disableColorOutput) {
            return CommandLineOptionsParsingResult.Failed("Fancy output mode cannot be used when color output has been disabled.")
        }

        val taggedAndOverriddenImages = imageTags.keys.intersect(imageOverrides.keys)

        if (taggedAndOverriddenImages.isNotEmpty()) {
            return CommandLineOptionsParsingResult.Failed("Cannot both tag the built image for container '${taggedAndOverriddenImages.first()}' and also override the image for that container.")
        }

        if (dockerContextOption.valueSource == OptionValueSource.CommandLine) {
            val forbiddenOptionsWithDockerContext = setOf(
                dockerHostOption,
                dockerUseTLSOption,
                dockerVerifyTLSOption,
                dockerTLSCACertificatePathOption,
                dockerTLSCertificatePathOption,
                dockerTLSKeyPathOption,
                dockerCertificateDirectoryOption,
            )

            forbiddenOptionsWithDockerContext.forEach {
                if (it.valueSource == OptionValueSource.CommandLine) {
                    return CommandLineOptionsParsingResult.Failed("Cannot use both ${dockerContextOption.longOption} and ${it.longOption}.")
                }
            }
        }

        return null
    }

    private fun parseTaskName(remainingArgs: Iterable<String>): CommandLineOptionsParsingResult {
        if (
            showHelp ||
            showVersionInfo ||
            listTasks ||
            runUpgrade ||
            runCleanup ||
            permanentlyDisableTelemetry ||
            permanentlyEnableTelemetry ||
            generateShellTabCompletionScript != null ||
            generateShellTabCompletionTaskInformation != null ||
            cleanCaches.isNotEmpty()
        ) {
            return CommandLineOptionsParsingResult.Succeeded(createOptionsObject(null, emptyList()))
        }

        when (remainingArgs.count()) {
            0 -> return CommandLineOptionsParsingResult.Failed(
                "No task name provided. Re-run Batect and provide a task name, for example, './batect build'.\n" +
                    "Run './batect --list-tasks' for a list of all tasks in this project, or './batect --help' for help.",
            )
            1 -> {
                return CommandLineOptionsParsingResult.Succeeded(createOptionsObject(remainingArgs.first(), emptyList()))
            }
            else -> {
                val taskName = remainingArgs.first()
                val additionalArgs = remainingArgs.drop(1)

                if (additionalArgs.first() != "--") {
                    return CommandLineOptionsParsingResult.Failed(
                        "Too many arguments provided. The task name must be the last argument, with all Batect options appearing before the task name.\n" +
                            "'$taskName' was selected as the task name, and the first extra argument is '${additionalArgs.first()}'.\n" +
                            "To pass additional arguments to the task command, separate them from the task name with '--', for example, './batect my-task -- --log-level debug'.",
                    )
                }

                val additionalTaskCommandArguments = additionalArgs.drop(1)
                return CommandLineOptionsParsingResult.Succeeded(createOptionsObject(taskName, additionalTaskCommandArguments))
            }
        }
    }

    private fun resolvePathToDockerCertificate(path: Path, valueSource: OptionValueSource, defaultFileName: String): Path = when {
        valueSource == OptionValueSource.CommandLine -> path
        dockerCertificateDirectoryOption.valueSource != OptionValueSource.Default -> dockerCertificateDirectory.resolve(defaultFileName)
        dockerConfigDirectoryOption.valueSource != OptionValueSource.Default -> dockerConfigDirectory.resolve(defaultFileName)
        else -> path
    }

    private fun resolveDockerContext(): String {
        if (dockerContextOption.valueSource == OptionValueSource.CommandLine) {
            return dockerContext!!
        }

        if (dockerHostOption.valueSource != OptionValueSource.Default) {
            return DockerCLIContext.default.name
        }

        if (dockerContext != null) {
            return dockerContext!!
        }

        return activeDockerContextResolver(dockerConfigDirectory.toOkioPath()).name
    }

    private fun createOptionsObject(taskName: String?, additionalTaskCommandArguments: Iterable<String>) = CommandLineOptions(
        showHelp = showHelp,
        showVersionInfo = showVersionInfo,
        runUpgrade = runUpgrade,
        runCleanup = runCleanup,
        listTasks = listTasks,
        permanentlyDisableTelemetry = permanentlyDisableTelemetry,
        permanentlyEnableTelemetry = permanentlyEnableTelemetry,
        configurationFileName = configurationFileName,
        configVariablesSourceFile = configVariablesSourceFileName,
        imageOverrides = imageOverrides,
        imageTags = imageTags,
        logFileName = logFileName,
        requestedOutputStyle = requestedOutputStyle,
        disableColorOutput = disableColorOutput,
        disableUpdateNotification = disableUpdateNotification,
        disableWrapperCacheCleanup = disableWrapperCacheCleanup,
        disableCleanupAfterFailure = disableCleanupAfterFailure || disableCleanup,
        disableCleanupAfterSuccess = disableCleanupAfterSuccess || disableCleanup,
        disablePortMappings = disablePortMappings,
        dontPropagateProxyEnvironmentVariables = dontPropagateProxyEnvironmentVariables,
        taskName = taskName,
        additionalTaskCommandArguments = additionalTaskCommandArguments,
        configVariableOverrides = configVariableOverrides,
        docker = DockerCommandLineOptions(
            contextName = resolveDockerContext(),
            host = dockerHost,
            useTLS = dockerUseTLS || dockerVerifyTLS,
            verifyTLS = dockerVerifyTLS,
            tlsKeyPath = resolvePathToDockerCertificate(dockerTLSKeyPath, dockerTLSKeyPathOption.valueSource, "key.pem"),
            tlsCertificatePath = resolvePathToDockerCertificate(dockerTLSCertificatePath, dockerTLSCertificatePathOption.valueSource, "cert.pem"),
            tlsCACertificatePath = resolvePathToDockerCertificate(dockerTlsCACertificatePath, dockerTLSCACertificatePathOption.valueSource, "ca.pem"),
            configDirectory = dockerConfigDirectory,
        ),
        cacheType = cacheType,
        existingNetworkToUse = existingNetworkToUse,
        skipPrerequisites = skipPrerequisites,
        disableTelemetry = disableTelemetry,
        enableBuildKit = enableBuildKit,
        generateShellTabCompletionScript = generateShellTabCompletionScript,
        generateShellTabCompletionTaskInformation = generateShellTabCompletionTaskInformation,
        maximumLevelOfParallelism = maximumLevelOfParallelism,
        cleanCaches = cleanCaches,
    )
}

sealed class CommandLineOptionsParsingResult {
    data class Succeeded(val options: CommandLineOptions) : CommandLineOptionsParsingResult()
    data class Failed(val message: String) : CommandLineOptionsParsingResult()
}

typealias ActiveDockerContextResolver = (okio.Path) -> DockerCLIContext
