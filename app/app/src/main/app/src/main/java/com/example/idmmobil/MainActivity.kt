package com.example.idmmobil

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

class DownloadTask(val url: String, val name: String) {
    var status by mutableStateOf("Bekliyor")
    var progress by mutableStateOf(0f)
    var downloadedBytes by mutableStateOf(0L)
    var job: Job? = null
    var probed = false
    var total = -1L
    var canResume = false
    var parts = 1
    var partDone = LongArray(1)
}

val client = OkHttpClient()

fun fileNameFrom(url: String): String {
    val n = url.substringBefore('?').substringAfterLast('/').ifBlank { "dosya" }
    return n.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

fun tempFile(ctx: Context, name: String): File {
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
    return File(dir, name)
}

fun saveToDownloads(ctx: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return false
    ctx.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    }
    file.delete()
    return true
}

suspend fun runDownload(file: File, t: DownloadTask) = withContext(Dispatchers.IO) {
    if (!t.probed) {
        try {
            client.newCall(Request.Builder().url(t.url).head().build()).execute().use {
                t.total = it.header("Content-Length")?.toLongOrNull() ?: -1L
                t.canResume = it.header("Accept-Ranges") == "bytes" && t.total > 0
            }
        } catch (e: Exception) {
            t.total = -1L
            t.canResume = false
        }
        t.probed = true
        t.parts = if (t.canResume && t.total > 1_000_000L) 4 else 1
        t.partDone = LongArray(t.parts)
    }
    if (!t.canResume) {
        t.partDone[0] = 0L
        if (file.exists()) file.delete()
    } else {
        RandomAccessFile(file, "rw").use { if (it.length() != t.total) it.setLength(t.total) }
    }
    val n = t.parts
    val size = if (t.total > 0) t.total / n else 0L
    coroutineScope {
        for (i in 0 until n) launch {
            val start = i * size
            val end = if (i == n - 1) t.total - 1 else start + size - 1
            val from = if (t.canResume) start + t.partDone[i] else 0L
            if (t.canResume && from > end) return@launch
            val rb = Request.Builder().url(t.url)
            if (t.canResume) rb.header("Range", "bytes=$from-$end")
            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                if (t.canResume && resp.code != 206) throw RuntimeException("Sunucu parçalı indirmeyi desteklemiyor")
                val body = resp.body ?: throw RuntimeException("Boş yanıt")
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(from)
                    val buf = ByteArray(32 * 1024)
                    body.byteStream().use { ins ->
                        while (true) {
                            ensureActive()
                            val r = ins.read(buf)
                            if (r < 0) break
                            raf.write(buf, 0, r)
                            t.partDone[i] += r.toLong()
                            val done = t.partDone.sum()
                            t.downloadedBytes = done
                            if (t.total > 0) {
                                t.progress = (done.toFloat() / t.total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun startTask(scope: CoroutineScope, ctx: Context, t: DownloadTask) {
    t.job?.cancel()
    t.status = "İndiriliyor"
    t.job = scope.launch {
        try {
            val file = tempFile(ctx, t.name)
            runDownload(file, t)
            t.progress = 1f
            val saved = withContext(Dispatchers.IO) { saveToDownloads(ctx, file) }
            t.status = if (saved) "Tamamlandı (İndirilenler klasörü)" else "Tamamlandı"
        } catch (e: CancellationException) {
            t.status = "Duraklatıldı"
            throw e
        } catch (e: Exception) {
            t.status = "Hata: ${e.message}"
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val tasks = remember { mutableStateListOf<DownloadTask>() }
    var link by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("IDM Mobil", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("İndirme linki") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val u = link.trim()
                if (u.startsWith("http")) {
                    val t = DownloadTask(u, fileNameFrom(u))
                    tasks.add(0, t)
                    startTask(scope, ctx, t)
                    link = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("İndir") }

        LazyColumn {
            items(tasks) { t ->
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { t.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        val info = if (t.total > 0) "%${(t.progress * 100).toInt()}"
                        else "${t.downloadedBytes / 1_000_000} MB"
                        Text("${t.status} - $info")
                        Row {
                            TextButton(onClick = { t.job?.cancel() }) { Text("Duraklat") }
                            TextButton(onClick = { startTask(scope, ctx, t) }) { Text("Devam") }
                        }
                    }
                }
            }
        }
    }
}
