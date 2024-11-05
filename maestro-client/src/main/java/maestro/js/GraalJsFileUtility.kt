package maestro.js

import org.graalvm.polyglot.HostAccess.Export
import java.nio.file.Files
import java.nio.file.Paths


class GraalJsFileUtility() {

    @Export
    fun saveToFile(path: String, content: String): String {
        try {
            Files.write(Paths.get(path), content.toByteArray())
            return "SUCCESS"
        } catch (e: Exception){
            return "FAILURE "+e.message
        }
    }
}