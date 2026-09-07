package io.github.teccheck.gear360app.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

private const val TAG = "G360-MEDIA"
private const val PREFS_NAME = "gear360_media_transfer"
private const val KEY_DESTINATION_TREE_URI = "destination_tree_uri"
private const val KEY_AUTO_TRANSFER = "auto_transfer"
private const val KEY_IMPORTED_URLS = "imported_urls"

data class MediaImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = errorMessage == null
}

object Gear360MediaTransfer {
    private val discoveryEndpoints = listOf(
        "http://192.168.107.1:7679/",
        "http://192.168.107.1:8888/",
        "http://192.168.49.10:7679/",
        "http://192.168.49.10:8888/",
        "http://192.168.43.1:8888/",
        "http://192.168.43.1:8888/cgi-bin/files"
    )

    private val mediaLinkRegex =
        Regex("""(?i)(?:href=["']?)?([^"'<>\s]+\.(?:jpg|jpeg|mp4)(?:\?[^"'<>\s]*)?)""")

    fun saveDestinationFolder(context: Context, uri: Uri) {
        prefs(context).edit()
            .putString(KEY_DESTINATION_TREE_URI, uri.toString())
            .apply()
    }

    fun getDestinationFolderUri(context: Context): Uri? {
        return prefs(context).getString(KEY_DESTINATION_TREE_URI, null)?.let(Uri::parse)
    }

    fun getDestinationFolderName(context: Context): String? {
        val uri = getDestinationFolderUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment
    }

    fun isAutoTransferEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_TRANSFER, false)
    }

    fun setAutoTransferEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_TRANSFER, enabled)
            .apply()
    }

    fun importMedia(context: Context, callback: (MediaImportResult) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val result = try {
                importMediaBlocking(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Media import failed", e)
                MediaImportResult(0, 0, e.message ?: e.javaClass.simpleName)
            }

            Handler(Looper.getMainLooper()).post {
                callback(result)
            }
        }.start()
    }

    private fun importMediaBlocking(context: Context): MediaImportResult {
        val destinationUri = getDestinationFolderUri(context)
            ?: return MediaImportResult(0, 0, "No destination folder selected")
        val destination = DocumentFile.fromTreeUri(context, destinationUri)
            ?: return MediaImportResult(0, 0, "Destination folder cannot be opened")

        if (!destination.canWrite()) {
            return MediaImportResult(0, 0, "Destination folder is not writable")
        }

        val importedUrls = importedUrls(context).toMutableSet()
        val mediaUrls = discoverMediaUrls().distinctBy { it.toString() }
        var importedCount = 0
        var skippedCount = 0

        for (url in mediaUrls) {
            val urlKey = url.toString()
            val fileName = fileNameFor(url)

            if (importedUrls.contains(urlKey) || destination.findFile(fileName) != null) {
                importedUrls.add(urlKey)
                skippedCount++
                continue
            }

            val targetFile = destination.createFile(mimeFor(fileName), fileName)
            if (targetFile == null) {
                skippedCount++
                continue
            }

            try {
                downloadToDocument(context, url, targetFile)
                importedUrls.add(urlKey)
                importedCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download $url", e)
                targetFile.delete()
                skippedCount++
            }
        }

        saveImportedUrls(context, importedUrls)
        return MediaImportResult(importedCount, skippedCount)
    }

    private fun discoverMediaUrls(): List<URL> {
        val urls = linkedSetOf<URL>()

        for (endpoint in discoveryEndpoints) {
            val baseUrl = try {
                URL(endpoint)
            } catch (e: MalformedURLException) {
                continue
            }

            Log.d(TAG, "Trying media listing endpoint $baseUrl")
            val listing = fetchText(baseUrl) ?: continue
            for (match in mediaLinkRegex.findAll(listing)) {
                val mediaUrl = resolveUrl(baseUrl, match.groupValues[1]) ?: continue
                Log.d(TAG, "Discovered media candidate $mediaUrl")
                urls.add(mediaUrl)
            }
        }

        return urls.toList()
    }

    private fun fetchText(url: URL): String? {
        val connection = openHttpConnection(url)
        return try {
            val response = connection.responseCode
            if (response !in 200..399) return null

            connection.inputStream.bufferedReader().use {
                it.readText()
            }
        } catch (e: Exception) {
            Log.d(TAG, "No listing at $url: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadToDocument(context: Context, url: URL, file: DocumentFile) {
        val connection = openHttpConnection(url)
        try {
            val response = connection.responseCode
            if (response !in 200..299) {
                throw IllegalStateException("HTTP $response")
            }

            connection.inputStream.use { input ->
                context.contentResolver.openOutputStream(file.uri)?.use { output ->
                    input.copyTo(output)
                } ?: throw IllegalStateException("Cannot open output stream")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttpConnection(url: URL): HttpURLConnection {
        return (WifiUtils.openConnection(url) as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 6_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
        }
    }

    private fun resolveUrl(baseUrl: URL, href: String): URL? {
        val cleanHref = href.trim().trim('"', '\'')
        return try {
            URL(baseUrl, cleanHref)
        } catch (e: MalformedURLException) {
            null
        }
    }

    private fun fileNameFor(url: URL): String {
        val rawName = url.path.substringAfterLast('/').ifBlank {
            "gear360-${System.currentTimeMillis()}.bin"
        }

        return URLDecoder.decode(rawName, Charsets.UTF_8.name())
    }

    private fun mimeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun importedUrls(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_IMPORTED_URLS, emptySet()) ?: emptySet()
    }

    private fun saveImportedUrls(context: Context, urls: Set<String>) {
        prefs(context).edit()
            .putStringSet(KEY_IMPORTED_URLS, urls)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
