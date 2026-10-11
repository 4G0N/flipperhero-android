package com.flipperhero.flipper

import com.flipperdevices.protobuf.CommandStatus
import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.GetErrorResponse
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.InfoResponse
import com.flipperdevices.protobuf.storage.ListResponse
import com.flipperdevices.protobuf.storage.ReadResponse
import com.flipperdevices.protobuf.storage.StatResponse
import com.flipperdevices.protobuf.system.UpdateResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A small Flipper on the other end of [MockTransport]: an in-memory SD card plus the app and
 * system commands the client uses. Answers with real protobuf messages, so tests exercise the
 * actual [FlipperRpcClient] code paths instead of a protocol fake. Mirrors Swift `SimulatedFlipper`.
 */
class SimulatedFlipper(files: Map<String, String> = emptyMap()) {
    val transport = MockTransport()
    private val lock = Any()
    private val filesMap = HashMap<String, ByteArray>()
    private val dirsSet = hashSetOf("/ext")
    private val writes = HashMap<Int, Pair<String, ByteArray>>()

    /** Status to answer for a given kind of request, to simulate failures. */
    var failures: MutableMap<String, CommandStatus> = HashMap()
    var updateResult: UpdateResponse.UpdateResultCode = UpdateResponse.UpdateResultCode.OK
    var appErrorText: String = ""

    val files: Map<String, ByteArray> get() = synchronized(lock) { HashMap(filesMap) }
    val dirs: Set<String> get() = synchronized(lock) { HashSet(dirsSet) }
    val requests: List<Main> get() = transport.requests

