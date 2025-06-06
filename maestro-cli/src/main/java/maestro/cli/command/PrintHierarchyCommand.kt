/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.command

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.TreeNode
import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.yellow
import maestro.utils.CliInsights
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount
import picocli.CommandLine
import picocli.CommandLine.Option
import java.lang.StringBuilder

@CommandLine.Command(
    name = "hierarchy",
    description = [
        "Print out the view hierarchy of the connected device"
    ],
    hidden = true
)
class PrintHierarchyCommand : Runnable {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        names = ["--android-webview-hierarchy"],
        description = ["Set to \"devtools\" to use Chrome dev tools for Android WebView hierarchy"],
        hidden = true,
    )
    private var androidWebViewHierarchy: String? = null

    @CommandLine.Option(
        names = ["--reinstall-driver"],
        description = ["[Beta] Reinstalls xctestrunner driver before running the test. Set to false if the driver shouldn't be reinstalled"],
        hidden = true
    )
    private var reinstallDriver: Boolean = true

    @Option(
        names = ["--apple-team-id"],
        description = ["The Team ID is a unique 10-character string generated by Apple that is assigned to your team's apple account."],
        hidden = true
    )
    private var appleTeamId: String? = null

    @CommandLine.Option(
        names = ["--compact"],
        description = ["Output in CSV format with element_num,depth,attributes,parent_num columns"],
        hidden = false
    )
    private var compact: Boolean = false

    @CommandLine.Option(
        names = ["--device-index"],
        description = ["The index of the device to run the test on"],
        hidden = true
    )
    private var deviceIndex: Int? = null

    override fun run() {
        TestDebugReporter.install(
            debugOutputPathAsString = null,
            flattenDebugOutput = false,
            printToConsole = parent?.verbose == true,
        )

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            teamId = appleTeamId,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
            reinstallDriver = reinstallDriver,
            deviceIndex = deviceIndex
        ) { session ->
            session.maestro.setAndroidChromeDevToolsEnabled(androidWebViewHierarchy == "devtools")
            val callback: (Insight) -> Unit = {
                val message = StringBuilder()
                val level = it.level.toString().lowercase().replaceFirstChar(Char::uppercase)
                message.append(level.yellow() + ": ")
                it.message.chunkStringByWordCount(12).forEach { chunkedMessage ->
                    message.append("$chunkedMessage ")
                }
                println(message.toString())
            }
            val insights = CliInsights

            insights.onInsightsUpdated(callback)

            val tree = session.maestro.viewHierarchy().root

            insights.unregisterListener(callback)

            if (compact) {
                // Output in CSV format
                println("element_num,depth,attributes,parent_num")
                val nodeToId = mutableMapOf<TreeNode, Int>()
                val csv = StringBuilder()
                
                // Assign IDs to each node
                var counter = 0
                tree?.aggregate()?.forEach { node ->
                    nodeToId[node] = counter++
                }
                
                // Process tree recursively to generate CSV
                processTreeToCSV(tree, 0, null, nodeToId, csv)
                
                println(csv.toString())
            } else {
                // Original JSON output format
                val hierarchy = jacksonObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(tree)
                
                println(hierarchy)
            }
        }
    }
    
    private fun processTreeToCSV(
        node: TreeNode?, 
        depth: Int, 
        parentId: Int?, 
        nodeToId: Map<TreeNode, Int>,
        csv: StringBuilder
    ) {
        if (node == null) return
        
        val nodeId = nodeToId[node] ?: return
        
        // Build attributes string
        val attributesList = mutableListOf<String>()
        
        // Add normal attributes
        node.attributes.forEach { (key, value) ->
            if (value.isNotEmpty() && value != "false") {
                attributesList.add("$key=$value")
            }
        }
        
        // Add boolean properties if true
        if (node.clickable == true) attributesList.add("clickable=true")
        if (node.enabled == true) attributesList.add("enabled=true")
        if (node.focused == true) attributesList.add("focused=true")
        if (node.checked == true) attributesList.add("checked=true")
        if (node.selected == true) attributesList.add("selected=true")
        
        // Join all attributes with "; "
        val attributesString = attributesList.joinToString("; ")
        
        // Escape quotes in the attributes string if needed
        val escapedAttributes = attributesString.replace("\"", "\"\"")
        
        // Add this node to CSV
        csv.append("$nodeId,$depth,\"$escapedAttributes\",${parentId ?: ""}\n")
        
        // Process children
        node.children.forEach { child ->
            processTreeToCSV(child, depth + 1, nodeId, nodeToId, csv)
        }
    }

    private fun removeEmptyValues(tree: TreeNode?): TreeNode? {
        if (tree == null) {
            return null
        }

        return TreeNode(
            attributes = tree.attributes.filter {
                it.value != "" && it.value.toString() != "false"
            }.toMutableMap(),
            children = tree.children.map { removeEmptyValues(it) }.filterNotNull(),
            checked = if(tree.checked == true) true else null,
            clickable = if(tree.clickable == true) true else null,
            enabled = if(tree.enabled == true) true else null,
            focused = if(tree.focused == true) true else null,
            selected = if(tree.selected == true) true else null,
        )
    }
}
