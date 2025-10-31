package com.example.mqtt_app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.Charset
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private var mqttClient: MqttClient? = null
    private lateinit var inputBroker: EditText
    private lateinit var inputClientId: EditText
    private lateinit var inputTopicBase: EditText
    private lateinit var inputTopicFanBase: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var switchLed: SwitchCompat
    private lateinit var switchFan: SwitchCompat
    private lateinit var textStatus: TextView
    private lateinit var textLogs: TextView
    private lateinit var scrollLogs: ScrollView

    private var updatingToggleFromStatus: Boolean = false
    private var updatingFanFromStatus: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupDefaultValues()
        setupHandlers()
    }

    private fun bindViews() {
        inputBroker = findViewById(R.id.inputBroker)
        inputClientId = findViewById(R.id.inputClientId)
        inputTopicBase = findViewById(R.id.inputTopicBase)
        inputTopicFanBase = findViewById(R.id.inputTopicFanBase)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        switchLed = findViewById(R.id.switchLed)
        switchFan = findViewById(R.id.switchFan)
        textStatus = findViewById(R.id.textStatus)
        textLogs = findViewById(R.id.textLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
    }

    private fun setupDefaultValues() {
        inputBroker.setText(getString(R.string.hint_broker))
        inputClientId.setText("")
        inputTopicBase.setText(getString(R.string.hint_topic_led))
        inputTopicFanBase.setText(getString(R.string.hint_topic_fan))
        setStatusText("Disconnected")
    }

    private fun setupHandlers() {
        btnConnect.setOnClickListener {
            connect()
        }
        btnDisconnect.setOnClickListener {
            disconnect()
        }
        switchLed.setOnCheckedChangeListener { _, isChecked ->
            if (updatingToggleFromStatus) return@setOnCheckedChangeListener
            publishSet(isChecked)
        }
        switchFan.setOnCheckedChangeListener { _, isChecked ->
            if (updatingFanFromStatus) return@setOnCheckedChangeListener
            publishFanSet(isChecked)
        }
    }

    private fun connect() {
        val rawBroker = inputBroker.text.toString().trim()
        if (rawBroker.isEmpty()) {
            appendLog("Broker URI is empty")
            return
        }

        val broker = normalizeBrokerUri(rawBroker)
        val clientId = inputClientId.text.toString().ifBlank { generateClientId() }

        Thread {
            try {
                appendLog("Connecting to $broker as $clientId ...")
                val client = MqttClient(broker, clientId, MemoryPersistence())
                mqttClient = client
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                }
                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        appendLog("Connection lost: ${cause?.message ?: "unknown"}")
                        runOnUiThread { setStatusText("Disconnected") }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.toString(Charset.defaultCharset()) ?: ""
                        appendLog("<- $topic : $payload")
                        runOnUiThread { handleIncomingMessage(topic.orEmpty(), payload) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                client.connect(options)
                runOnUiThread { setStatusText("Connected") }
                subscribeAll()
            } catch (e: Exception) {
                appendLog("Connect exception: ${e.message}")
                runOnUiThread { setStatusText("Disconnected") }
            }
        }.start()
    }

    private fun subscribeAll() {
        val client = mqttClient ?: return
        try {
            client.subscribe("home/#", 1)
            appendLog("Subscribed to home/#")
        } catch (e: MqttException) {
            appendLog("Subscribe failed: ${e.message}")
        }
    }

    private fun publishSet(isOn: Boolean) {
        val base = inputTopicBase.text.toString().trim().trim('/')
        if (base.isEmpty()) {
            appendLog("Topic base is empty")
            return
        }
        val topic = "$base/set"
        val payload = if (isOn) "ON" else "OFF"
        publish(topic, payload, qos = 1, retained = false)
    }

    private fun publishFanSet(isOn: Boolean) {
        val base = inputTopicFanBase.text.toString().trim().trim('/')
        if (base.isEmpty()) {
            appendLog("Fan topic base is empty")
            return
        }
        val topic = "$base/set"
        val payload = if (isOn) "ON" else "OFF"
        publish(topic, payload, qos = 1, retained = false)
    }

    private fun publish(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        val client = mqttClient
        if (client == null || !client.isConnected) {
            appendLog("Cannot publish, not connected")
            return
        }
        val message = MqttMessage(payload.toByteArray(Charset.defaultCharset())).apply {
            this.qos = qos
            isRetained = retained
        }
        try {
            client.publish(topic, message)
            appendLog("-> $topic : $payload")
        } catch (e: MqttException) {
            appendLog("Publish failed: ${e.message}")
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        val base = inputTopicBase.text.toString().trim().trim('/')
        if (base.isNotEmpty()) {
            val statusTopic = "$base/status"
            if (topic.equals(statusTopic, ignoreCase = false)) {
                setStatusText("Status: $payload")
                val normalized = payload.trim().uppercase()
                val shouldBeChecked = normalized == "ON" || normalized == "1" || normalized == "TRUE"
                updatingToggleFromStatus = true
                switchLed.isChecked = shouldBeChecked
                updatingToggleFromStatus = false
            }
        }

        val fanBase = inputTopicFanBase.text.toString().trim().trim('/')
        if (fanBase.isNotEmpty()) {
            val fanStatusTopic = "$fanBase/status"
            if (topic.equals(fanStatusTopic, ignoreCase = false)) {
                val normalized = payload.trim().uppercase()
                val shouldBeChecked = normalized == "ON" || normalized == "1" || normalized == "TRUE"
                updatingFanFromStatus = true
                switchFan.isChecked = shouldBeChecked
                updatingFanFromStatus = false
            }
        }
    }

    private fun disconnect() {
        val client = mqttClient ?: return
        try {
            appendLog("Disconnecting...")
            client.disconnect()
            appendLog("Disconnected")
            setStatusText("Disconnected")
        } catch (e: MqttException) {
            appendLog("Disconnect exception: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnectForcibly(500)
            mqttClient?.close()
            mqttClient = null
        } catch (_: Exception) {
        }
    }

    private fun normalizeBrokerUri(uri: String): String {
        // Accept tcp:// or mqtt://; convert mqtt:// to tcp:// for Paho
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("tcp://", ignoreCase = true) -> trimmed
            trimmed.startsWith("mqtt://", ignoreCase = true) -> "tcp://" + trimmed.removePrefix("mqtt://")
            trimmed.startsWith("ssl://", ignoreCase = true) -> trimmed // if user provides SSL
            else -> "tcp://$trimmed" // if user typed host:port only
        }
    }

    private fun generateClientId(): String =
        "android-" + UUID.randomUUID().toString().replace("-", "").take(22)

    private fun setStatusText(text: String) {
        textStatus.text = text
    }

    private fun appendLog(line: String) {
        val newText = (textLogs.text?.toString() ?: "") +
            "\n" +
            "[" + (System.currentTimeMillis() % 100000).toString().padStart(5, '0') + "] " + line
        textLogs.text = newText.trim()
        scrollLogs.post {
            scrollLogs.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}