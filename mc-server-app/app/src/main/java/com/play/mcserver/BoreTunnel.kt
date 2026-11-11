package com.play.mcserver

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * bore (https://github.com/ekzhang/bore) 프로토콜 클라이언트.
 * 공유기 설정 없이 `bore.pub:포트` 같은 공개 주소로 내 서버를 열어준다.
 *
 * 프로토콜: 제어 연결(7835)에 null 문자로 구분된 JSON 메시지를 주고받음.
 *   -> {"Hello":원하는포트}   <- {"Hello":배정된포트}
 *   <- {"Connection":"uuid"} 이 오면 새 연결을 열어 {"Accept":"uuid"} 를 보낸 뒤
 *      그 연결과 로컬 마크 서버를 양방향으로 이어준다.
 *   <- "Heartbeat" 는 무시.
 */
class BoreTunnel(
    private val host: String,
    private val targetPort: Int,
    private val preferredPort: Int,
    private val log: (String) -> Unit,
    private val onAddress: (String?) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var control: Socket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "bore-control").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        try { control?.close() } catch (_: Exception) {}
        onAddress(null)
    }

    private fun loop() {
        var wanted = preferredPort
        while (running.get()) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, CONTROL_PORT), 10_000)
                sock.tcpNoDelay = true
                control = sock
                val input = sock.getInputStream()
                val out = sock.getOutputStream()

                send(out, """{"Hello":$wanted}""")
                val hello = recv(input) ?: throw RuntimeException("서버가 연결을 끊음")
                val err = stringField(hello, "Error")
                if (err != null) {
                    if (wanted != 0) {
                        // 원하던 포트가 이미 사용 중 → 아무 포트나 받기
                        wanted = 0
                        sock.close()
                        continue
                    }
                    throw RuntimeException(err)
                }
                val port = numberField(hello, "Hello") ?: throw RuntimeException("알 수 없는 응답: $hello")
                wanted = port
                val address = "$host:$port"
                log("[터널] 외부 접속 주소: $address")
                onAddress(address)

                while (running.get()) {
                    val msg = recv(input) ?: break
                    val id = stringField(msg, "Connection")
                    when {
                        id != null -> Thread({ accept(id) }, "bore-conn").apply { isDaemon = true; start() }
                        msg.contains("Error") -> log("[터널] 오류: $msg")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("[터널] 연결 문제: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { control?.close() } catch (_: Exception) {}
                control = null
            }
            if (running.get()) {
                onAddress(null)
                log("[터널] 5초 후 다시 연결합니다...")
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun accept(id: String) {
        var remote: Socket? = null
        var local: Socket? = null
        try {
            remote = Socket().apply { connect(InetSocketAddress(host, CONTROL_PORT), 10_000); tcpNoDelay = true }
            send(remote.getOutputStream(), """{"Accept":"$id"}""")
            local = Socket().apply { connect(InetSocketAddress("127.0.0.1", targetPort), 5_000); tcpNoDelay = true }
            val r = remote
            val l = local
            val t = Thread({ pipe(r.getInputStream(), l.getOutputStream(), r, l) }, "bore-pipe").apply { isDaemon = true; start() }
            pipe(l.getInputStream(), r.getOutputStream(), r, l)
            t.join()
        } catch (_: Exception) {
            try { remote?.close() } catch (_: Exception) {}
            try { local?.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { a.close() } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val CONTROL_PORT = 7835
        const val DEFAULT_HOST = "bore.pub"

        internal fun send(out: OutputStream, json: String) {
            out.write(json.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.flush()
        }

        internal fun recv(input: InputStream): String? {
            val sb = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == 0) return sb.toString("UTF-8")
                if (sb.size() > 4096) throw RuntimeException("메시지가 너무 김")
                sb.write(b)
            }
        }

        internal fun numberField(json: String, key: String): Int? =
            Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

        internal fun stringField(json: String, key: String): String? =
            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
    }
}
