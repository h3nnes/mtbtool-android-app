package dev.henrik.mtbtool

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class ImportCommand(
    val args: List<String>,
    val rawLine: String
)

sealed class ImportEvent {
    data class Progress(val done: Int, val total: Int, val command: ImportCommand, val exitCode: Int) : ImportEvent()
    data class Done(val ok: Int, val fail: Int) : ImportEvent()
    data class Error(val message: String) : ImportEvent()
}

object BulkImporter {

    fun parseFile(resolver: ContentResolver, uri: Uri): List<ImportCommand> {
        val lines = resolver.openInputStream(uri)?.bufferedReader()?.readLines()
            ?: return emptyList()
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val tokens = line.split(Regex("\\s+"))
                val args = if (tokens.firstOrNull() == "/vendor/bin/mtb") tokens.drop(1) else tokens
                ImportCommand(args = args, rawLine = line)
            }
    }

    fun import(
        commands: List<ImportCommand>,
        shizukuManager: ShizukuManager
    ): Flow<ImportEvent> = flow {
        var ok = 0
        var fail = 0
        commands.forEachIndexed { index, cmd ->
            val exitCode = try {
                shizukuManager.execMtb(cmd.args.toTypedArray())
            } catch (e: Exception) {
                emit(ImportEvent.Error("UserService disconnected: ${e.message}"))
                return@flow
            }
            if (exitCode == 0) ok++ else fail++
            emit(ImportEvent.Progress(done = index + 1, total = commands.size, command = cmd, exitCode = exitCode))
        }
        emit(ImportEvent.Done(ok = ok, fail = fail))
    }.flowOn(Dispatchers.IO)
}
