package com.jarvis.phonebridge

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var micOn = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var status: TextView
    private lateinit var device: TextView
    private lateinit var url: EditText
    private lateinit var connect: Button
    private lateinit var mic: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        device = findViewById(R.id.device)
        url = findViewById(R.id.serverUrl)
        connect = findViewById(R.id.connect)
        mic = findViewById(R.id.mic)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
        connect.setOnClickListener { connectBridge() }
        mic.setOnClickListener { if (micOn) stopMic() else startMic() }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        device.text = "Audio input mode: " + if (am.isBluetoothScoOn) "Bluetooth/SCO" else "phone/default"
    }
    private fun connectBridge() {
        val endpoint = url.text.toString().trim()
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) { status.text = "Enter ws:// or wss:// JARVIS server address"; return }
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        socket = client.newWebSocket(Request.Builder().url(endpoint).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { runOnUiThread { status.text = "JARVIS Bridge: CONNECTED"; connect.text = "DISCONNECT"; mic.isEnabled = true } }
            override fun onMessage(ws: WebSocket, text: String) { runOnUiThread { status.text = "JARVIS: $text" } }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {}
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { runOnUiThread { status.text = "Connection failed: ${t.message}"; mic.isEnabled = false } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { runOnUiThread { status.text = "JARVIS Bridge: DISCONNECTED"; mic.isEnabled = false } }
        })
    }
    private fun startMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val sampleRate = 16000
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { status.text = "Audio input unavailable"; return }
        recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        recorder?.startRecording(); micOn = true; mic.text = "MIC ON — TAP TO MUTE"; status.text = "JARVIS Bridge: LISTENING"
        captureJob = scope.launch { val buffer = ByteArray(min); while (isActive && micOn) { val n = recorder?.read(buffer, 0, buffer.size) ?: 0; if (n > 0) socket?.send(ByteString.of(*buffer.copyOf(n))) } }
    }
    private fun stopMic() {
        micOn = false; captureJob?.cancel(); recorder?.stop(); recorder?.release(); recorder = null
        mic.text = "MIC OFF"; status.text = "JARVIS Bridge: CONNECTED — MIC MUTED"
    }
    override fun onDestroy() { stopMic(); socket?.close(1000, "app closed"); scope.cancel(); super.onDestroy() }
}
