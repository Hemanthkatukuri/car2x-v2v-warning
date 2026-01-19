package com.example.car2xapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

enum class Mode { VEHICLE, RSU }

data class VehStats(
    val vehId: String,
    var label: String = "",

    // network stats
    var lastSeq: Long = -1,
    var rx: Int = 0,
    var lost: Int = 0,
    var latencySumMs: Double = 0.0,
    var latencyCount: Int = 0,
    var lastLatencyMs: Double? = null,

    // latest CAM state
    var lastLat: Double? = null,
    var lastLon: Double? = null,
    var lastSpeedKmh: Double = 0.0,
    var lastAccM: Double = 0.0,
    var lastUpdateRxTimeMs: Long = 0L,

    // V2V warning result (computed from nearest other vehicle)
    var nearestVehLabel: String? = null,
    var nearestDistM: Double? = null,
    var lastWarning: String = "UNKNOWN"
) {
    fun pdr(): Double {
        val total = rx + lost
        return if (total > 0) rx.toDouble() / total else 1.0
    }

    fun hasPosition(): Boolean = lastLat != null && lastLon != null
}

class MainActivity : ComponentActivity(), LocationListener {

    // ---------- constants ----------
    private val UDP_PORT = 5000
    private val SUMMARY_EVERY = 50

    // ---------- Android services ----------
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- background jobs ----------
    private var runningJob: Job? = null
    private var rsuSocket: DatagramSocket? = null

    // ---------- Vehicle state ----------
    private var camSeq: Long = 0
    private lateinit var vehicleId: String

    // ---------- RSU per-vehicle stats ----------
    private val statsByVeh = linkedMapOf<String, VehStats>() // preserves first-seen order

    // Map real vehicle_id -> "Vehicle-1", "Vehicle-2", ...
    private val vehLabelById = linkedMapOf<String, String>()
    private var nextVehIndex = 1

    // ---------- Logging / Storage ----------
    private var logDir: File? = null
    private var csvFile: File? = null
    private var txtFile: File? = null
    private var csvWriter: FileWriter? = null
    private var txtWriter: FileWriter? = null

    // ---------- Compose state ----------
    private var gpsLat by mutableStateOf(0.0)
    private var gpsLon by mutableStateOf(0.0)
    private var gpsSpeedKmh by mutableStateOf(0f)
    private var gpsHeadingDeg by mutableStateOf(0f)
    private var gpsAccM by mutableStateOf(0f)
    private var gpsTimeMs by mutableStateOf(0L)

    private var rsuStatus by mutableStateOf("RSU: Idle")
    private var rsuWarningLevel by mutableStateOf("UNKNOWN") // overall: worst among vehicles
    private var rsuSummaryText by mutableStateOf("Summary: -")
    private var rsuCsvPath by mutableStateOf("Log folder: -")

    // UI list (one line per vehicle)
    private var rsuVehicleLines by mutableStateOf(listOf<String>())

    // ---------- permission ----------
    private val requestFineLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLocationUpdates()
        else rsuStatus = "Location permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        vehicleId = buildVehicleId()

        requestLocationPermission()

