package com.example.go2rtcplay

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.go2rtcplay.data.ConfigRepository
import com.example.go2rtcplay.data.ServerAddress
import com.example.go2rtcplay.discovery.LanScanner
import kotlinx.coroutines.*
import java.net.URI

class ConfigActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private lateinit var addressList: RecyclerView
    private lateinit var scanBtn: Button
    private lateinit var addBtn: Button
    private lateinit var confirmBtn: Button
    private lateinit var scanStatusText: TextView
    private lateinit var scanProgressBar: ProgressBar
    private lateinit var localIpText: TextView
    private lateinit var cbAuto: CheckBox
    private lateinit var cbWebrtc: CheckBox
    private lateinit var cbMse: CheckBox
    private lateinit var cbHls: CheckBox
    private lateinit var cbMjpeg: CheckBox
    private lateinit var refreshIntervalInput: EditText

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adapter: ServerAdapter? = null
    private var scanJob: Job? = null
    private var updatingProtocolChecks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        configRepo = ConfigRepository(this)
        addressList = findViewById(R.id.addressList)
        scanBtn = findViewById(R.id.scanBtn)
        addBtn = findViewById(R.id.addBtn)
        confirmBtn = findViewById(R.id.confirmBtn)
        scanStatusText = findViewById(R.id.scanStatusText)
        scanProgressBar = findViewById(R.id.scanProgressBar)
        localIpText = findViewById(R.id.localIpText)
        cbAuto = findViewById(R.id.cbAuto)
        cbWebrtc = findViewById(R.id.cbWebrtc)
        cbMse = findViewById(R.id.cbMse)
        cbHls = findViewById(R.id.cbHls)
        cbMjpeg = findViewById(R.id.cbMjpeg)
        refreshIntervalInput = findViewById(R.id.refreshIntervalInput)

        addressList.layoutManager = LinearLayoutManager(this)
        refreshList()
        showLocalIp()
        setupProtocolCheckboxes()
        setupRefreshInterval()

        scanBtn.setOnClickListener { startScan() }
        addBtn.setOnClickListener { showAddDialog() }
        confirmBtn.setOnClickListener {
            val intervalText = refreshIntervalInput.text.toString().trim()
            val intervalSec = intervalText.toIntOrNull()
            if (intervalSec == null || intervalSec !in 1..60) {
                Toast.makeText(this, getString(R.string.invalid_refresh_interval), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            configRepo.setRefreshInterval(intervalSec * 1000)

            if (configRepo.getActiveServer() != null) {
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.no_server_selected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshList() {
        val servers = configRepo.getServers()
        adapter = ServerAdapter(servers,
            onSelect = { server ->
                configRepo.setActiveServer(server)
                refreshList()
            },
            onDelete = { server ->
                configRepo.removeServer(server)
                refreshList()
            }
        )
        addressList.adapter = adapter
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        scanBtn.isEnabled = false
        scanBtn.text = getString(R.string.scanning)
        scanProgressBar.visibility = android.view.View.VISIBLE
        scanStatusText.visibility = android.view.View.VISIBLE
        scanProgressBar.progress = 0

        val scanner = LanScanner()
        val subnets = scanner.getLocalSubnets()
        val total = subnets.size * 254
        scanStatusText.text = getString(R.string.subnet_info, subnets.joinToString(", "), total)

        scanJob = scope.launch {
            val found = scanner.scan(onProgress = { scanned, total, ip ->
                launch(Dispatchers.Main) {
                    scanProgressBar.max = total
                    scanProgressBar.progress = scanned
                    scanStatusText.text = getString(R.string.scan_progress, scanned, total, ip)
                }
            })

            found.forEach { configRepo.addServer(it) }
            refreshList()

            scanBtn.isEnabled = true
            scanBtn.text = getString(R.string.scan_done)
            scanProgressBar.visibility = android.view.View.GONE
            scanStatusText.visibility = android.view.View.GONE

            if (found.isEmpty()) {
                Toast.makeText(this@ConfigActivity, getString(R.string.no_server_found, subnets.joinToString(", ")), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ConfigActivity, getString(R.string.found_servers, found.size), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLocalIp() {
        try {
            val ips = LanScanner().getLocalIps()
            localIpText.text = if (ips.isNotEmpty()) {
                getString(R.string.local_ip, ips.joinToString(", "))
            } else {
                getString(R.string.local_ip_unavailable)
            }
        } catch (e: Exception) {
            localIpText.text = getString(R.string.local_ip_failed)
        }
    }

    private fun setupProtocolCheckboxes() {
        val selected = configRepo.getPreferredProtocol()

        updatingProtocolChecks = true
        if (selected.isEmpty()) {
            cbAuto.isChecked = true
        } else {
            val parts = selected.split(",")
            cbAuto.isChecked = false
            cbWebrtc.isChecked = parts.contains("webrtc")
            cbMse.isChecked = parts.contains("mse")
            cbHls.isChecked = parts.contains("hls")
            cbMjpeg.isChecked = parts.contains("mjpeg")
        }
        updatingProtocolChecks = false

        val listener = android.widget.CompoundButton.OnCheckedChangeListener { button, isChecked ->
            if (updatingProtocolChecks) return@OnCheckedChangeListener
            if (button == cbAuto && isChecked) {
                updatePreferredProtocol()
                return@OnCheckedChangeListener
            }
            if (button != cbAuto && isChecked) {
                updatingProtocolChecks = true
                cbAuto.isChecked = false
                updatingProtocolChecks = false
            }
            updatePreferredProtocol()
        }
        listOf(cbAuto, cbWebrtc, cbMse, cbHls, cbMjpeg).forEach { it.setOnCheckedChangeListener(listener) }
    }

    private fun updatePreferredProtocol() {
        updatingProtocolChecks = true
        if (cbAuto.isChecked) {
            cbWebrtc.isChecked = false
            cbMse.isChecked = false
            cbHls.isChecked = false
            cbMjpeg.isChecked = false
            configRepo.setPreferredProtocol("")
        } else {
            val p = mutableListOf<String>()
            if (cbWebrtc.isChecked) p.add("webrtc")
            if (cbMse.isChecked) p.add("mse")
            if (cbHls.isChecked) p.add("hls")
            if (cbMjpeg.isChecked) p.add("mjpeg")
            if (p.isEmpty()) {
                cbAuto.isChecked = true
                configRepo.setPreferredProtocol("")
            } else {
                configRepo.setPreferredProtocol(p.joinToString(","))
            }
        }
        updatingProtocolChecks = false
    }

    private fun setupRefreshInterval() {
        val ms = configRepo.getRefreshInterval()
        refreshIntervalInput.setText((ms / 1000).toString())
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_address)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        input.setTextColor(android.graphics.Color.BLACK)
        input.setHintTextColor(android.graphics.Color.GRAY)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(getString(R.string.add_server_title))
            .setView(input)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val server = parseServerAddress(text)
                    if (server == null) {
                        Toast.makeText(this, getString(R.string.invalid_address), Toast.LENGTH_SHORT).show()
                    } else {
                        configRepo.addServer(server)
                        configRepo.setActiveServer(server)
                        refreshList()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun parseServerAddress(text: String): ServerAddress? {
        val normalized = text.trim().trimEnd('/')
        if (normalized.isEmpty()) return null
        return try {
            val uri = URI(if (normalized.contains("://")) normalized else "http://$normalized")
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 1984
            ServerAddress(host, port)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
