package com.play.mcserver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var shownLogVersion = -1L

    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var settings: View
    private lateinit var type: RadioGroup
    private lateinit var version: EditText
    private lateinit var ram: EditText
    private lateinit var port: EditText
    private lateinit var eula: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var command: EditText

    private val stateListener: (ServerState) -> Unit = { render(it) }

    private val refresher = object : Runnable {
        override fun run() {
            if (ServerManager.logVersion != shownLogVersion) {
                shownLogVersion = ServerManager.logVersion
                console.text = ServerManager.logText()
                consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
            }
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        address = findViewById(R.id.address)
        settings = findViewById(R.id.settings)
        type = findViewById(R.id.type)
        version = findViewById(R.id.version)
        ram = findViewById(R.id.ram)
        port = findViewById(R.id.port)
        eula = findViewById(R.id.eula)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        console = findViewById(R.id.console)
        consoleScroll = findViewById(R.id.consoleScroll)
        command = findViewById(R.id.command)

        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        type.check(if (prefs.getString("type", "paper") == "vanilla") R.id.typeVanilla else R.id.typePaper)
        version.setText(prefs.getString("version", "1.21.1"))
        ram.setText(prefs.getInt("ram", defaultRam()).toString())
        port.setText(prefs.getInt("port", 25565).toString())
        eula.isChecked = prefs.getBoolean("eula", false)

        findViewById<Button>(R.id.latest).setOnClickListener {
            Thread {
                try {
                    val v = Downloader.latestRelease()
                    main.post { version.setText(v) }
                } catch (e: Exception) {
                    main.post { toast("최신 버전 확인 실패: ${e.message}") }
                }
            }.start()
        }

        start.setOnClickListener { onStartClicked() }
        stop.setOnClickListener { ServerManager.stop() }
        findViewById<Button>(R.id.send).setOnClickListener { sendCommand() }
        command.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendCommand(); true } else false
        }

        if (ServerManager.logVersion == 0L) {
            ServerManager.log("[앱] 서버 파일 위치: ${ServerManager.serverDir(this).absolutePath}")
        }
    }

    override fun onResume() {
        super.onResume()
        ServerManager.addStateListener(stateListener)
        render(ServerManager.state)
        main.post(refresher)
    }

    override fun onPause() {
        ServerManager.removeStateListener(stateListener)
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun defaultRam(): Int {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = (info.totalMem / 1024 / 1024).toInt()
        return (totalMb / 4).coerceIn(768, 3072) / 128 * 128
    }

    private fun onStartClicked() {
        if (!eula.isChecked) { toast("먼저 EULA 동의에 체크해 주세요"); return }
        val ramMb = ram.text.toString().toIntOrNull()
        val portNum = port.text.toString().toIntOrNull()
        if (ramMb == null || ramMb < 512) { toast("RAM은 512MB 이상으로 입력해 주세요"); return }
        if (portNum == null || portNum !in 1024..65535) { toast("포트는 1024~65535 사이로 입력해 주세요"); return }

        val cfg = ServerConfig(
            type = if (type.checkedRadioButtonId == R.id.typeVanilla) "vanilla" else "paper",
            version = version.text.toString().trim(),
            ramMb = ramMb,
            port = portNum,
        )
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("type", cfg.type).putString("version", cfg.version)
            .putInt("ram", cfg.ramMb).putInt("port", cfg.port)
            .putBoolean("eula", true).apply()

        ServerManager.start(this, cfg)
        startForegroundService(Intent(this, ServerService::class.java))
    }

    private fun sendCommand() {
        val c = command.text.toString().trim().removePrefix("/")
        if (c.isEmpty()) return
        if (ServerManager.state != ServerState.RUNNING) { toast("서버가 실행 중이 아닙니다"); return }
        ServerManager.sendCommand(c)
        command.setText("")
    }

    private fun render(s: ServerState) {
        status.text = s.label
        status.setTextColor(
            when (s) {
                ServerState.RUNNING -> 0xFF62E6A2.toInt()
                ServerState.STOPPED -> 0xFFFF667D.toInt()
                else -> 0xFFFFC857.toInt()
            }
        )
        val p = port.text.toString().ifBlank { "25565" }
        val ips = ServerManager.localAddresses()
        address.text = if (ips.isEmpty()) "와이파이에 연결하면 접속 주소가 표시됩니다"
        else "같은 와이파이에서 접속: " + ips.joinToString("  ") { "$it:$p" }

        val idle = s == ServerState.STOPPED
        settings.isEnabled = idle
        listOf(version, ram, port, eula).forEach { it.isEnabled = idle }
        for (i in 0 until type.childCount) type.getChildAt(i).isEnabled = idle
        start.isEnabled = idle
        stop.isEnabled = s == ServerState.RUNNING
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
