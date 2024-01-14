package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.cli.device.Platform
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

object SessionStore {
    private val keyValueStoreMap: HashMap<String, KeyValueStore> = HashMap()
    private val keyValueStore by lazy {
        KeyValueStore(
            Paths
                .get(
                    System.getProperty("user.home"),
                    ".maestro",
                    "sessions"
                )
                .toFile()
                .also { it.parentFile.mkdirs() }
        )
    }

    private fun getKeyValueStore(deviceName: String): KeyValueStore{
//        println("DeviceName: $deviceName")
        if (keyValueStoreMap.containsKey(deviceName)){
//            println("DeviceName Contained")
            return keyValueStoreMap.getValue(deviceName)
        }
        else{
//            println("DeviceName Not Contained")
            val keyValueStore =  KeyValueStore(
                Paths
                    .get(
                        System.getProperty("user.home"),
                        ".maestro",
                        "sessions$deviceName"
                    )
                    .toFile()
                    .also { it.parentFile.mkdirs() }
            )
            keyValueStoreMap[deviceName] = keyValueStore
            return keyValueStore
        }
    }

    fun heartbeat(sessionId: String, platform: Platform, deviceName: String? = null) {
//        println("DeviceName: $deviceName")
       val keyValueStoreUpdated = if (deviceName == null) {
            keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        synchronized(keyValueStoreUpdated) {
            keyValueStoreUpdated.set(
                key(sessionId, platform),
                System.currentTimeMillis().toString()
            )

            pruneInactiveSessions(deviceName)
        }
    }

    private fun pruneInactiveSessions(deviceName: String? = null) {
//        println("DeviceName: $deviceName")
        val finalKeyValueStore = if (deviceName == null) {
            SessionStore.keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        finalKeyValueStore.keys()
            .forEach { key ->
                val lastHeartbeat = keyValueStore.get(key)?.toLongOrNull()
                if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat >= TimeUnit.SECONDS.toMillis(21)) {
                    keyValueStore.delete(key)
                }
            }
    }

    fun delete(sessionId: String, platform: Platform, deviceName: String? = null ) {
//        println("DeviceName: $deviceName")
        val finalKeyValueStore = if (deviceName == null) {
            keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        synchronized(finalKeyValueStore) {
            finalKeyValueStore.delete(
                key(sessionId, platform)
            )
        }
    }

    fun activeSessions(deviceName: String? = null ): List<String> {
        println("activeSessions: ${Thread.currentThread().name}")
        val finalKeyValueStore = if (deviceName == null) {
            keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        synchronized(finalKeyValueStore) {
            return finalKeyValueStore
                .keys()
                .filter { key ->
//                    println("activeSessions End: ${Thread.currentThread().name}")
                    val lastHeartbeat = finalKeyValueStore.get(key)?.toLongOrNull()
                    lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < TimeUnit.SECONDS.toMillis(21)
                }
        }
    }

    fun hasActiveSessions(
        sessionId: String,
        platform: Platform,
        deviceName: String? = null
    ): Boolean {
       println("hasActiveSessions: ${Thread.currentThread().name}")
        val finalKeyValueStore = if (deviceName == null) {
            keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        synchronized(finalKeyValueStore) {
            return activeSessions(deviceName)
                .any { it != key(sessionId, platform) }
        }
    }

    fun <T> withExclusiveLock( deviceName: String? = null, block: () -> T): T {
        println("withExclusiveLock: ${Thread.currentThread().name}")
        val finalKeyValueStore = if (deviceName == null) {
            keyValueStore
        }
        else{
            getKeyValueStore(deviceName)
        }
        return finalKeyValueStore.withExclusiveLock(block)
    }

    private fun key(sessionId: String, platform: Platform): String {
        return "${platform}_$sessionId"
    }

}