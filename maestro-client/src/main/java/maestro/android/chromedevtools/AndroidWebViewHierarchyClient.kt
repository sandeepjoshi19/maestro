package maestro.android.chromedevtools

import dadb.Dadb
import maestro.Bounds
import maestro.TreeNode
import maestro.UiElement
import maestro.UiElement.Companion.toUiElementOrNull
import org.slf4j.LoggerFactory
import java.io.Closeable

class AndroidWebViewHierarchyClient(dadb: Dadb): Closeable {

    private val devToolsClient = DadbChromeDevToolsClient(dadb)

    fun augmentHierarchy(baseHierarchy: TreeNode, chromeDevToolsEnabled: Boolean): TreeNode {
        LOGGER.info("HAS WEBVIEW: ${hasWebView(baseHierarchy)}")
        if (!chromeDevToolsEnabled) return baseHierarchy
        if (!hasWebView(baseHierarchy)) return baseHierarchy
        // TODO: Adapt to handle chrome in the same way
        LOGGER.info("GETTING WEBVIEW HIERARCHY")
        val webViewNode = findWebViewNodes(baseHierarchy)
        val  bounds = webViewNode?.toUiElementOrNull()?.bounds
        val webViewHierarchy = devToolsClient.getWebViewTreeNodes(bounds)
        LOGGER.info("MERGING HIERARCHY")
        val merged = mergeHierarchies(baseHierarchy, webViewHierarchy)
        return merged
    }

    override fun close() {
        devToolsClient.close()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(AndroidWebViewHierarchyClient::class.java)
        fun mergeHierarchies(baseHierarchy: TreeNode, webViewHierarchy: List<TreeNode>): TreeNode {
            if (webViewHierarchy.isEmpty()) return baseHierarchy

            val webViewNode = findWebViewNodes(baseHierarchy)
            if (webViewNode != null) {
                webViewNode.children = webViewHierarchy
            }
            return baseHierarchy
        }

        private fun UiElement.mergeWith(base: UiElement): Boolean {
            if (!this.bounds.intersects(base.bounds)) return false
            val thisTexts = this.treeNode.texts()
            val baseTexts = base.treeNode.texts()
            val thisId = this.treeNode.attributes["resource-id"]
            val baseId = base.treeNode.attributes["resource-id"]

            // web view text is a substring of base text
            val mergeableText = thisTexts.any { baseTexts.any { baseText -> baseText.contains(it) } }

            // web view id matches base id
            val mergeableId = thisId?.isNotEmpty() == true && baseId?.isNotEmpty() == true && thisId == baseId

            // web view id matches base text
            val mergeableId2 = baseTexts.any { it == thisId }

            if (!mergeableText && !mergeableId && !mergeableId2) return false

            val newAttributes = this.treeNode.attributes

            newAttributes.remove("bounds")
            if (baseTexts.isNotEmpty()) {
                newAttributes.remove("text")
                newAttributes.remove("hintText")
                newAttributes.remove("accessibilityText")
            }
            if (baseId?.isNotEmpty() == true) newAttributes.remove("resource-id")

            newAttributes.entries.removeIf { it.value.isEmpty() }

            base.treeNode.attributes += newAttributes

            return true
        }

        private fun findWebViewNodes(node: TreeNode?): TreeNode? {
            if(node == null){
                return null
            }

            if (isWebView(node)){
                return node
            }
            return node.children.firstNotNullOfOrNull { findWebViewNodes(it) }
        }

        private fun TreeNode.texts(): List<String> {
            return listOfNotNull(attributes["text"], attributes["hintText"], attributes["accessibilityText"]).filter { it.isNotEmpty() }
        }

        private fun Bounds.intersects(other: Bounds): Boolean {
            return this.x < other.x + other.width && this.x + this.width > other.x && this.y < other.y + other.height && this.y + this.height > other.y
        }

        private fun hasWebView(node: TreeNode): Boolean {
            if (isWebView(node)) return true
            for (child in node.children) {
                if (hasWebView(child)) return true
            }
            return false
        }

        private fun isWebView(node: TreeNode): Boolean {
            return node.attributes["class"] == "android.webkit.WebView" || node.attributes["accessibilityText"]?.equals("Web View", true) == true || node.attributes["accessibilityText"]?.equals("WebView",true) == true
        }
    }
}