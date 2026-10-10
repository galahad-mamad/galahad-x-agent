package com.agent.app.agent

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Environment
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import com.agent.app.screen.ScreenReader
import com.agent.app.shell.ShellBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tool Registry - the agent's real tools
 *
 * Tools:
 * - UI: open_settings, open_app, get_screen, click, type, scroll, swipe, back, home
 * - Shell: shell, write_file, read_file, list_files, run_script
 * - System: download, get_info
 * - Control: wait, done
 *
 * Every tool returns an observation containing stdout+stderr+exit code
 * so the model can understand errors and fix them (debug loop).
 */
class ToolRegistry(
    private val context: Context,
    private val screenReader: () -> ScreenReader?,
    private val shell: ShellBridge,
    private val memory: com.agent.app.memory.AgentMemory? = null
) {
    companion object {
        private const val TAG = "ToolRegistry"
        private const val SCRIPTS_DIR = "scripts"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun workDir(): File {
        // workspace = the app's filesDir; the model may use scripts/ or any path
        val dir = context.filesDir
        if (!dir.exists()) dir.mkdirs()
        File(dir, SCRIPTS_DIR).mkdirs()
        return dir
    }

    /**
     * Tool schema sent to the LLM
     */
    fun toolSchemasJson(): String {
        return """
[
  {"name":"open_settings","desc":"Open phone settings","args":"{}"},
  {"name":"open_app","desc":"Open an app","args":"{\"package\":\"package name or app name\"}"},
  {"name":"get_screen","desc":"Read the current screen content from the accessibility tree","args":"{}"},
  {"name":"click","desc":"Tap a text item on screen","args":"{\"text\":\"item text\"}"},
  {"name":"type","desc":"Type text into the focused input field","args":"{\"text\":\"text\"}"},
  {"name":"scroll_down","desc":"Scroll down","args":"{}"},
  {"name":"swipe","desc":"Swipe (up/down/left/right)","args":"{\"direction\":\"up|down|left|right\"}"},
  {"name":"back","desc":"Back button","args":"{}"},
  {"name":"home","desc":"Home button","args":"{}"},
  {"name":"shell","desc":"Run a shell command. Output includes stderr and exit code","args":"{\"cmd\":\"command\",\"timeout_sec\":30}"},
  {"name":"write_file","desc":"Write a file. For large content write in chunks with append:true (~2000 chars each)","args":"{\"path\":\"file name\",\"content\":\"content\",\"append\":false}"},
  {"name":"read_file","desc":"Read a file","args":"{\"path\":\"file name\"}"},
  {"name":"edit_file","desc":"Edit a file with find/replace: replaces exact old_text with new_text (keeps the rest). Use it to fix errors in scripts instead of rewriting the whole file","args":"{\"path\":\"script.py\",\"old_text\":\"exact text to find\",\"new_text\":\"replacement\"}"},
  {"name":"list_files","desc":"List files in the working directory (scripts/)","args":"{\"path\":\".\"}"},
  {"name":"run_script","desc":"Run an sh script. Output includes stderr and exit code","args":"{\"script\":\"script text or file name\",\"timeout_sec\":60}"},
  {"name":"download","desc":"Download a file from the internet by url","args":"{\"url\":\"https://...\",\"filename\":\"file name\"}"},
  {"name":"fetch_url","desc":"Read a web page as text (HTML is converted to plain text). Also works for APIs (raw JSON)","args":"{\"url\":\"https://...\"}"},
  {"name":"search","desc":"Web search. Results: title + link + snippet","args":"{\"query\":\"search text\"}"},
  {"name":"open_url","desc":"Open a URL in the phone browser for the user","args":"{\"url\":\"https://...\"}"},
  {"name":"vision_analyze","desc":"Look at and analyze an image. The image goes to a vision model and an answer comes back. Use for any question about image content","args":"{\"path\":\"image path\",\"question\":\"question or task\"}"},
  {"name":"run_background","desc":"Run a command in the background without blocking. Returns a pid immediately. For long tasks (downloads, processing)","args":"{\"cmd\":\"sh command\",\"name\":\"job name\"}"},
  {"name":"poll_process","desc":"Check a background process. Returns output and exit code when finished","args":"{\"pid\":12345}"},
  {"name":"plan","desc":"Record an execution plan for a project (website, app, multi-file). Always call before starting a big project","args":"{\"steps\":[\"first step\",\"second step\"]}"},
  {"name":"serve","desc":"Start a local HTTP server from a folder. Then call open_url so the user can see it","args":"{\"dir\":\"folder path\",\"port\":8080}"},
  {"name":"stop_server","desc":"Stop the local server","args":"{}"},
  {"name":"server_status","desc":"Local server status (up/down + address)","args":"{}"},
  {"name":"get_info","desc":"Device info (battery, storage, network, Android version)","args":"{}"},
  {"name":"list_inbox","desc":"List files attached by the user","args":"{}"},
  {"name":"file_info","desc":"File info (size, type, image dimensions)","args":"{\"path\":\"file path\"}"},
  {"name":"skill_list","desc":"Show skills saved on this device. Always check before starting work — the routine may already be saved","args":"{}"},
  {"name":"skill_load","desc":"Read a skill in full (SKILL.md) — step-by-step instructions of the saved routine","args":"{\"name\":\"skill_name\"}"},
  {"name":"skill_save","desc":"Save a repeatable routine as a new skill (long-term memory). name: short English, content: step-by-step with exact commands","args":"{\"name\":\"web-build\",\"content\":\"# ...\"}"},
  {"name":"skill_update","desc":"Update an existing skill: replace old_text with new_text (empty old_text = append)","args":"{\"name\":\"web-build\",\"old_text\":\"old text\",\"new_text\":\"new text\"}"},
  {"name":"skill_delete","desc":"Delete a saved skill","args":"{\"name\":\"name\"}"},
  {"name":"install_tool","desc":"Install a tool. Bundled: python (3.14+pip+numpy+pillow), ffmpeg. ANY other static binary (nmap, curl...): pass name + url of a static build for this device arch. For python packages use pip","args":"{\"name\":\"python\"}"},
  {"name":"memory","desc":"Persistent memory across sessions — save/edit facts. target=user for user preferences, target=memory for the rest. Single: action+content/old_text. Batch: operations=[{action, content?, old_text?}]","args":"{\"target\":\"user\",\"operations\":[{\"action\":\"add\",\"content\":\"user loves coffee\"}]}"},
  {"name":"session_search","desc":"Search all past chat sessions — when the user mentions something said/done before. Result: id,title,hits,snippet","args":"{\"query\":\"multi-word search\"}"},
  {"name":"python","desc":"Run Python 3.14: -c code, a script, or -m module","args":"{\"args\":\"-c print(1)\"}"},
  {"name":"pip","desc":"pip inside python: install/list/uninstall from PyPI","args":"{\"cmd\":\"install requests\"}"},
  {"name":"run_ffmpeg","desc":"Run ffmpeg (build/edit/convert video, slideshow from images, audio, compression). Write cmd as ffmpeg arguments without the binary name","args":"{\"cmd\":\"-i in.mp4 -vf scale=720:-2 -y out.mp4\"}"},
  {"name":"image_edit","desc":"Edit an image. actions: rotate(angle), resize(width,height), convert(png/jpg/webp), compress(quality 1-100), crop(x,y,width,height), flip(horizontal|vertical), filter(grayscale|sepia|invert|warm|cold), brightness(-100 to 100), blur","args":"{\"path\":\"path\",\"action\":\"rotate|resize|convert|compress|crop|flip|filter|brightness|blur\",\"angle\":90,\"width\":800,\"height\":600,\"format\":\"png\",\"quality\":85,\"x\":0,\"y\":0,\"direction\":\"horizontal\",\"value\":50}"},
  {"name":"send_file","desc":"Send a file to the user in chat (output/edited)","args":"{\"path\":\"file path\"}"},
  {"name":"open_file","desc":"Open a file with a suitable app (image/video/audio/document)","args":"{\"path\":\"file path\"}"},
  {"name":"list_dir_custom","desc":"List contents of any directory path","args":"{\"path\":\"/sdcard/Download\"}"},
  {"name":"wait","desc":"Wait a few seconds","args":"{\"seconds\":2}"},
  {"name":"done","desc":"Call this when the task is fully done","args":"{\"result\":\"task summary\"}"}
]
        
""".trimIndent()
    }

    /**
     * Execute a tool -> observation for the LLM
     */
    fun execute(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_settings" -> openSettings()
                "open_app" -> openApp(args.optString("package", ""))
                "get_screen" -> getScreen()
                "click" -> click(args.optString("text", ""))
                "type" -> type(args.optString("text", ""))
                "scroll_down" -> scroll("down")
                "swipe" -> scroll(args.optString("direction", "down"))
                "back" -> globalAction(2)
                "home" -> globalAction(3)
                "shell" -> runShell(args)
                "write_file" -> writeFile(args)
                "read_file" -> readFile(args)
                "edit_file" -> editFile(args)
                "list_files" -> listFiles(args)
                "run_script" -> runScript(args)
                "download" -> download(args)
                "fetch_url" -> fetchUrl(args)
                "search" -> searchWeb(args)
                "open_url" -> openUrl(args)
                "vision_analyze" -> visionAnalyze(args)
                "run_background" -> runBackground(args)
                "poll_process" -> pollProcess(args)
                "install_tool" -> installTool(args)
            "python" -> runPythonTool(args)
            "memory" -> runMemory(args)
            "session_search" -> runSessionSearch(args)
            "pip" -> runPip(args)
            "run_ffmpeg" -> runFfmpeg(args)
            "skill_list" -> skillTool("skill_list", args)
            "skill_load" -> skillTool("skill_load", args)
            "skill_save" -> skillTool("skill_save", args)
            "skill_update" -> skillTool("skill_update", args)
            "skill_delete" -> skillTool("skill_delete", args)
            "plan" -> makePlan(args)
                "serve" -> serveHttp(args)
                "stop_server" -> stopHttp().let { "server stopped" }
                "server_status" -> serverStatus()
                "get_info" -> getInfo()
                "list_inbox" -> listInbox()
                "file_info" -> fileInfo(args)
                "image_edit" -> imageEdit(args)
                "send_file" -> sendFile(args)
                "open_file" -> openFile(args)
                "list_dir_custom" -> listDirCustom(args)
                "wait" -> {
                    val sec = args.optInt("seconds", 2).coerceIn(1, 10)
                    Thread.sleep(sec * 1000L)
                    "waited"
                }
                "done" -> "DONE"
                else -> "ERROR: unknown tool '$name'"
            }
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ============ UI TOOLS ============

    private fun openSettings(): String {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Thread.sleep(1500)
            "settings opened"
        } catch (e: Exception) {
            val r = shell.execute("am start -a android.settings.SETTINGS")
            if (r.isSuccess) "settings opened" else "ERROR: failed to open - ${r.error.take(200)}"
        }
    }

    private fun openApp(pkgOrName: String): String {
        if (pkgOrName.isBlank()) return "ERROR: app name is empty"
        try {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkgOrName)
                ?: pm.getInstalledApplications(0).firstOrNull {
                    pm.getApplicationLabel(it).toString().contains(pkgOrName, true)
                }?.let { pm.getLaunchIntentForPackage(it.packageName) }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Thread.sleep(1500)
                return "app opened"
            }
        } catch (e: Exception) {
            // fallback
        }
        return "ERROR: no app named '$pkgOrName'. Check existing apps or try another name"
    }

    private fun getScreen(): String {
        val sr = screenReader() ?: return "ERROR: Accessibility Service unavailable. Ask the user to enable it (Settings > Accessibility > Galahad-X-Agent)"
        val content = sr.getScreenContent()
        val elements = sr.getInteractiveElements()
        val elText = elements.take(30).joinToString("\n") { e ->
            buildString {
                append("- ")
                if (e.text.isNotBlank()) append("\"${e.text}\"")
                if (e.contentDescription.isNotBlank()) append(" (${e.contentDescription})")
                append(" [${e.className.substringAfterLast('.')}]")
                if (e.isClickable) append(" clickable")
            }
        }
        return buildString {
            append("=== SCREEN TEXT ===\n")
            append(content.ifBlank { "(empty)" }.take(3000))
            append("\n\n=== INTERACTIVE ELEMENTS ===\n")
            append(elText.ifBlank { "(no interactive elements)" })
        }
    }

    private fun click(text: String): String {
        val sr = screenReader() ?: return "ERROR: accessibility unavailable"
        if (text.isBlank()) return "ERROR: empty text"
        return if (sr.clickByText(text)) "clicked '$text'"
        else "ERROR: no item with text '$text'. Call get_screen first and use the exact text"
    }

    private fun type(text: String): String {
        val sr = screenReader() ?: return "ERROR: accessibility unavailable"
        if (text.isBlank()) return "ERROR: empty text"
        return if (sr.typeText(text)) "typed: '$text'"
        else "ERROR: no focused input field. Click the field first"
    }

    private fun scroll(direction: String): String {
        val sr = screenReader() ?: return "ERROR: accessibility unavailable"
        val ok = when (direction) {
            "up" -> sr.scrollUp()
            else -> sr.scrollDown()
        }
        return if (ok) "scrolled $direction" else "ERROR: could not scroll (page not scrollable or no accessibility)"
    }

    private fun globalAction(action: Int): String {
        val sr = screenReader()
        val ok = when (action) {
            2 -> sr?.pressBack() ?: false
            else -> sr?.pressHome() ?: false
        }
        if (ok) return if (action == 2) "back pressed" else "went home"
        // shell fallback
        val key = if (action == 2) "KEYCODE_BACK" else "KEYCODE_HOME"
        val r = shell.execute("input keyevent $key")
        return if (r.isSuccess) "done (via shell)" else "ERROR: failed"
    }

    // ============ SHELL TOOLS ============

    private fun runShell(args: JSONObject): String {
        val cmd = args.optString("cmd", "")
        if (cmd.isBlank()) return "ERROR: empty command"
        val timeout = args.optInt("timeout_sec", 60).coerceIn(5, 120)
        val r = shell.executeWithTimeout(cmd, timeout, workDir(), pyBinDir(), pyShellEnv())
        return formatShellOutput(r.exitCode, r.output, r.error, r.timedOut)
    }

    private fun writeFile(args: JSONObject): String {
        val path = args.optString("path", "")
        val content = args.optString("content", "")
        val append = args.optBoolean("append", false)
        if (path.isBlank()) return "ERROR: empty path"
        return try {
            val f = if (path.startsWith("/")) File(path) else File(workDir(), path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            "${if (append) "appended to file" else "file written"}: ${f.absolutePath} (${f.length()} bytes total)"
        } catch (e: Exception) {
            "ERROR: write failed - ${e.message}"
        }
    }

    private fun readFile(args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) return "ERROR: empty path"
        return try {
            val f = if (path.startsWith("/")) File(path) else File(workDir(), path)
            if (!f.exists()) "ERROR: file '$path' not found"
            else f.readText().take(5000)
        } catch (e: Exception) {
            "ERROR: read failed - ${e.message}"
        }
    }

    /** Targeted find/replace edit - keeps the rest of the file intact */
    private fun editFile(args: JSONObject): String {
        val path = args.optString("path", "")
        val oldText = args.optString("old_text")
        val newText = args.optString("new_text")
        if (path.isBlank()) return "ERROR: empty path"
        if (oldText.isBlank()) return "ERROR: empty old_text - copy the exact text to replace from read_file"
        return try {
            val f = if (path.startsWith("/")) File(path) else File(workDir(), path)
            if (!f.isFile) return "ERROR: file '$path' not found"
            val content = f.readText()
            if (!content.contains(oldText))
                return "ERROR: old_text not found in '$path' - copy it EXACTLY as read_file showed it (spaces, quotes and case matter)"
            val count = content.split(oldText).size - 1
            f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true)
            f.writeText(content.replace(oldText, newText))
            "EDITED: $path ($count replacement${if (count > 1) "s" else ""}) - backup saved as ${f.name}.bak"
        } catch (e: Exception) {
            "ERROR: edit failed: ${e.message}"
        }
    }

    private fun listFiles(args: JSONObject): String {
        val path = args.optString("path", ".")
        return try {
            val f = if (path == "." || !path.startsWith("/")) File(workDir(), path) else File(path)
            if (!f.exists()) return "ERROR: directory '$path' not found"
            val items = f.listFiles() ?: emptyArray()
            if (items.isEmpty()) "(empty)"
            else items.joinToString("\n") {
                (if (it.isDirectory) "📁 " else "📄 ") + it.name + (if (it.isFile) " (${it.length()}B)" else "")
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun runScript(args: JSONObject): String {
        val script = args.optString("script", "")
        if (script.isBlank()) return "ERROR: empty script"
        val timeout = args.optInt("timeout_sec", 60).coerceIn(5, 120)

        return try {
            // if it is a file name, run the file with the right interpreter
            val file = File(workDir(), script)
            val cmd = if (file.exists()) {
                val firstLine = file.bufferedReader().use { it.readLine() }?.trim() ?: ""
                val shebang = if (firstLine.startsWith("#!")) firstLine.removePrefix("#!").trim() else ""
                val isPython = file.name.endsWith(".py") || shebang.contains("python")
                when {
                    // python script: run with the bundled python (or system python3 fallback)
                    isPython -> if (pyBin().exists()) "${pyBin().absolutePath} ${file.absolutePath}"
                                else "python3 ${file.absolutePath}"
                    // absolute interpreter from shebang (e.g. #!/data/.../node) when it exists
                    shebang.startsWith("/") && !shebang.startsWith("/usr/bin/env") ->
                        "${shebang.split(Regex("\\s+")).first()} ${file.absolutePath}"
                    // default: sh (a shebang line is just a comment inside it)
                    else -> "sh ${file.absolutePath}"
                }
            } else {
                "sh -c ${JSONObject.quote(script)}"
            }
            val r = shell.executeWithTimeout(cmd, timeout, workDir(), pyBinDir(), pyShellEnv())
            formatShellOutput(r.exitCode, r.output, r.error, r.timedOut)
        } catch (e: Exception) {
            "ERROR: script failed: ${e.message}"
        }
    }

    private fun download(args: JSONObject): String {
        val url = args.optString("url", "")
        val filename = args.optString("filename", url.substringAfterLast('/'))
        if (url.isBlank()) return "ERROR: empty url"
        return try {
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code}"
            val bytes = resp.body?.bytes() ?: return "ERROR: empty body"
            val f = File(workDir(), filename)
            f.writeBytes(bytes)
            "downloaded: ${f.absolutePath} (${bytes.size} bytes)"
        } catch (e: Exception) {
            "ERROR: download failed - ${e.message}"
        }
    }

    // ============ NATIVE TOOL SCHEMAS (OpenAI function-calling) ============

    /** Convert the compact internal schema to function-calling format - field types are inferred from the examples */
    fun nativeToolsJson(): String {
        return try {
            val arr = JSONArray(toolSchemasJson())
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val props = JSONObject()
                try {
                    val a = JSONObject(t.optString("args", "{}"))
                    val keys = a.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = a.get(k)
                        val type = when (v) {
                            is Int, is Long -> "integer"
                            is Double, is Float -> "number"
                            is Boolean -> "boolean"
                            else -> "string"
                        }
                        props.put(k, JSONObject().put("type", type))
                    }
                } catch (_: Exception) {}
                out.put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", t.getString("name"))
                        .put("description", t.getString("desc"))
                        .put("parameters", JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", JSONArray()))))
            }
            out.toString()
        } catch (e: Exception) {
            Log.e(TAG, "nativeToolsJson failed", e)
            "[]"
        }
    }

    // ============ PLAN ============

    // ============ tool shop: automatic static binary install ============

    private val skillStore = com.agent.app.chat.SkillStore(context)

    // ============ Skill store ============

    private fun skillTool(name: String, args: JSONObject): String {
        val skillName = args.optString("name").ifBlank { args.optString("skill") }.ifBlank { args.optString("skill_name") }
        fun content(): String = args.optString("content").ifBlank { args.optString("text") }
            .ifBlank { args.optString("steps") }.ifBlank { args.optString("instructions") }
            .ifBlank { args.optString("body") }
        return when (name) {
            "skill_list" -> skillStore.list()
            "skill_load" -> {
                val direct = skillStore.load(skillName)
                if (direct.startsWith("ERROR: skill")) skillStore.loadByPrefix(skillName) else direct
            }
            "skill_save" -> {
                val c = content()
                if (skillName.isBlank()) "ERROR: empty skill name - pass a short English name like pentest-recon"
                else if (c.isBlank()) "ERROR: empty content - put the step-by-step routine in the content field"
                else skillStore.save(skillName, c)
            }
            "skill_update" -> skillStore.update(skillName,
                args.optString("old_text").ifBlank { args.optString("old") },
                args.optString("new_text").ifBlank { args.optString("new").ifBlank { args.optString("content") } })
            "skill_delete" -> skillStore.delete(skillName)
            else -> "ERROR: unknown skill op"
        }
    }

    // ===== python (termux prefix extracted from assets) =====
    private fun pyPrefix(): java.io.File =
        java.io.File(context.filesDir, "data/data/com.termux/files/usr")

    private fun pyEnv(): Map<String, String> {
        val p = pyPrefix().absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to "$p/lib",
            "PYTHONHOME" to p,
            "SSL_CERT_FILE" to "$p/etc/tls/cert.pem",
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to java.io.File(context.filesDir, "tmp").apply { mkdirs() }.absolutePath
        )
    }

    private fun pyBin(): java.io.File = java.io.File(pyPrefix(), "bin/python3.14")

    /** true only if the bundled python actually executes (catches broken/foreign binaries) */
    private fun pyBinRuns(): Boolean {
        return try {
            if (!pyBin().exists()) {
                false
            } else {
                // same environment as pyExec: the relocated python needs PYTHONHOME
                val pb = ProcessBuilder(listOf(pyBin().absolutePath, "--version")).redirectErrorStream(true)
                pb.environment().putAll(pyEnv())
                val p = pb.start()
                val finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) p.destroyForcibly()
                finished && p.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun installPython(): String {
        try {
            if (pyBin().exists()) {
                if (pyBinRuns()) return "ALREADY INSTALLED: Python 3.14 + pip + numpy + pillow - now use the python or pip tool"
                // present but not runnable (foreign/desktop binary at the path) - repair from the bundled asset
                Log.w(TAG, "install_python: binary present but not runnable - repairing")
                pyPrefix().deleteRecursively()
            }
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val asset = when {
                abi.contains("arm64") || abi.contains("aarch64") -> "python-arm64.zip"
                abi.contains("x86_64") || abi.contains("amd64") -> "python-x86_64.zip"
                else -> return "ERROR: device architecture '$abi' not supported (arm64 and x86_64 only)"
            }
            Log.d(TAG, "install_python: abis=${android.os.Build.SUPPORTED_ABIS.joinToString()} asset=$asset")
            val tmp = java.io.File(context.cacheDir, asset)
            context.assets.open("python/$asset").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            java.util.zip.ZipInputStream(tmp.inputStream()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val outFile = java.io.File(context.filesDir, entry.name)
                    if (entry.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zin.copyTo(out, 64 * 1024) }
                    }
                    entry = zin.nextEntry
                }
            }
            tmp.delete()
            java.io.File(pyPrefix(), "bin").listFiles()?.forEach { it.setExecutable(true, false) }
            if (!pyBin().exists()) return "ERROR: extraction failed - python3.14 not found"
            // smoke test
            val pb = ProcessBuilder(pyBin().absolutePath, "-c", "import sys; print(sys.version)")
            pb.environment().putAll(pyEnv())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().take(300)
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            proc.destroy()
            if (!out.contains("3.14")) return "ERROR: python extracted but failed to run: $out"
            return "INSTALLED: Python 3.14 + pip + numpy + pillow ✓\n" +
                    "usage: python tool (args like -c \"print(1)\" or script.py) and pip tool (e.g. install requests)"
        } catch (e: Exception) {
            Log.w(TAG, "install_python failed: ${e.message}")
            return "ERROR: python install failed - ${e.message}"
        }
    }

    /** Run a command in sh with a custom env - python and non-python separated (LD_LIBRARY_PATH only for python) */
    private fun shExec(cmdLine: String, timeoutSec: Long, env: Map<String, String> = emptyMap()): String {
        val pb = ProcessBuilder("sh", "-c", cmdLine)
            .directory(context.filesDir)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return output.take(3000) + "\n[timeout ${timeoutSec}s - long command killed]"
        }
        val tail = output.takeLast(4000)
        return if (tail.isBlank()) "(no output) exit=${proc.exitValue()}" else tail
    }

    /** python bin dir for adding to shell PATH (empty if not installed) */
    private fun pyBinDir(): String = if (pyBin().exists()) pyBin().parent else ""

    /** env for shell commands when python is installed (PYTHONHOME etc - prefix is relocated) */
    private fun pyShellEnv(): Map<String, String> = if (pyBin().exists()) pyEnv() else emptyMap()

    /** Auto-install python once in background at first launch (idempotent) */
    fun ensurePythonAsync() {
        Thread {
            try {
                if (pyBin().exists()) {
                    if (pyBinRuns()) return@Thread
                    Log.w(TAG, "python present but not runnable - reinstalling from asset")
                    pyPrefix().deleteRecursively()
                }
                Log.d(TAG, "auto-installing python in background"); installPython()
            }
            catch (e: Exception) { Log.w(TAG, "auto python install: ${e.message}") }
        }.start()
    }

    /** Tokenize a python args string honoring quotes, then run WITHOUT any shell (multiline code safe) */
    private fun pyTokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in s) {
            when {
                quote != null -> { if (ch == quote) quote = null else cur.append(ch) }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Run python with argv list - NO shell involved, so multiline -c code and quotes are safe */
    private fun pyExec(argv: List<String>, timeoutSec: Long): String {
        val pb = ProcessBuilder(listOf(pyBin().absolutePath) + argv)
            .directory(workDir())
            .redirectErrorStream(true)
        pb.environment().putAll(pyEnv())
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return output.take(3000) + "\n[timeout ${timeoutSec}s - long command killed]"
        }
        val tail = output.takeLast(4000)
        return if (tail.isBlank()) "(no output) exit=${proc.exitValue()}" else tail
    }

    private fun runPythonTool(args: JSONObject): String {
        val pyArgs = args.optString("args").ifBlank { args.optString("cmd") }
        if (pyArgs.isBlank()) return "ERROR: empty args - example: -c \"print(2**100)\" or myscript.py or -m pip install requests"
        if (!pyBin().exists()) return "ERROR: python not installed - run install_tool with name=python first"
        // run directly with argv - NO shell, so multiline -c code, quotes and $ are all safe
        return try {
            pyExec(pyTokenize(pyArgs), 180)
        } catch (e: Exception) {
            "ERROR: python failed to start (${e.message}) - run install_tool with name=python to repair the installation"
        }
    }

    /** Search past sessions: keyword + snippet */
    private fun runSessionSearch(args: JSONObject): String {
        val q = args.optString("query").trim()
        if (q.isBlank()) return "ERROR: empty query"
        val store = com.agent.app.chat.SessionStore(context)
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val results = org.json.JSONArray()
        store.listSessions().sortedByDescending { it.updatedAt }.forEach { meta ->
            if (results.length() >= 5) return@forEach
            val data = store.loadSession(meta.id) ?: return@forEach
            val hits = data.messages.filter { m ->
                terms.any { t -> m.content.contains(t, ignoreCase = true) }
            }
            if (hits.isNotEmpty()) {
                results.put(org.json.JSONObject()
                    .put("id", meta.id)
                    .put("title", meta.title.take(60))
                    .put("hits", hits.size)
                    .put("snippet", hits.last().content.replace("\n", " ").take(280))
                )
            }
        }
        return if (results.length() == 0) "nothing found in past sessions for: $q"
        else org.json.JSONObject().put("results", results).toString()
    }

    /** memory tool — single + atomic batch ops, validated before the budget check */
    private fun runMemory(args: JSONObject): String {
        val store = com.agent.app.memory.MemoryStore(context)
        Log.d(TAG, "memory raw args: $args")
        var target = args.optString("target", "memory")
        if (target.isBlank() || target == "null") target = "memory"
        if (target != "memory" && target != "user") {
            return "ERROR: invalid target '$target' - only memory or user"
        }
        // tolerance: operations may be a JSON string or absent
        val opsRaw = args.opt("operations")
        val ops: org.json.JSONArray? = when (opsRaw) {
            is org.json.JSONArray -> opsRaw
            is String -> runCatching { org.json.JSONArray(opsRaw) }.getOrNull()
            else -> null
        }
        val result: Map<String, Any> = if (ops != null && ops.length() > 0) {
            val list = (0 until ops.length()).map { ops.getJSONObject(it) }
            store.applyBatch(target, list)
        } else {
            // if action is empty but content exists -> treat as add (lenient to the model)
            val effAction = args.optString("action").ifBlank {
                if (args.optString("content").isNotBlank()) "add" else ""
            }
            when (effAction) {
                "add" -> {
                    val c = args.optString("content")
                    if (c.isBlank()) mapOf("success" to false, "error" to "add requires content")
                    else store.add(target, c)
                }
                "replace" -> {
                    val old = args.optString("old_text")
                    val c = args.optString("content")
                    if (old.isBlank() || c.isBlank())
                        store.replace(target, old.ifBlank { "\u0000" }, c)
                    else store.replace(target, old, c)
                }
                "remove" -> {
                    store.remove(target, args.optString("old_text"))
                }
                else -> mapOf("success" to false, "error" to "unknown action '$effAction' - pass action or operations (add/replace/remove)")
            }
        }
        return org.json.JSONObject(result).toString()
    }

    private fun runPip(args: JSONObject): String {
        val pipArgs = args.optString("cmd").ifBlank { args.optString("args") }
        if (pipArgs.isBlank()) return "ERROR: empty cmd - example: install requests or list"
        if (!pyBin().exists()) return "ERROR: python not installed - run install_tool with name=python first"
        val out = shExec("${pyBin().absolutePath} -m pip $pipArgs", 300, pyEnv())
        return if (out.contains("Successfully installed") || out.contains("already satisfied") || pipArgs.startsWith("list")) out
        else out + "\n(if it failed: native libs without wheels cannot be installed - check pip list for what you have)"
    }

    private fun toolsDir(): java.io.File =
        java.io.File(context.filesDir, "tools").apply { mkdirs() }

    private fun installTool(args: JSONObject): String {
        val name = args.optString("name", "ffmpeg").lowercase().replace(Regex("[^a-z0-9._-]"), "")
        val url = args.optString("url")
        if (name == "python") return installPython()
        // generic: install ANY static binary from a url (nmap, curl, git...)
        if (url.isNotBlank()) {
            val exe = java.io.File(toolsDir(), name)
            return try {
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                if (!resp.isSuccessful) return "ERROR: HTTP ${resp.code} downloading $url"
                val bytes = resp.body?.bytes() ?: return "ERROR: empty body"
                exe.writeBytes(bytes)
                exe.setExecutable(true, false)
                Log.d(TAG, "install_binary: $name ${bytes.size} bytes from $url")
                // smoke test: does it at least execute?
                val pb = ProcessBuilder(exe.absolutePath, "--version").redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().take(200)
                proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                proc.destroy()
                val linkedFail = out.contains("CANNOT LINK") || out.contains("library")
                "INSTALLED: $name (${bytes.size / 1024}KB) at ${exe.absolutePath}" +
                    (if (linkedFail) "\nWARNING: first run output: $out\n(this may need an arm64 build instead of this device's ${android.os.Build.SUPPORTED_ABIS.firstOrNull()})" else "")
            } catch (e: Exception) {
                "ERROR: install $name failed - ${e.message}"
            }
        }
        if (name != "ffmpeg") {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            return "ERROR: '$name' is not bundled. You CAN install any static binary: install_tool name=$name url=<download url of a static $abi build> (search the web for it), chmod happens automatically. For python packages use the pip tool"
        }
        val exe = java.io.File(toolsDir(), "ffmpeg")
        if (exe.exists() && exe.canExecute()) {
            return "ALREADY: ffmpeg installed (${exe.length() / (1024 * 1024)}MB). Use it via the run_ffmpeg tool"
        }
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val flavor = when {
                abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
                abi.contains("x86_64") || abi.contains("amd64") -> "x64"
                else -> return "ERROR: device architecture '$abi' not supported (arm64 and x86_64 only)"
            }
            Log.d(TAG, "install_ffmpeg: abis=${android.os.Build.SUPPORTED_ABIS.joinToString()} asset=ffmpeg-$flavor")
            context.assets.open("ffmpeg/ffmpeg-$flavor").use { input ->
                exe.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            exe.setExecutable(true, false)
            if (!exe.canExecute()) {
                "ERROR: chmod failed"
            } else {
                val pb = ProcessBuilder(exe.absolutePath, "-version").redirectErrorStream(true)
                val proc = pb.start()
                val first = proc.inputStream.bufferedReader().readLine() ?: ""
                proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                proc.destroy()
                if (!first.contains("ffmpeg")) {
                    exe.delete()
                    "ERROR: bionic ffmpeg did not run: $first"
                } else {
                    "INSTALLED: $first - path: ${exe.absolutePath}\n" +
                        "usage: via the run_ffmpeg tool (without the binary name)"
                }
            }
        } catch (e: Exception) {
            exe.delete()
            "ERROR: ffmpeg install from APK failed - ${e.message}"
        }
    }
    private fun runFfmpeg(args: JSONObject): String {
        val exe = java.io.File(toolsDir(), "ffmpeg")
        if (!exe.exists()) return "ERROR: ffmpeg not installed. Run install_tool with name=ffmpeg first."
        val cmd = args.optString("cmd")
        if (cmd.isBlank()) return "ERROR: empty cmd. Example: -f lavfi -i testsrc=duration=3:size=640x360 -y out.mp4"
        return try {
            val pb = ProcessBuilder("sh", "-c", "${exe.absolutePath} $cmd")
                .directory(context.filesDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().takeLast(4000)
            if (!proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "ERROR: ffmpeg took over 3 minutes and was stopped"
            }
            val code = proc.exitValue()
            if (code == 0) {
                "FFMPEG_OK (exit=0)\n${out.takeLast(600)}"
            } else {
                "FFMPEG exit=$code\n$out"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun makePlan(args: JSONObject): String {
        // the model sometimes sends steps as a string (newline-separated or textual JSON)
        var steps = args.optJSONArray("steps")
        if (steps == null) {
            val raw = args.optString("steps")
            if (raw.isBlank()) return "ERROR: empty steps"
            steps = try {
                org.json.JSONArray(raw)
            } catch (e: Exception) {
                org.json.JSONArray(raw.split("\n").map { it.trim().removePrefix("-").removePrefix("•") }.filter { it.isNotBlank() })
            }
        }
        val sb = StringBuilder("📋 Execution plan:\n")
        for (i in 0 until steps.length()) sb.append("${i + 1}. ${steps.optString(i)}\n")
        sb.append("✅ plan recorded - now execute step 1")
        return sb.toString()
    }

    // ============ LOCAL HTTP SERVER ============

    private var httpSocket: java.net.ServerSocket? = null
    @Volatile private var httpRunning = false
    @Volatile private var httpPort = 0
    @Volatile private var httpRoot: String = ""

    private fun serveHttp(args: JSONObject): String {
        val dirArg = args.optString("dir", ".")
        val root = File(if (dirArg.startsWith("/")) dirArg else File(workDir(), dirArg).absolutePath)
        if (!root.isDirectory) return "ERROR: not a directory: $root"
        val port = args.optInt("port", 8080)
        stopHttp()
        return try {
            val ss = java.net.ServerSocket(port)
            httpSocket = ss
            httpPort = port
            httpRoot = root.absolutePath
            httpRunning = true
            Thread {
                while (httpRunning) {
                    val c = try { ss.accept() } catch (e: Exception) { break }
                    Thread {
                        try {
                            val inp = c.getInputStream()
                            val reader = inp.bufferedReader()
                            val reqLine = reader.readLine() ?: ""
                            var path = reqLine.split(" ").getOrElse(1) { "/" }
                            path = java.net.URLDecoder.decode(path, "UTF-8")
                            path = path.trimStart('/')
                            if (path.contains("..")) path = ""
                            if (path.isBlank() || path.endsWith("/")) path += "index.html"
                            var f = File(httpRoot, path)
                            if (f.isDirectory) f = File(f, "index.html")
                            val out = c.getOutputStream()
                            if (!f.isFile || !f.canRead()) {
                                val b = "404 Not Found: $path".toByteArray()
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(b)
                            } else {
                                val bytes = f.readBytes()
                                val mime = when (f.extension.lowercase()) {
                                    "html", "htm" -> "text/html; charset=utf-8"
                                    "css" -> "text/css; charset=utf-8"
                                    "js" -> "application/javascript; charset=utf-8"
                                    "json" -> "application/json"
                                    "png" -> "image/png"
                                    "jpg", "jpeg" -> "image/jpeg"
                                    "webp" -> "image/webp"
                                    "svg" -> "image/svg+xml"
                                    "ico" -> "image/x-icon"
                                    "woff", "woff2" -> "font/woff2"
                                    "ttf" -> "font/ttf"
                                    "mp3" -> "audio/mpeg"
                                    "mp4" -> "video/mp4"
                                    else -> "application/octet-stream"
                                }
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(bytes)
                            }
                            out.flush()
                        } catch (_: Exception) {
                        } finally {
                            try { c.close() } catch (_: Exception) {}
                        }
                    }.apply { isDaemon = true; start() }
                }
                try { ss.close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            "SERVER: http://127.0.0.1:$port - local server started from $root. Now call open_url so the user sees it in the browser. Added/changed files are served automatically"
        } catch (e: Exception) {
            "ERROR: server failed to start - ${e.message}"
        }
    }

    private fun stopHttp() {
        httpRunning = false
        try { httpSocket?.close() } catch (_: Exception) {}
        httpSocket = null
    }

    private fun serverStatus(): String {
        return if (httpRunning && httpSocket?.isBound == true) {
            "SERVER_UP: http://127.0.0.1:$httpPort from $httpRoot"
        } else {
            "SERVER_DOWN: no server running"
        }
    }

    // ============ BACKGROUND PROCESSES ============

    private class BgProc(val process: Process, val name: String, val startedAt: Long) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
    }

    private val bgProcs = java.util.concurrent.ConcurrentHashMap<Int, BgProc>()
    private val bgCounter = java.util.concurrent.atomic.AtomicInteger(1000)

    /** Run in the background - output is drained on separate threads so the pipe never blocks */
    private fun runBackground(args: JSONObject): String {
        val cmd = args.optString("cmd", "").trim()
        if (cmd.isBlank()) return "ERROR: empty cmd"
        val name = args.optString("name", cmd.take(30))
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd).directory(workDir())
            val proc = pb.start()
            val bg = BgProc(proc, name, System.currentTimeMillis())
            val jobId = bgCounter.incrementAndGet()
            bgProcs[jobId] = bg
            Thread {
                try { proc.inputStream.bufferedReader().forEachLine { bg.stdout.append(it).append('\n') } } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            Thread {
                try { proc.errorStream.bufferedReader().forEachLine { bg.stderr.append(it).append('\n') } } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            "BG_STARTED: pid=$jobId name=$name - background job started. Do other work and check it later with poll_process"
        } catch (e: Exception) {
            "ERROR: could not start - ${e.message}"
        }
    }

    private fun pollProcess(args: JSONObject): String {
        val pid = args.optInt("pid", -1)
        val bg = bgProcs[pid] ?: return "ERROR: process $pid not found (maybe already reported)"
        val alive = bg.process.isAlive
        val elapsed = (System.currentTimeMillis() - bg.startedAt) / 1000
        if (alive) {
            return "RUNNING: '${bg.name}' still running (${elapsed}s). poll again later"
        }
        val exit = bg.process.exitValue()
        val out = bg.stdout.takeLast(2000)
        val err = bg.stderr.takeLast(800)
        bgProcs.remove(pid)
        return "FINISHED: '${bg.name}' exit=$exit (${elapsed}s)\n--- STDOUT ---\n$out\n--- STDERR ---\n$err"
    }

    /** Vision tool - this only stages the file; the vision model call happens in ChatController */
    private fun visionAnalyze(args: JSONObject): String {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) return "ERROR: empty path"
        val f = File(path)
        if (!f.exists()) return "ERROR: not a file: $path"
        val kb = f.length() / 1024
        return "IMAGE_READY: $path (${kb}KB) - image sent to the vision model. See the answer in the next OBSERVATION"
    }

    // ============ WEB TOOLS ============

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** Fetch a web page as text - HTML is converted to plain text */
    private fun fetchUrl(args: JSONObject): String {
        var url = args.optString("url", "").trim()
        if (url.isBlank()) return "ERROR: empty url"
                if (!url.startsWith("http")) url = "https://$url"
                return try {
                    val req = Request.Builder().url(url)
                .header("User-Agent", browserUA)
                .header("Accept", "text/html,application/json,*/*")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val ctype = resp.header("Content-Type") ?: ""
                if (!resp.isSuccessful && body.isBlank())
                    return "ERROR: HTTP ${resp.code} for $url"
                val text = if (ctype.contains("html") || body.trimStart().startsWith("<")) {
                    htmlToText(body)
                } else body
                val trimmed = text.take(8000)
                val more = if (text.length > 8000) "\n... (truncated, total: ${text.length} chars)" else ""
                "HTTP ${resp.code} | $ctype | final url: ${resp.request.url}\n$trimmed$more"
            }
        } catch (e: java.net.UnknownHostException) {
            "ERROR: domain not found: $url - wrong address or filtered"
        } catch (e: java.net.SocketTimeoutException) {
            "ERROR: timeout - site did not respond (probably filtered or slow)"
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Web search via DuckDuckGo (no API key needed) */
    private fun searchWeb(args: JSONObject): String {
        val query = args.optString("query", "").trim()
        if (query.isBlank()) return "ERROR: empty query"
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        // try html.duckduckgo.com first, then lite
        for (endpoint in listOf(
            "https://html.duckduckgo.com/html/?q=$enc",
            "https://lite.duckduckgo.com/lite/?q=$enc"
        )) {
            try {
                val req = Request.Builder().url(endpoint)
                    .header("User-Agent", browserUA)
                    .header("Accept-Language", "en,fa")
                    .build()
                val html = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string() ?: ""
                }
                if (html.isBlank()) continue
                val results = parseSearchResults(html)
                if (results.isNotEmpty()) {
                    return "Search results for \"$query\":\n\n${results.take(8).joinToString("\n\n")}"
                }
            } catch (e: Exception) {
                // try the next endpoint
                Log.w(TAG, "search endpoint failed: $endpoint", e)
            }
        }
        return "ERROR: web search unavailable (filtered or offline). Do not guess URLs - tell the user."
    }

    /** Extract results from DuckDuckGo HTML */
    private fun parseSearchResults(html: String): List<String> {
        val out = mutableListOf<String>()
        // result links: both the result__a class (html) and lite links
        val linkRegex = Regex("<a[^>]*(?:result__a|result-link)[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("<(?:td|span|div)[^>]*class=\"[^\"]*result-snippet[^\"]*\"[^>]*>(.*?)</(?:td|span|div)>", RegexOption.DOT_MATCHES_ALL)
        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        var i = 0
        for (m in linkRegex.findAll(html)) {
            var href = m.groupValues[1]
            val title = cleanHtml(m.groupValues[2])
            if (title.isBlank()) continue
            // unwrap the uddg redirect
            val uddg = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
            if (uddg != null) href = java.net.URLDecoder.decode(uddg, "UTF-8")
            else if (href.startsWith("//")) href = "https:$href"
            val snippet = snippets.getOrNull(i)?.take(200) ?: ""
            out.add("${i + 1}. $title\n   $href${if (snippet.isNotBlank()) "\n   $snippet" else ""}")
            i++
            if (i >= 8) break
        }
        return out
    }

    private fun openUrl(args: JSONObject): String {
        var url = args.optString("url", "").trim()
        if (url.isBlank()) return "ERROR: empty url"
                if (!url.startsWith("http")) url = "https://$url"
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "opened in browser: $url"
        } catch (e: Exception) {
            "ERROR: browser failed to open - ${e.message}"
        }
    }

    /** HTML -> plain text */
    private fun htmlToText(html: String): String {
        var t = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        // decode common entities
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&laquo;", "«").replace("&raquo;", "»")
            .replace(Regex("&#x?[0-9a-fA-F]+;"), " ")
            .replace(Regex("&[a-z]+;"), " ")
        return t.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    private fun cleanHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .trim().replace(Regex("\\s+"), " ")

    private fun getInfo(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val r = shell.execute("df -h /data | tail -1")
        val storage = r.output.trim()
        val ver = shell.execute("getprop ro.build.version.release").output.trim()
        val sdk = shell.execute("getprop ro.build.version.sdk").output.trim()
        val net = shell.execute("ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1 && echo online || echo offline").output.trim()
        val root = shell.execute("su -c id 2>/dev/null | grep -q uid=0 && echo yes || echo no").output.trim()

        return """
            |Battery: $battery% ${if (charging) "(charging)" else ""}
            |Android: $ver (SDK $sdk)
            |Internet: $net
            |root: $root
            |Storage /data: $storage
            |Working dir: ${workDir().absolutePath}
            |Available tools: sh, toybox (sed/awk/grep/tar/ping/ss/netstat)
        """.trimMargin()
    }

    // ============ FILE / MEDIA TOOLS ============

    private fun inboxDir(): File = File(context.filesDir, "inbox").apply { mkdirs() }
    private fun editedDir(): File = File(context.filesDir, "edited").apply { mkdirs() }

    private fun listInbox(): String {
        val items = inboxDir().listFiles() ?: emptyArray()
        val edited = editedDir().listFiles() ?: emptyArray()
        if (items.isEmpty() && edited.isEmpty()) return "(no attached files)"
        return buildString {
            if (items.isNotEmpty()) {
                appendLine("=== user attachments ===")
                items.forEach { appendLine("- ${it.name} (${it.length()}B) path: ${it.absolutePath}") }
            }
            if (edited.isNotEmpty()) {
                appendLine("=== edited files ===")
                edited.forEach { appendLine("- ${it.name} (${it.length()}B) path: ${it.absolutePath}") }
            }
        }
    }

    private fun resolvePath(path: String): File =
        if (path.startsWith("/")) File(path) else File(context.filesDir, path)

    private fun fileInfo(args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) return "ERROR: empty path"
        val f = resolvePath(path)
        if (!f.exists()) return "ERROR: file '$path' not found. Locate it with list_inbox or list_dir_custom"
        val sb = StringBuilder()
        sb.appendLine("name: ${f.name}")
        sb.appendLine("path: ${f.absolutePath}")
        sb.appendLine("size: ${f.length()} bytes")
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        sb.appendLine("type: ${mime ?: "unknown"}")
        if (f.extension.lowercase() in listOf("png","jpg","jpeg","webp","gif","bmp")) {
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                sb.appendLine("image dimensions: ${opts.outWidth}x${opts.outHeight}")
            } catch (e: Exception) {}
        }
        return sb.toString()
    }

    private fun imageEdit(args: JSONObject): String {
        val path = args.optString("path", "")
        val action = args.optString("action", "")
        if (path.isBlank()) return "ERROR: empty path"
                val f = resolvePath(path)
                if (!f.exists()) return "ERROR: file not found: $path"
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                ?: return "ERROR: image cannot be decoded (format maybe unsupported)"

            var out: android.graphics.Bitmap = bmp
            var ext = f.extension.ifBlank { "png" }

            when (action) {
                "rotate" -> {
                    val angle = args.optInt("angle", 90).toFloat()
                    val m = android.graphics.Matrix().apply { postRotate(angle) }
                    out = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                "resize" -> {
                    val w = args.optInt("width", bmp.width / 2).coerceIn(1, 8192)
                    val h = args.optInt("height", bmp.height / 2).coerceIn(1, 8192)
                    out = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                }
                "convert" -> {
                    ext = args.optString("format", "png").lowercase().let {
                        if (it in listOf("png","jpg","jpeg","webp")) it.replace("jpeg","jpg") else "png"
                    }
                }
                "compress" -> {
                    // keep quality on the same format
                    ext = if (f.extension.lowercase() in listOf("jpg","jpeg")) "jpg" else if (f.extension.lowercase()=="webp") "webp" else "jpg"
                }
                "flip" -> {
                    val h = args.optString("direction", "horizontal") != "vertical"
                    android.graphics.Matrix().apply {
                        if (h) postScale(-1f, 1f) else postScale(1f, -1f)
                    }.let { m -> out = android.graphics.Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true) }
                }
                "crop" -> {
                    val x = args.optInt("x", 0).coerceIn(0, out.width - 1)
                    val y = args.optInt("y", 0).coerceIn(0, out.height - 1)
                    val w = args.optInt("width", out.width).coerceIn(1, out.width - x)
                    val h = args.optInt("height", out.height).coerceIn(1, out.height - y)
                    out = android.graphics.Bitmap.createBitmap(out, x, y, w, h)
                }
                "filter" -> {
                    val cm = android.graphics.ColorMatrix()
                    when (args.optString("value", args.optString("filter", "grayscale"))) {
                        "grayscale" -> cm.setSaturation(0f)
                        "sepia" -> {
                            cm.setSaturation(0f)
                            val sepia = android.graphics.ColorMatrix(
                                floatArrayOf(
                                    0.393f, 0.769f, 0.189f, 0f, 0f,
                                    0.349f, 0.686f, 0.168f, 0f, 0f,
                                    0.272f, 0.534f, 0.131f, 0f, 0f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                            cm.postConcat(sepia)
                        }
                        "invert" -> cm.set(floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f))
                        "warm" -> cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                            1.1f, 0f, 0f, 0f, 10f, 0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 0.9f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
                        "cold" -> cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                            0.9f, 0f, 0f, 0f, -10f, 0f, 1.0f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
                        else -> return "ERROR: unknown filter. allowed: grayscale, sepia, invert, warm, cold"
                    }
                    val b = android.graphics.Bitmap.createBitmap(out.width, out.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(b).apply {
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).let { p ->
                            android.graphics.ColorMatrixColorFilter(cm).let { p.colorFilter = it }
                            drawBitmap(out, 0f, 0f, p)
                        }
                    }
                    out = b
                }
                "brightness" -> {
                    val v = args.optInt("value", 0).coerceIn(-100, 100)
                    val b = android.graphics.Bitmap.createBitmap(out.width, out.height, android.graphics.Bitmap.Config.ARGB_8888)
                    val cm = android.graphics.ColorMatrix().apply {
                        setScale(1f + v / 100f, 1f + v / 100f, 1f + v / 100f, 1f)
                    }
                    android.graphics.Canvas(b).apply {
                        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        p.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                        drawBitmap(out, 0f, 0f, p)
                    }
                    out = b
                }
                "blur" -> {
                    // fast blur: downscale then upscale (a few times)
                    val times = (args.optInt("value", 25) / 8).coerceIn(1, 4)
                    var small = out
                    for (i in 0 until times) {
                        val s = android.graphics.Matrix().apply { setScale(0.5f, 0.5f) }
                        small = android.graphics.Bitmap.createBitmap(small, 0, 0, small.width, small.height, s, true)
                    }
                    val b = android.graphics.Bitmap.createBitmap(out.width, out.height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(b).apply {
                        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        drawBitmap(small, null, android.graphics.Rect(0, 0, out.width, out.height), p)
                    }
                    if (small != out) small.recycle()
                    out = b
                }
                else -> return "ERROR: unknown action '$action'. allowed: rotate, resize, convert, compress, crop, flip, filter, brightness, blur"
            }

            val outFile = File(editedDir(), "${System.currentTimeMillis()}_edited.$ext")
            val quality = args.optInt("quality", 85).coerceIn(1, 100)
            val fmt = when (ext) {
                "jpg" -> android.graphics.Bitmap.CompressFormat.JPEG
                "webp" -> android.graphics.Bitmap.CompressFormat.WEBP
                else -> android.graphics.Bitmap.CompressFormat.PNG
            }
            outFile.outputStream().use { out.compress(fmt, quality, it) }
            val dims = "${out.width}x${out.height}"
            if (out != bmp) out.recycle()
            bmp.recycle()
            return "edited ($action) -> ${outFile.absolutePath} (${outFile.length()}B, dims $dims)"
        } catch (e: Exception) {
            return "ERROR: edit failed - ${e.message}"
        }
    }

    private fun sendFile(args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) return "ERROR: empty path"
        val f = resolvePath(path)
        if (!f.exists()) return "ERROR: file not found: $path"
        return "FILE_ATTACHED:${f.absolutePath}"
    }

    private fun openFile(args: JSONObject): String {
        val path = args.optString("path", "")
        if (path.isBlank()) return "ERROR: empty path"
        val f = resolvePath(path)
        if (!f.exists()) return "ERROR: file not found: $path"
        return try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Thread.sleep(1000)
            "opened file: ${f.name}"
        } catch (e: Exception) {
            "ERROR: failed to open file - ${e.message}"
        }
    }

    private fun listDirCustom(args: JSONObject): String {
        val path = args.optString("path", "/sdcard")
        val f = File(path)
        if (!f.exists()) return "ERROR: path does not exist: $path"
        if (!f.isDirectory) return "ERROR: path is a file, not a directory"
        val items = f.listFiles()?.take(60) ?: emptyList()
        if (items.isEmpty()) return "(empty)"
        return items.joinToString("\n") {
            (if (it.isDirectory) "📁 " else "📄 ") + it.name + (if (it.isFile) " (${it.length()}B)" else "")
        }
    }

    private fun formatShellOutput(exitCode: Int, out: String, err: String, timedOut: Boolean): String {
        val sb = StringBuilder()
        if (timedOut) sb.appendLine("⚠️ TIMEOUT: command took too long and was killed")
        if (out.isNotBlank()) sb.appendLine(out.take(4000))
        if (err.isNotBlank()) sb.appendLine("--- STDERR ---\n${err.take(2000)}")
        val combined = out + "\n" + err
        val loaderFail = combined.contains("CANNOT LINK EXECUTABLE") ||
            (exitCode == 126 && combined.contains("No such file or directory"))
        if (loaderFail) sb.appendLine("[hint] this binary cannot run here: wrong libc or prefix. Android needs an Android (bionic) build - desktop/glibc archives (python.org, astral-sh, *-linux-x86_64*) never work. For python use install_tool name=python")
        sb.appendLine("--- exit code: ${exitCode} ---")
        if (exitCode != 0 && out.isBlank() && err.isBlank()) {
            sb.appendLine("command errored with no output. the command may not exist (try: which <cmd>)")
        }
        return sb.toString()
    }
}
