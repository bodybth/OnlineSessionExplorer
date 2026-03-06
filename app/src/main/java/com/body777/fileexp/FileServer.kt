package com.body777.fileexp

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileServer(
    private val context: Context,
    port: Int,
    private var serveDir: String,
    private var password: String
) : NanoHTTPD(port) {

    // ── Session store ─────────────────────────────────────────────────────────
    private val authedSessions = ConcurrentHashMap.newKeySet<String>()
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    // ── Asset cache — loaded once, never re-read from disk ────────────────────
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val templateHtml: String by lazy { loadAsset("template.html") }
    private val loginHtml:    String by lazy { loadAsset("login.html") }

    // ── ETag cache — maps file path → "size-mtime" ────────────────────────────
    private val etagCache = ConcurrentHashMap<String, String>()

    companion object {
        private const val CHUNK_SIZE    = 65536          // 64 KB read buffer
        private const val GZIP_MIN_SIZE = 1024           // only gzip responses > 1 KB
        private val GZIP_TYPES = setOf(                  // content types eligible for gzip
            "text/html", "text/plain", "text/css",
            "application/javascript", "application/json",
            "text/xml", "application/xml", "image/svg+xml"
        )
    }

    fun updateConfig(dir: String, pwd: String) { serveDir = dir; password = pwd }

    // ── Main router ───────────────────────────────────────────────────────────
    override fun serve(session: IHTTPSession): Response {
        val uri    = session.uri ?: "/"
        val method = session.method
        AppState.log("HTTP", "${method.name} $uri")

        if (uri != "/login" && uri != "/logout" && !isAuthed(session))
            return redirect("/login?next=${URLEncoder.encode(uri, "UTF-8")}")

        return when {
            uri == "/login"  -> handleLogin(session)
            uri == "/logout" -> handleLogout(session)
            uri == "/api/upload"    && method == Method.POST -> handleUpload(session)
            uri == "/api/rename"    && method == Method.POST -> handleRename(session)
            uri == "/api/delete"    && method == Method.POST -> handleDelete(session)
            uri == "/api/mkdir"     && method == Method.POST -> handleMkdir(session)
            uri == "/api/read"      && method == Method.GET  -> handleReadFile(session)
            uri == "/api/save"      && method == Method.POST -> handleSaveFile(session)
            uri == "/api/zip"       && method == Method.POST -> handleZip(session)
            uri == "/api/unzip"     && method == Method.POST -> handleUnzip(session)
            uri == "/api/search"    && method == Method.GET  -> handleSearch(session)
            uri == "/api/fetch-url" && method == Method.POST -> handleFetchUrl(session)
            else -> handleFileOrDir(session, uri)
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────
    private fun isAuthed(s: IHTTPSession) =
        authedSessions.contains(s.cookies.read("ose_session") ?: "")

    private fun handleLogin(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            try { session.parseBody(body) } catch (_: Exception) {}
            if (session.parms["password"] == password) {
                val id = UUID.randomUUID().toString()
                authedSessions.add(id)
                AppState.log("AUTH", "Login OK")
                val resp = redirect(session.parms["next"] ?: "/")
                resp.addHeader("Set-Cookie", "ose_session=$id; Path=/; HttpOnly; SameSite=Strict")
                return resp
            }
            AppState.log("AUTH", "Login failed")
            return htmlResp(loginHtml.replace("__ERROR_BLOCK__",
                """<div class="error"><span class="material-icons-round">error_outline</span>Incorrect password.</div>"""),
                acceptsGzip(session))
        }
        return htmlResp(loginHtml.replace("__ERROR_BLOCK__", ""), acceptsGzip(session))
    }

    private fun handleLogout(session: IHTTPSession): Response {
        val id = session.cookies.read("ose_session")
        if (id != null) authedSessions.remove(id)
        val resp = redirect("/login")
        resp.addHeader("Set-Cookie", "ose_session=; Path=/; Max-Age=0; HttpOnly")
        return resp
    }

    // ── File / Dir ────────────────────────────────────────────────────────────
    private fun handleFileOrDir(session: IHTTPSession, uri: String): Response {
        val rel  = URLDecoder.decode(uri.trimStart('/'), "UTF-8")
        val file = safeFile(rel) ?: return errorResp(Status.FORBIDDEN, "Forbidden")
        if (!file.exists()) return errorResp(Status.NOT_FOUND, "Not found")
        return if (file.isDirectory) listDirectory(file, rel, session)
               else serveFile(file, session)
    }

    // ── Serve static file with chunking + ETag + range ────────────────────────
    private fun serveFile(
        file: File,
        session: IHTTPSession,
        forceDownload: Boolean = session.parms["dl"] == "1"
    ): Response {
        val mime  = getMimeType(file.name)
        val size  = file.length()
        val etag  = etag(file)

        // ── 304 Not Modified ──────────────────────────────────────────────────
        val ifNoneMatch = session.headers["if-none-match"]
        if (ifNoneMatch != null && ifNoneMatch == etag) {
            val resp = newFixedLengthResponse(Status.NOT_MODIFIED, mime, "")
            resp.addHeader("ETag", etag)
            resp.addHeader("Cache-Control", "max-age=60")
            return resp
        }

        // ── Range request (video / audio seeking) ─────────────────────────────
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.substring(6).split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end   = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: (size - 1) else size - 1
            if (start > end || end >= size)
                return errorResp(Status.RANGE_NOT_SATISFIABLE, "Range Not Satisfiable")
            val len  = end - start + 1
            val fis  = FileInputStream(file).also { it.skip(start) }
            val resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, fis.buffered(CHUNK_SIZE), len)
            resp.addHeader("Content-Range",  "bytes $start-$end/$size")
            resp.addHeader("Content-Length", len.toString())
            resp.addHeader("Accept-Ranges",  "bytes")
            resp.addHeader("ETag",           etag)
            resp.addHeader("Connection",     "keep-alive")
            return resp
        }

        // ── Full file with chunked buffered stream ─────────────────────────────
        val fis  = FileInputStream(file).buffered(CHUNK_SIZE)
        val resp = newFixedLengthResponse(Status.OK, mime, fis, size)
        resp.addHeader("Content-Length", size.toString())
        resp.addHeader("Accept-Ranges",  "bytes")
        resp.addHeader("ETag",           etag)
        resp.addHeader("Cache-Control",  "max-age=60")
        resp.addHeader("Connection",     "keep-alive")
        if (forceDownload)
            resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return resp
    }

    // ── Directory listing (gzip-compressed HTML) ──────────────────────────────
    private fun listDirectory(dir: File, relPath: String, session: IHTTPSession): Response {
        val entries = try {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        } catch (_: Exception) { return errorResp(Status.FORBIDDEN, "Permission denied") }

        var nDirs = 0; var nFiles = 0; var totalSize = 0L
        val items = mutableListOf<FileItem>()
        for (e in entries) {
            val isDir = e.isDirectory
            val size  = if (isDir) 0L else try { e.length() } catch (_: Exception) { 0L }
            val mtime = try { e.lastModified() } catch (_: Exception) { 0L }
            val meta  = fileMeta(e.name, isDir)
            val url   = "/" + (if (relPath.isEmpty()) "" else "$relPath/") + e.name + if (isDir) "/" else ""
            items.add(FileItem(e.name, url, if (!isDir) "$url?dl=1" else "",
                isDir, meta, size, humanSize(size), mtime, sdf.format(Date(mtime))))
            if (isDir) nDirs++ else { nFiles++; totalSize += size }
        }

        val displayPath = "/${relPath.trimEnd('/')}"
        val parentUrl   = if (relPath.isEmpty()) "/" else {
            val p = relPath.trimEnd('/').split("/").dropLast(1)
            if (p.isEmpty()) "/" else "/" + p.joinToString("/") + "/"
        }
        val stats = "<span><b>$nDirs</b> folders</span><span><b>$nFiles</b> files</span>" +
                    if (totalSize > 0) "<span><b>${humanSize(totalSize)}</b> total</span>" else ""

        val html = templateHtml
            .replace("__TITLE__",             escHtml("OSE · $displayPath"))
            .replace("__DISPLAY_PATH__",      escHtml(displayPath))
            .replace("__BREADCRUMBS__",       buildBreadcrumbs(relPath))
            .replace("__STATS__",             stats)
            .replace("__GRID_ITEMS__",        buildGridItems(items, displayPath, parentUrl))
            .replace("__LIST_ITEMS__",        buildListItems(items, displayPath, parentUrl))
            .replace("__EMPTY_HIDDEN__",      if (items.isEmpty()) "" else "display:none")
            .replace("__CURRENT_PATH_JSON__", "\"${escJs(displayPath)}\"")

        return htmlResp(html, acceptsGzip(session))
    }

    // ── Gzip-aware HTML response ───────────────────────────────────────────────
    private fun htmlResp(html: String, gzip: Boolean): Response {
        val bytes = html.toByteArray(Charsets.UTF_8)
        return if (gzip && bytes.size > GZIP_MIN_SIZE) {
            val compressed = gzipBytes(bytes)
            val resp = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(compressed), compressed.size.toLong())
            resp.addHeader("Content-Encoding", "gzip")
            resp.addHeader("Content-Length",   compressed.size.toString())
            resp.addHeader("Vary",             "Accept-Encoding")
            resp.addHeader("Connection",       "keep-alive")
            resp
        } else {
            val resp = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8",
                ByteArrayInputStream(bytes), bytes.size.toLong())
            resp.addHeader("Content-Length", bytes.size.toString())
            resp.addHeader("Connection",     "keep-alive")
            resp
        }
    }

    // ── Gzip-aware JSON response ───────────────────────────────────────────────
    private fun jsonResp(status: Status, body: String, session: IHTTPSession? = null): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val useGzip = session != null && acceptsGzip(session) && bytes.size > GZIP_MIN_SIZE
        return if (useGzip) {
            val compressed = gzipBytes(bytes)
            val resp = newFixedLengthResponse(status, "application/json",
                ByteArrayInputStream(compressed), compressed.size.toLong())
            resp.addHeader("Content-Encoding", "gzip")
            resp.addHeader("Content-Length",   compressed.size.toString())
            resp.addHeader("Vary",             "Accept-Encoding")
            resp
        } else {
            newFixedLengthResponse(status, "application/json", body)
        }
    }

    // ── HTML builders ─────────────────────────────────────────────────────────
    private fun buildBreadcrumbs(relPath: String): String {
        val parts = relPath.trim('/').split("/").filter { it.isNotEmpty() }
        val sb = StringBuilder("""<a href="/">root</a>""")
        for ((i, part) in parts.withIndex())
            sb.append("""<span class="sep">/</span><a href="/${parts.take(i+1).joinToString("/") + "/"}">${escHtml(part)}</a>""")
        return sb.toString()
    }

    private fun buildGridItems(items: List<FileItem>, displayPath: String, parentUrl: String): String {
        val sb = StringBuilder()
        if (displayPath != "/")
            sb.append("""<div class="card" data-name=".." data-size="-1" data-date="0"><a href="$parentUrl"><div class="icon" style="color:#fbc02d"><span class="material-icons-round">arrow_back</span></div><div class="name">..</div><div class="meta">Go up</div></a></div>""")
        for (item in items) {
            sb.append("""<div class="card" data-name="${escHtml(item.name.lowercase())}" data-size="${item.size}" data-date="${item.mtime}">
              <a href="${escHtml(item.url)}">
                <div class="icon" style="color:${item.meta.color}"><span class="material-icons-round">${item.meta.icon}</span></div>
                <div class="name" title="${escHtml(item.name)}">${escHtml(item.name)}</div>
                <div class="meta">${if (item.isDir) item.meta.label else item.sizeH}</div>
              </a>
              <div class="item-actions">${buildExtraBtns(item)}
                <button class="action-btn" onclick="event.stopPropagation();openRename('${escJs(item.name)}')" title="Rename"><span class="material-icons-round">drive_file_rename_outline</span></button>
                <button class="action-btn del" onclick="event.stopPropagation();openDelete('${escJs(item.name)}')" title="Delete"><span class="material-icons-round">delete_outline</span></button>
              </div>
            </div>""")
        }
        return sb.toString()
    }

    private fun buildListItems(items: List<FileItem>, displayPath: String, parentUrl: String): String {
        val sb = StringBuilder()
        if (displayPath != "/")
            sb.append("""<div class="list-item" data-name=".." data-size="-1" data-date="0"><a href="$parentUrl"><div class="icon" style="color:#fbc02d"><span class="material-icons-round">arrow_back</span></div><div class="info"><div class="fname">..</div><div class="fmeta">Parent folder</div></div></a></div>""")
        for (item in items) {
            sb.append("""<div class="list-item" data-name="${escHtml(item.name.lowercase())}" data-size="${item.size}" data-date="${item.mtime}">
              <a href="${escHtml(item.url)}"><div class="icon" style="color:${item.meta.color}"><span class="material-icons-round">${item.meta.icon}</span></div>
                <div class="info"><div class="fname" title="${escHtml(item.name)}">${escHtml(item.name)}</div><div class="fmeta">${item.meta.label} · ${item.modified}</div></div>
              </a>
              <div class="fsize">${if (!item.isDir) item.sizeH else ""}</div>
              ${if (!item.isDir) """<a class="dl-btn" href="${escHtml(item.dlUrl)}" download title="Download"><span class="material-icons-round" style="font-size:16px">download</span></a>""" else ""}
              <div class="item-actions">${buildExtraBtns(item)}
                <button class="action-btn" onclick="openRename('${escJs(item.name)}')" title="Rename"><span class="material-icons-round">drive_file_rename_outline</span></button>
                <button class="action-btn del" onclick="openDelete('${escJs(item.name)}')" title="Delete"><span class="material-icons-round">delete_outline</span></button>
              </div>
            </div>""")
        }
        return sb.toString()
    }

    private fun buildExtraBtns(item: FileItem): String {
        val sb = StringBuilder()
        if (!item.isDir && isEditable(item.name))
            sb.append("""<button class="action-btn edit" onclick="event.stopPropagation();openEditor('${escJs(item.name)}')" title="Edit"><span class="material-icons-round">edit_note</span></button>""")
        if (!item.isDir && item.name.lowercase().endsWith(".zip"))
            sb.append("""<button class="action-btn" onclick="event.stopPropagation();doUnzip('${escJs(item.name)}')" title="Unzip"><span class="material-icons-round">folder_zip</span></button>""")
        if (item.isDir)
            sb.append("""<button class="action-btn" onclick="event.stopPropagation();doZipItem('${escJs(item.name)}')" title="Zip"><span class="material-icons-round">archive</span></button>""")
        return sb.toString()
    }

    private fun isEditable(name: String) = name.substringAfterLast('.', "").lowercase() in setOf(
        "txt","md","py","js","ts","html","htm","css","json","xml","sh","bat","yaml","yml",
        "ini","cfg","conf","log","csv","java","kt","cpp","c","h","rs","go","rb","php",
        "sql","env","toml","gradle","properties"
    )

    // ── API handlers ──────────────────────────────────────────────────────────

    private fun handleReadFile(session: IHTTPSession): Response {
        val relPath = URLDecoder.decode(session.parms["path"] ?: "", "UTF-8").trimStart('/')
        val file = safeFile(relPath) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        if (!file.exists() || !file.isFile) return jsonResp(Status.NOT_FOUND, """{"error":"Not found"}""")
        if (file.length() > 2 * 1024 * 1024) return jsonResp(Status.BAD_REQUEST, """{"error":"File too large (max 2MB)"}""")
        return try {
            val content = file.readText(Charsets.UTF_8)
            val obj = JSONObject().apply { put("ok", true); put("content", content); put("name", file.name) }
            jsonResp(Status.OK, obj.toString(), session)
        } catch (e: Exception) {
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "")}"}""")
        }
    }

    private fun handleSaveFile(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val file = safeFile(body.optString("path", "").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        return try {
            file.writeText(body.optString("content", ""), Charsets.UTF_8)
            AppState.log("SAVE", file.absolutePath)
            jsonResp(Status.OK, """{"ok":true}""")
        } catch (e: Exception) {
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "")}"}""")
        }
    }

    private fun handleZip(session: IHTTPSession): Response {
        val body    = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val dir     = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val nameArr = body.optJSONArray("names") ?: return jsonResp(Status.BAD_REQUEST, """{"error":"No names"}""")
        val names   = (0 until nameArr.length()).map { nameArr.getString(it) }
        val zipName = (names.firstOrNull()?.substringBeforeLast('.') ?: "archive") + ".zip"
        val destZip = File(dir, zipName)
        return try {
            ZipOutputStream(FileOutputStream(destZip).buffered(CHUNK_SIZE)).use { zos ->
                names.forEach { name -> File(dir, name).takeIf { it.exists() }?.let { zipEntry(zos, it, it.name) } }
            }
            AppState.log("ZIP", "${destZip.name} (${names.size} items)")
            jsonResp(Status.OK, """{"ok":true,"name":"${escJs(zipName)}"}""")
        } catch (e: Exception) {
            destZip.delete()
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "")}"}""")
        }
    }

    private fun zipEntry(zos: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            zos.putNextEntry(ZipEntry("$entryName/")); zos.closeEntry()
            file.listFiles()?.forEach { zipEntry(zos, it, "$entryName/${it.name}") }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            file.inputStream().buffered(CHUNK_SIZE).use { it.copyTo(zos, CHUNK_SIZE) }
            zos.closeEntry()
        }
    }

    private fun handleUnzip(session: IHTTPSession): Response {
        val body    = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val dir     = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val name    = body.optString("name", "").trim()
        val zipFile = File(dir, name)
        if (!zipFile.exists()) return jsonResp(Status.NOT_FOUND, """{"error":"Not found"}""")
        val destDir = File(dir, name.removeSuffix(".zip").removeSuffix(".ZIP"))
        destDir.mkdirs()
        return try {
            ZipInputStream(FileInputStream(zipFile).buffered(CHUNK_SIZE)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalPath)) { entry = zis.nextEntry; continue }
                    if (entry.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); FileOutputStream(out).buffered(CHUNK_SIZE).use { zis.copyTo(it, CHUNK_SIZE) } }
                    entry = zis.nextEntry
                }
            }
            AppState.log("UNZIP", "${zipFile.name} → ${destDir.name}/")
            jsonResp(Status.OK, """{"ok":true,"dest":"${escJs(destDir.name)}"}""")
        } catch (e: Exception) {
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "")}"}""")
        }
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val query   = session.parms["q"]?.trim()?.lowercase() ?: ""
        val relPath = URLDecoder.decode(session.parms["path"] ?: "/", "UTF-8").trimStart('/')
        if (query.length < 2) return jsonResp(Status.BAD_REQUEST, """{"error":"Query too short"}""")
        val root    = safeFile(relPath) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val results = JSONArray()
        var count   = 0
        try {
            root.walkTopDown().onEnter { it.canRead() }
                .filter { it.name.lowercase().contains(query) && it != root }
                .take(200)
                .forEach { f ->
                    val isDir  = f.isDirectory
                    val relUrl = "/" + f.canonicalPath.removePrefix(File(serveDir).canonicalPath).trimStart('/') + if (isDir) "/" else ""
                    val meta   = fileMeta(f.name, isDir)
                    results.put(JSONObject().apply {
                        put("name",  f.name);  put("url",   relUrl)
                        put("isDir", isDir);   put("size",  if (isDir) "" else humanSize(f.length()))
                        put("path",  f.parent?.removePrefix(File(serveDir).canonicalPath) ?: "/")
                        put("icon",  meta.icon); put("color", meta.color)
                    })
                    count++
                }
        } catch (_: Exception) {}
        AppState.log("SEARCH", "\"$query\" → $count results")
        return jsonResp(Status.OK, """{"ok":true,"results":$results,"count":$count}""", session)
    }

    private fun handleFetchUrl(session: IHTTPSession): Response {
        val body   = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val urlStr = body.optString("url", "").trim()
        val dir    = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))
            return jsonResp(Status.BAD_REQUEST, """{"error":"Only HTTP/HTTPS URLs allowed"}""")
        return try {
            val url      = URL(urlStr)
            val fileName = url.path.substringAfterLast('/').let { if (it.isBlank() || !it.contains('.')) "download_${System.currentTimeMillis()}" else it }
            val dest     = File(dir, fileName)
            url.openStream().buffered(CHUNK_SIZE).use { inp ->
                FileOutputStream(dest).buffered(CHUNK_SIZE).use { out -> inp.copyTo(out, CHUNK_SIZE) }
            }
            AppState.log("FETCH", "$urlStr → ${dest.name} (${humanSize(dest.length())})")
            jsonResp(Status.OK, """{"ok":true,"name":"${escJs(fileName)}","size":"${escJs(humanSize(dest.length()))}"}""")
        } catch (e: Exception) {
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "Download failed")}"}""")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val relPath = URLDecoder.decode(session.parms["path"] ?: "/", "UTF-8")
        val dir = safeFile(relPath.trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        if (!dir.isDirectory) return jsonResp(Status.BAD_REQUEST, """{"error":"Not a directory"}""")
        return try {
            val files    = HashMap<String, String>()
            session.parseBody(files)
            val tempPath = files["file"] ?: return jsonResp(Status.BAD_REQUEST, """{"error":"No file"}""")
            val origName = session.parms["file"]?.let { File(it).name }
                ?: File(tempPath).name.let { if (it.startsWith("NanoHTTPD-")) "upload_${System.currentTimeMillis()}" else it }
            File(tempPath).copyTo(File(dir, origName), overwrite = true)
            try { File(tempPath).delete() } catch (_: Exception) {}
            AppState.log("UPLOAD", origName)
            jsonResp(Status.OK, """{"ok":true,"name":"${escJs(origName)}"}""")
        } catch (e: Exception) {
            jsonResp(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "Upload failed")}"}""")
        }
    }

    private fun handleRename(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val old  = body.optString("old_name", "").trim()
        val new  = body.optString("new_name", "").trim()
        if (old.isEmpty() || new.isEmpty()) return jsonResp(Status.BAD_REQUEST, """{"error":"Missing name"}""")
        if (new.contains('/') || new.contains('\\')) return jsonResp(Status.BAD_REQUEST, """{"error":"Invalid name"}""")
        val dir = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val src = File(dir, old); val dst = File(dir, new)
        if (!src.exists()) return jsonResp(Status.NOT_FOUND, """{"error":"Not found"}""")
        if (dst.exists())  return jsonResp(Status.CONFLICT,  """{"error":"Already exists"}""")
        src.renameTo(dst)
        etagCache.remove(src.absolutePath)
        AppState.log("RENAME", "$old → $new")
        return jsonResp(Status.OK, """{"ok":true}""")
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val body   = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val name   = body.optString("name", "").trim()
        if (name.isEmpty()) return jsonResp(Status.BAD_REQUEST, """{"error":"Missing name"}""")
        val dir    = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val target = File(dir, name)
        if (!target.exists()) return jsonResp(Status.NOT_FOUND, """{"error":"Not found"}""")
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        etagCache.remove(target.absolutePath)
        AppState.log("DELETE", "$name (ok=$ok)")
        return jsonResp(Status.OK, """{"ok":$ok}""")
    }

    private fun handleMkdir(session: IHTTPSession): Response {
        val body   = readJsonBody(session) ?: return jsonResp(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val name   = body.optString("name", "").trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\'))
            return jsonResp(Status.BAD_REQUEST, """{"error":"Invalid name"}""")
        val dir    = safeFile(body.optString("path", "/").trimStart('/')) ?: return jsonResp(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val target = File(dir, name)
        if (target.exists()) return jsonResp(Status.CONFLICT, """{"error":"Already exists"}""")
        target.mkdir()
        AppState.log("MKDIR", target.absolutePath)
        return jsonResp(Status.OK, """{"ok":true}""")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun safeFile(rel: String): File? {
        val root = File(serveDir).canonicalFile
        val t    = File(root, rel).canonicalFile
        return if (t.absolutePath.startsWith(root.absolutePath)) t else null
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: return null
            JSONObject(postData)
        } catch (_: Exception) { null }
    }

    private fun loadAsset(name: String): String {
        val cached = assetCache[name]
        if (cached != null) return String(cached, Charsets.UTF_8)
        val bytes = context.assets.open(name).readBytes()
        assetCache[name] = bytes
        return String(bytes, Charsets.UTF_8)
    }

    /** ETag = "size-lastModified" — cheap, no MD5 needed */
    private fun etag(file: File): String {
        val key = file.absolutePath
        val tag = "${file.length()}-${file.lastModified()}"
        etagCache[key] = tag
        return "\"$tag\""
    }

    private fun acceptsGzip(session: IHTTPSession): Boolean =
        session.headers["accept-encoding"]?.contains("gzip", ignoreCase = true) == true

    private fun gzipBytes(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(input.size / 2)
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun humanSize(b: Long): String {
        if (b < 1024) return "$b B"
        var n = b.toDouble()
        for (u in listOf("KB","MB","GB","TB")) { n /= 1024.0; if (n < 1024) return "%.1f %s".format(n, u) }
        return "%.1f PB".format(n)
    }

    private fun getMimeType(name: String) = when (name.substringAfterLast('.', "x").lowercase()) {
        "html","htm"->"text/html";"css"->"text/css";"js"->"application/javascript"
        "json"->"application/json";"xml"->"text/xml"
        "txt","md","log","sh","py","kt","java","cpp","c","h","rs","go","rb","php","sql","env","toml","yaml","yml","ini","cfg","conf","gradle","properties","csv"->"text/plain"
        "pdf"->"application/pdf"
        "png"->"image/png";"jpg","jpeg"->"image/jpeg";"gif"->"image/gif";"webp"->"image/webp";"svg"->"image/svg+xml"
        "mp3"->"audio/mpeg";"wav"->"audio/wav";"ogg"->"audio/ogg";"flac"->"audio/flac"
        "mp4"->"video/mp4";"mkv"->"video/x-matroska";"webm"->"video/webm";"avi"->"video/x-msvideo"
        "apk"->"application/vnd.android.package-archive";"zip"->"application/zip";"gz"->"application/gzip"
        else->"application/octet-stream"
    }

    private fun fileMeta(name: String, isDir: Boolean): FileMeta {
        if (isDir) return FileMeta("folder","#fbc02d","Folder")
        return when (name.substringAfterLast('.', "x").lowercase()) {
            "pdf"       ->FileMeta("picture_as_pdf","#e53935","PDF")
            "doc","docx"->FileMeta("description","#1565c0","Word")
            "xls","xlsx"->FileMeta("table_chart","#2e7d32","Excel")
            "ppt","pptx"->FileMeta("slideshow","#e65100","PowerPoint")
            "txt","md"  ->FileMeta("article","#546e7a","Text")
            "log"       ->FileMeta("article","#546e7a","Log")
            "py"        ->FileMeta("code","#f9a825","Python")
            "js","ts"   ->FileMeta("code","#f9a825","JS/TS")
            "kt","java" ->FileMeta("code","#e65100","Code")
            "html","htm"->FileMeta("language","#e65100","HTML")
            "css"       ->FileMeta("palette","#6a1b9a","CSS")
            "json","toml","yaml","yml"->FileMeta("data_object","#00838f","Data")
            "xml"       ->FileMeta("data_object","#00838f","XML")
            "sh","bat"  ->FileMeta("terminal","#33691e","Script")
            "sql"       ->FileMeta("storage","#00838f","SQL")
            "png","jpg","jpeg","webp"->FileMeta("image","#00acc1","Image")
            "gif"       ->FileMeta("gif_box","#00acc1","GIF")
            "svg"       ->FileMeta("image","#00acc1","SVG")
            "mp3","wav","flac","ogg"->FileMeta("music_note","#8e24aa","Audio")
            "mp4","mkv","avi","webm"->FileMeta("movie","#c62828","Video")
            "zip","rar","tar","gz","7z"->FileMeta("folder_zip","#ff8f00","Archive")
            "apk"       ->FileMeta("android","#00c853","APK")
            else        ->FileMeta("insert_drive_file","#78909c", name.substringAfterLast('.', "x").uppercase().ifEmpty{"File"})
        }
    }

    private fun escHtml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun escJs(s: String)   = s.replace("\\","\\\\").replace("'","\\'").replace("\"","\\\"").replace("\n","\\n").replace("\r","")

    private fun errorResp(s: Status, m: String) = newFixedLengthResponse(s, "text/plain", m)
    private fun redirect(url: String) = newFixedLengthResponse(Status.REDIRECT, "text/plain", "").also { it.addHeader("Location", url) }

    data class FileMeta(val icon: String, val color: String, val label: String)
    data class FileItem(val name: String, val url: String, val dlUrl: String, val isDir: Boolean,
                        val meta: FileMeta, val size: Long, val sizeH: String, val mtime: Long, val modified: String)
}
