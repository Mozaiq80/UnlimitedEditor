package com.custom.unlimitededitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import kotlin.math.roundToInt

enum class PublishTargetType(val label: String) {
    WORDPRESS("워드프레스 (WordPress - 이미지 자동 업로드)"),
    GITHUB("GitHub 블로그 (.md + 이미지 저장소 커밋)"),
    NOTION("Notion (노션 상위 페이지에 하위 문서 생성)"),
    HTML_CLIPBOARD("HTML 서식 클립보드 복사 (네이버티스토리 붙여넣기)")
}

data class ResolvedImageData(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class PublishRequest(
    val target: PublishTargetType,
    val title: String,
    val rawMarkdown: String,
    val originalFileName: String,
    val isDraft: Boolean,
    val webDavFileUrl: String?
)

data class PublishOutcome(
    val success: Boolean,
    val message: String,
    val publishedUrl: String? = null
)

object PublishManager {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 TextEdit/1.0"

    private val permissiveSslSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }.socketFactory
    }

    private val permissiveHostnameVerifier = HostnameVerifier { _, _ -> true }

    private fun formatExceptionDetail(e: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 4) {
            if (depth > 0) sb.append("\n원인: ")
            sb.append("${cur.javaClass.simpleName}: ${cur.message ?: "(메시지 없음)"}")
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    fun publish(
        context: Context,
        req: PublishRequest,
        onProgress: (String) -> Unit
    ): PublishOutcome {
        return try {
            when (req.target) {
                PublishTargetType.WORDPRESS -> publishToWordPress(context, req, onProgress)
                PublishTargetType.GITHUB -> publishToGitHub(context, req, onProgress)
                PublishTargetType.NOTION -> publishToNotion(req, onProgress)
                PublishTargetType.HTML_CLIPBOARD -> copyAsRichHtml(context, req, onProgress)
            }
        } catch (e: Exception) {
            PublishOutcome(false, formatExceptionDetail(e))
        }
    }

    fun normalizeWordPressRoot(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "https://$s"
        }
        s = s.replace(Regex("""/(wp-admin|wp-login\.php|wp-json).*$""", RegexOption.IGNORE_CASE), "")
        return s.trimEnd('/')
    }

    fun testWordPressConnection(): PublishOutcome {
        val siteUrl = normalizeWordPressRoot(EditorSettings.wpSiteUrl)
        val user = EditorSettings.wpUsername.trim()
        val appPass = EditorSettings.wpAppPassword.trim()
        if (siteUrl.isEmpty() || user.isEmpty() || appPass.isEmpty()) {
            return PublishOutcome(false, "워드프레스 사이트 주소, 아이디, 애플리케이션 비밀번호를 먼저 입력하세요.")
        }
        val authHeader = basicAuth(user, appPass)
        var resolvedIpInfo = ""
        return try {
            val host = URL(siteUrl).host
            resolvedIpInfo = try {
                val addrs = InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "" }
                "DNS($host -> $addrs)"
            } catch (dnsEx: Exception) {
                "DNS 실패($host): ${dnsEx.message}"
            }

            val candidates = listOf(
                "$siteUrl/wp-json/wp/v2/users/me",
                "$siteUrl/?rest_route=/wp/v2/users/me"
            )
            var lastDiag = ""
            for (ep in candidates) {
                val (code, body, finalUrl) = executeRequest("GET", ep, authHeader, null, "application/json", null)
                if (code in 200..299) {
                    val name = try { JSONObject(body).optString("name", user) } catch (_: Exception) { user }
                    return PublishOutcome(true, "연결 성공! 계정: $name\n$resolvedIpInfo\n경로: $finalUrl")
                }
                lastDiag = "HTTP $code [$ep]\n응답: ${body.take(180)}"
                if (code != 404) break
            }
            PublishOutcome(false, "연결 실패 ($resolvedIpInfo)\n$lastDiag")
        } catch (e: Exception) {
            PublishOutcome(false, "접속 실패 [$siteUrl]\n$resolvedIpInfo\n${formatExceptionDetail(e)}")
        }
    }

    private fun executeRequest(
        method: String,
        urlString: String,
        authHeader: String?,
        bodyBytes: ByteArray?,
        contentType: String?,
        contentDisposition: String?
    ): Triple<Int, String, String> {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            val url = URL(currentUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    sslSocketFactory = permissiveSslSocketFactory
                    hostnameVerifier = permissiveHostnameVerifier
                }
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                if (authHeader != null) setRequestProperty("Authorization", authHeader)
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (contentDisposition != null) setRequestProperty("Content-Disposition", contentDisposition)
                if (bodyBytes != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(bodyBytes.size)
                }
            }
            try {
                if (bodyBytes != null) {
                    conn.outputStream.use { it.write(bodyBytes); it.flush() }
                }
                val code = conn.responseCode
                if (code in listOf(301, 302, 307, 308)) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        currentUrl = URL(url, loc).toString()
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                return Triple(code, respBody, currentUrl)
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("리다이렉트 횟수 초과: $currentUrl")
    }

    private fun optimizeImageForWeb(raw: ResolvedImageData): ResolvedImageData {
        if (raw.mimeType.equals("image/gif", true) || raw.mimeType.equals("image/svg+xml", true)) {
            return raw
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return raw

            val maxDim = 1600
            if (max(srcW, srcH) <= maxDim && raw.bytes.size <= 900_000) {
                return raw
            }

            var sampleSize = 1
            while (max(srcW / sampleSize, srcH / sampleSize) > maxDim * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(raw.bytes, 0, raw.bytes.size, decodeOpts) ?: return raw

            val curMax = max(decoded.width, decoded.height)
            val scaled = if (curMax > maxDim) {
                val ratio = maxDim.toFloat() / curMax.toFloat()
                val dstW = max(1, (decoded.width * ratio).roundToInt())
                val dstH = max(1, (decoded.height * ratio).roundToInt())
                Bitmap.createScaledBitmap(decoded, dstW, dstH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            scaled.recycle()

            val jpgName = raw.fileName.substringBeforeLast('.') + ".jpg"
            ResolvedImageData(jpgName, "image/jpeg", out.toByteArray())
        } catch (_: Exception) {
            raw
        }
    }

    private fun resolveLocalImage(context: Context, rawPath: String, webDavFileUrl: String?): ResolvedImageData? {
        if (rawPath.startsWith("http://", true) || rawPath.startsWith("https://", true)) return null

        if (rawPath.startsWith("content://") || rawPath.startsWith("file://")) {
            val uri = Uri.parse(rawPath)
            var name = "image_${System.currentTimeMillis()}.jpg"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    name = c.getString(idx) ?: name
                }
            }
            val mime = context.contentResolver.getType(uri) ?: guessMimeFromName(name)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("이미지 파일을 읽을 수 없습니다: $name")
            return optimizeImageForWeb(ResolvedImageData(name, mime, bytes))
        }

        if (!webDavFileUrl.isNullOrBlank()) {
            val baseDir = webDavFileUrl.substringBeforeLast('/') + "/"
            val cleanRel = rawPath.removePrefix("./")
            val encodedRel = cleanRel.split('/').joinToString("/") { WebDavClient.encodeSegment(it) }
            val targetUrl = "$baseDir$encodedRel"
            val name = cleanRel.substringAfterLast('/')
            val bytes = WebDavClient.downloadBytes(
                targetUrl,
                EditorSettings.webDavUsername,
                EditorSettings.webDavPassword
            )
            return optimizeImageForWeb(ResolvedImageData(name, guessMimeFromName(name), bytes))
        }

        return null
    }

    private fun guessMimeFromName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }
    }

    private fun basicAuth(user: String, pass: String): String {
        return "Basic " + Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun publishToWordPress(
        context: Context,
        req: PublishRequest,
        onProgress: (String) -> Unit
    ): PublishOutcome {
        val siteUrl = normalizeWordPressRoot(EditorSettings.wpSiteUrl)
        val user = EditorSettings.wpUsername.trim()
        val appPass = EditorSettings.wpAppPassword.trim()
        if (siteUrl.isEmpty() || user.isEmpty() || appPass.isEmpty()) {
            return PublishOutcome(false, "워드프레스 사이트 주소, 아이디, 애플리케이션 비밀번호를 먼저 설정하세요.")
        }

        val authHeader = basicAuth(user, appPass)
        var processedMd = req.rawMarkdown
        val images = MarkdownConverter.extractImages(processedMd)
        val uploadedCache = HashMap<String, String>()
        var uploadedCount = 0

        for ((idx, imgRef) in images.withIndex()) {
            if (imgRef.path.startsWith("http://", true) || imgRef.path.startsWith("https://", true)) continue
            val cachedUrl = uploadedCache[imgRef.path]
            if (cachedUrl != null) {
                processedMd = processedMd.replace(imgRef.path, cachedUrl)
                continue
            }

            onProgress("이미지 최적화 중 (${idx + 1}/${images.size})...")
            val resolved = resolveLocalImage(context, imgRef.path, req.webDavFileUrl) ?: continue

            val kb = max(1, resolved.bytes.size / 1024)
            onProgress("이미지 업로드 중 (${idx + 1}/${images.size}, ${kb}KB)...")

            val asciiSafeName = resolved.fileName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            val disp = """attachment; filename="$asciiSafeName""""
            val mediaEndpoints = listOf("$siteUrl/wp-json/wp/v2/media", "$siteUrl/?rest_route=/wp/v2/media")

            var respJsonStr = ""
            var mediaOk = false
            var lastErr = ""
            for (ep in mediaEndpoints) {
                val (code, body, _) = executeRequest("POST", ep, authHeader, resolved.bytes, resolved.mimeType, disp)
                if (code in 200..299) {
                    respJsonStr = body
                    mediaOk = true
                    break
                }
                lastErr = "HTTP $code [$ep]: ${body.take(150)}"
                if (code != 404) break
            }
            if (!mediaOk) {
                throw IllegalStateException("미디어 업로드 실패 -> $lastErr")
            }

            val sourceUrl = JSONObject(respJsonStr).optString("source_url", "")
            if (sourceUrl.isNotEmpty()) {
                uploadedCache[imgRef.path] = sourceUrl
                processedMd = processedMd.replace(imgRef.path, sourceUrl)
                uploadedCount++
            }
        }

        onProgress("워드프레스 본문 전송 중 ($siteUrl)...")
        val htmlBody = MarkdownConverter.toHtml(processedMd)
        val payload = JSONObject().apply {
            put("title", req.title)
            put("content", htmlBody)
            put("status", if (req.isDraft) "draft" else "publish")
        }.toString().toByteArray(Charsets.UTF_8)

        val postEndpoints = listOf("$siteUrl/wp-json/wp/v2/posts", "$siteUrl/?rest_route=/wp/v2/posts")
        var postResp = ""
        var postOk = false
        var lastPostErr = ""
        for (ep in postEndpoints) {
            val (code, body, _) = executeRequest("POST", ep, authHeader, payload, "application/json; charset=utf-8", null)
            if (code in 200..299) {
                postResp = body
                postOk = true
                break
            }
            lastPostErr = "HTTP $code [$ep]: ${body.take(180)}"
            if (code != 404) break
        }
        if (!postOk) {
            throw IllegalStateException("글 게시 실패 -> $lastPostErr")
        }

        val postObj = JSONObject(postResp)
        val link = postObj.optString("link", "")
        val statusLabel = if (req.isDraft) "임시글(Draft)" else "공개 글(Publish)"
        val imgNote = if (uploadedCount > 0) " (이미지 ${uploadedCount}장 업로드 포함)" else ""
        return PublishOutcome(true, "워드프레스 $statusLabel 등록 완료!$imgNote", link.ifEmpty { null })
    }

    private fun publishToGitHub(
        context: Context,
        req: PublishRequest,
        onProgress: (String) -> Unit
    ): PublishOutcome {
        val repo = EditorSettings.ghOwnerRepo.trim().trim('/')
        val token = EditorSettings.ghToken.trim()
        val branch = EditorSettings.ghBranch.trim().ifEmpty { "main" }
        val postsDir = EditorSettings.ghPostsDir.trim().trim('/')
        val imagesDir = EditorSettings.ghImagesDir.trim().trim('/')

        if (repo.isEmpty() || token.isEmpty()) {
            return PublishOutcome(false, "GitHub 저장소(owner/repo)와 Personal Access Token을 먼저 설정하세요.")
        }

        var processedMd = req.rawMarkdown
        val images = MarkdownConverter.extractImages(processedMd)
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var uploadedImgCount = 0

        for ((idx, imgRef) in images.withIndex()) {
            if (imgRef.path.startsWith("http://", true) || imgRef.path.startsWith("https://", true)) continue
            onProgress("GitHub 이미지 커밋 중 (${idx + 1}/${images.size})...")
            val resolved = resolveLocalImage(context, imgRef.path, req.webDavFileUrl) ?: continue
            val safeName = "${dateStamp}_" + resolved.fileName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            val repoImgPath = if (imagesDir.isEmpty()) safeName else "$imagesDir/$safeName"

            putGitHubFile(repo, branch, token, repoImgPath, resolved.bytes, "Upload image $safeName")
            processedMd = processedMd.replace(imgRef.path, "/$repoImgPath")
            uploadedImgCount++
        }

        onProgress("GitHub 마크다운 포스트 커밋 중...")
        val datePrefix = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val slug = req.title.trim().replace(Regex("""[^\w가-힣-]+"""), "-").trim('-').ifEmpty { "post" }
        val mdName = if (req.originalFileName.endsWith(".md", true)) req.originalFileName else "$datePrefix-$slug.md"
        val repoPostPath = if (postsDir.isEmpty()) mdName else "$postsDir/$mdName"

        val respJson = putGitHubFile(
            repo,
            branch,
            token,
            repoPostPath,
            processedMd.toByteArray(Charsets.UTF_8),
            "Publish post: ${req.title}"
        )
        val htmlUrl = JSONObject(respJson).optJSONObject("content")?.optString("html_url", "") ?: ""
        val imgNote = if (uploadedImgCount > 0) " (이미지 ${uploadedImgCount}장 포함)" else ""
        return PublishOutcome(true, "GitHub 커밋 완료: $repoPostPath$imgNote", htmlUrl.ifEmpty { null })
    }

    private fun putGitHubFile(
        repo: String,
        branch: String,
        token: String,
        repoPath: String,
        contentBytes: ByteArray,
        commitMessage: String
    ): String {
        val encodedPath = repoPath.split('/').joinToString("/") { WebDavClient.encodeSegment(it) }
        val apiUrl = "https://api.github.com/repos/$repo/contents/$encodedPath?ref=$branch"
        val auth = "Bearer $token"

        var existingSha: String? = null
        val (getCode, getBody, _) = executeRequest("GET", apiUrl, auth, null, null, null)
        if (getCode == 200) {
            existingSha = JSONObject(getBody).optString("sha", "").ifEmpty { null }
        }

        val putUrl = "https://api.github.com/repos/$repo/contents/$encodedPath"
        val bodyObj = JSONObject().apply {
            put("message", commitMessage)
            put("content", Base64.encodeToString(contentBytes, Base64.NO_WRAP))
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }
        val payload = bodyObj.toString().toByteArray(Charsets.UTF_8)

        val (putCode, putResp, _) = executeRequest("PUT", putUrl, auth, payload, "application/json; charset=utf-8", null)
        if (putCode !in 200..299) {
            throw IllegalStateException("GitHub API 오류 (HTTP $putCode): ${putResp.take(150)}")
        }
        return putResp
    }

    private fun publishToNotion(
        req: PublishRequest,
        onProgress: (String) -> Unit
    ): PublishOutcome {
        val token = EditorSettings.notionToken.trim()
        val parentId = EditorSettings.notionParentPageId.trim()
        if (token.isEmpty() || parentId.isEmpty()) {
            return PublishOutcome(false, "Notion Integration Token과 상위 Page ID를 먼저 설정하세요.")
        }

        onProgress("Notion 페이지 블록 변환 및 생성 중...")
        val blocks = MarkdownConverter.toNotionBlocks(req.rawMarkdown)
        val payloadObj = JSONObject().apply {
            put("parent", JSONObject().put("page_id", parentId))
            put("properties", JSONObject().put(
                "title",
                JSONObject().put("title", org.json.JSONArray().put(
                    JSONObject().put("text", JSONObject().put("content", req.title))
                ))
            ))
            put("children", blocks)
        }
        val payload = payloadObj.toString().toByteArray(Charsets.UTF_8)

        val (code, body, _) = executeRequest(
            "POST",
            "https://api.notion.com/v1/pages",
            "Bearer $token",
            payload,
            "application/json; charset=utf-8",
            null
        )
        if (code !in 200..299) {
            throw IllegalStateException("Notion API 오류 (HTTP $code): ${body.take(160)}")
        }

        val pageUrl = JSONObject(body).optString("url", "")
        return PublishOutcome(true, "Notion 새 페이지 생성 완료!", pageUrl.ifEmpty { null })
    }

    private fun copyAsRichHtml(
        context: Context,
        req: PublishRequest,
        onProgress: (String) -> Unit
    ): PublishOutcome {
        onProgress("HTML 서식 변환 및 클립보드 복사 중...")
        val htmlBody = "<h1>${req.title}</h1>\n" + MarkdownConverter.toHtml(req.rawMarkdown)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newHtmlText(req.title, req.rawMarkdown, htmlBody))
        return PublishOutcome(true, "서식 포함 HTML이 클립보드에 복사되었습니다. 네이버 블로그티스토리 에디터에 그대로 붙여넣으세요.")
    }
}