package maestro.android.chromedevtools

import dadb.Dadb
import maestro.Bounds
import maestro.TreeNode
import maestro.UiElement
import maestro.UiElement.Companion.toUiElementOrNull
import org.slf4j.LoggerFactory

object AndroidWebViewHierarchy {
    private val LOGGER = LoggerFactory.getLogger(AndroidWebViewHierarchy::class.java)

    fun augmentHierarchy(dadb: Dadb, baseHierarchy: TreeNode, chromeDevToolsEnabled: Boolean): TreeNode {
        LOGGER.info("HAS WEBVIEW: ${hasWebView(baseHierarchy)}")
        if (!chromeDevToolsEnabled) return baseHierarchy
        if (!hasWebView(baseHierarchy)) return baseHierarchy
        // TODO: Adapt to handle chrome in the same way
        LOGGER.info("GETTING WEBVIEW HIERARCHY")
        val webViewNode = findWebViewNodes(baseHierarchy)
        val  bounds = webViewNode?.toUiElementOrNull()?.bounds
        val webViewHierarchy = DadbChromeDevToolsClient(dadb).getWebViewTreeNodes(bounds)
        LOGGER.info("MERGING HIERARCHY")
        val merged = mergeHierarchies(baseHierarchy, webViewHierarchy)
        return merged
    }

    private fun hasWebView(node: TreeNode): Boolean {
        if (isWebView(node)) return true
        for (child in node.children) {
            if (hasWebView(child)) return true
        }
        return false
    }

    private fun isWebView(node: TreeNode): Boolean {
        return node.attributes["class"] == "android.webkit.WebView" || node.attributes["accessibilityText"] == "Web View" || node.attributes["accessibilityText"]?.equals("WebView",true) == true
    }

    fun mergeHierarchies(baseHierarchy: TreeNode, webViewHierarchy: List<TreeNode>): TreeNode {

        if (webViewHierarchy.isEmpty()) return baseHierarchy

        val webViewNode = findWebViewNodes(baseHierarchy)
        if (webViewNode != null) {
            webViewNode.children = webViewHierarchy
        }

        return baseHierarchy

//        val newNodes = mutableListOf<TreeNode>()
//        val baseNodes = baseHierarchy.aggregate().mapNotNull { it.toUiElementOrNull() }
//        // We can use a quadtree here if this is too slow
//        val webViewNodes = webViewHierarchy.flatMap { it.aggregate() }.filter {
//            it.attributes["text"]?.isNotBlank() == true
//                    || it.attributes["resource-id"]?.isNotBlank() == true
//                    || it.attributes["hintText"]?.isNotBlank() == true
//                    || it.attributes["accessibilityText"]?.isNotBlank() == true
//        }.mapNotNull { it.toUiElementOrNull() }.filter {
//            it.bounds.width > 0 && it.bounds.height > 0
//        }
//        webViewNodes.forEach { webViewNode ->
//            if (!baseNodes.any { webViewNode.mergeWith(it) }) {
//                newNodes.add(webViewNode.treeNode)
//            }
//        }
//        if (newNodes.isEmpty()) return baseHierarchy
//        return TreeNode(children = listOf(baseHierarchy) + newNodes)
    }

    private fun findWebViewNodes(node: TreeNode?): TreeNode? {
        if(node == null){
            return null
        }

        if (node.attributes["class"] == "android.webkit.WebView"){
            return node
        }

        for ( child in node.children ){
            return node.children.firstNotNullOfOrNull { findWebViewNodes(it) }
        }
        return null
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

    private fun TreeNode.texts(): List<String> {
        return listOfNotNull(attributes["text"], attributes["hintText"], attributes["accessibilityText"]).filter { it.isNotEmpty() }
    }

    private fun Bounds.intersects(other: Bounds): Boolean {
        return this.x < other.x + other.width && this.x + this.width > other.x && this.y < other.y + other.height && this.y + this.height > other.y
    }
}