        setContent {
            MaterialTheme {
                AppUi(
                    gpsLat = gpsLat,
                    gpsLon = gpsLon,
                    gpsSpeedKmh = gpsSpeedKmh,
                    gpsHeadingDeg = gpsHeadingDeg,
                    gpsAccM = gpsAccM,
                    rsuStatus = rsuStatus,
                    rsuSummaryText = rsuSummaryText,
                    rsuCsvPath = rsuCsvPath,
                    rsuWarningLevel = rsuWarningLevel,
                    rsuVehicleLines = rsuVehicleLines,
                    onStartVehicle = { ip, intervalMs ->
                        startLocationUpdates()
                        startCamSending(ip, intervalMs)
                    },
                    onStartRsu = {
                        startLocationUpdates()
                        startRsuReceiver()
                    },
                    onStop = {
                        stopNetworkingOnly()
                    }
                )
            }
        }
    }

    // ---------------- permissions ----------------

    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startLocationUpdates()
        else requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // ---------------- location ----------------

    private fun startLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            val gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)

            val netEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)

            runCatching { locationManager.removeUpdates(this) }

            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this
                )
            }

            if (netEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this
                )
            }

            if (!gpsEnabled && !netEnabled) {
                rsuStatus = "Location providers OFF (enable GPS)"
            }
        } catch (e: SecurityException) {
            rsuStatus = "Location SecurityException: ${e.message}"
        } catch (e: Exception) {
            rsuStatus = "Location error: ${e.message}"
        }
    }

    override fun onLocationChanged(location: Location) {
        mainHandler.post {
            gpsLat = location.latitude
            gpsLon = location.longitude
            gpsSpeedKmh = (location.speed * 3.6f)
            gpsHeadingDeg = location.bearing
            gpsAccM = location.accuracy
            gpsTimeMs = location.time
        }
    }

    // ---------------- Vehicle: CAM build + send ----------------

    private fun startCamSending(rsuIp: String, intervalMs: Long) {
        stopNetworkingOnly()
        rsuStatus = "Vehicle: sending to $rsuIp:$UDP_PORT every ${intervalMs}ms"

        runningJob = CoroutineScope(Dispatchers.IO).launch {
            var sock: DatagramSocket? = null
            try {
                val address = InetAddress.getByName(rsuIp)
                sock = DatagramSocket() // reuse
                while (isActive) {
                    try {
                        val cam = buildCamJson(vehicleId = vehicleId)
                        val data = cam.toString().toByteArray(Charsets.UTF_8)
                        val packet = DatagramPacket(data, data.size, address, UDP_PORT)
                        sock.send(packet)
                    } catch (_: Exception) {
                    }
                    delay(intervalMs)
                }
            } finally {
                runCatching { sock?.close() }
            }
        }
    }

    private fun buildCamJson(vehicleId: String): JSONObject {
        camSeq += 1
        return JSONObject().apply {
            put("msg_type", "CAM")
            put("vehicle_id", vehicleId)
            put("seq", camSeq)
            put("timestamp_ms", System.currentTimeMillis())
            put("lat", gpsLat)
            put("lon", gpsLon)
            put("speed_kmh", gpsSpeedKmh)
            put("heading_deg", gpsHeadingDeg)
            put("pos_accuracy_m", gpsAccM)
            put("gps_time_ms", gpsTimeMs)
        }
    }

    // ---------------- RSU: receive + V2V warnings + storage ----------------

    private fun startRsuReceiver() {
        stopNetworkingOnly()

        statsByVeh.clear()
        vehLabelById.clear()
        nextVehIndex = 1
        rsuVehicleLines = emptyList()

        rsuWarningLevel = "UNKNOWN"
        rsuSummaryText = "Summary: -"

        openLogWriters()

        rsuStatus = "RSU: Listening on UDP $UDP_PORT (V2V warnings enabled)"

        runningJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                rsuSocket = DatagramSocket(UDP_PORT)
                val buf = ByteArray(4096)

                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    rsuSocket?.receive(packet)

                    val rxTimeMs = System.currentTimeMillis()
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    // Raw log (TXT): store exactly what arrived + sender info
                    val fromIp = packet.address?.hostAddress ?: "?"
                    writeRawLine(rxTimeMs, fromIp, packet.port, text)

                    handleRsuPacket(text, rxTimeMs)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rsuSocket?.close() }
                rsuSocket = null
                closeLogWriters()
            }
        }
    }

    private fun handleRsuPacket(payload: String, rxTimeMs: Long) {
        try {
            val msg = JSONObject(payload)
            if (msg.optString("msg_type") != "CAM") return

            val vehId = msg.optString("vehicle_id", "unknown")
            val seq = msg.optLong("seq", -1)
            val lat = msg.getDouble("lat")
            val lon = msg.getDouble("lon")
            val speed = msg.optDouble("speed_kmh", 0.0)
            val accM = msg.optDouble("pos_accuracy_m", 0.0)
            val txTimeMs = if (msg.has("timestamp_ms")) msg.optLong("timestamp_ms") else null

            // Assign stable label per session: Vehicle-1, Vehicle-2, ...
            val label = vehLabelById.getOrPut(vehId) {
                val v = "Vehicle-$nextVehIndex"
                nextVehIndex += 1
                v
            }

            // Update per-vehicle stats
            val st = statsByVeh.getOrPut(vehId) { VehStats(vehId = vehId) }
            st.label = label

            // per-vehicle packet loss
            if (st.lastSeq != -1L && seq > st.lastSeq + 1) {
                st.lost += (seq - st.lastSeq - 1).toInt()
            }
            st.lastSeq = seq
            st.rx += 1

            // latency per vehicle
            var latencyMs: Double? = null
            if (txTimeMs != null) {
                latencyMs = (rxTimeMs - txTimeMs).toDouble()
                st.lastLatencyMs = latencyMs
                st.latencySumMs += latencyMs
                st.latencyCount += 1
            }

            // store latest position for V2V
            st.lastLat = lat
            st.lastLon = lon
            st.lastSpeedKmh = speed
            st.lastAccM = accM
            st.lastUpdateRxTimeMs = rxTimeMs

            // ====== NEW: compute V2V nearest-neighbor distances and warnings ======
            computeV2VWarnings()

            // CSV row (structured): now distance_m means V2V nearest distance
            val v2vDist = st.nearestDistM
            writeCsvRow(
                rxTimeMs = rxTimeMs,
                vehLabel = label,
                vehId = vehId,
                seq = seq,
                lat = lat,
                lon = lon,
                v2vNearestLabel = st.nearestVehLabel,
                v2vNearestDistM = v2vDist,
                speed = speed,
                accM = accM,
                latency = latencyMs,
                warning = st.lastWarning
            )

            // Overall warning = worst among all vehicles
            val overall = worstWarning(statsByVeh.values)

            // Summary (global; derived from per-vehicle totals)
            val totalRx = statsByVeh.values.sumOf { it.rx }
            val totalLost = statsByVeh.values.sumOf { it.lost }
            val globalPdr = if (totalRx + totalLost > 0) totalRx.toDouble() / (totalRx + totalLost) else 1.0

            val summary = if (totalRx > 0 && totalRx % SUMMARY_EVERY == 0) {
                val latCount = statsByVeh.values.sumOf { it.latencyCount }
                val latSum = statsByVeh.values.sumOf { it.latencySumMs }
                val avgLat = if (latCount > 0) latSum / latCount else 0.0
                "Summary@${totalRx}: PDR=%.3f AvgLat=%.1f ms Vehicles=%d".format(globalPdr, avgLat, statsByVeh.size)
            } else null

            // Build UI lines
            val lines = statsByVeh.values.map { s ->
                val latTxt = s.lastLatencyMs?.let { "%.0fms".format(it) } ?: "-"
                val near = s.nearestVehLabel ?: "-"
                val nearD = s.nearestDistM?.let { "%.1fm".format(it) } ?: "-"
                "%s  RX=%d LOST=%d PDR=%.3f  Nearest=%s  V2V=%s  Spd=%.1f  Acc=%.1fm  Lat=%s  %s"
                    .format(s.label, s.rx, s.lost, s.pdr(), near, nearD, s.lastSpeedKmh, s.lastAccM, latTxt, s.lastWarning)
            }

            mainHandler.post {
                rsuWarningLevel = overall
                rsuVehicleLines = lines
                if (summary != null) rsuSummaryText = summary
            }

        } catch (_: Exception) {
        }
    }

    // NEW: compute nearest neighbor and warning per vehicle (V2V)
    private fun computeV2VWarnings() {
        val list = statsByVeh.values.toList().filter { it.hasPosition() }

        // reset
        for (v in statsByVeh.values) {
            v.nearestVehLabel = null
            v.nearestDistM = null
            v.lastWarning = "UNKNOWN"
        }

        if (list.size < 2) {
            // only one vehicle -> no V2V relationship
            if (list.size == 1) {
                val v = list[0]
                v.lastWarning = "SAFE" // alone is effectively safe
            }
            return
        }

        // compute nearest for each vehicle
        for (i in list.indices) {
            val a = list[i]
            var bestDist: Double? = null
            var bestLabel: String? = null

            for (j in list.indices) {
                if (i == j) continue
                val b = list[j]
                val d = haversineMeters(a.lastLat!!, a.lastLon!!, b.lastLat!!, b.lastLon!!)
                if (bestDist == null || d < bestDist) {
                    bestDist = d
                    bestLabel = b.label
                }
            }

            a.nearestDistM = bestDist
            a.nearestVehLabel = bestLabel

            a.lastWarning = when {
                bestDist == null -> "UNKNOWN"
                bestDist <= 8.0 -> "DANGER"
                bestDist <= 15.0 -> "WARN"
                else -> "SAFE"
            }
        }
    }

    private fun worstWarning(values: Collection<VehStats>): String {
        // DANGER > WARN > SAFE > UNKNOWN
        var worst = "UNKNOWN"
        for (v in values) {
            val w = v.lastWarning
            if (w == "DANGER") return "DANGER"
            if (w == "WARN" && worst != "DANGER") worst = "WARN"
            if (w == "SAFE" && worst == "UNKNOWN") worst = "SAFE"
        }
        return worst
    }

    // ---------------- storage helpers ----------------

    private fun openLogWriters() {
        closeLogWriters()

        try {
            val base = getExternalFilesDir(null) ?: filesDir
            logDir = File(base, "Car2XLogs").apply { mkdirs() }

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            csvFile = File(logDir, "rsu_log_$ts.csv")
            txtFile = File(logDir, "rsu_raw_$ts.txt")

            csvWriter = FileWriter(csvFile!!, true)
            txtWriter = FileWriter(txtFile!!, true)

            // NOTE: distance now is V2V nearest distance.
            csvWriter?.write("time_rx_ms,vehicle_label,vehicle_id,seq,lat,lon,nearest_vehicle_label,v2v_distance_m,speed_kmh,pos_accuracy_m,latency_ms,warning\n")
            csvWriter?.flush()

            rsuCsvPath = "Log folder: ${logDir!!.absolutePath}"
        } catch (e: Exception) {
            rsuCsvPath = "Log error: ${e.message}"
        }
    }

    private fun writeCsvRow(
        rxTimeMs: Long,
        vehLabel: String,
        vehId: String,
        seq: Long,
        lat: Double,
        lon: Double,
        v2vNearestLabel: String?,
        v2vNearestDistM: Double?,
        speed: Double,
        accM: Double,
        latency: Double?,
        warning: String
    ) {
        try {
            val row = buildString {
                append(rxTimeMs); append(",")
                append(vehLabel); append(",")
                append(vehId); append(",")
                append(seq); append(",")
                append(lat); append(",")
                append(lon); append(",")
                append(v2vNearestLabel ?: ""); append(",")
                append(if (v2vNearestDistM != null) "%.2f".format(v2vNearestDistM) else ""); append(",")
                append("%.2f".format(speed)); append(",")
                append("%.2f".format(accM)); append(",")
                append(if (latency != null) "%.2f".format(latency) else ""); append(",")
                append(warning)
                append("\n")
            }
            csvWriter?.write(row)
            csvWriter?.flush()
        } catch (_: Exception) {
        }
    }

    private fun writeRawLine(rxTimeMs: Long, fromIp: String, fromPort: Int, payload: String) {
        try {
            txtWriter?.write("$rxTimeMs from=$fromIp:$fromPort $payload\n")
            txtWriter?.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeLogWriters() {
        runCatching { csvWriter?.close() }
        runCatching { txtWriter?.close() }
        csvWriter = null
        txtWriter = null
        csvFile = null
        txtFile = null
        logDir = null
    }

    // ---------------- math ----------------

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun buildVehicleId(): String {
        val raw = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val tail = raw.takeLast(4).ifBlank {
            (System.currentTimeMillis() % 10000).toString().padStart(4, '0')
        }
        return "veh_$tail"
    }

    // ---------------- stop ----------------

    private fun stopNetworkingOnly() {
        runningJob?.cancel()
        runningJob = null

        runCatching { rsuSocket?.close() }
        rsuSocket = null

        closeLogWriters()

        rsuStatus = "Stopped (networking stopped; GPS may still update)"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkingOnly()
        runCatching { locationManager.removeUpdates(this) }
    }
}

/* ======================= UI ======================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUi(
    gpsLat: Double,
    gpsLon: Double,
    gpsSpeedKmh: Float,
    gpsHeadingDeg: Float,
    gpsAccM: Float,
    rsuStatus: String,
    rsuSummaryText: String,
    rsuCsvPath: String,
    rsuWarningLevel: String,
    rsuVehicleLines: List<String>,
    onStartVehicle: (rsuIp: String, intervalMs: Long) -> Unit,
    onStartRsu: () -> Unit,
    onStop: () -> Unit
) {
    var mode by remember { mutableStateOf(Mode.VEHICLE) }
    var rsuIp by remember { mutableStateOf("") }
    var camIntervalMsText by remember { mutableStateOf("500") }
    var appStatus by remember { mutableStateOf("Idle") }

    val warningColor = when (rsuWarningLevel) {
        "DANGER" -> Color(0xFFE53935)
        "WARN" -> Color(0xFFFFB300)
        "SAFE" -> Color(0xFF43A047)
        else -> Color(0xFF9E9E9E)
    }

    val warningText = when (rsuWarningLevel) {
        "DANGER" -> "DANGER ZONE"
        "WARN" -> "WARNING ZONE"
        "SAFE" -> "SAFE ZONE"
        else -> "UNKNOWN"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Car2X Demo",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Operation Mode",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        ModeSelectionButton(
                            selected = mode == Mode.VEHICLE,
                            onClick = { mode = Mode.VEHICLE },
                            icon = Icons.Default.DirectionsCar,
                            label = "Vehicle",
                            modifier = Modifier.weight(1f)
                        )

                        ModeSelectionButton(
                            selected = mode == Mode.RSU,
                            onClick = { mode = Mode.RSU },
                            icon = Icons.Default.Router,
                            label = "RSU",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Configuration",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (mode == Mode.VEHICLE) {
                        OutlinedTextField(
                            value = rsuIp,
                            onValueChange = { rsuIp = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("RSU IP Address") },
                            placeholder = { Text("e.g. 192.168.43.1") },
                            leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = camIntervalMsText,
                            onValueChange = { camIntervalMsText = it.filter(Char::isDigit) },
                            label = { Text("CAM Interval (ms)") },
                            placeholder = { Text("500") },
                            leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "RSU does VEHICLE-to-VEHICLE warnings (nearest neighbor distance).",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GPS Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    StatusRow(icon = Icons.Default.Info, label = "Status", value = appStatus)
                    StatusRow(icon = Icons.Default.MyLocation, label = "Latitude", value = "%.6f".format(gpsLat))
                    StatusRow(icon = Icons.Default.MyLocation, label = "Longitude", value = "%.6f".format(gpsLon))
                    StatusRow(icon = Icons.Default.Speed, label = "Speed", value = "%.1f km/h".format(gpsSpeedKmh))
                    StatusRow(icon = Icons.Default.Explore, label = "Heading", value = "%.1f°".format(gpsHeadingDeg))
                    StatusRow(icon = Icons.Default.PrecisionManufacturing, label = "Accuracy", value = "%.1f m".format(gpsAccM))
                }
            }

            if (mode == Mode.RSU) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RSU Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        StatusRow(icon = Icons.Default.NetworkCheck, label = "Status", value = rsuStatus)
                        StatusRow(icon = Icons.Default.Assessment, label = "Summary", value = rsuSummaryText.replace("Summary: ", ""))
                        StatusRow(icon = Icons.Default.Description, label = "Logs", value = rsuCsvPath.replace("Log folder: ", ""))

                        Divider()

                        if (rsuVehicleLines.isEmpty()) {
                            Text("No vehicles yet.")
                        } else {
                            rsuVehicleLines.forEach { line ->
                                Text(text = line, style = MaterialTheme.typography.bodyMedium)
                                Divider(modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = warningColor.copy(alpha = 0.15f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when (rsuWarningLevel) {
                                    "DANGER" -> Icons.Default.Warning
                                    "WARN" -> Icons.Default.WarningAmber
                                    "SAFE" -> Icons.Default.CheckCircle
                                    else -> Icons.Default.Help
                                },
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = warningColor
                            )
                            Text(
                                text = warningText,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = warningColor,
                                textAlign = TextAlign.Center
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = warningColor.copy(alpha = 0.2f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .background(warningColor)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        if (mode == Mode.VEHICLE) {
                            if (rsuIp.isBlank()) {
                                appStatus = "Error: enter RSU IP"
                                return@Button
                            }
                            val intervalMs = camIntervalMsText.toLongOrNull() ?: 500L
                            onStartVehicle(rsuIp.trim(), intervalMs)
                            appStatus = "Vehicle started"
                        } else {
                            onStartRsu()
                            appStatus = "RSU started"
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f).height(56.dp),
                    onClick = {
                        onStop()
                        appStatus = "Stopped"
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModeSelectionButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 4.dp else 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}
