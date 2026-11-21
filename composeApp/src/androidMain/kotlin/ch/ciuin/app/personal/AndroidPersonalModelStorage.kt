package ch.ciuin.app.personal

import android.content.Context
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class AndroidPersonalModelStorage(context: Context) : PersonalModelStorage {
    private val file by lazy { context.noBackupFilesDir.toPath().resolve("personal-model.json") }

    override fun read(): String? = try {
        Files.readAllBytes(file).decodeToString(throwOnInvalidSequence = true)
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(json: String) {
        val target = file
        val pending = Files.createTempFile(target.parent, ".personal-model-", ".tmp")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write(json.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(pending, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
