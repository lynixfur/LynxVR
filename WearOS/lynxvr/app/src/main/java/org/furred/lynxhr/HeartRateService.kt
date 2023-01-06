package org.furred.lynxhr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.android.volley.RequestQueue
import com.android.volley.toolbox.Volley
import com.illposed.osc.OSCMessage
import com.illposed.osc.OSCPortOut
import dev.gustavoavila.websocketclient.WebSocketClient
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import kotlin.math.roundToInt


class HeartRateService : Service(), SensorEventListener {

    private lateinit var mHeartRateSensor: Sensor
    private lateinit var mSensorManager: SensorManager
    private lateinit var httpQueue: RequestQueue
    private lateinit var preferences: SharedPreferences

    private var DEFAULT_VRC_ENDPOINT_HR1 = "/avatar/parameters/lynxhr_hr1_i" // 0-500
    private var DEFAULT_VRC_ENDPOINT_HR2 = "/avatar/parameters/lynxhr_hr2_u" // 0 - 1
    private var DEFAULT_VRC_ENDPOINT_HR3 = "/avatar/parameters/lynxhr_hr3_s" // -1 - 1
    private var DEFAULT_VRC_ENDPOINT_BAT = "/avatar/parameters/lynxhr_bat"
    private var DEFAULT_VRC_ENDPOINT_BAT_CHAR = "/avatar/parameters/lynxhr_bat_charging"
    private val CHANNEL_ID = "HeartRateService"

    // NeosVR Websocket Server
    private var webSocketClient: WebSocketClient? = null

    companion object {
        fun startService(context: Context) {
            val startIntent = Intent(context, HeartRateService::class.java)
            ContextCompat.startForegroundService(context, startIntent)
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, HeartRateService::class.java)
            context.stopService(stopIntent)
        }
    }

    private fun createWebSocketClient() {
        val uri: URI
        try {
            uri = URI("ws://" + preferences.getString(
                MainActivity.Config.CONF_HTTP_HOSTNAME,
                MainActivity.Config.CONF_HTTP_HOSTNAME_DEFAULT
            ).toString() + ":" + preferences.getInt(
                MainActivity.Config.CONF_NEOS_WS_PORT,
                MainActivity.Config.CONF_NEOS_WS_PORT_DEFAULT
            ).toString())
        } catch (e: URISyntaxException) {
            e.printStackTrace()
            return
        }
        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen() {
                println("onOpen")
                webSocketClient!!.send("0")
            }

            override fun onTextReceived(message: String) {
                println("onTextReceived")
            }

            override fun onBinaryReceived(data: ByteArray) {
                println("onBinaryReceived")
            }

            override fun onPingReceived(data: ByteArray) {
                println("onPingReceived")
            }

            override fun onPongReceived(data: ByteArray) {
                println("onPongReceived")
            }

            override fun onException(e: java.lang.Exception) {
                println(e.message)
            }

            override fun onCloseReceived() {
                println("onCloseReceived")
            }
        }
        (webSocketClient as WebSocketClient).setConnectTimeout(10000)
        (webSocketClient as WebSocketClient).setReadTimeout(60000)
        (webSocketClient as WebSocketClient).enableAutomaticReconnection(5000)
        (webSocketClient as WebSocketClient).connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        doSomething()

        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(R.string.notification_title.toString())
            .setContentText(R.string.notification_text.toString())
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)

        // Start Neos Server

        // Create Neos Websocket Client
        createWebSocketClient();

        return START_NOT_STICKY

    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startNeosServer() {
        // Neos Websocket Server
        //wsServer.start()

        //Log.d("NEOSVR WS", "The NeosVR Websocket Server has been started.")

    }

    private fun stopNeosServer() {
        // Neos Websocket Server
        //wsServer.stop()
        //Log.d("NEOSVR WS", "The NeosVR Websocket Server has been stopped.")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            R.string.notification_channel_title.toString(),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager!!.createNotificationChannel(serviceChannel)
    }

    private fun doSomething() {

        preferences = this.getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)
        httpQueue = Volley.newRequestQueue(this)

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mHeartRateSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        startMeasure()

    }

    private fun startMeasure() {

        val sensorRegistered: Boolean = mSensorManager.registerListener(
            this,
            mHeartRateSensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        Log.d("Sensor Status:", " Sensor registered: " + (if (sensorRegistered) "yes" else "no"))
        sendStatusToActivity(MainActivity.Config.CONF_SENDING_STATUS_STARTING)
    }

    private fun stopMeasure() {
        mSensorManager.unregisterListener(this)
        sendStatusToActivity(MainActivity.Config.CONF_SENDING_STATUS_NOT_RUNNING)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val mHeartRateFloat: Float = event!!.values[0]

        val mHeartRate: Int = mHeartRateFloat.roundToInt()
        Log.d("HR: ", mHeartRate.toString())

        sendHeartRate(mHeartRate)
        sendHeartRateToActivity(mHeartRate)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // ignored
    }

    private fun sendHeartRate(heartrate: Int) {

        val gfgThread = Thread {
            try {
                try {
                    var osc = OSCPortOut(
                        InetAddress.getByName( preferences.getString(
                            MainActivity.Config.CONF_HTTP_HOSTNAME,
                            MainActivity.Config.CONF_HTTP_HOSTNAME_DEFAULT
                        )), preferences.getInt(
                            MainActivity.Config.CONF_HTTP_PORT,
                            MainActivity.Config.CONF_HTTP_PORT_DEFAULT
                        )
                    )
                    osc.send(OSCMessage(DEFAULT_VRC_ENDPOINT_HR1, listOf(heartrate)))
                    osc.send(OSCMessage(DEFAULT_VRC_ENDPOINT_HR2, listOf((heartrate.toFloat()/255f))))
                    osc.send(OSCMessage(DEFAULT_VRC_ENDPOINT_HR3, listOf((heartrate.toFloat()/127f-1f))))
                    osc.send(OSCMessage(DEFAULT_VRC_ENDPOINT_BAT, listOf(0)))
                    osc.send(OSCMessage(DEFAULT_VRC_ENDPOINT_BAT_CHAR, listOf(false)))

                    // NeosVR Client
                    webSocketClient!!.send(heartrate.toString());

                    sendStatusToActivity(MainActivity.Config.CONF_SENDING_STATUS_OK)
                } catch(e: Exception) {
                    Log.e("OSC", e.toString())
                    sendStatusToActivity(MainActivity.Config.CONF_SENDING_STATUS_ERROR)
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }

        gfgThread.start()
    }

    override fun onDestroy() {
        stopMeasure()
        // Stop NeosVR Server

        // Stop NeosVR Client
        webSocketClient!!.close();
        super.onDestroy()
    }

    private fun sendHeartRateToActivity(heartrate: Int) {
        val intent = Intent(MainActivity.Config.CONF_BROADCAST_HEARTRATE_UPDATE)
        intent.putExtra("heartrate", heartrate)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendStatusToActivity(status: String) {
        val intent = Intent(MainActivity.Config.CONF_BROADCAST_STATUS)
        intent.putExtra("status", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}