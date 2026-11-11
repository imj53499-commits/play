package com.play.mcserver

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 마인크래프트 서버 jar 다운로드 (Mojang 공식 / PaperMC). */
object Downloader {
    private const val UA = "McServerAndroidApp/1.0 (personal phone server)"
    private const val MOJANG_MANIFEST =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location")).toString()
                c.disconnect()
            } else if (code >= 400) {
                c.disconnect()
                throw RuntimeException("HTTP $code: $current")
            } else {
                return c
            }
        }
        throw RuntimeException("리다이렉트가 너무 많음: $url")
    }

    private fun getJson(url: String): JSONObject =
        open(url).let { c -> c.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }

    fun latestRelease(): String = getJson(MOJANG_MANIFEST).getJSONObject("latest").getString("release")

    private fun download(url: String, dest: File, log: (String) -> Unit) {
        val c = open(url)
        val total = c.contentLengthLong
        val part = File(dest.parentFile, dest.name + ".part")
        var done = 0L
        var lastPct = -1L
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = done * 100 / total
                        if (pct / 10 != lastPct / 10) {
                            log("[앱] 다운로드 $pct%")
                            lastPct = pct
                        }
                    }
                }
            }
        }
        if (!part.renameTo(dest)) throw RuntimeException("파일 저장 실패: $dest")
    }

    fun ensureServerJar(dir: File, cfg: ServerConfig, log: (String) -> Unit): File {
        val version = cfg.version.ifBlank { latestRelease() }
        val jar = File(dir, "${cfg.type}-$version.jar")
        if (jar.exists() && jar.length() > 0) return jar

        log("[앱] ${if (cfg.type == "paper") "Paper" else "바닐라"} $version 서버 파일 받는 중...")
        val url = if (cfg.type == "paper") paperUrl(version) else vanillaUrl(version)
        download(url, jar, log)
        log("[앱] 다운로드 완료: ${jar.name}")
        return jar
    }

    private fun vanillaUrl(version: String): String {
        val versions = getJson(MOJANG_MANIFEST).getJSONArray("versions")
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.getString("id") == version) {
                return getJson(v.getString("url"))
                    .getJSONObject("downloads").getJSONObject("server").getString("url")
            }
        }
        throw RuntimeException("버전 '$version' 을 찾을 수 없습니다")
    }

    private fun paperUrl(version: String): String {
        // 새 API (fill v3)
        try {
            val build = getJson("https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest")
            return build.getJSONObject("downloads").getJSONObject("server:default").getString("url")
        } catch (_: Exception) {
        }
        // 예전 API (v2)
        try {
            val base = "https://api.papermc.io/v2/projects/paper/versions/$version"
            val builds = getJson("$base/builds").getJSONArray("builds")
            val last = builds.getJSONObject(builds.length() - 1)
            val name = last.getJSONObject("downloads").getJSONObject("application").getString("name")
            return "$base/builds/${last.getInt("build")}/downloads/$name"
        } catch (e: Exception) {
            throw RuntimeException("Paper $version 을 찾을 수 없습니다 (Paper가 아직 지원하지 않는 버전일 수 있어요. 버전을 낮추거나 바닐라를 선택하세요)")
        }
    }
}
