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
    val tunnel: Boolean,
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
    @Volatile private var tunnel: BoreTunnel? = null
    @Volatile private var upnp: Upnp? = null
    @Volatile private var upnpPort = 0

    /** 공유기 포트가 열렸을 때 중계 없이 직접 접속하는 주소 (핑 낮음). 없으면 null */
    @Volatile var directAddress: String? = null
        private set

    /** 외부(다른 IP)에서 접속할 수 있는 터널 주소. 없으면 null */
    @Volatile var publicAddress: String? = null
        private set
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
        // 출력 스레드보다 먼저 터널을 열어야, 서버가 바로 꺼져도 터널이 같이 정리됨
        if (cfg.tunnel) {
            startTunnel(ctx, cfg.port)
            Thread({ openRouterPort(cfg.port) }, "upnp").start()
        }

        Thread({
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine {
                    log(it)
                    if (it.contains("requires running the server with Java")) {
                        log("[앱] ⚠ 이 마크 버전은 앱에 들어있는 Java보다 새 Java가 필요해요. 버전을 낮추거나 앱을 업데이트하세요.")
                    }
                }
            } catch (_: Exception) {
            }
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            log("[앱] 서버가 종료됨 (코드 $code)")
            tunnel?.stop()
            tunnel = null
            closeRouterPort()
            process = null
            stdin = null
            setState(ServerState.STOPPED)
        }, "mc-output").start()
    }

    private fun openRouterPort(port: Int) {
        val ip = localAddresses().firstOrNull { Upnp.isPrivate(it) } ?: return // 와이파이 아님
        try {
            log("[공유기] 포트 자동 열기 시도 중... (UPnP)")
            val u = Upnp(ip)
            if (u.discover() == null) {
                log("[공유기] UPnP 공유기를 못 찾았어요. 공유기 설정에서 UPnP를 켜거나 직접 포트포워딩하세요. (중계 주소는 계속 사용 가능)")
                return
            }
            if (!u.addPortMapping(port, "MC Server (phone)")) {
                log("[공유기] 포트 열기 실패. 공유기 설정에서 UPnP가 꺼져 있을 수 있어요.")
                return
            }
            if (state != ServerState.RUNNING) { u.deletePortMapping(port); return } // 그새 서버가 꺼짐
            upnp = u
            upnpPort = port
            val ext = u.externalIp()
            when {
                ext == null -> log("[공유기] 포트는 열었지만 공인 IP를 알 수 없어요.")
                Upnp.isPrivate(ext) -> log("[공유기] 포트는 열었지만 공유기가 또 다른 공유기 뒤에 있어요(이중 공유기, $ext). 직접 접속은 안 될 수 있어요.")
                state != ServerState.RUNNING -> {}
                else -> {
                    directAddress = "$ext:$port"
                    log("[공유기] 직접 접속 주소(핑 낮음): $ext:$port")
                    setState(state)
                }
            }
        } catch (e: Exception) {
            log("[공유기] 오류: ${e.message}")
        }
    }

    private fun closeRouterPort() {
        directAddress = null
        val u = upnp ?: return
        val port = upnpPort
        upnp = null
        Thread { u.deletePortMapping(port) }.start()
    }

    private fun startTunnel(ctx: Context, port: Int) {
        val prefs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        log("[터널] 외부 접속용 터널 여는 중... (${BoreTunnel.DEFAULT_HOST})")
        tunnel = BoreTunnel(
            host = BoreTunnel.DEFAULT_HOST,
            targetPort = port,
            preferredPort = prefs.getInt("tunnelPort", 0),
            log = { log(it) },
            onAddress = { addr ->
                publicAddress = if (state == ServerState.RUNNING) addr else null
                addr?.substringAfterLast(":")?.toIntOrNull()?.let {
                    prefs.edit().putInt("tunnelPort", it).apply()
                }
                setState(state) // 화면 갱신
            },
        ).also { it.start() }
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
            // 와이파이(wlan), 핫스팟(ap/swlan), USB 테더링(rndis), 유선(eth)만.
            // rmnet/ccmni/clat 같은 모바일 데이터 인터페이스는 외부에서 접속 불가라 제외
            .filter { ni -> listOf("wlan", "swlan", "ap", "rndis", "eth").any { ni.name.startsWith(it) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() && !it.startsWith("192.0.0.") }
            .distinct()
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
