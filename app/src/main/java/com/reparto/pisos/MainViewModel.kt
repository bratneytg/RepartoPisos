package com.reparto.pisos

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        const val MAX_PHOTOS = 8
    }

    private val prefs = app.getSharedPreferences("reparto", Context.MODE_PRIVATE)
    private val imagesDir = File(app.cacheDir, "images").apply { mkdirs() }

    // ---- datos de entrada (se guardan para no perderlos si Android cierra la app)
    var parcelsText by mutableStateOf(prefs.getString("text", "") ?: "")
        private set
    val photos = mutableStateListOf<String>()

    // ---- ajustes
    var apiKey by mutableStateOf(prefs.getString("apiKey", "") ?: "")
        private set
    var model by mutableStateOf(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        private set
    var useAi by mutableStateOf(prefs.getBoolean("useAi", true))
        private set
    var topIsHighest by mutableStateOf(prefs.getBoolean("topIsHighest", true))
        private set
    var bottomFloor by mutableStateOf(prefs.getInt("bottomFloor", 0))
        private set

    // ---- estado de la búsqueda
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<List<ParcelResult>?>(null)
        private set
    var panel by mutableStateOf<List<PanelEntry>>(emptyList())
        private set
    var usedAi by mutableStateOf(false)
        private set

    init {
        (prefs.getString("photos", "") ?: "")
            .split('\n')
            .filter { it.isNotBlank() && File(it).exists() }
            .forEach { photos.add(it) }
    }

    // ------------------------------------------------------------- entrada

    fun onTextChange(t: String) {
        parcelsText = t
        prefs.edit().putString("text", t).apply()
    }

    private fun savePhotos() {
        prefs.edit().putString("photos", photos.joinToString("\n")).apply()
    }

    private fun newImageFile(): File {
        imagesDir.mkdirs()
        return File(imagesDir, "foto_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
    }

    fun prepareCameraUri(): Uri {
        val f = newImageFile()
        prefs.edit().putString("pending", f.absolutePath).apply()
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    fun onCameraResult(ok: Boolean) {
        val path = prefs.getString("pending", null) ?: return
        prefs.edit().remove("pending").apply()
        val f = File(path)
        if (ok && f.exists() && f.length() > 0 && photos.size < MAX_PHOTOS) {
            photos.add(path)
            savePhotos()
        } else {
            f.delete()
        }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val f = newImageFile()
                        getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                            f.outputStream().use { out -> input.copyTo(out) }
                        }
                        f.absolutePath
                    }.getOrNull()
                }
            }
            added.forEach { if (photos.size < MAX_PHOTOS) photos.add(it) else File(it).delete() }
            savePhotos()
        }
    }

    fun removePhoto(path: String) {
        photos.remove(path)
        File(path).delete()
        savePhotos()
    }

    fun clearAll() {
        parcelsText = ""
        prefs.edit().putString("text", "").apply()
        photos.toList().forEach { File(it).delete() }
        photos.clear()
        savePhotos()
        results = null
        panel = emptyList()
        error = null
        notice = null
    }

    fun saveSettings(key: String, model: String, useAi: Boolean, topIsHighest: Boolean, bottomFloor: Int) {
        this.apiKey = AiClient.cleanKey(key)
        this.model = model.trim().ifEmpty { DEFAULT_MODEL }
        this.useAi = useAi
        this.topIsHighest = topIsHighest
        this.bottomFloor = bottomFloor
        prefs.edit()
            .putString("apiKey", this.apiKey)
            .putString("model", this.model)
            .putBoolean("useAi", useAi)
            .putBoolean("topIsHighest", topIsHighest)
            .putInt("bottomFloor", bottomFloor)
            .apply()
    }

    // ------------------------------------------------------------ búsqueda

    fun search() {
        val parcels = Matcher.parseParcels(parcelsText)
        if (parcels.isEmpty()) {
            error = "Escribe al menos un nombre."
            return
        }
        if (photos.isEmpty()) {
            error = "Añade al menos una foto del timbre."
            return
        }
        error = null
        notice = null
        results = null
        loading = true
        val paths = photos.toList()

        viewModelScope.launch {
            try {
                var aiError: String? = null
                if (useAi && apiKey.isNotBlank()) {
                    try {
                        AiClient.keyProblem(apiKey)?.let { throw java.io.IOException(it) }
                        status = "Leyendo el timbre con la IA… (20-60 s)"
                        val (res, entries) = runAi(parcels, paths)
                        results = res
                        panel = entries
                        usedAi = true
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        aiError = e.message ?: e.toString()
                    }
                }
                status = "Leyendo el timbre en el móvil…"
                val (res, entries) = runLocal(parcels, paths)
                results = res
                panel = entries
                usedAi = false
                if (aiError != null) {
                    notice = "La IA no ha funcionado ($aiError). He usado la lectura local, que es menos fiable."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Error: ${e.message ?: e}"
            } finally {
                loading = false
                status = ""
            }
        }
    }

    private suspend fun runAi(parcels: List<Parcel>, paths: List<String>): Pair<List<ParcelResult>, List<PanelEntry>> {
        val jpegs = withContext(Dispatchers.IO) { paths.map { ImageUtil.jpegBytes(ImageUtil.load(it, 2000)) } }
        val text = AiClient.call(apiKey, model, jpegs, AiClient.buildPrompt(parcels))
        return AiClient.toResults(parcels, AiClient.extractJson(text))
    }

    private suspend fun runLocal(parcels: List<Parcel>, paths: List<String>): Pair<List<ParcelResult>, List<PanelEntry>> {
        val entries = ArrayList<PanelEntry>()
        for (p in paths) {
            val bitmap = withContext(Dispatchers.IO) { ImageUtil.load(p, 3000) }
            val lines = Ocr.read(bitmap)
            entries += PanelParser.build(lines, topIsHighest, bottomFloor)
        }
        return Matcher.matchAll(parcels, entries) to entries
    }
}
