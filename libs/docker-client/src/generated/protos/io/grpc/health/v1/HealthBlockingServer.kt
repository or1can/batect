// Code generated by Wire protocol buffer compiler, do not edit.
// Source: grpc.health.v1.Health in github.com/grpc/grpc-proto/grpc/health/v1/health.proto
package io.grpc.health.v1

import com.squareup.wire.MessageSink
import com.squareup.wire.Service
import com.squareup.wire.WireRpc
import kotlin.Unit

public interface HealthBlockingServer : Service {
  /**
   * If the requested service is unknown, the call will fail with status
   * NOT_FOUND.
   */
  @WireRpc(
    path = "/grpc.health.v1.Health/Check",
    requestAdapter = "io.grpc.health.v1.HealthCheckRequest#ADAPTER",
    responseAdapter = "io.grpc.health.v1.HealthCheckResponse#ADAPTER",
    sourceFile = "github.com/grpc/grpc-proto/grpc/health/v1/health.proto"
  )
  public fun Check(request: HealthCheckRequest): HealthCheckResponse

  /**
   * Performs a watch for the serving status of the requested service.
   * The server will immediately send back a message indicating the current
   * serving status.  It will then subsequently send a new message whenever
   * the service's serving status changes.
   *
   * If the requested service is unknown when the call is received, the
   * server will send a message setting the serving status to
   * SERVICE_UNKNOWN but will *not* terminate the call.  If at some
   * future point, the serving status of the service becomes known, the
   * server will send a new message with the service's serving status.
   *
   * If the call terminates with status UNIMPLEMENTED, then clients
   * should assume this method is not supported and should not retry the
   * call.  If the call terminates with any other status (including OK),
   * clients should retry the call with appropriate exponential backoff.
   */
  @WireRpc(
    path = "/grpc.health.v1.Health/Watch",
    requestAdapter = "io.grpc.health.v1.HealthCheckRequest#ADAPTER",
    responseAdapter = "io.grpc.health.v1.HealthCheckResponse#ADAPTER",
    sourceFile = "github.com/grpc/grpc-proto/grpc/health/v1/health.proto"
  )
  public fun Watch(request: HealthCheckRequest, response: MessageSink<HealthCheckResponse>): Unit
}
