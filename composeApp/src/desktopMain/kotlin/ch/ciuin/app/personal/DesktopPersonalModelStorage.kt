package ch.ciuin.app.personal

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class DesktopPersonalModelStorage(private val file: Path = desktopPersonalModelPath()) : PersonalModelStorage {
    override fun read(): String? = try {
        Files.readString(file)
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(json: String) {
        val directory = Files.createDirectories(file.parent)
        val pending = Files.createTempFile(directory, ".personal-model-", ".tmp")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                output.write(json.encodeToByteArray())
                output.fd.sync()
            }
            Files.move(pending, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}

private fun desktopPersonalModelPath(): Path {
    val home = Paths.get(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    val directory = when {
        os.startsWith("mac") -> home.resolve("Library/Application Support/Ciuin")
        os.startsWith("windows") -> {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            (localAppData ?: home.resolve("AppData/Local")).resolve("Ciuin")
        }
        else -> {
            val stateHome = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
                ?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
            (stateHome ?: home.resolve(".local/state")).resolve("ciuin")
        }
    }
    return directory.resolve("personal-model.json")
}
