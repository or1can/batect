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
import batect.cli.options.defaultvalues.EnvironmentVariableDefaultValueProviderFactory
import batect.docker.DockerHttpConfigDefaults
import batect.dockerclient.DockerCLIContext
import batect.execution.CacheType
import batect.os.HostEnvironmentVariables
import batect.os.PathResolutionResult
import batect.os.PathResolver
import batect.os.PathResolverFactory
import batect.os.PathType
import batect.os.SystemInfo
import batect.testutils.equalTo
import batect.testutils.given
import batect.testutils.on
import batect.ui.OutputStyle
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.has
import com.natpryce.hamkrest.isA
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.Suite
import org.spekframework.spek2.style.specification.describe
import java.nio.file.Path

object CommandLineOptionsParserSpec : Spek({
    describe("a command line interface") {
        val fileSystem = Jimfs.newFileSystem(Configuration.unix())

        val pathResolver = mock<PathResolver> {
            on { resolve(any()) } doAnswer { invocation ->
                val originalPath = invocation.getArgument<String>(0)
                val pathType = if (originalPath.contains("dir")) { PathType.Directory } else { PathType.File }
                val resolvedPath = fileSystem.getPath("/resolved", originalPath)

                PathResolutionResult.Resolved(originalPath, resolvedPath, pathType, "resolved to $resolvedPath")
            }
        }

        val pathResolverFactory = mock<PathResolverFactory> {
            on { createResolverForCurrentDirectory() } doReturn pathResolver
        }

        val defaultDockerHost = "http://some-docker-host"
        val dockerHttpConfigDefaults = mock<DockerHttpConfigDefaults> {
            on { this.defaultDockerHost } doReturn defaultDockerHost
        }

        val systemInfo = mock<SystemInfo> {
            on { homeDirectory } doReturn fileSystem.getPath("home-dir")
        }

        fun parse(
            args: List<String>,
            environmentVariables: HostEnvironmentVariables = HostEnvironmentVariables(),
            activeDockerContextResolver: ActiveDockerContextResolver = { DockerCLIContext.default },
        ): CommandLineOptionsParsingResult {
            val parser = CommandLineOptionsParser(
                pathResolverFactory,
                EnvironmentVariableDefaultValueProviderFactory(environmentVariables),
                dockerHttpConfigDefaults,
                systemInfo,
                activeDockerContextResolver,
            )

            return parser.parse(args)
        }

        fun parseAndExpectSuccess(
            args: List<String>,
            environmentVariables: HostEnvironmentVariables = HostEnvironmentVariables(),
            activeDockerContextResolver: ActiveDockerContextResolver = { DockerCLIContext.default },
        ): CommandLineOptions {
            val result = parse(args, environmentVariables, activeDockerContextResolver)
            assertThat(result, isA<CommandLineOptionsParsingResult.Succeeded>())

            return (result as CommandLineOptionsParsingResult.Succeeded).options
        }

        val defaultCommandLineOptions = CommandLineOptions(
            configVariablesSourceFile = fileSystem.getPath("/resolved/batect.local.yml"),
            docker = DockerCommandLineOptions(
                host = defaultDockerHost,
                configDirectory = fileSystem.getPath("home-dir", ".docker"),
                tlsCACertificatePath = fileSystem.getPath("home-dir", ".docker", "ca.pem"),
                tlsCertificatePath = fileSystem.getPath("home-dir", ".docker", "cert.pem"),
                tlsKeyPath = fileSystem.getPath("home-dir", ".docker", "key.pem"),
            ),
        )

        given("no arguments") {
            on("parsing the command line") {
                val result = parse(emptyList())

                it("returns an error message") {
                    assertThat(
                        result,
                        equalTo(
                            CommandLineOptionsParsingResult.Failed(
                                "No task name provided. Re-run Batect and provide a task name, for example, './batect build'.\n" +
                                    "Run './batect --list-tasks' for a list of all tasks in this project, or './batect --help' for help.",
                            ),
                        ),
                    )
                }
            }
        }

        given("a single argument for the task name") {
            on("parsing the command line") {
                val result = parseAndExpectSuccess(listOf("some-task"))

                it("returns a set of options with just the task name populated") {
                    assertThat(
                        result,
                        equalTo(
                            defaultCommandLineOptions.copy(
                                taskName = "some-task",
                            ),
                        ),
                    )
                }
            }
        }

        given("multiple arguments without a '--' prefix") {
            on("parsing the command line") {
                val result = parse(listOf("some-task", "some-extra-arg"))

                it("returns an error message") {
                    assertThat(
                        result,
                        equalTo(
                            CommandLineOptionsParsingResult.Failed(
                                "Too many arguments provided. The task name must be the last argument, with all Batect options appearing before the task name.\n" +
                                    "'some-task' was selected as the task name, and the first extra argument is 'some-extra-arg'.\n" +
                                    "To pass additional arguments to the task command, separate them from the task name with '--', for example, './batect my-task -- --log-level debug'.",
                            ),
                        ),
                    )
                }
            }
        }

        given("multiple arguments with a '--' prefix") {
            on("parsing the command line") {
                val result = parseAndExpectSuccess(listOf("some-task", "--", "some-extra-arg"))

                it("returns a set of options with the task name and additional arguments populated") {
                    assertThat(
                        result,
                        equalTo(
                            defaultCommandLineOptions.copy(
                                taskName = "some-task",
                                additionalTaskCommandArguments = listOf("some-extra-arg"),
                            ),
                        ),
                    )
                }
            }
        }

        given("a flag followed by a single argument") {
            on("parsing the command line") {
                val result = parseAndExpectSuccess(listOf("--no-color", "some-task"))

                it("returns a set of options with the task name populated and the flag set") {
                    assertThat(
                        result,
                        equalTo(
                            defaultCommandLineOptions.copy(
                                disableColorOutput = true,
                                taskName = "some-task",
                            ),
                        ),
                    )
                }
            }
        }

        given("a flag followed by multiple arguments") {
            on("parsing the command line") {
                val result = parse(listOf("--no-color", "some-task", "some-extra-arg"))

                it("returns an error message") {
                    assertThat(
                        result,
                        equalTo(
                            CommandLineOptionsParsingResult.Failed(
                                "Too many arguments provided. The task name must be the last argument, with all Batect options appearing before the task name.\n" +
                                    "'some-task' was selected as the task name, and the first extra argument is 'some-extra-arg'.\n" +
                                    "To pass additional arguments to the task command, separate them from the task name with '--', for example, './batect my-task -- --log-level debug'.",
                            ),
                        ),
                    )
                }
            }
        }

        given("colour output has been disabled and fancy output mode has been selected") {
            on("parsing the command line") {
                val result = parse(listOf("--no-color", "--output=fancy", "some-task", "some-extra-arg"))

                it("returns an error message") {
                    assertThat(result, equalTo(CommandLineOptionsParsingResult.Failed("Fancy output mode cannot be used when color output has been disabled.")))
                }
            }
        }

        given("--tag-image and --override-image are used for the same container") {
            on("parsing the command line") {
                val result = parse(listOf("--tag-image", "some-container=some-container:abc123", "--override-image", "some-container=some-other-container:abc123", "some-task"))

                it("returns an error message") {
                    assertThat(result, equalTo(CommandLineOptionsParsingResult.Failed("Cannot both tag the built image for container 'some-container' and also override the image for that container.")))
                }
            }
        }

        mapOf(
            listOf("--help") to defaultCommandLineOptions.copy(showHelp = true),
            listOf("-h") to defaultCommandLineOptions.copy(showHelp = true),
            listOf("--help", "some-task") to defaultCommandLineOptions.copy(showHelp = true),
            listOf("--version") to defaultCommandLineOptions.copy(showVersionInfo = true),
            listOf("--version", "some-task") to defaultCommandLineOptions.copy(showVersionInfo = true),
            listOf("--list-tasks") to defaultCommandLineOptions.copy(listTasks = true),
            listOf("-T") to defaultCommandLineOptions.copy(listTasks = true),
            listOf("--list-tasks", "some-task") to defaultCommandLineOptions.copy(listTasks = true),
            listOf("--upgrade") to defaultCommandLineOptions.copy(runUpgrade = true),
            listOf("--upgrade", "some-task") to defaultCommandLineOptions.copy(runUpgrade = true),
            listOf("--clean") to defaultCommandLineOptions.copy(runCleanup = true),
            listOf("--clean", "some-task") to defaultCommandLineOptions.copy(runCleanup = true),
            listOf("-f=somefile.yml", "some-task") to defaultCommandLineOptions.copy(configurationFileName = fileSystem.getPath("/resolved/somefile.yml"), taskName = "some-task"),
            listOf("--config-file=somefile.yml", "some-task") to defaultCommandLineOptions.copy(configurationFileName = fileSystem.getPath("/resolved/somefile.yml"), taskName = "some-task"),
            listOf("--config-vars-file=somefile.yml", "some-task") to defaultCommandLineOptions.copy(configVariablesSourceFile = fileSystem.getPath("/resolved/somefile.yml"), taskName = "some-task"),
            listOf("--log-file=somefile.log", "some-task") to defaultCommandLineOptions.copy(logFileName = fileSystem.getPath("/resolved/somefile.log"), taskName = "some-task"),
            listOf("--output=simple", "some-task") to defaultCommandLineOptions.copy(requestedOutputStyle = OutputStyle.Simple, taskName = "some-task"),
            listOf("--output=quiet", "some-task") to defaultCommandLineOptions.copy(requestedOutputStyle = OutputStyle.Quiet, taskName = "some-task"),
            listOf("--output=fancy", "some-task") to defaultCommandLineOptions.copy(requestedOutputStyle = OutputStyle.Fancy, taskName = "some-task"),
            listOf("--no-color", "some-task") to defaultCommandLineOptions.copy(disableColorOutput = true, taskName = "some-task"),
            listOf("--no-update-notification", "some-task") to defaultCommandLineOptions.copy(disableUpdateNotification = true, taskName = "some-task"),
            listOf("--no-wrapper-cache-cleanup", "some-task") to defaultCommandLineOptions.copy(disableWrapperCacheCleanup = true, taskName = "some-task"),
            listOf("--no-cleanup-after-failure", "some-task") to defaultCommandLineOptions.copy(disableCleanupAfterFailure = true, taskName = "some-task"),
            listOf("--no-cleanup-after-success", "some-task") to defaultCommandLineOptions.copy(disableCleanupAfterSuccess = true, taskName = "some-task"),
            listOf("--no-cleanup", "some-task") to defaultCommandLineOptions.copy(disableCleanupAfterFailure = true, disableCleanupAfterSuccess = true, taskName = "some-task"),
            listOf("--no-proxy-vars", "some-task") to defaultCommandLineOptions.copy(dontPropagateProxyEnvironmentVariables = true, taskName = "some-task"),
            listOf("--config-var", "a=b", "--config-var", "c=d", "some-task") to defaultCommandLineOptions.copy(configVariableOverrides = mapOf("a" to "b", "c" to "d"), taskName = "some-task"),
            listOf("--override-image", "container-1=image-1", "--override-image", "container-2=image-2", "some-task") to defaultCommandLineOptions.copy(
                imageOverrides = mapOf("container-1" to "image-1", "container-2" to "image-2"),
                taskName = "some-task",
            ),
            listOf("--docker-host=some-host", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(host = "some-host"), taskName = "some-task"),
            listOf("--docker-context=some-context", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(contextName = "some-context"), taskName = "some-task"),
            listOf("--docker-tls", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(useTLS = true), taskName = "some-task"),
            listOf("--docker-tls-verify", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(useTLS = true, verifyTLS = true), taskName = "some-task"),
            listOf("--docker-tls-ca-cert=some-ca-cert", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(tlsCACertificatePath = fileSystem.getPath("/resolved/some-ca-cert")), taskName = "some-task"),
            listOf("--docker-tls-cert=some-cert", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(tlsCertificatePath = fileSystem.getPath("/resolved/some-cert")), taskName = "some-task"),
            listOf("--docker-tls-key=some-key", "some-task") to defaultCommandLineOptions.copy(docker = defaultCommandLineOptions.docker.copy(tlsKeyPath = fileSystem.getPath("/resolved/some-key")), taskName = "some-task"),
            listOf("--docker-cert-path=some-cert-dir", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-cert-dir/ca.pem"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-cert-dir/cert.pem"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-cert-dir/key.pem"),
                ),
                taskName = "some-task",
            ),
            listOf("--docker-config=some-config-dir", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    configDirectory = fileSystem.getPath("/resolved/some-config-dir"),
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-config-dir/ca.pem"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-config-dir/cert.pem"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-config-dir/key.pem"),
                ),
                taskName = "some-task",
            ),
            listOf("--docker-cert-path=some-cert-dir", "--docker-config=some-config-dir", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    configDirectory = fileSystem.getPath("/resolved/some-config-dir"),
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-cert-dir/ca.pem"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-cert-dir/cert.pem"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-cert-dir/key.pem"),
                ),
                taskName = "some-task",
            ),
            listOf("--docker-cert-path=some-cert-dir", "--docker-tls-ca-cert=some-ca-cert", "--docker-tls-cert=some-cert", "--docker-tls-key=some-key", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-ca-cert"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-cert"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-key"),
                ),
                taskName = "some-task",
            ),
            listOf("--docker-config=some-config-dir", "--docker-tls-ca-cert=some-ca-cert", "--docker-tls-cert=some-cert", "--docker-tls-key=some-key", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    configDirectory = fileSystem.getPath("/resolved/some-config-dir"),
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-ca-cert"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-cert"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-key"),
                ),
                taskName = "some-task",
            ),
            listOf("--docker-cert-path=some-cert-dir", "--docker-config=some-config-dir", "--docker-tls-ca-cert=some-ca-cert", "--docker-tls-cert=some-cert", "--docker-tls-key=some-key", "some-task") to defaultCommandLineOptions.copy(
                docker = defaultCommandLineOptions.docker.copy(
                    configDirectory = fileSystem.getPath("/resolved/some-config-dir"),
                    tlsCACertificatePath = fileSystem.getPath("/resolved/some-ca-cert"),
                    tlsCertificatePath = fileSystem.getPath("/resolved/some-cert"),
                    tlsKeyPath = fileSystem.getPath("/resolved/some-key"),
                ),
                taskName = "some-task",
            ),
            listOf("--cache-type=volume", "some-task") to defaultCommandLineOptions.copy(cacheType = CacheType.Volume, taskName = "some-task"),
            listOf("--cache-type=directory", "some-task") to defaultCommandLineOptions.copy(cacheType = CacheType.Directory, taskName = "some-task"),
            listOf("--use-network=my-network", "some-task") to defaultCommandLineOptions.copy(existingNetworkToUse = "my-network", taskName = "some-task"),
            listOf("--skip-prerequisites", "some-task") to defaultCommandLineOptions.copy(skipPrerequisites = true, taskName = "some-task"),
            listOf("--permanently-disable-telemetry") to defaultCommandLineOptions.copy(permanentlyDisableTelemetry = true),
            listOf("--permanently-enable-telemetry") to defaultCommandLineOptions.copy(permanentlyEnableTelemetry = true),
            listOf("--no-telemetry", "some-task") to defaultCommandLineOptions.copy(disableTelemetry = true, taskName = "some-task"),
            listOf("--disable-ports", "some-task") to defaultCommandLineOptions.copy(disablePortMappings = true, taskName = "some-task"),
            listOf("--enable-buildkit", "some-task") to defaultCommandLineOptions.copy(enableBuildKit = true, taskName = "some-task"),
            listOf("--generate-completion-script=fish") to defaultCommandLineOptions.copy(generateShellTabCompletionScript = Shell.Fish),
            listOf("--generate-completion-task-info=fish") to defaultCommandLineOptions.copy(generateShellTabCompletionTaskInformation = Shell.Fish),
            listOf("--max-parallelism=3", "some-task") to defaultCommandLineOptions.copy(maximumLevelOfParallelism = 3, taskName = "some-task"),
            listOf("--tag-image", "some-container=some-container:abc123", "some-task") to defaultCommandLineOptions.copy(imageTags = mapOf("some-container" to setOf("some-container:abc123")), taskName = "some-task"),
            listOf("--tag-image", "some-container=some-container:abc123", "--tag-image", "some-container=some-other-container:abc123", "some-task") to defaultCommandLineOptions.copy(
                imageTags = mapOf("some-container" to setOf("some-container:abc123", "some-other-container:abc123")),
                taskName = "some-task",
            ),
            listOf("--tag-image", "some-container=some-container:abc123", "--tag-image", "some-other-container=some-other-container:abc123", "some-task") to defaultCommandLineOptions.copy(
                imageTags = mapOf("some-container" to setOf("some-container:abc123"), "some-other-container" to setOf("some-other-container:abc123")),
                taskName = "some-task",
            ),
            listOf("--tag-image", "some-container=some-container:abc123", "--override-image", "some-other-container=some-other-container:abc123", "some-task") to defaultCommandLineOptions.copy(
                imageTags = mapOf("some-container" to setOf("some-container:abc123")),
                imageOverrides = mapOf("some-other-container" to "some-other-container:abc123"),
                taskName = "some-task",
            ),
            listOf("--clean-cache=some-cache-name") to defaultCommandLineOptions.copy(cleanCaches = setOf("some-cache-name")),
        ).forEach { (args, expectedResult) ->
            given("the arguments $args") {
                on("parsing the command line") {
                    val result = parseAndExpectSuccess(args)

                    it("returns a set of options with the expected options populated") {
                        assertThat(result, equalTo(expectedResult))
                    }
                }
            }
        }

        setOf("--docker-host", "--docker-tls-ca-cert", "--docker-tls-cert", "--docker-tls-key").forEach { option ->
            given("--docker-context and $option are both provided") {
                on("parsing the command line") {
                    val result = parse(listOf("--docker-context=some-context", "$option=some-value", "some-task"))

                    it("returns an error message") {
                        assertThat(result, equalTo(CommandLineOptionsParsingResult.Failed("Cannot use both --docker-context and $option.")))
                    }
                }
            }
        }

        given("--docker-context and --docker-cert-path are both provided") {
            on("parsing the command line") {
                val result = parse(listOf("--docker-context=some-context", "--docker-cert-path=some-dir", "some-task"))

                it("returns an error message") {
                    assertThat(result, equalTo(CommandLineOptionsParsingResult.Failed("Cannot use both --docker-context and --docker-cert-path.")))
                }
            }
        }

        setOf("--docker-tls", "--docker-tls-verify").forEach { option ->
            given("--docker-context and $option are both provided") {
                on("parsing the command line") {
                    val result = parse(listOf("--docker-context=some-context", "$option", "some-task"))

                    it("returns an error message") {
                        assertThat(result, equalTo(CommandLineOptionsParsingResult.Failed("Cannot use both --docker-context and $option.")))
                    }
                }
            }
        }

        describe("configuring the active Docker context") {
            val activeContextResolver: ActiveDockerContextResolver = { path ->
                when (path) {
                    defaultCommandLineOptions.docker.configDirectory.toOkioPath() -> DockerCLIContext("default-configuration-directory-active-context")
                    "/resolved/non-default-docker-config-dir".toPath() -> DockerCLIContext("non-default-configuration-directory-active-context")
                    else -> throw UnsupportedOperationException("Unknown path: $path")
                }
            }

            given("the --docker-context command line option is present") {
                val args = listOf("--docker-context=cli-context", "some-task")
                val environmentVariables = HostEnvironmentVariables("DOCKER_CONTEXT" to "env-context", "DOCKER_HOST" to "some://env.sock")
                val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                it("uses the context name provided on the command line") {
                    assertThat(result.docker.contextName, equalTo("cli-context"))
                }
            }

            given("the --docker-context command line option is not present") {
                given("the --docker-host command line option is present") {
                    val args = listOf("--docker-host=some://cli.sock", "some-task")
                    val environmentVariables = HostEnvironmentVariables("DOCKER_CONTEXT" to "env-context", "DOCKER_HOST" to "some://env.sock")
                    val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                    it("uses the default context name, ignoring the DOCKER_CONTEXT environment variable") {
                        assertThat(result.docker.contextName, equalTo(DockerCLIContext.default.name))
                    }

                    it("uses the host from the command line") {
                        assertThat(result.docker.host, equalTo("some://cli.sock"))
                    }
                }

                given("the --docker-host command line option is not present") {
                    given("the DOCKER_HOST environment variable is set") {
                        val args = listOf("some-task")
                        val environmentVariables = HostEnvironmentVariables("DOCKER_CONTEXT" to "env-context", "DOCKER_HOST" to "some://env.sock")
                        val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                        it("uses the default context name, ignoring the DOCKER_CONTEXT environment variable") {
                            assertThat(result.docker.contextName, equalTo(DockerCLIContext.default.name))
                        }

                        it("uses the host from the DOCKER_HOST environment variable") {
                            assertThat(result.docker.host, equalTo("some://env.sock"))
                        }
                    }

                    given("the DOCKER_HOST environment variable is not set") {
                        given("the DOCKER_CONTEXT environment variable is set") {
                            val args = listOf("some-task")
                            val environmentVariables = HostEnvironmentVariables("DOCKER_CONTEXT" to "env-context")
                            val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                            it("uses the context name from the DOCKER_CONTEXT environment variable") {
                                assertThat(result.docker.contextName, equalTo("env-context"))
                            }
                        }

                        given("the DOCKER_CONTEXT environment variable is not set") {
                            val environmentVariables = HostEnvironmentVariables()

                            given("a Docker configuration directory is set") {
                                val args = listOf("--docker-config=non-default-docker-config-dir", "some-task")
                                val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                                it("uses the active context from the provided Docker configuration directory") {
                                    assertThat(result.docker.contextName, equalTo("non-default-configuration-directory-active-context"))
                                }
                            }

                            given("a Docker configuration directory is not set") {
                                val args = listOf("some-task")
                                val result = parseAndExpectSuccess(args, environmentVariables, activeContextResolver)

                                it("uses the active context from the default Docker configuration directory") {
                                    assertThat(result.docker.contextName, equalTo("default-configuration-directory-active-context"))
                                }
                            }
                        }
                    }
                }
            }
        }

        describe("configuring Docker file locations") {
            fun Suite.itParsesArgumentsWith(
                environmentVariables: HostEnvironmentVariables,
                topLevelArgs: List<String>,
                configDirectoryDescription: String,
                expectedConfigDirectory: Path,
                certificatesDirectoryDescription: String,
                defaultCertificatesDirectory: Path,
            ) {
                val expectedTlsCACertificatePath = defaultCertificatesDirectory.resolve("ca.pem")
                val expectedTLSCertificatePath = defaultCertificatesDirectory.resolve("cert.pem")
                val expectedTLSKey = defaultCertificatesDirectory.resolve("key.pem")

                given("no specific file arguments are provided") {
                    val args = topLevelArgs + listOf("some-task")
                    val result = parseAndExpectSuccess(args, environmentVariables)

                    it("returns a set of options with the $configDirectoryDescription and certificate files within the $certificatesDirectoryDescription") {
                        assertThat(
                            result.docker,
                            hasConfigDirectory(expectedConfigDirectory)
                                and hasTlsCACertificatePath(expectedTlsCACertificatePath)
                                and hasTLSCertificatePath(expectedTLSCertificatePath)
                                and hasTLSKeyPath(expectedTLSKey),
                        )
                    }
                }

                given("the --docker-tls-ca-cert argument is provided") {
                    val args = topLevelArgs + listOf("--docker-tls-ca-cert=some-tls-ca-cert", "some-task")
                    val result = parseAndExpectSuccess(args, environmentVariables)

                    it("returns a set of options with the value for the TLS CA certificate file from the argument") {
                        assertThat(
                            result.docker,
                            hasConfigDirectory(expectedConfigDirectory)
                                and hasTlsCACertificatePath(fileSystem.getPath("/resolved/some-tls-ca-cert"))
                                and hasTLSCertificatePath(expectedTLSCertificatePath)
                                and hasTLSKeyPath(expectedTLSKey),
                        )
                    }
                }

                given("the --docker-tls-cert argument is provided") {
                    val args = topLevelArgs + listOf("--docker-tls-cert=some-tls-cert", "some-task")
                    val result = parseAndExpectSuccess(args, environmentVariables)

                    it("returns a set of options with the value for the TLS certificate file from the argument") {
                        assertThat(
                            result.docker,
                            hasConfigDirectory(expectedConfigDirectory)
                                and hasTlsCACertificatePath(expectedTlsCACertificatePath)
                                and hasTLSCertificatePath(fileSystem.getPath("/resolved/some-tls-cert"))
                                and hasTLSKeyPath(expectedTLSKey),
                        )
                    }
                }

                given("the --docker-tls-key argument is provided") {
                    val args = topLevelArgs + listOf("--docker-tls-key=some-tls-key", "some-task")
                    val result = parseAndExpectSuccess(args, environmentVariables)

                    it("returns a set of options with the value for the TLS key file from the argument") {
                        assertThat(
                            result.docker,
                            hasConfigDirectory(expectedConfigDirectory)
                                and hasTlsCACertificatePath(expectedTlsCACertificatePath)
                                and hasTLSCertificatePath(expectedTLSCertificatePath)
                                and hasTLSKeyPath(fileSystem.getPath("/resolved/some-tls-key")),
                        )
                    }
                }
            }

            given("the DOCKER_CONFIG environment variable is not set") {
                val configDirectoryEnvironmentVariable = emptyMap<String, String>()

                given("the --docker-config argument is not provided") {
                    val configDirectoryArgument = emptyList<String>()
                    val expectedConfigDirectory = defaultCommandLineOptions.docker.configDirectory
                    val configDirectoryDescription = "the default Docker config directory"

                    given("the DOCKER_CERT_PATH environment variable is not set") {
                        val environmentVariables = HostEnvironmentVariables(configDirectoryEnvironmentVariable)

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }

                    given("the DOCKER_CERT_PATH environment variable is set") {
                        val environmentVariables = HostEnvironmentVariables(
                            configDirectoryEnvironmentVariable +
                                ("DOCKER_CERT_PATH" to "some-environment-cert-dir"),
                        )

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory configured in the DOCKER_CERT_PATH environment variable",
                                fileSystem.getPath("/resolved/some-environment-cert-dir"),
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }
                }

                given("the --docker-config argument is provided") {
                    val configDirectoryArgument = listOf("--docker-config=some-config-dir")
                    val expectedConfigDirectory = fileSystem.getPath("/resolved/some-config-dir")
                    val configDirectoryDescription = "the Docker config directory provided on the command line"

                    given("the DOCKER_CERT_PATH environment variable is not set") {
                        val environmentVariables = HostEnvironmentVariables(configDirectoryEnvironmentVariable)

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }

                    given("the DOCKER_CERT_PATH environment variable is set") {
                        val environmentVariables = HostEnvironmentVariables(
                            configDirectoryEnvironmentVariable +
                                ("DOCKER_CERT_PATH" to "some-environment-cert-dir"),
                        )

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory configured in the DOCKER_CERT_PATH environment variable",
                                fileSystem.getPath("/resolved/some-environment-cert-dir"),
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }
                }
            }

            given("the DOCKER_CONFIG environment variable is set") {
                val configDirectoryEnvironmentVariable = mapOf("DOCKER_CONFIG" to "some-environment-config-dir")

                given("the --docker-config argument is not provided") {
                    val configDirectoryArgument = emptyList<String>()
                    val expectedConfigDirectory = fileSystem.getPath("/resolved/some-environment-config-dir")
                    val configDirectoryDescription = "the Docker config directory configured in the DOCKER_CONFIG environment variable"

                    given("the DOCKER_CERT_PATH environment variable is not set") {
                        val environmentVariables = HostEnvironmentVariables(configDirectoryEnvironmentVariable)

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }

                    given("the DOCKER_CERT_PATH environment variable is set") {
                        val environmentVariables = HostEnvironmentVariables(
                            configDirectoryEnvironmentVariable +
                                ("DOCKER_CERT_PATH" to "some-environment-cert-dir"),
                        )

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory configured in the DOCKER_CERT_PATH environment variable",
                                fileSystem.getPath("/resolved/some-environment-cert-dir"),
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }
                }

                given("the --docker-config argument is provided") {
                    val configDirectoryArgument = listOf("--docker-config=some-config-dir")
                    val expectedConfigDirectory = fileSystem.getPath("/resolved/some-config-dir")
                    val configDirectoryDescription = "the Docker config directory provided on the command line"

                    given("the DOCKER_CERT_PATH environment variable is not set") {
                        val environmentVariables = HostEnvironmentVariables(configDirectoryEnvironmentVariable)

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }

                    given("the DOCKER_CERT_PATH environment variable is set") {
                        val environmentVariables = HostEnvironmentVariables(
                            configDirectoryEnvironmentVariable +
                                ("DOCKER_CERT_PATH" to "some-environment-cert-dir"),
                        )

                        given("the --docker-cert-path argument is not provided") {
                            val args = configDirectoryArgument + emptyList()

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory configured in the DOCKER_CERT_PATH environment variable",
                                fileSystem.getPath("/resolved/some-environment-cert-dir"),
                            )
                        }

                        given("the --docker-cert-path argument is provided") {
                            val args = configDirectoryArgument + listOf("--docker-cert-path=some-cert-dir")

                            itParsesArgumentsWith(
                                environmentVariables,
                                args,
                                configDirectoryDescription,
                                expectedConfigDirectory,
                                "the certificates directory provided on the command line",
                                fileSystem.getPath("/resolved/some-cert-dir"),
                            )
                        }
                    }
                }
            }
        }
    }
})

private fun hasConfigDirectory(expected: Path) = has("Docker config directory", DockerCommandLineOptions::configDirectory, equalTo(expected))
private fun hasTlsCACertificatePath(expected: Path) = has("Docker TLS CA certificate path", DockerCommandLineOptions::tlsCACertificatePath, equalTo(expected))
private fun hasTLSCertificatePath(expected: Path) = has("Docker TLS certificate path", DockerCommandLineOptions::tlsCertificatePath, equalTo(expected))
private fun hasTLSKeyPath(expected: Path) = has("Docker TLS key path", DockerCommandLineOptions::tlsKeyPath, equalTo(expected))
