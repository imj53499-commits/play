package com.play.mcserver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 공유기 자동 포트 열기 (UPnP IGD).
 * 집 공유기에 "25565 포트로 오는 연결은 이 폰으로 보내줘" 라고 요청해서
 * 친구가 중계 서버 없이 `공인IP:포트` 로 바로 접속할 수 있게 한다.
 */
class Upnp(
    private val localIp: String,
    private val ssdpAddress: String = "239.255.255.250",
    private val ssdpPort: Int = 1900,
) {
    data class Gateway(val controlUrl: String, val serviceType: String)

    private var gateway: Gateway? = null

    /** 공유기를 찾는다. 못 찾으면 null */
    fun discover(timeoutMs: Int = 3000): Gateway? {
        val targets = listOf(
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1",
        )
        val locations = linkedSetOf<String>()
        DatagramSocket(InetSocketAddress(InetAddress.getByName(localIp), 0)).use { sock ->
            sock.soTimeout = 500
            val dest = InetSocketAddress(InetAddress.getByName(ssdpAddress), ssdpPort)
            repeat(2) {
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val data = msg.toByteArray()
                    sock.send(DatagramPacket(data, data.size, dest))
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { locations += it }
                } catch (_: SocketTimeoutException) {
                    if (locations.isNotEmpty()) break
                }
            }
        }
        for (loc in locations) {
            try {
                val gw = parseDescription(loc, httpGet(loc)) ?: continue
                gateway = gw
                return gw
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun externalIp(): String? = try {
        val r = soap("GetExternalIPAddress", "")
        tag(r, "NewExternalIPAddress")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** 포트 열기. 성공하면 true */
    fun addPortMapping(port: Int, description: String): Boolean {
        for (lease in listOf(0, 7200)) { // 일부 공유기는 무기한(0)을 거부함
            try {
                soap(
                    "AddPortMapping",
                    "<NewRemoteHost></NewRemoteHost>" +
                        "<NewExternalPort>$port</NewExternalPort>" +
                        "<NewProtocol>TCP</NewProtocol>" +
                        "<NewInternalPort>$port</NewInternalPort>" +
                        "<NewInternalClient>$localIp</NewInternalClient>" +
                        "<NewEnabled>1</NewEnabled>" +
                        "<NewPortMappingDescription>$description</NewPortMappingDescription>" +
                        "<NewLeaseDuration>$lease</NewLeaseDuration>"
                )
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun deletePortMapping(port: Int) {
        try {
            soap(
                "DeletePortMapping",
                "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>"
            )
        } catch (_: Exception) {
        }
    }

    private fun soap(action: String, args: String): String {
        val gw = gateway ?: throw IllegalStateException("공유기를 찾지 못함")
        val body = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${gw.serviceType}\">$args</u:$action>" +
            "</s:Body></s:Envelope>"
        val c = URL(gw.controlUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPAction", "\"${gw.serviceType}#$action\"")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        c.disconnect()
        if (code >= 400) throw RuntimeException("UPnP $action 실패 ($code): ${tag(text, "errorDescription") ?: ""}")
        return text
    }

    companion object {
        internal fun httpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            return c.inputStream.use { String(it.readBytes()) }.also { c.disconnect() }
        }

        internal fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name[^>]*>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

        /** 장치 설명 XML 에서 WANIPConnection / WANPPPConnection 의 controlURL 을 찾는다 */
        internal fun parseDescription(location: String, xml: String): Gateway? {
            val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
            val services = Regex("(?s)<(?:\\w+:)?service>(.*?)</(?:\\w+:)?service>").findAll(xml).map { it.groupValues[1] }
            for (wanted in listOf("WANIPConnection", "WANPPPConnection")) {
                for (svc in services) {
                    val type = tag(svc, "serviceType") ?: continue
                    if (!type.contains(wanted)) continue
                    val control = tag(svc, "controlURL") ?: continue
                    return Gateway(URL(URL(base), control).toString(), type)
                }
            }
            return null
        }

        /** 공유기가 받은 IP가 사설/통신사 공유 IP면 외부에서 직접 접속 불가 (이중 공유기 등) */
        fun isPrivate(ip: String): Boolean {
            val p = ip.split(".").mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return true
            return p[0] == 10 || p[0] == 127 ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 100 && p[1] in 64..127) ||
                (p[0] == 169 && p[1] == 254)
        }
    }
}
