package com.universalvoicetranslator

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class Language(val code: String, val name: String)

object TranslationClient {
    fun translate(
        serverUrl: String,
        text: String,
        source: Language,
        target: Language,
    ): String {
        val baseUrl = serverUrl.trim().trimEnd('/')
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw IllegalArgumentException("Enter a server address starting with http:// or https://.")
        }

        val connection = (URL("$baseUrl/translate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            val payload = JSONObject()
                .put("q", text)
                .put("source", source.code)
                .put("target", target.code)
                .put("format", "text")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val detail = runCatching { JSONObject(responseBody).optString("error") }.getOrNull()
                throw IOException(detail?.takeIf(String::isNotBlank) ?: "Translation server returned HTTP $responseCode.")
            }

            return JSONObject(responseBody).optString("translatedText")
                .takeIf(String::isNotBlank)
                ?: throw IOException("The translation server returned an empty result.")
        } finally {
            connection.disconnect()
        }
    }
}