    init {
        for ((path, text) in files) {
            filesMap[path] = text.toByteArray(Charsets.UTF_8)
            var parent = path.substringBeforeLast('/', "")
            while (parent.length > 1) {
                dirsSet.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        transport.responder = { request -> synchronized(lock) { respond(request) } }
    }

    fun client(timeout: Duration = 2.seconds): FlipperRpcClient {
        val client = FlipperRpcClient(transport, timeout)
        client.start()
        return client
    }

    /** Short names of what arrived, e.g. "appStart Sub-GHz RPC", for asserting sequences. */
    val log: List<String>
        get() = requests.mapNotNull { message ->
            when (message.contentCase) {
                Main.ContentCase.STORAGE_STAT_REQUEST -> "stat ${message.storageStatRequest.path}"
                Main.ContentCase.STORAGE_LIST_REQUEST -> "list ${message.storageListRequest.path}"
                Main.ContentCase.STORAGE_READ_REQUEST -> "read ${message.storageReadRequest.path}"
                Main.ContentCase.STORAGE_WRITE_REQUEST -> "write ${message.storageWriteRequest.path}"
                Main.ContentCase.STORAGE_MKDIR_REQUEST -> "mkdir ${message.storageMkdirRequest.path}"
                Main.ContentCase.STORAGE_DELETE_REQUEST ->
                    "delete ${message.storageDeleteRequest.path}" +
                        if (message.storageDeleteRequest.recursive) " recursive" else ""
                Main.ContentCase.STORAGE_RENAME_REQUEST ->
                    "rename ${message.storageRenameRequest.oldPath} ${message.storageRenameRequest.newPath}"
                Main.ContentCase.STORAGE_INFO_REQUEST -> "info ${message.storageInfoRequest.path}"
                Main.ContentCase.APP_START_REQUEST ->
                    "appStart ${message.appStartRequest.name} ${message.appStartRequest.args}"
                Main.ContentCase.APP_LOAD_FILE_REQUEST -> "appLoad ${message.appLoadFileRequest.path}"
                Main.ContentCase.APP_BUTTON_PRESS_REQUEST -> "appPress ${message.appButtonPressRequest.args}"
                Main.ContentCase.APP_BUTTON_RELEASE_REQUEST -> "appRelease"
                Main.ContentCase.APP_EXIT_REQUEST -> "appExit"
                Main.ContentCase.APP_GET_ERROR_REQUEST -> "appError"
                Main.ContentCase.SYSTEM_REBOOT_REQUEST -> "reboot ${message.systemRebootRequest.mode.name.lowercase()}"
                Main.ContentCase.SYSTEM_UPDATE_REQUEST -> "update ${message.systemUpdateRequest.updateManifest}"
                Main.ContentCase.SYSTEM_PLAY_AUDIOVISUAL_ALERT_REQUEST -> "alert"
                else -> null
            }
        }

    private fun status(kind: String): CommandStatus = failures[kind] ?: CommandStatus.OK

    private fun answer(request: Main, kind: String, content: (Main.Builder.() -> Unit)? = null): List<Main> {
        val s = status(kind)
        return listOf(reply(request, status = s, content = if (s == CommandStatus.OK) content else null))
    }

    private fun respond(request: Main): List<Main> {
        when (request.contentCase) {
            Main.ContentCase.STORAGE_STAT_REQUEST -> {
                val path = request.storageStatRequest.path
                filesMap[path]?.let { data ->
                    return answer(request, "stat") {
                        setStorageStatResponse(
                            StatResponse.newBuilder()
                                .setFile(File.newBuilder().setType(File.FileType.FILE).setSize(data.size)),
                        )
                    }
                }
                if (dirsSet.contains(path)) {
                    return answer(request, "stat") {
                        setStorageStatResponse(StatResponse.newBuilder().setFile(File.newBuilder().setType(File.FileType.DIR)))
                    }
                }
                return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
            }

            Main.ContentCase.STORAGE_READ_REQUEST -> {
                val data = filesMap[request.storageReadRequest.path]
                    ?: return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
                // The firmware sends files in 512-byte parts.
                val parts = ArrayList<Main>()
                var offset = 0
                do {
                    val end = minOf(offset + 512, data.size)
                    val chunk = data.copyOfRange(offset, end)
                    parts.add(
                        reply(request, hasNext = end < data.size) {
                            setStorageReadResponse(ReadResponse.newBuilder().setFile(File.newBuilder().setData(chunk.toByteString())))
                        },
                    )
                    offset = end
                } while (offset < data.size)
                return parts
            }

            Main.ContentCase.STORAGE_WRITE_REQUEST -> {
                val r = request.storageWriteRequest
                val existing = writes[request.commandId]
                val combined = (existing?.second ?: ByteArray(0)) + r.file.data.toByteArray()
                val pending = (existing?.first ?: r.path) to combined
                if (request.hasNext) {
                    writes[request.commandId] = pending
                    return emptyList()
                }
                writes.remove(request.commandId)
                if (status("write") == CommandStatus.OK) filesMap[pending.first] = pending.second
                return answer(request, "write")
            }

            Main.ContentCase.STORAGE_MKDIR_REQUEST -> {
                val path = request.storageMkdirRequest.path
                if (dirsSet.contains(path)) return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_EXIST))
                dirsSet.add(path)
                return answer(request, "mkdir")
            }

            Main.ContentCase.STORAGE_DELETE_REQUEST -> {
                val r = request.storageDeleteRequest
                val iterator = filesMap.keys.toList()
                for (key in iterator) {
                    if (key == r.path || (r.recursive && key.startsWith(r.path + "/"))) filesMap.remove(key)
                }
                dirsSet.remove(r.path)
                return answer(request, "delete")
            }

            Main.ContentCase.STORAGE_RENAME_REQUEST -> {
                val r = request.storageRenameRequest
                val data = filesMap.remove(r.oldPath)
                    ?: return listOf(reply(request, status = CommandStatus.ERROR_STORAGE_NOT_EXIST))
                filesMap[r.newPath] = data
                return answer(request, "rename")
            }

            Main.ContentCase.STORAGE_INFO_REQUEST ->
                return answer(request, "info") {
                    setStorageInfoResponse(InfoResponse.newBuilder().setTotalSpace(1000).setFreeSpace(400))
                }

            Main.ContentCase.STORAGE_LIST_REQUEST -> {
                val prefix = request.storageListRequest.path + "/"
                val response = ListResponse.newBuilder()
                for ((path, data) in filesMap) {
                    if (path.startsWith(prefix) && !path.substring(prefix.length).contains("/")) {
                        response.addFile(File.newBuilder().setName(path.substring(prefix.length)).setSize(data.size))
                    }
                }
                for (dir in dirsSet) {
                    if (dir.startsWith(prefix) && !dir.substring(prefix.length).contains("/")) {
                        response.addFile(File.newBuilder().setName(dir.substring(prefix.length)).setType(File.FileType.DIR))
                    }
                }
                return answer(request, "list") { setStorageListResponse(response) }
            }

            Main.ContentCase.APP_START_REQUEST -> return answer(request, "appStart")
            Main.ContentCase.APP_LOAD_FILE_REQUEST -> return answer(request, "appLoad")
            Main.ContentCase.APP_BUTTON_PRESS_REQUEST -> return answer(request, "appPress")
            Main.ContentCase.APP_BUTTON_RELEASE_REQUEST -> return answer(request, "appRelease")
            Main.ContentCase.APP_EXIT_REQUEST -> return answer(request, "appExit")
            Main.ContentCase.APP_GET_ERROR_REQUEST ->
                return answer(request, "appError") {
                    setAppGetErrorResponse(
                        GetErrorResponse.newBuilder()
                            .setCode(if (appErrorText.isEmpty()) 0 else 3)
                            .setText(appErrorText),
                    )
                }

            Main.ContentCase.SYSTEM_REBOOT_REQUEST -> return answer(request, "reboot")
            Main.ContentCase.SYSTEM_PLAY_AUDIOVISUAL_ALERT_REQUEST -> return answer(request, "alert")
            Main.ContentCase.SYSTEM_UPDATE_REQUEST ->
                return answer(request, "update") {
                    setSystemUpdateResponse(UpdateResponse.newBuilder().setCode(updateResult))
                }

            else -> return listOf(reply(request))
        }
    }
}
