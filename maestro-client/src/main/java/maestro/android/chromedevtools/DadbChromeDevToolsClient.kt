package maestro.android.chromedevtools

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dadb.Dadb
import maestro.Bounds
import maestro.Maestro
import maestro.TreeNode
import maestro.utils.HttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.use
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureTimeMillis

private data class RuntimeResponse<T>(
    val result: RemoteObject<T>?
)

private data class RemoteObject<T>(
    val type: String,
    val value: T?,
)

data class WebViewInfo(
    val socketName: String?,
    val webSocketDebuggerUrl: String?,
    val visible: Boolean?,
    val attached: Boolean?,
    val empty: Boolean?,
    val screenX: Int?,
    val screenY: Int?,
    val width: Int?,
    val height: Int?,
    val type: String?,
    val url: String?
)

private data class WebViewResponse(
    val description: String?,
    val title: String?,
    val type: String?,
    val url: String?,
    val webSocketDebuggerUrl: String?,
)

private data class WebViewDescription(
    val visible: Boolean?,
    val attached: Boolean?,
    val empty: Boolean?,
    val screenX: Int?,
    val screenY: Int?,
    val width: Int?,
    val height: Int?,
)

private data class DevToolsResponse<T>(
    val id: Int,
    val result: T,
    val error: RpcError?
)

data class RpcError(
    val code: Int?,
    val message: String?
)

data class VisibilityResult(
    val type: String,
    val value: String
)

class DadbChromeDevToolsClient(private val dadb: Dadb): Closeable {

    private val json = jacksonObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val okhttp = HttpClient.build("DadbChromeDevToolsClient").newBuilder()
        .dadb(dadb)
        .callTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    private val script = Maestro::class.java.getResourceAsStream("/maestro-web.js")?.let {
        it.bufferedReader().use { br ->
            br.readText()
        }
    } ?: error("Could not read maestro web script")

    override fun close() {
        okhttp.dispatcher.executorService.shutdown()
        okhttp.connectionPool.evictAll()
        okhttp.cache?.close()
    }

    fun getWebViewTreeNodes(bounds: Bounds?): List<TreeNode> {
        return getWebViewInfos()
            .filter {
                if( it.type != "page"){
                    false
                }
                else{
                    val visibilityResult = evaluateScript<RuntimeResponse<String>>(it.socketName!!, it.webSocketDebuggerUrl!!,"document.visibilityState")?.result?.value
                    visibilityResult == "visible"
                }
            }
            .map { info ->
                val response = evaluateScript<RuntimeResponse<TreeNode>>(info.socketName!!, info.webSocketDebuggerUrl!!, "$script; maestro.viewportX = ${bounds?.x ?: info.screenX }; maestro.viewportY = ${bounds?.y ?: info.screenY}; maestro.viewportWidth = ${bounds?.width ?: info.width}; maestro.viewportHeight = ${bounds?.height ?: info.height}; window.maestro.getContentDescription();")?.result
                response?.value ?: TreeNode()
            }
    }

    inline fun <reified T> evaluateScript(socketName: String, webSocketDebuggerUrl: String, script: String) = makeRequest<T>(
        socketName = socketName,
        webSocketDebuggerUrl = webSocketDebuggerUrl,
        method = "Runtime.evaluate",
        params = mapOf(
            "expression" to script,
            "returnByValue" to true,
        ),
    )

