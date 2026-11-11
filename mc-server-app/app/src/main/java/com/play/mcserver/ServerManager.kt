package com.play.mcserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ServerState(val label: String) {
    STOPPED("● 꺼짐"),
    PREPARING("● 준비 중..."),
    RUNNING("● 실행 중"),
    STOPPING("● 끄는 중..."),
}

data class ServerConfig(
    val type: String,      // "paper" | "vanilla"
    val version: String,
    val ramMb: Int,
    val port: Int,
)

/** 서버 프로세스와 콘솔 로그를 관리하는 싱글톤. */
object ServerManager {
    private const val MAX_LINES = 1500

    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val stateListeners = mutableSetOf<(ServerState) -> Unit>()

    @Volatile var logVersion = 0L
        private set
    @Volatile var state = ServerState.STOPPED
        private set

    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null

    fun serverDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "server").apply { mkdirs() }

    fun log(msg: String) {
        synchronized(lines) {
            lines.addLast(msg)
            while (lines.size > MAX_LINES) lines.removeFirst()
            logVersion++
        }
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun addStateListener(l: (ServerState) -> Unit) = synchronized(stateListeners) { stateListeners += l }
    fun removeStateListener(l: (ServerState) -> Unit) = synchronized(stateListeners) { stateListeners -= l }

    private fun setState(s: ServerState) {
        state = s
        val ls = synchronized(stateListeners) { stateListeners.toList() }
        main.post { ls.forEach { it(s) } }
    }

    fun start(ctx: Context, cfg: ServerConfig) {
        if (state != ServerState.STOPPED) return
        setState(ServerState.PREPARING)
        val app = ctx.applicationContext
        Thread({
            try {
                val runtime = RuntimeInstaller.ensure(app) { log(it) }
                val dir = serverDir(app)
                val jar = Downloader.ensureServerJar(dir, cfg) { log(it) }
                prepareFiles(dir, cfg)
                launch(app, runtime, dir, jar, cfg)
            } catch (t: Throwable) {
                log("[앱] 오류: ${t.message ?: t.javaClass.simpleName}")
                setState(ServerState.STOPPED)
            }
        }, "mc-start").start()
    }

    private fun prepareFiles(dir: File, cfg: ServerConfig) {
        File(dir, "eula.txt").writeText("# 앱에서 사용자가 동의함\neula=true\n")
        val props = File(dir, "server.properties")
        if (props.exists()) {
            val updated = props.readLines().map {
                if (it.startsWith("server-port=")) "server-port=${cfg.port}" else it
            }.toMutableList()
            if (updated.none { it.startsWith("server-port=") }) updated += "server-port=${cfg.port}"
            props.writeText(updated.joinToString("\n") + "\n")
        } else {
            // 폰 성능을 고려한 기본값
            props.writeText(
                """
                server-port=${cfg.port}
                view-distance=6
                simulation-distance=5
                max-players=8
                motd=§a폰에서 돌아가는 마크 서버
                """.trimIndent() + "\n"
            )
        }
    }

    private fun launch(ctx: Context, runtime: File, dir: File, jar: File, cfg: ServerConfig) {
        val java = File(runtime, "jre/bin/java")
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        val jre = File(runtime, "jre")
        val libPath = listOf(
            File(runtime, "lib"), File(jre, "lib"), File(jre, "lib/server"),
        ).joinToString(":") { it.absolutePath }

        val cmd = listOf(
            java.absolutePath,
            "-Xms${minOf(512, cfg.ramMb)}M",
            "-Xmx${cfg.ramMb}M",
            "-XX:-UseContainerSupport",
            "-XX:+UseG1GC",
            "-Djava.io.tmpdir=${tmp.absolutePath}",
            "-Duser.home=${dir.absolutePath}",
            "-Djava.awt.headless=true",
            "-Dfile.encoding=UTF-8",
            "-jar", jar.name, "nogui",
        )
        log("[앱] 서버 시작: ${jar.name} (RAM ${cfg.ramMb}MB, 포트 ${cfg.port})")
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", libPath)
            put("JAVA_HOME", jre.absolutePath)
            put("HOME", dir.absolutePath)
            put("TMPDIR", tmp.absolutePath)
        }
        val p = pb.start()
        process = p
        stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        setState(ServerState.RUNNING)

        Thread({
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { log(it) }
            } catch (_: Exception) {
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            log("[앱] 서버가 종료됨 (코드 $code)")
            process = null
            stdin = null
            setState(ServerState.STOPPED)
        }, "mc-output").start()
    }

    fun sendCommand(command: String) {
        val w = stdin ?: return
        log("> $command")
        Thread {
            try {
                synchronized(w) { w.write(command + "\n"); w.flush() }
            } catch (e: Exception) {
                log("[앱] 명령 전송 실패: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        val p = process ?: return
        if (state == ServerState.STOPPING) return
        setState(ServerState.STOPPING)
        sendCommand("stop")
        Thread {
            if (!p.waitFor(40, TimeUnit.SECONDS)) {
                log("[앱] 응답이 없어 강제 종료합니다")
                p.destroyForcibly()
            }
        }.start()
    }

    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}

/** APK에 포함된 Java 런타임(runtime.zip)을 앱 내부 저장소에 풀어둔다. */
object RuntimeInstaller {
    fun ensure(ctx: Context, log: (String) -> Unit): File {
        val dir = File(ctx.filesDir, "runtime")
        val marker = File(dir, ".installed")
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == stamp && File(dir, "jre/bin/java").exists()) return dir

        log("[앱] Java 런타임 설치 중... (처음 한 번만, 1~2분)")
        dir.deleteRecursively()
        dir.mkdirs()
        val input = try {
            ctx.assets.open("runtime.zip")
        } catch (e: Exception) {
            throw IllegalStateException("APK에 Java 런타임이 없습니다. GitHub Actions로 빌드한 APK를 사용하세요.")
        }
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dir, e.name)
                if (!out.canonicalPath.startsWith(dir.canonicalPath)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        dir.walk().filter { it.isFile }.forEach { f ->
            val inBin = f.parentFile?.name == "bin"
            if (inBin || f.name == "jspawnhelper" || f.name.endsWith(".so") || f.name.contains(".so.")) {
                f.setExecutable(true, false)
            }
            f.setReadable(true, false)
        }
        marker.writeText(stamp)
        log("[앱] Java 런타임 설치 완료")
        return dir
    }
}
