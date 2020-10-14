/*
   Copyright 2017-2020 Charles Korn.

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

package batect.docker.build

import batect.docker.DockerImage
import batect.docker.ImageBuildFailedException
import batect.testutils.createForEachTest
import batect.testutils.equalTo
import batect.testutils.given
import batect.testutils.withMessage
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.isEmpty
import com.natpryce.hamkrest.isEmptyString
import com.natpryce.hamkrest.throws
import okio.sink
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.io.ByteArrayOutputStream
import java.io.StringReader

object BuildKitImageBuildResponseBodySpec : Spek({
    describe("a BuildKit image build response body") {
        val output by createForEachTest { ByteArrayOutputStream() }
        val outputStream by createForEachTest { output.sink() }
        val eventsPosted by createForEachTest { mutableListOf<ImageBuildEvent>() }
        val eventCallback: ImageBuildEventCallback = { e -> eventsPosted.add(e) }
        val body by createForEachTest { BuildKitImageBuildResponseBody() }

        given("an empty response") {
            val input = ""

            beforeEachTest {
                body.readFrom(StringReader(input), outputStream, eventCallback)
            }

            it("posts no events") {
                assertThat(eventsPosted, isEmpty)
            }

            it("produces no output") {
                assertThat(output.toString(), isEmptyString)
            }
        }

        given("a response with an error message") {
            val input = """
                {"errorDetail":{"message":"failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully"},"error":"failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully"}
            """.trimIndent()

            beforeEachTest {
                body.readFrom(StringReader(input), outputStream, eventCallback)
            }

            it("posts a build error event") {
                assertThat(
                    eventsPosted,
                    equalTo(
                        listOf(
                            BuildError("failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully")
                        )
                    )
                )
            }

            it("produces no output") {
                // We don't need to write anything to the output - error output comes from status updates.
                assertThat(output.toString(), isEmptyString)
            }
        }

        given("a response with an image ID") {
            val input = """
                {"id":"moby.image.id","aux":{"ID":"sha256:a91e78399e9bb1cf844a290323ae8731b78dad99b647db80987260383c13694a"}}
            """.trimIndent()

            beforeEachTest {
                body.readFrom(StringReader(input), outputStream, eventCallback)
            }

            it("posts a build complete event") {
                assertThat(
                    eventsPosted,
                    equalTo(
                        listOf(
                            BuildComplete(DockerImage("sha256:a91e78399e9bb1cf844a290323ae8731b78dad99b647db80987260383c13694a"))
                        )
                    )
                )
            }

            it("produces no output") {
                assertThat(output.toString(), isEmptyString)
            }
        }

        given("a response with a series of build trace messages that culminates in the build succeeding") {
            // The 'aux' field is a base64-encoded byte array that can be decoded as a StatusResponse message from github.com/moby/buildkit/api/services/control/control.proto. Decode with the buildkit_decode.sh script in this directory,
            // or: echo "<value of 'aux' field>" | base64 --decode | protoc --decode moby.buildkit.v1.StatusResponse --proto_path ~/go/src/github.com/docker/cli/vendor/github.com/moby/buildkit/api/services/control --proto_path ~/go/src/github.com/docker/cli/vendor/ control.proto

            val input = """
                {"id":"moby.buildkit.trace","aux":"Cn0KR3NoYTI1NjpiMGQwNWRlMGY3ZTYzZWU2ZGFjOWEzZjA1MzE4ZWYyZmM1NzIwM2EzYWNjOTQzOGI5NWQwYTU1MGNkNGJlOGNlGiRbaW50ZXJuYWxdIGxvYWQgcmVtb3RlIGJ1aWxkIGNvbnRleHQqDAiivbH7BRCbtL+kAw=="}
                {"id":"moby.buildkit.trace","aux":"CosBCkdzaGEyNTY6YjBkMDVkZTBmN2U2M2VlNmRhYzlhM2YwNTMxOGVmMmZjNTcyMDNhM2FjYzk0MzhiOTVkMGE1NTBjZDRiZThjZRokW2ludGVybmFsXSBsb2FkIHJlbW90ZSBidWlsZCBjb250ZXh0KgwIor2x+wUQm7S/pAMyDAiivbH7BRDzjsOnAw=="}
                {"id":"moby.buildkit.trace","aux":"CosBCkdzaGEyNTY6YjBkMDVkZTBmN2U2M2VlNmRhYzlhM2YwNTMxOGVmMmZjNTcyMDNhM2FjYzk0MzhiOTVkMGE1NTBjZDRiZThjZRokW2ludGVybmFsXSBsb2FkIHJlbW90ZSBidWlsZCBjb250ZXh0KgwIor2x+wUQs/jMpwMyDAiivbH7BRDDp82nAw=="}
                {"id":"moby.buildkit.trace","aux":"CqMBCkdzaGEyNTY6NzM4OWI1MjczOTkxNzEyOTk0YjllMTdlMWQzZTAyOWFjNDg5MjE4ZmFkNGUzN2UwNjNkMzBkZTkwOTQ1NWI4NBJHc2hhMjU2OmIwZDA1ZGUwZjdlNjNlZTZkYWM5YTNmMDUzMThlZjJmYzU3MjAzYTNhY2M5NDM4Yjk1ZDBhNTUwY2Q0YmU4Y2UaD2NvcHkgL2NvbnRleHQgLw=="}
                {"id":"moby.buildkit.trace","aux":"CrEBCkdzaGEyNTY6NzM4OWI1MjczOTkxNzEyOTk0YjllMTdlMWQzZTAyOWFjNDg5MjE4ZmFkNGUzN2UwNjNkMzBkZTkwOTQ1NWI4NBJHc2hhMjU2OmIwZDA1ZGUwZjdlNjNlZTZkYWM5YTNmMDUzMThlZjJmYzU3MjAzYTNhY2M5NDM4Yjk1ZDBhNTUwY2Q0YmU4Y2UaD2NvcHkgL2NvbnRleHQgLyoMCKK9sfsFEOHSwLID"}
                {"id":"moby.buildkit.trace","aux":"Cr8BCkdzaGEyNTY6NzM4OWI1MjczOTkxNzEyOTk0YjllMTdlMWQzZTAyOWFjNDg5MjE4ZmFkNGUzN2UwNjNkMzBkZTkwOTQ1NWI4NBJHc2hhMjU2OmIwZDA1ZGUwZjdlNjNlZTZkYWM5YTNmMDUzMThlZjJmYzU3MjAzYTNhY2M5NDM4Yjk1ZDBhNTUwY2Q0YmU4Y2UaD2NvcHkgL2NvbnRleHQgLyoMCKK9sfsFEOHSwLIDMgwIor2x+wUQ7qG70AM="}
                {"id":"moby.buildkit.trace","aux":"CpUBCkdzaGEyNTY6NTM3YmY0YjFkYmI2NjBjMzBhMzY4MDUwN2FiMDY5NDU2OTg0YzU4ZDcxNWNlYmVjNTFjZjE1MzQzYTFkNmRjNho8W2ludGVybmFsXSBsb2FkIG1ldGFkYXRhIGZvciBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgwIor2x+wUQnPbR2wM="}
                {"id":"moby.buildkit.trace","aux":"CqMBCkdzaGEyNTY6NTM3YmY0YjFkYmI2NjBjMzBhMzY4MDUwN2FiMDY5NDU2OTg0YzU4ZDcxNWNlYmVjNTFjZjE1MzQzYTFkNmRjNho8W2ludGVybmFsXSBsb2FkIG1ldGFkYXRhIGZvciBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgwIor2x+wUQnPbR2wMyDAiivbH7BRCgjPbbAw=="}
                {"id":"moby.buildkit.trace","aux":"CnUKR3NoYTI1NjpmZGFkNjcxYTkxMmIyZDAxMDMzM2FmMzBkMDUwMzY3ZDdmMGM2N2Y2YmQ0OGU0M2JjM2EyZWVkNzg5MTQ3ZDMyGipbMS8xXSBGUk9NIGRvY2tlci5pby9saWJyYXJ5L2FscGluZTozLjEyLjA="}
                {"id":"moby.buildkit.trace","aux":"CoMBCkdzaGEyNTY6ZmRhZDY3MWE5MTJiMmQwMTAzMzNhZjMwZDA1MDM2N2Q3ZjBjNjdmNmJkNDhlNDNiYzNhMmVlZDc4OTE0N2QzMhoqWzEvMV0gRlJPTSBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgwIor2x+wUQ1Jqa3AM="}
                {"id":"moby.buildkit.trace","aux":"CpEBCkdzaGEyNTY6ZmRhZDY3MWE5MTJiMmQwMTAzMzNhZjMwZDA1MDM2N2Q3ZjBjNjdmNmJkNDhlNDNiYzNhMmVlZDc4OTE0N2QzMhoqWzEvMV0gRlJPTSBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgwIor2x+wUQ1Jqa3AMyDAiivbH7BRCTx73cAw=="}
                {"id":"moby.buildkit.trace","aux":"CpEBCkdzaGEyNTY6ZmRhZDY3MWE5MTJiMmQwMTAzMzNhZjMwZDA1MDM2N2Q3ZjBjNjdmNmJkNDhlNDNiYzNhMmVlZDc4OTE0N2QzMhoqWzEvMV0gRlJPTSBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wIAEqDAiivbH7BRDvsencAzIKCKO9sfsFELyAAQppCkdzaGEyNTY6ZThjNjEzZTA3YjBiN2ZmMzM4OTNiNjk0Zjc3NTlhMTBkNDJlMTgwZjJiNGRjMzQ5ZmI1N2RjNmI3MWRjYWIwMBoSZXhwb3J0aW5nIHRvIGltYWdlKgoIo72x+wUQpJ4DEn8KEGV4cG9ydGluZyBsYXllcnMSR3NoYTI1NjplOGM2MTNlMDdiMGI3ZmYzMzg5M2I2OTRmNzc1OWExMGQ0MmUxODBmMmI0ZGMzNDlmYjU3ZGM2YjcxZGNhYjAwMgoIo72x+wUQivwLOgoIo72x+wUQoLcDQgoIo72x+wUQ5vYL"}
                {"id":"moby.buildkit.trace","aux":"ErgBClV3cml0aW5nIGltYWdlIHNoYTI1NjphOTFlNzgzOTllOWJiMWNmODQ0YTI5MDMyM2FlODczMWI3OGRhZDk5YjY0N2RiODA5ODcyNjAzODNjMTM2OTRhEkdzaGEyNTY6ZThjNjEzZTA3YjBiN2ZmMzM4OTNiNjk0Zjc3NTlhMTBkNDJlMTgwZjJiNGRjMzQ5ZmI1N2RjNmI3MWRjYWIwMDIKCKO9sfsFEPbHIDoKCKO9sfsFELbFIA=="}
                {"id":"moby.buildkit.trace","aux":"EsYBClV3cml0aW5nIGltYWdlIHNoYTI1NjphOTFlNzgzOTllOWJiMWNmODQ0YTI5MDMyM2FlODczMWI3OGRhZDk5YjY0N2RiODA5ODcyNjAzODNjMTM2OTRhEkdzaGEyNTY6ZThjNjEzZTA3YjBiN2ZmMzM4OTNiNjk0Zjc3NTlhMTBkNDJlMTgwZjJiNGRjMzQ5ZmI1N2RjNmI3MWRjYWIwMDILCKO9sfsFEIj+uQE6CgijvbH7BRC2xSBCCwijvbH7BRCO9LkBEp8BCjpuYW1pbmcgdG8gZG9ja2VyLmlvL2xpYnJhcnkvYmF0ZWN0LWludGVncmF0aW9uLXRlc3RzLWltYWdlEkdzaGEyNTY6ZThjNjEzZTA3YjBiN2ZmMzM4OTNiNjk0Zjc3NTlhMTBkNDJlMTgwZjJiNGRjMzQ5ZmI1N2RjNmI3MWRjYWIwMDILCKO9sfsFELyfvAE6CwijvbH7BRDjnLwB"}
                {"id":"moby.image.id","aux":{"ID":"sha256:a91e78399e9bb1cf844a290323ae8731b78dad99b647db80987260383c13694a"}}
                {"id":"moby.buildkit.trace","aux":"CnYKR3NoYTI1NjplOGM2MTNlMDdiMGI3ZmYzMzg5M2I2OTRmNzc1OWExMGQ0MmUxODBmMmI0ZGMzNDlmYjU3ZGM2YjcxZGNhYjAwGhJleHBvcnRpbmcgdG8gaW1hZ2UqCgijvbH7BRCkngMyCwijvbH7BRC1uKUCEqwBCjpuYW1pbmcgdG8gZG9ja2VyLmlvL2xpYnJhcnkvYmF0ZWN0LWludGVncmF0aW9uLXRlc3RzLWltYWdlEkdzaGEyNTY6ZThjNjEzZTA3YjBiN2ZmMzM4OTNiNjk0Zjc3NTlhMTBkNDJlMTgwZjJiNGRjMzQ5ZmI1N2RjNmI3MWRjYWIwMDILCKO9sfsFEKLDowI6CwijvbH7BRDjnLwBQgsIo72x+wUQ3LujAg=="}
                {"stream":"Successfully tagged batect-integration-tests-image:latest\n"}
            """.trimIndent()

            beforeEachTest {
                body.readFrom(StringReader(input), outputStream, eventCallback)
            }

            it("posts build status messages as the build progresses") {
                assertThat(
                    eventsPosted,
                    equalTo(
                        listOf(
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(0, "[internal] load remote build context"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(1, "copy /context /"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(2, "[internal] load metadata for docker.io/library/alpine:3.12.0"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(3, "[1/1] FROM docker.io/library/alpine:3.12.0"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(4, "exporting to image"))),
                            BuildComplete(DockerImage("sha256:a91e78399e9bb1cf844a290323ae8731b78dad99b647db80987260383c13694a"))
                        )
                    )
                )
            }

            it("streams output showing the progression of the build") {
                assertThat(
                    output.toString(),
                    equalTo(
                        """
                        |#1 [internal] load remote build context
                        |#1 DONE
                        |
                        |#2 copy /context /
                        |#2 DONE
                        |
                        |#3 [internal] load metadata for docker.io/library/alpine:3.12.0
                        |#3 DONE
                        |
                        |#4 [1/1] FROM docker.io/library/alpine:3.12.0
                        |#4 CACHED
                        |
                        |#5 exporting to image
                        |#5 exporting layers: done
                        |#5 writing image sha256:a91e78399e9bb1cf844a290323ae8731b78dad99b647db80987260383c13694a: done
                        |#5 naming to docker.io/library/batect-integration-tests-image: done
                        |#5 DONE
                        |
                        """.trimMargin()
                    )
                )
            }
        }

        given("a response with a series of build trace messages that culminates in the build failing") {
            val input = """
                {"id":"moby.buildkit.trace","aux":"Cm8KR3NoYTI1NjpkOGE5MTRhNDk0MTA0ZTVlMTU0NzQ4NDJiMDUxMDJlNDY0YWE3YmUwOGI4MTk5NjE0MjczNmM3YzdlZjZjYWY0GiRbaW50ZXJuYWxdIGxvYWQgcmVtb3RlIGJ1aWxkIGNvbnRleHQ="}
                {"id":"moby.buildkit.trace","aux":"Cn0KR3NoYTI1NjpkOGE5MTRhNDk0MTA0ZTVlMTU0NzQ4NDJiMDUxMDJlNDY0YWE3YmUwOGI4MTk5NjE0MjczNmM3YzdlZjZjYWY0GiRbaW50ZXJuYWxdIGxvYWQgcmVtb3RlIGJ1aWxkIGNvbnRleHQqDAjf/7n7BRCameK1Aw=="}
                {"id":"moby.buildkit.trace","aux":"CosBCkdzaGEyNTY6ZDhhOTE0YTQ5NDEwNGU1ZTE1NDc0ODQyYjA1MTAyZTQ2NGFhN2JlMDhiODE5OTYxNDI3MzZjN2M3ZWY2Y2FmNBokW2ludGVybmFsXSBsb2FkIHJlbW90ZSBidWlsZCBjb250ZXh0KgwI3/+5+wUQmpnitQMyDAjf/7n7BRDCvMS4Aw=="}
                {"id":"moby.buildkit.trace","aux":"CosBCkdzaGEyNTY6ZDhhOTE0YTQ5NDEwNGU1ZTE1NDc0ODQyYjA1MTAyZTQ2NGFhN2JlMDhiODE5OTYxNDI3MzZjN2M3ZWY2Y2FmNBokW2ludGVybmFsXSBsb2FkIHJlbW90ZSBidWlsZCBjb250ZXh0KgwI3/+5+wUQv+vPuAMyDAjf/7n7BRDlttC4Aw=="}
                {"id":"moby.buildkit.trace","aux":"CqMBCkdzaGEyNTY6MmU3ZjhjNGI2Y2EwMTJiNGY0Y2UxZTNkMGU0MTBlZjNlNDBkZDBmYjI0NmQyZGFhZGIyMjFmOGIyODcxOTAwNxJHc2hhMjU2OmQ4YTkxNGE0OTQxMDRlNWUxNTQ3NDg0MmIwNTEwMmU0NjRhYTdiZTA4YjgxOTk2MTQyNzM2YzdjN2VmNmNhZjQaD2NvcHkgL2NvbnRleHQgLw=="}
                {"id":"moby.buildkit.trace","aux":"CrEBCkdzaGEyNTY6MmU3ZjhjNGI2Y2EwMTJiNGY0Y2UxZTNkMGU0MTBlZjNlNDBkZDBmYjI0NmQyZGFhZGIyMjFmOGIyODcxOTAwNxJHc2hhMjU2OmQ4YTkxNGE0OTQxMDRlNWUxNTQ3NDg0MmIwNTEwMmU0NjRhYTdiZTA4YjgxOTk2MTQyNzM2YzdjN2VmNmNhZjQaD2NvcHkgL2NvbnRleHQgLyoMCN//ufsFEKuKhcUD"}
                {"id":"moby.buildkit.trace","aux":"Cr4BCkdzaGEyNTY6MmU3ZjhjNGI2Y2EwMTJiNGY0Y2UxZTNkMGU0MTBlZjNlNDBkZDBmYjI0NmQyZGFhZGIyMjFmOGIyODcxOTAwNxJHc2hhMjU2OmQ4YTkxNGE0OTQxMDRlNWUxNTQ3NDg0MmIwNTEwMmU0NjRhYTdiZTA4YjgxOTk2MTQyNzM2YzdjN2VmNmNhZjQaD2NvcHkgL2NvbnRleHQgLyoMCN//ufsFEKuKhcUDMgsI4P+5+wUQ8oWvAw=="}
                {"id":"moby.buildkit.trace","aux":"CpQBCkdzaGEyNTY6NTM3YmY0YjFkYmI2NjBjMzBhMzY4MDUwN2FiMDY5NDU2OTg0YzU4ZDcxNWNlYmVjNTFjZjE1MzQzYTFkNmRjNho8W2ludGVybmFsXSBsb2FkIG1ldGFkYXRhIGZvciBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgsI4P+5+wUQn8uEDQ=="}
                {"id":"moby.buildkit.trace","aux":"CqEBCkdzaGEyNTY6NTM3YmY0YjFkYmI2NjBjMzBhMzY4MDUwN2FiMDY5NDU2OTg0YzU4ZDcxNWNlYmVjNTFjZjE1MzQzYTFkNmRjNho8W2ludGVybmFsXSBsb2FkIG1ldGFkYXRhIGZvciBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgsI4P+5+wUQn8uEDTILCOD/ufsFEP2vkA0="}
                {"id":"moby.buildkit.trace","aux":"CrABCkdzaGEyNTY6MWM1YzdlMmY1ZDVhZmFkNTg2OWNlMjkzMjU1Y2FmZDliNTkzYTNhYTU1NDY3YzU0ZWViMWY1YzM3MzQ4MDhkMhJHc2hhMjU2OmZkYWQ2NzFhOTEyYjJkMDEwMzMzYWYzMGQwNTAzNjdkN2YwYzY3ZjZiZDQ4ZTQzYmMzYTJlZWQ3ODkxNDdkMzIaHFsyLzNdIFJVTiBlY2hvICJoZWxsbyB3b3JsZCIKpAEKR3NoYTI1NjplZTc2MTBjZDRiYjQxN2QyOWUwNzI2ZTQ1Yzk1NWI5MDg1NzliNGNmNmQ1MzRlYjg1OWViYWI0MDgyYTUxNTVlEkdzaGEyNTY6MWM1YzdlMmY1ZDVhZmFkNTg2OWNlMjkzMjU1Y2FmZDliNTkzYTNhYTU1NDY3YzU0ZWViMWY1YzM3MzQ4MDhkMhoQWzMvM10gUlVOIGV4aXQgMQqPAQpHc2hhMjU2OmZkYWQ2NzFhOTEyYjJkMDEwMzMzYWYzMGQwNTAzNjdkN2YwYzY3ZjZiZDQ4ZTQzYmMzYTJlZWQ3ODkxNDdkMzIaKlsxLzNdIEZST00gZG9ja2VyLmlvL2xpYnJhcnkvYWxwaW5lOjMuMTIuMCoLCOD/ufsFEJDAsA0yCwjg/7n7BRCsgr4N"}
                {"id":"moby.buildkit.trace","aux":"Co8BCkdzaGEyNTY6ZmRhZDY3MWE5MTJiMmQwMTAzMzNhZjMwZDA1MDM2N2Q3ZjBjNjdmNmJkNDhlNDNiYzNhMmVlZDc4OTE0N2QzMhoqWzEvM10gRlJPTSBkb2NrZXIuaW8vbGlicmFyeS9hbHBpbmU6My4xMi4wKgsI4P+5+wUQpPXSDTILCOD/ufsFEPac4Q0KvQEKR3NoYTI1NjoxYzVjN2UyZjVkNWFmYWQ1ODY5Y2UyOTMyNTVjYWZkOWI1OTNhM2FhNTU0NjdjNTRlZWIxZjVjMzczNDgwOGQyEkdzaGEyNTY6ZmRhZDY3MWE5MTJiMmQwMTAzMzNhZjMwZDA1MDM2N2Q3ZjBjNjdmNmJkNDhlNDNiYzNhMmVlZDc4OTE0N2QzMhocWzIvM10gUlVOIGVjaG8gImhlbGxvIHdvcmxkIioLCOD/ufsFENnG6g0SmQEKJ3Jlc29sdmUgZG9ja2VyLmlvL2xpYnJhcnkvYWxwaW5lOjMuMTIuMBJHc2hhMjU2OmZkYWQ2NzFhOTEyYjJkMDEwMzMzYWYzMGQwNTAzNjdkN2YwYzY3ZjZiZDQ4ZTQzYmMzYTJlZWQ3ODkxNDdkMzIyCwjg/7n7BRCfsNQNOgsI4P+5+wUQ89DTDUILCOD/ufsFEMKu1A0="}
                {"id":"moby.buildkit.trace","aux":"GmYKR3NoYTI1NjoxYzVjN2UyZjVkNWFmYWQ1ODY5Y2UyOTMyNTVjYWZkOWI1OTNhM2FhNTU0NjdjNTRlZWIxZjVjMzczNDgwOGQyEgsI4P+5+wUQwoK+ZBgBIgxoZWxsbyB3b3JsZAo="}
                {"id":"moby.buildkit.trace","aux":"CsoBCkdzaGEyNTY6MWM1YzdlMmY1ZDVhZmFkNTg2OWNlMjkzMjU1Y2FmZDliNTkzYTNhYTU1NDY3YzU0ZWViMWY1YzM3MzQ4MDhkMhJHc2hhMjU2OmZkYWQ2NzFhOTEyYjJkMDEwMzMzYWYzMGQwNTAzNjdkN2YwYzY3ZjZiZDQ4ZTQzYmMzYTJlZWQ3ODkxNDdkMzIaHFsyLzNdIFJVTiBlY2hvICJoZWxsbyB3b3JsZCIqCwjg/7n7BRDZxuoNMgsI4P+5+wUQ/PfheQ=="}
                {"id":"moby.buildkit.trace","aux":"CrEBCkdzaGEyNTY6ZWU3NjEwY2Q0YmI0MTdkMjllMDcyNmU0NWM5NTViOTA4NTc5YjRjZjZkNTM0ZWI4NTllYmFiNDA4MmE1MTU1ZRJHc2hhMjU2OjFjNWM3ZTJmNWQ1YWZhZDU4NjljZTI5MzI1NWNhZmQ5YjU5M2EzYWE1NTQ2N2M1NGVlYjFmNWMzNzM0ODA4ZDIaEFszLzNdIFJVTiBleGl0IDEqCwjg/7n7BRD8r8Z6"}
                {"id":"moby.buildkit.trace","aux":"CpACCkdzaGEyNTY6ZWU3NjEwY2Q0YmI0MTdkMjllMDcyNmU0NWM5NTViOTA4NTc5YjRjZjZkNTM0ZWI4NTllYmFiNDA4MmE1MTU1ZRJHc2hhMjU2OjFjNWM3ZTJmNWQ1YWZhZDU4NjljZTI5MzI1NWNhZmQ5YjU5M2EzYWE1NTQ2N2M1NGVlYjFmNWMzNzM0ODA4ZDIaEFszLzNdIFJVTiBleGl0IDEqCwjg/7n7BRD8r8Z6MgwI4P+5+wUQtOPw6QE6T2V4ZWN1dG9yIGZhaWxlZCBydW5uaW5nIFsvYmluL3NoIC1jIGV4aXQgMV06IHJ1bmMgZGlkIG5vdCB0ZXJtaW5hdGUgc3VjZXNzZnVsbHk="}
                {"errorDetail":{"message":"failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully"},"error":"failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully"}
            """.trimIndent()

            beforeEachTest {
                body.readFrom(StringReader(input), outputStream, eventCallback)
            }

            it("posts build status messages as the build progresses") {
                assertThat(
                    eventsPosted,
                    equalTo(
                        listOf(
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(0, "[internal] load remote build context"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(1, "copy /context /"))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(2, "[internal] load metadata for docker.io/library/alpine:3.12.0"))),
                            // The FROM step is marked as complete immediately, so we don't need to post a progress event for it.
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(4, "[2/3] RUN echo \"hello world\""))),
                            BuildProgress(setOf(ActiveImageBuildStep.NotDownloading(5, "[3/3] RUN exit 1"))),
                            BuildError("failed to solve with frontend dockerfile.v0: failed to build LLB: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully")
                        )
                    )
                )
            }

            it("streams output showing the progression of the build") {
                assertThat(
                    output.toString(),
                    equalTo(
                        """
                        |#1 [internal] load remote build context
                        |#1 DONE
                        |
                        |#2 copy /context /
                        |#2 DONE
                        |
                        |#3 [internal] load metadata for docker.io/library/alpine:3.12.0
                        |#3 DONE
                        |
                        |#4 [1/3] FROM docker.io/library/alpine:3.12.0
                        |#4 resolve docker.io/library/alpine:3.12.0: done
                        |#4 DONE
                        |
                        |#5 [2/3] RUN echo "hello world"
                        |#5 0.181 hello world
                        |#5 DONE
                        |
                        |#6 [3/3] RUN exit 1
                        |#6 ERROR: executor failed running [/bin/sh -c exit 1]: runc did not terminate sucessfully
                        |
                        """.trimMargin()
                    )
                )
            }
        }

        mapOf(
            "\n" to """""""",
            "{\n" to """"{""""
        ).forEach { (input, description) ->
            given("a response with the malformed input $description") {
                it("throws an appropriate exception") {
                    assertThat(
                        { body.readFrom(StringReader(input), outputStream, eventCallback) },
                        throws<ImageBuildFailedException>(withMessage("Received malformed response from Docker daemon during build: $description"))
                    )
                }
            }
        }
    }
})