    inline fun <reified T> makeRequest(socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T? {
        val resultTypeReference = object : TypeReference<T>() {}
        return makeRequest(resultTypeReference, socketName, webSocketDebuggerUrl, method, params)
    }

    fun <T> makeRequest(resultTypeReference: TypeReference<T>, socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T? {
        val request = json.writeValueAsString(mapOf("id" to 1, "method" to method, "params" to params))
        val url = webSocketDebuggerUrl.replace("ws", "http").toHttpUrl().newBuilder()
            .host("localabstract.$socketName.adb")
            .build()
        val response = makeSingleWebsocketRequest(url, request)
        return try {
            if (response.isBlank()) return null
            val resultType = TypeFactory.defaultInstance().constructType(resultTypeReference)
            val responseType = TypeFactory.defaultInstance()
                .constructParametricType(DevToolsResponse::class.java, resultType)
            json.readValue<DevToolsResponse<T>>(response, responseType).result
        } catch (e: JsonProcessingException) {
            throw IOException("Failed to parse DOM snapshot: $response", e)
        }
    }

    fun getWebViewInfos(): List<WebViewInfo> {
        return getWebViewSocketNames().flatMap(::getWebViewInfos)
    }

    fun makeSingleWebsocketRequest(url: HttpUrl, message: String): String {
        val future = CompletableFuture<String>()
        val ws = okhttp.newWebSocket(
            Request.Builder()
                .url(url)
                .build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    future.complete(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    future.completeExceptionally(t)
                }
            }
        )
        ws.send(message)
        val response = try {
            future.get(200, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
//            throw TimeoutException("Timed out waiting for websocket response")
            return ""
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        ws.close(1000, null)
        return response
    }

    private fun getWebViewInfos(socketName: String): List<WebViewInfo> {
        val url = "http://localabstract.$socketName.adb/json"

        val call = okhttp.newCall(Request.Builder()
            .url(url)
            .header("Host", "localhost:9222") // Expected by devtools server
            .build())

        val response = try {
            call.execute()
        } catch (e: IOException) {
            logger.error("IOException while getting WebView info from $url. Defaulting to empty list.", e)
            return emptyList()
        }

        if (response.code != 200) {
            logger.error("Request to get WebView infos failed with code ${response.code}. Defaulting to empty list.")
            return emptyList()
        }

        val body = response.body?.string()
        return try {
            json.readValue<List<WebViewResponse>>(body?: Gson().toJson("[]")).mapNotNull { parsed ->
                // Description is empty for eg. service workers
                var description: WebViewDescription? = null
                if ( !parsed.description.isNullOrBlank() && isJson(parsed.description)) {
                    description = json.readValue(parsed.description, WebViewDescription::class.java)
                }
                WebViewInfo(
                    socketName = socketName,
                    webSocketDebuggerUrl = parsed.webSocketDebuggerUrl,
                    visible = description?.visible,
                    attached = description?.attached,
                    empty = description?.empty,
                    screenX = description?.screenX,
                    screenY = description?.screenY,
                    width = description?.width,
                    height = description?.height,
                    type = parsed.type,
                    url = parsed.url
                )
            }.filter { (it.attached == true && it.visible == true && it.empty != true ) || it.type == "page"}
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("Failed to parse WebView chrome dev tools response:\n$body", e)
        }
    }

    fun isJson(string: String): Boolean {
        return try {
            JsonParser.parseString(string)
            true
        } catch (e: JsonSyntaxException) {
            false
        }
    }

    private fun getWebViewSocketNames(): Set<String> {
        val response = dadb.shell("cat /proc/net/unix")
        if (response.exitCode != 0) {
            throw IllegalStateException("Failed get WebView socket names. Command 'cat /proc/net/unix' failed: ${response.allOutput}")
        }
        return response.allOutput.trim().lines().mapNotNull { line ->
            line.split(Regex("\\s+")).lastOrNull()?.takeIf { it.startsWith(CHROME_WEBVIEW_SOCKET_PREFIX) || it.startsWith(WEB_VIEW_SOCKET_PREFIX) }?.substring(1)
        }.toSet()
    }

    companion object {
        private const val CHROME_WEBVIEW_SOCKET_PREFIX = "@chrome_devtools_remote"
        private const val WEB_VIEW_SOCKET_PREFIX = "@webview_devtools_remote_"

        private val logger = LoggerFactory.getLogger(Maestro::class.java)
    }
}

fun main() {
    (Dadb.discover() ?: throw IllegalStateException("No devices found")).use { dadb ->
        DadbChromeDevToolsClient(dadb).apply {
                measureTimeMillis {
                    getWebViewTreeNodes(null).forEach {
                        println(Gson().toJson(it))
                    }
                }.also { println("time: $it") }
        }
    }
}