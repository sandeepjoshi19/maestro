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
import okio.use
import kotlin.system.measureTimeMillis

private data class RuntimeResponse<T>(
    val result: RemoteObject<T>
)

private data class RemoteObject<T>(
    val type: String,
    val value: T,
)

data class WebViewInfo(
    val socketName: String?,
    val webSocketDebuggerUrl: String?,
    val visible: Boolean?,
    val screenX: Int?,
    val screenY: Int?,
    val width: Int?,
    val height: Int?,
)

private data class WebViewResponse(
    val description: String?,
    val webSocketDebuggerUrl: String?,
)

private data class WebViewDescription(
    val visible: Boolean?,
    val screenX: Int?,
    val screenY: Int?,
    val width: Int?,
    val height: Int?,
)

private data class DevToolsResponse<T>(
    val id: Int,
    val result: T,
)

class DadbChromeDevToolsClient(private val dadb: Dadb) {

    private val json = jacksonObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val httpClient = DadbHttpClient(dadb)

    private val script = Maestro::class.java.getResourceAsStream("/maestro-web.js")?.let {
        it.bufferedReader().use { br ->
            br.readText()
        }
    } ?: error("Could not read maestro web script")

    fun getWebViewTreeNodes(bounds: Bounds?): List<TreeNode> {
        //println("WebView Size: ${getWebViewInfos().size}")
        return getWebViewInfos()
//            .filter {
//                //println(it.webSocketDebuggerUrl)
//                it.visible }
            .map { info ->
                //println("Info: $info")
                evaluateScript<RuntimeResponse<TreeNode>>(info.socketName!!, info.webSocketDebuggerUrl!!, "$script; maestro.viewportX = ${bounds?.x ?: info.screenX }; maestro.viewportY = ${bounds?.y ?: info.screenY}; maestro.viewportWidth = ${bounds?.width ?: info.width}; maestro.viewportHeight = ${bounds?.height ?: info.height}; window.maestro.getContentDescription();").result.value
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

    inline fun <reified T> makeRequest(socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T {
        val resultTypeReference = object : TypeReference<T>() {}
        //println("MakeRequest: $resultTypeReference,  $socketName, $webSocketDebuggerUrl, $method")
        return makeRequest(resultTypeReference, socketName, webSocketDebuggerUrl, method, params)
    }

    fun <T> makeRequest(resultTypeReference: TypeReference<T>, socketName: String, webSocketDebuggerUrl: String, method: String, params: Any?): T {
        val request = json.writeValueAsString(mapOf("id" to 1, "method" to method, "params" to params))
        val response = dadb.open("localabstract:$socketName").use { stream ->
            DadbWebSocketClient(stream, webSocketDebuggerUrl).use { client ->
                client.connect()
                client.sendText(request)
                client.readFrame().payload.string(Charsets.UTF_8)
            }
        }
        //println(request)
        //println(response)
        return try {
            //println(resultTypeReference.javaClass.toString())
            val resultType = TypeFactory.defaultInstance().constructType(resultTypeReference)
            //println(resultType.javaClass.toString())
            val responseType = TypeFactory.defaultInstance()
                .constructParametricType(DevToolsResponse::class.java, resultType)
            //println(responseType.toString())
            json.readValue<DevToolsResponse<T>>(response, responseType).result
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("Failed to parse DOM snapshot: $response", e)
        }
    }

    fun getWebViewInfos(): List<WebViewInfo> {
        //println("WebView Socket Info: ${getWebViewSocketNames().size}")
        return getWebViewSocketNames().flatMap(::getWebViewInfos)
    }

    private fun getWebViewInfos(socketName: String): List<WebViewInfo> {
        //println("Socket Name: $socketName")
        val destination = "localabstract:$socketName"
        // Host and port don't matter here but must be set
        val response = httpClient.get(destination, "http://localhost:9222/json")
        if (response.statusCode != 200) {
            throw IllegalStateException("Failed to get WebView info from $destination.\n$response")
        }

        return try {
            json.readValue<List<WebViewResponse>>(response.body.toByteArray()).map { parsed ->
                //println("Parsed : $parsed")
                var description: WebViewDescription? = null
                if(!parsed.description.isNullOrBlank() && isJson(parsed.description)) {
                    description = json.readValue(parsed.description, WebViewDescription::class.java)
                }
                WebViewInfo(
                    socketName = socketName,
                    webSocketDebuggerUrl = parsed.webSocketDebuggerUrl,
                    visible = description?.visible,
                    screenX = description?.screenX,
                    screenY = description?.screenY,
                    width = description?.width,
                    height = description?.height,
                )
            }
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("Failed to parse WebView chrome dev tools response: ${response.body.string(Charsets.UTF_8)}", e)
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

    private fun getWebViewSocketNames(): List<String> {
        val response = dadb.shell("cat /proc/net/unix")
        //println("Respinse: $response")
        if (response.exitCode != 0) {
            throw IllegalStateException("Failed get WebView socket names. Command 'cat /proc/net/unix' failed: ${response.allOutput}")
        }
        return response.allOutput.trim().lines().mapNotNull { line ->
            line.split(Regex("\\s+")).lastOrNull()?.takeIf { it.startsWith(WEB_VIEW_SOCKET_PREFIX) }?.substring(1)
        }
    }

    companion object {
        private const val WEB_VIEW_SOCKET_PREFIX = "@chrome_devtools_remote"
    }
}

fun main() {
    (Dadb.discover() ?: throw IllegalStateException("No devices found")).use { dadb ->
        //println(dadb)
        measureTimeMillis {
            DadbChromeDevToolsClient(dadb).apply {
//                //println(getWebViewTreeNodes())
                getWebViewTreeNodes(null).forEach {
                    //println(it)
                }
            }
        }.also { println("time: $it") }
    }
}