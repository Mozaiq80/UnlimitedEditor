package com.custom.unlimitededitor

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import javax.net.ssl.SSLSocketFactory

data class WebDavItem(
    val name: String,
    val fullUrl: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
)

object WebDavClient {

    private fun buildAuthHeader(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun normalizeDirUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun encodeSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    fun downloadBytes(fileUrl: String, username: String, password: String): ByteArray {
        val url = URL(fileUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            if (username.isNotEmpty() || password.isNotEmpty()) {
                setRequestProperty("Authorization", buildAuthHeader(username, password))
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code (${conn.responseMessage})")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun uploadBytes(fileUrl: String, bytes: ByteArray, username: String, password: String) {
        val url = URL(fileUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                setRequestProperty("Authorization", buildAuthHeader(username, password))
            }
        }
        try {
            conn.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("업로드 실패 HTTP $code (${conn.responseMessage})")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Android HttpURLConnection의 PROPFIND 제한을 우회하기 위한 경량 소켓 기반 PROPFIND(Depth: 1) 요청
     */
    fun listDirectory(dirUrl: String, username: String, password: String): List<WebDavItem> {
        val normalized = normalizeDirUrl(dirUrl)
        val url = URL(normalized)
        val isHttps = url.protocol.equals("https", ignoreCase = true)
        val host = url.host
        val port = if (url.port > 0) url.port else if (isHttps) 443 else 80
        val rawPath: String = try {
            url.toURI().rawPath ?: ""
        } catch (_: Exception) {
            url.path ?: ""
        }
        val path = rawPath.ifEmpty { "/" }

        val xmlBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
  </d:prop>
</d:propfind>""".toByteArray(Charsets.UTF_8)

        val socket: Socket = if (isHttps) {
            SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            Socket(host, port)
        }
        socket.soTimeout = 15_000

        try {
            val sb = StringBuilder()
            sb.append("PROPFIND ").append(path).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append(if ((isHttps && port != 443) || (!isHttps && port != 80)) ":$port" else "").append("\r\n")
            sb.append("Depth: 1\r\n")
            sb.append("Content-Type: application/xml; charset=utf-8\r\n")
            sb.append("Content-Length: ").append(xmlBody.size).append("\r\n")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                sb.append("Authorization: ").append(buildAuthHeader(username, password)).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")

            val out = socket.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(xmlBody)
            out.flush()

            val inp = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
                val b = inp.read()
                if (b < 0) break
                headerBytes.write(b)
                if (prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10) break
                prev3 = prev2; prev2 = prev1; prev1 = b
            }

            val headerStr = headerBytes.toString("ISO-8859-1")
            val statusLine = headerStr.lineSequence().firstOrNull() ?: ""
            val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (statusCode !in 200..299) {
                throw IllegalStateException("서버 응답 오류: $statusLine")
            }

            val isChunked = headerStr.lines().any {
                it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true)
            }
            val bodyBytes = if (isChunked) readChunkedBody(inp) else inp.readBytes()
            val bodyXml = bodyBytes.toString(Charsets.UTF_8)
            return parseMultiStatusXml(bodyXml, normalized)
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun readChunkedBody(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val lineBuf = ByteArrayOutputStream()
            while (true) {
                val b = inp.read()
                if (b < 0 || b == 10) break
                if (b != 13) lineBuf.write(b)
            }
            val sizeHex = lineBuf.toString("ASCII").trim().substringBefore(';')
            if (sizeHex.isEmpty()) continue
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            var remaining = chunkSize
            val buf = ByteArray(4096)
            while (remaining > 0) {
                val read = inp.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
            inp.read() // \r
            inp.read() // \n
        }
        return out.toByteArray()
    }

    private fun parseMultiStatusXml(xml: String, baseDirUrl: String): List<WebDavItem> {
        val baseUrlObj = URL(baseDirUrl)
        val baseOrigin = "${baseUrlObj.protocol}://${baseUrlObj.host}" +
            if (baseUrlObj.port > 0) ":${baseUrlObj.port}" else ""
        val basePathTrimmed = baseUrlObj.path.trimEnd('/')

        val result = ArrayList<WebDavItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var inResponse = false
        var href = ""
        var isCollection = false
        var contentLength = 0L
        var currentTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    currentTag = tag
                    if (tag == "response") {
                        inResponse = true
                        href = ""
                        isCollection = false
                        contentLength = 0L
                    } else if (inResponse && tag == "collection") {
                        isCollection = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inResponse) {
                        val txt = parser.text.trim()
                        if (txt.isNotEmpty()) {
                            when (currentTag) {
                                "href" -> href += txt
                                "getcontentlength" -> contentLength = txt.toLongOrNull() ?: 0L
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "response" && inResponse) {
                        inResponse = false
                        val fullUrl = when {
                            href.startsWith("http://", true) || href.startsWith("https://", true) -> href
                            href.startsWith("/") -> "$baseOrigin$href"
                            else -> "$baseDirUrl$href"
                        }
                        val itemUrlObj = try { URL(fullUrl) } catch (_: Exception) { null }
                        val itemPathTrimmed = itemUrlObj?.path?.trimEnd('/') ?: ""
                        if (itemPathTrimmed.isNotEmpty() && itemPathTrimmed != basePathTrimmed) {
                            val rawName = itemPathTrimmed.substringAfterLast('/')
                            val decodedName = try {
                                URLDecoder.decode(rawName, "UTF-8")
                            } catch (_: Exception) { rawName }
                            if (decodedName.isNotEmpty()) {
                                val finalUrl = if (isCollection && !fullUrl.endsWith("/")) "$fullUrl/" else fullUrl
                                result.add(WebDavItem(decodedName, finalUrl, isCollection, contentLength))
                            }
                        }
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }

        return result.sortedWith(compareBy<WebDavItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }
}