package com.tecsup.controlinn

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MainActivity : AppCompatActivity() {

    private val MQTT_BROKER_URL = "tcp://38.250.116.214:1883"
    private val CLIENT_ID = "Android_Faja_Remote"
    private val MQTT_USER = "joseph"
    private val MQTT_PASS = "1234"

    private val TOPIC_COMANDO = "/faja/comando"
    private val TOPIC_VELOCIDAD = "/faja/velocidad"
    private val TOPIC_TELEMETRIA = "/faja/telemetria"

    private var mqttClient: MqttClient? = null
    private val gson = Gson()
    
    private lateinit var tvStatus: TextView
    private lateinit var tvBoxCount: TextView
    private lateinit var tvSensors: TextView
    private lateinit var layoutManualControls: LinearLayout
    private lateinit var sbSpeed: SeekBar

    private var boxCount = 0
    private var s1Active = false
    private var s2Active = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvBoxCount = findViewById(R.id.tvBoxCount)
        tvSensors = findViewById(R.id.tvSensors)
        layoutManualControls = findViewById(R.id.layoutManualControls)
        sbSpeed = findViewById(R.id.sbSpeed)

        findViewById<Button>(R.id.btnAuto).setOnClickListener {
            enviarMensaje(TOPIC_COMANDO, "A")
            layoutManualControls.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnManual).setOnClickListener {
            enviarMensaje(TOPIC_COMANDO, "M")
            layoutManualControls.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnOn).setOnClickListener { enviarMensaje(TOPIC_COMANDO, "ON") }
        findViewById<Button>(R.id.btnOff).setOnClickListener { enviarMensaje(TOPIC_COMANDO, "OFF") }
        findViewById<Button>(R.id.btnReverse).setOnClickListener { enviarMensaje(TOPIC_COMANDO, "D") }
        findViewById<Button>(R.id.btnEmergency).setOnClickListener { ejecutarParoEmergencia() }
        findViewById<Button>(R.id.btnResetCounter).setOnClickListener {
            boxCount = 0
            tvBoxCount.text = "0"
        }

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    enviarMensaje(TOPIC_VELOCIDAD, progress.toString())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        conectarServidor()
    }

    private fun conectarServidor() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                mqttClient = MqttClient(MQTT_BROKER_URL, CLIENT_ID, MemoryPersistence())
                val opciones = MqttConnectOptions().apply {
                    userName = MQTT_USER
                    password = MQTT_PASS.toCharArray()
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                }
                
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        runOnUiThread { tvStatus.text = "Conexión perdida" }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        Log.d("MQTT", "Mensaje recibido en $topic: ${message?.toString()}")
                        if (topic == TOPIC_TELEMETRIA) {
                            handleTelemetry(message?.toString() ?: "")
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(opciones)
                mqttClient?.subscribe(TOPIC_TELEMETRIA)
                
                runOnUiThread { tvStatus.text = "Conectado" }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvStatus.text = "Error de conexión" }
            }
        }
    }

    private fun enviarMensaje(topico: String, mensaje: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (mqttClient?.isConnected == true) {
                    val mqttMessage = MqttMessage(mensaje.toByteArray()).apply {
                        qos = 1
                    }
                    mqttClient?.publish(topico, mqttMessage)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun ejecutarParoEmergencia() {
        enviarMensaje(TOPIC_COMANDO, "M")
        enviarMensaje(TOPIC_COMANDO, "OFF")
        enviarMensaje(TOPIC_VELOCIDAD, "0")
        runOnUiThread {
            sbSpeed.progress = 0
            layoutManualControls.visibility = View.VISIBLE
        }
    }

    private fun handleTelemetry(json: String) {
        try {
            val data = gson.fromJson(json, TelemetryData::class.java)
            
            val umbral = 12.0

            if (data.s1 > 0 && data.s1 <= umbral) {
                if (!s1Active) {
                    boxCount++
                    s1Active = true
                }
            } else if (data.s1 > umbral) {
                s1Active = false
            }

            if (data.s2 > 0 && data.s2 <= umbral) {
                if (!s2Active) {
                    if (boxCount > 0) boxCount--
                    s2Active = true
                }
            } else if (data.s2 > umbral) {
                s2Active = false
            }

            runOnUiThread {
                tvBoxCount.text = boxCount.toString()
                tvSensors.text = "S1: ${data.s1}cm | S2: ${data.s2}cm"
            }
        } catch (e: Exception) {
            Log.e("MQTT", "Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

data class TelemetryData(val s1: Double, val s2: Double, val vel: Int)
