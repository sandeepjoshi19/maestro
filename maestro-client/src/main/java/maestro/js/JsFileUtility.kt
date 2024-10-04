package maestro.js

import org.mozilla.javascript.ScriptableObject
import java.nio.file.Files
import java.nio.file.Paths

class JsFileUtility : ScriptableObject() {
    fun saveToFile(path: String, content: String): String {
        try {
            Files.write(Paths.get(path), content.toByteArray())
            return "SUCCESS"
        } catch (e: Exception){
            return "FAILURE "+e.message
        }
    }

    override fun getClassName(): String {
        return "JsFileUtility"
    }
}