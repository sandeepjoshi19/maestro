package maestro.studio

import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import maestro.Maestro
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call

object MaestroHierarchyServer {

    fun start(port: Int, maestro: Maestro?) {
        embeddedServer(Netty, port = port) {
            install(CORS) {
                anyHost() // Allows requests from any host.
                allowHeader(HttpHeaders.ContentType)
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    // Log the exception for debugging
                    cause.printStackTrace()
                    call.respond("Failed to get hierarchy: ${cause.message}")
                }
            }
            receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                withContext(Dispatchers.IO) {
                    proceed()
                }
            }
            routing {

                intercept(ApplicationCallPipeline.Call) {
                    if (maestro == null) {
                        // Respond with 400 and immediately finish processing the request.
                        call.respond(HttpStatusCode.BadRequest, "Maestro is not initialized")
                        finish()
                    }
                }
                if (maestro != null) {
                    get("/hierarchy") {
                        println("Device Name: ${maestro.deviceName}")
                        val hierarchy = jacksonObjectMapper()
                            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(maestro.viewHierarchy().root)
                        this.call.respond(hierarchy)
                    }
                    DeviceService.routes(this, maestro)
                    get("/deviceName") {
                        this.call.respond(maestro.deviceName)
                    }
                    get("/isDeviceConnected") {
                        val isConnected: Boolean = maestro.driver.isActiveNow()
                        this.call.respond(isConnected)
                    }
                }
            }
            println("Server started on port $port")
        }.start()
    }
}