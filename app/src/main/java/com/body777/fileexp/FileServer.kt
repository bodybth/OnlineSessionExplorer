package com.body777.fileexp

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class FileServer(
    private val context: Context,
    port: Int,
    private var serveDir: String,
    private var password: String
) : NanoHTTPD(port) {

    // ── Session store ─────────────────────────────────────────────────────────
    private val authedSessions = mutableSetOf<String>()
    private val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    // ── Asset cache ───────────────────────────────────────────────────────────
    private val templateHtml: String by lazy { loadAsset("template.html") }
    private val loginHtml: String by lazy { loadAsset("login.html") }

    fun updateConfig(dir: String, pwd: String) {
        serveDir = dir; password = pwd
    }

    // ── Main router ───────────────────────────────────────────────────────────
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        AppState.log("HTTP", "${method.name} $uri")

        // Auth guard
        if (uri != "/login" && uri != "/logout" && !isAuthed(session)) {
            val next = URLEncoder.encode(uri, "UTF-8")
            return redirect("/login?next=$next")
        }

        return when {
            uri == "/login"  -> handleLogin(session)
            uri == "/logout" -> handleLogout(session)
            uri.startsWith("/api/upload") && method == Method.POST  -> handleUpload(session)
            uri == "/api/rename" && method == Method.POST           -> handleRename(session)
            uri == "/api/delete" && method == Method.POST           -> handleDelete(session)
            uri == "/api/mkdir"  && method == Method.POST           -> handleMkdir(session)
            else -> handleFileOrDir(session, uri)
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────
    private fun isAuthed(session: IHTTPSession): Boolean {
        val id = session.cookies.read("ose_session") ?: return false
        return authedSessions.contains(id)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            val body = HashMap<String, String>()
            try { session.parseBody(body) } catch (_: Exception) {}
            val pwd = session.parms["password"] ?: ""
            if (pwd == password) {
                val id = UUID.randomUUID().toString()
                authedSessions.add(id)
                AppState.log("AUTH", "Login successful — session $id")
                val next = session.parms["next"] ?: "/"
                val resp = redirect(next)
                resp.addHeader("Set-Cookie", "ose_session=$id; Path=/; HttpOnly; SameSite=Strict")
                return resp
            }
            AppState.log("AUTH", "Login failed — wrong password")
            return html(loginHtml.replace("__ERROR_BLOCK__",
                """<div class="error"><span class="material-icons-round">error_outline</span>Incorrect password. Try again.</div>"""))
        }
        return html(loginHtml.replace("__ERROR_BLOCK__", ""))
    }

    private fun handleLogout(session: IHTTPSession): Response {
        val id = session.cookies.read("ose_session")
        if (id != null) authedSessions.remove(id)
        AppState.log("AUTH", "Logged out")
        val resp = redirect("/login")
        resp.addHeader("Set-Cookie", "ose_session=; Path=/; Max-Age=0; HttpOnly")
        return resp
    }

    // ── File / Directory serving ──────────────────────────────────────────────
    private fun handleFileOrDir(session: IHTTPSession, uri: String): Response {
        val relPath = URLDecoder.decode(uri.trimStart('/'), "UTF-8")
        val file = safeFile(relPath) ?: return errorResp(Status.FORBIDDEN, "Forbidden")
        if (!file.exists()) return errorResp(Status.NOT_FOUND, "Not found")

        if (file.isDirectory) return listDirectory(file, relPath, session)

        // Force download?
        val dl = session.parms["dl"] == "1"
        return serveFile(file, session, dl)
    }

    private fun serveFile(file: File, session: IHTTPSession, forceDownload: Boolean): Response {
        val mime = getMimeType(file.name)
        val size = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).split("-")
            var start = if (range[0].isNotEmpty()) range[0].toLongOrNull() ?: 0L else 0L
            var end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLongOrNull() ?: (size - 1) else size - 1
            if (start > end || end >= size) return errorResp(Status.RANGE_NOT_SATISFIABLE, "Range Not Satisfiable")
            val length = end - start + 1
            val fis = FileInputStream(file)
            fis.skip(start)
            
            // FIX: Using newFixedLengthResponse instead of Response constructor
            val resp = newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, fis, length)
            resp.addHeader("Content-Range", "bytes $start-$end/$size")
            resp.addHeader("Content-Length", length.toString())
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }

        val fis = FileInputStream(file)
        // FIX: Using newFixedLengthResponse instead of Response constructor
        val resp = newFixedLengthResponse(Status.OK, mime, fis, size)
        resp.addHeader("Content-Length", size.toString())
        resp.addHeader("Accept-Ranges", "bytes")
        if (forceDownload) resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return resp
    }

    // ── Directory listing ─────────────────────────────────────────────────────
    private fun listDirectory(dir: File, relPath: String, session: IHTTPSession): Response {
        val entries = try { dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList() }
        catch (_: Exception) { return errorResp(Status.FORBIDDEN, "Permission denied") }

        var nDirs = 0; var nFiles = 0; var totalSize = 0L
        val items = mutableListOf<FileItem>()

        for (entry in entries) {
            val isDir = entry.isDirectory
            val size = if (isDir) 0L else try { entry.length() } catch (_: Exception) { 0L }
            val mtime = try { entry.lastModified() } catch (_: Exception) { 0L }
            val modified = sdf.format(Date(mtime))
            val meta = fileMeta(entry.name, isDir)
            val urlPath = "/" + (if (relPath.isEmpty()) "" else "$relPath/") + entry.name + (if (isDir) "/" else "")
            val dlUrl = if (!isDir) "$urlPath?dl=1" else ""
            items.add(FileItem(entry.name, urlPath, dlUrl, isDir, meta, size, humanSize(size), mtime, modified))
            if (isDir) nDirs++ else { nFiles++; totalSize += size }
        }

        val displayPath = "/${relPath.trimEnd('/')}"
        val parentUrl = if (relPath.isEmpty()) "/" else {
            val parts = relPath.trimEnd('/').split("/").dropLast(1)
            if (parts.isEmpty()) "/" else "/" + parts.joinToString("/") + "/"
        }
        val breadcrumbs = buildBreadcrumbs(relPath)
        val stats = "<span><b>$nDirs</b> folders</span><span><b>$nFiles</b> files</span>" +
                    (if (totalSize > 0) "<span><b>${humanSize(totalSize)}</b> total</span>" else "")
        val isEmpty = items.isEmpty()

        val html = templateHtml
            .replace("__TITLE__", "FileVault · $displayPath")
            .replace("__DISPLAY_PATH__", escHtml(displayPath))
            .replace("__BREADCRUMBS__", breadcrumbs)
            .replace("__STATS__", stats)
            .replace("__GRID_ITEMS__", buildGridItems(items, displayPath, parentUrl))
            .replace("__LIST_ITEMS__", buildListItems(items, displayPath, parentUrl))
            .replace("__EMPTY_HIDDEN__", if (isEmpty) "" else "display:none")
            .replace("__CURRENT_PATH_JSON__", "\"${escJs(displayPath)}\"")

        return html(html)
    }

    // ── HTML builders ─────────────────────────────────────────────────────────
    private fun buildBreadcrumbs(relPath: String): String {
        val parts = relPath.trim('/').split("/").filter { it.isNotEmpty() }
        val sb = StringBuilder("""<a href="/">root</a>""")
        for ((i, part) in parts.withIndex()) {
            val url = "/" + parts.take(i + 1).joinToString("/") + "/"
            sb.append("""<span class="sep">/</span><a href="${escHtml(url)}">${escHtml(part)}</a>""")
        }
        return sb.toString()
    }

    private fun buildGridItems(items: List<FileItem>, displayPath: String, parentUrl: String): String {
        val sb = StringBuilder()
        if (displayPath != "/") {
            sb.append("""<div class="card" data-name=".." data-size="-1" data-date="0">
              <a href="$parentUrl"><div class="icon" style="color:#fbc02d">
              <span class="material-icons-round">arrow_back</span></div>
              <div class="name">..</div><div class="meta">Go up</div></a></div>""")
        }
        for (item in items) {
            sb.append("""<div class="card" data-name="${escHtml(item.name.lowercase())}" data-size="${item.size}" data-date="${item.mtime}">
              <a href="${escHtml(item.url)}">
                <div class="icon" style="color:${item.meta.color}"><span class="material-icons-round">${item.meta.icon}</span></div>
                <div class="name" title="${escHtml(item.name)}">${escHtml(item.name)}</div>
                <div class="meta">${if (item.isDir) item.meta.label else item.sizeH}</div>
              </a>
              <div class="item-actions">
                <button class="action-btn" onclick="event.stopPropagation();openRename('${escJs(item.name)}')" title="Rename">
                  <span class="material-icons-round">drive_file_rename_outline</span></button>
                <button class="action-btn del" onclick="event.stopPropagation();openDelete('${escJs(item.name)}')" title="Delete">
                  <span class="material-icons-round">delete_outline</span></button>
              </div>
            </div>""")
        }
        return sb.toString()
    }

    private fun buildListItems(items: List<FileItem>, displayPath: String, parentUrl: String): String {
        val sb = StringBuilder()
        if (displayPath != "/") {
            sb.append("""<div class="list-item" data-name=".." data-size="-1" data-date="0">
              <a href="$parentUrl"><div class="icon" style="color:#fbc02d">
              <span class="material-icons-round">arrow_back</span></div>
              <div class="info"><div class="fname">..</div><div class="fmeta">Parent folder</div></div></a></div>""")
        }
        for (item in items) {
            sb.append("""<div class="list-item" data-name="${escHtml(item.name.lowercase())}" data-size="${item.size}" data-date="${item.mtime}">
              <a href="${escHtml(item.url)}">
                <div class="icon" style="color:${item.meta.color}"><span class="material-icons-round">${item.meta.icon}</span></div>
                <div class="info">
                  <div class="fname" title="${escHtml(item.name)}">${escHtml(item.name)}</div>
                  <div class="fmeta">${item.meta.label} · ${item.modified}</div>
                </div>
              </a>
              <div class="fsize">${if (!item.isDir) item.sizeH else ""}</div>
              ${if (!item.isDir) """<a class="dl-btn" href="${escHtml(item.dlUrl)}" download title="Download"><span class="material-icons-round" style="font-size:16px">download</span></a>""" else ""}
              <div class="item-actions">
                <button class="action-btn" onclick="openRename('${escJs(item.name)}')" title="Rename">
                  <span class="material-icons-round">drive_file_rename_outline</span></button>
                <button class="action-btn del" onclick="openDelete('${escJs(item.name)}')" title="Delete">
                  <span class="material-icons-round">delete_outline</span></button>
              </div>
            </div>""")
        }
        return sb.toString()
    }

    // ── API: Upload ───────────────────────────────────────────────────────────
    private fun handleUpload(session: IHTTPSession): Response {
        val relPath = URLDecoder.decode(session.parms["path"] ?: "/", "UTF-8")
        val dir = safeFile(relPath.trimStart('/')) ?: return json(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        if (!dir.isDirectory) return json(Status.BAD_REQUEST, """{"error":"Not a directory"}""")

        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tempPath = files["file"] ?: return json(Status.BAD_REQUEST, """{"error":"No file provided"}""")
            val originalName = session.parms["file"]?.let { File(it).name }
                ?: File(tempPath).name.let { if (it.startsWith("NanoHTTPD-")) "upload_${System.currentTimeMillis()}" else it }
            val dest = File(dir, originalName)
            File(tempPath).copyTo(dest, overwrite = true)
            try { File(tempPath).delete() } catch (_: Exception) {}
            AppState.log("UPLOAD", "Saved: ${dest.absolutePath}")
            json(Status.OK, """{"ok":true,"name":"${escJs(originalName)}"}""")
        } catch (e: Exception) {
            AppState.log("ERROR", "Upload failed: ${e.message}")
            json(Status.INTERNAL_ERROR, """{"error":"${escJs(e.message ?: "Upload failed")}"}""")
        }
    }

    // ── API: Rename ───────────────────────────────────────────────────────────
    private fun handleRename(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return json(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val relPath = body.optString("path", "/")
        val oldName = body.optString("old_name", "").trim()
        val newName = body.optString("new_name", "").trim()
        if (oldName.isEmpty() || newName.isEmpty()) return json(Status.BAD_REQUEST, """{"error":"Missing name"}""")
        if (newName.contains('/') || newName.contains('\\')) return json(Status.BAD_REQUEST, """{"error":"Invalid name"}""")
        val dir = safeFile(relPath.trimStart('/')) ?: return json(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val src = File(dir, oldName); val dst = File(dir, newName)
        if (!src.exists()) return json(Status.NOT_FOUND, """{"error":"Not found"}""")
        if (dst.exists()) return json(Status.CONFLICT, """{"error":"Name already exists"}""")
        src.renameTo(dst)
        AppState.log("RENAME", "$oldName → $newName")
        return json(Status.OK, """{"ok":true}""")
    }

    // ── API: Delete ───────────────────────────────────────────────────────────
    private fun handleDelete(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return json(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val relPath = body.optString("path", "/")
        val name = body.optString("name", "").trim()
        if (name.isEmpty()) return json(Status.BAD_REQUEST, """{"error":"Missing name"}""")
        val dir = safeFile(relPath.trimStart('/')) ?: return json(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val target = File(dir, name)
        if (!target.exists()) return json(Status.NOT_FOUND, """{"error":"Not found"}""")
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        AppState.log("DELETE", "$name (ok=$ok)")
        return json(Status.OK, """{"ok":$ok}""")
    }

    // ── API: Mkdir ────────────────────────────────────────────────────────────
    private fun handleMkdir(session: IHTTPSession): Response {
        val body = readJsonBody(session) ?: return json(Status.BAD_REQUEST, """{"error":"Bad request"}""")
        val relPath = body.optString("path", "/")
        val name = body.optString("name", "").trim()
        if (name.isEmpty()) return json(Status.BAD_REQUEST, """{"error":"Missing name"}""")
        if (name.contains('/') || name.contains('\\')) return json(Status.BAD_REQUEST, """{"error":"Invalid name"}""")
        val dir = safeFile(relPath.trimStart('/')) ?: return json(Status.FORBIDDEN, """{"error":"Forbidden"}""")
        val target = File(dir, name)
        if (target.exists()) return json(Status.CONFLICT, """{"error":"Already exists"}""")
        target.mkdir()
        AppState.log("MKDIR", target.absolutePath)
        return json(Status.OK, """{"ok":true}""")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun safeFile(rel: String): File? {
        val root = File(serveDir).canonicalFile
        val target = File(root, rel).canonicalFile
        return if (target.absolutePath.startsWith(root.absolutePath)) target else null
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject? {
        return try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val raw = body["postData"] ?: return null
            JSONObject(raw)
        } catch (_: Exception) { null }
    }

    private fun loadAsset(name: String): String =
        context.assets.open(name).bufferedReader().readText()

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var n = bytes.toDouble()
        for (u in listOf("KB", "MB", "GB", "TB")) {
            n /= 1024.0
            if (n < 1024.0) return "%.1f %s".format(n, u)
        }
        return "%.1f PB".format(n)
    }

    private fun getMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html"
            "css"  -> "text/css"
            "js"   -> "application/javascript"
            "json" -> "application/json"
            "xml"  -> "text/xml"
            "txt", "md" -> "text/plain"
            "pdf"  -> "application/pdf"
            "png"  -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif"  -> "image/gif"
            "webp" -> "image/webp"
            "svg"  -> "image/svg+xml"
            "mp3"  -> "audio/mpeg"
            "wav"  -> "audio/wav"
            "ogg"  -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4"  -> "video/mp4"
            "mkv"  -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi"  -> "video/x-msvideo"
            "apk"  -> "application/vnd.android.package-archive"
            "zip"  -> "application/zip"
            "gz"   -> "application/gzip"
            else   -> "application/octet-stream"
        }
    }

    private fun fileMeta(name: String, isDir: Boolean): FileMeta {
        if (isDir) return FileMeta("folder", "#fbc02d", "Folder")
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf"  -> FileMeta("picture_as_pdf", "#e53935", "PDF")
            "doc", "docx" -> FileMeta("description", "#1565c0", "Word")
            "xls", "xlsx" -> FileMeta("table_chart", "#2e7d32", "Excel")
            "ppt", "pptx" -> FileMeta("slideshow", "#e65100", "PowerPoint")
            "txt", "md"   -> FileMeta("article", "#546e7a", "Text")
            "py"   -> FileMeta("code", "#f9a825", "Python")
            "js"   -> FileMeta("code", "#f9a825", "JavaScript")
            "ts"   -> FileMeta("code", "#1565c0", "TypeScript")
            "html" -> FileMeta("language", "#e65100", "HTML")
            "css"  -> FileMeta("palette", "#6a1b9a", "CSS")
            "json" -> FileMeta("data_object", "#00838f", "JSON")
            "sh"   -> FileMeta("terminal", "#33691e", "Shell")
            "png", "jpg", "jpeg", "webp" -> FileMeta("image", "#00acc1", "Image")
            "gif"  -> FileMeta("gif_box", "#00acc1", "GIF")
            "svg"  -> FileMeta("image", "#00acc1", "SVG")
            "mp3", "wav", "flac", "ogg" -> FileMeta("music_note", "#8e24aa", "Audio")
            "mp4", "mkv", "avi", "webm" -> FileMeta("movie", "#c62828", "Video")
            "zip", "rar", "tar", "gz", "7z" -> FileMeta("folder_zip", "#ff8f00", "Archive")
            "apk"  -> FileMeta("android", "#00c853", "APK")
            else   -> FileMeta("insert_drive_file", "#78909c", name.substringAfterLast('.', "").uppercase().ifEmpty { "File" })
        }
    }

    private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun escJs(s: String)   = s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")

    private fun html(body: String) = newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", body)
    private fun json(status: Status, body: String) = newFixedLengthResponse(status, "application/json", body)
    private fun redirect(url: String): Response {
        val r = newFixedLengthResponse(Status.REDIRECT, "text/plain", "")
        r.addHeader("Location", url)
        return r
    }
    private fun errorResp(status: Status, msg: String) = newFixedLengthResponse(status, "text/plain", msg)

    // ── Data classes ──────────────────────────────────────────────────────────
    data class FileMeta(val icon: String, val color: String, val label: String)
    data class FileItem(
        val name: String, val url: String, val dlUrl: String,
        val isDir: Boolean, val meta: FileMeta,
        val size: Long, val sizeH: String, val mtime: Long, val modified: String
    )
}
