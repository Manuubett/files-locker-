package com.manuubett.lindaplay

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the DEH EMANUELS SOLUTIONS app store for a newer version of
 * Linda Play. Every function here talks to the network, so call them
 * from a background thread only.
 *
 * It reads the same public "apps" list the store page shows, and asks the
 * store's download endpoint for a temporary APK link.
 */
object UpdateChecker {

    // The name the app has in the store admin (not case sensitive)
    private const val STORE_NAME = "Linda Play"

    private const val PROJECT = "divine-treat-450709-b5"
    // Public web key from the store page (not a secret)
    private const val API_KEY = "AIzaSyDPZLbDio7OibE-FEXqFF1OdhM0qZc3yII"
    private const val BACKEND = "https://cbe-y1zb.onrender.com"
    private const val SITE = "https://dehemanuelssolutions.co.ke/appstore.html"

    class Update(
        val docId: String,
        val version: String,
        val notes: String
    )

    fun installedVersion(context: Context): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    // Newest "Linda Play" entry in the store, or null if there is none
    fun fetchLatest(): Update? {
        var best: Update? = null
        var pageToken: String? = null
        var pages = 0

        do {
            var url = "https://firestore.googleapis.com/v1/projects/$PROJECT" +
                "/databases/(default)/documents/apps?pageSize=100&key=$API_KEY"
            if (pageToken != null) url += "&pageToken=$pageToken"

            val json = JSONObject(httpGet(url))
            val docs = json.optJSONArray("documents")

            if (docs != null) {
                for (i in 0 until docs.length()) {
                    val doc = docs.getJSONObject(i)
                    val fields = doc.optJSONObject("fields") ?: continue

                    val name = text(fields, "name")
                    val version = text(fields, "version")

                    // Skips records that are not APK uploads (no version)
                    if (version.isBlank()) continue
                    if (!name.equals(STORE_NAME, ignoreCase = true)) continue

                    val current = best
                    if (current == null || isNewer(version, current.version)) {
                        best = Update(
                            docId = doc.getString("name").substringAfterLast('/'),
                            version = version.trim(),
                            notes = text(fields, "changelog")
                        )
                    }
                }
            }

            pageToken = json.optString("nextPageToken", "").ifBlank { null }
            pages++
        } while (pageToken != null && pages < 5)

        return best
    }

    // Temporary link to the APK file
    fun requestDownloadUrl(docId: String): String {
        val conn = URL("$BACKEND/api/apps/download")
            .openConnection() as HttpURLConnection

        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 70000       // the server may be waking up
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use {
                it.write(
                    JSONObject().put("appId", docId).toString().toByteArray()
                )
            }

            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }

            if (code >= 400) {
                throw Exception(json.optString("error", "Download failed"))
            }

            val link = json.optString("url", "")
            if (!link.startsWith("https://")) throw Exception("No download link")
            return link
        } finally {
            conn.disconnect()
        }
    }

    // "1.0.14" is newer than "1.0.9" (compared number by number)
    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)

        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }

        return false
    }

    private fun parts(version: String): List<Int> {
        return version.trim()
            .removePrefix("v").removePrefix("V")
            .split('.', '-')
            .map { piece -> piece.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    private fun text(fields: JSONObject, key: String): String {
        return fields.optJSONObject(key)?.optString("stringValue", "") ?: ""
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection

        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            // The store's key may be limited to the store website
            conn.setRequestProperty("Referer", SITE)

            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code >= 400) throw Exception("Store replied $code")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
