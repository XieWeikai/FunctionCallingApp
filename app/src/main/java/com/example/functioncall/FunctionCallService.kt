package com.example.functioncall

import android.app.Service
import android.content.Intent
import android.os.IBinder

import android.app.*
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.isAccessible

// Data classes to represent the JSON structure
data class FunctionCall(
    val name: String,
    val arguments: Map<String, Any>
)

class FunctionCallService : Service() {
    private lateinit var httpServer: NanoHTTPD
    private val PORT = 8080
    private val CHANNEL_ID = "FunctionCallServiceChannel"

    override fun onBind(intent: Intent): IBinder? {
        // 本例中不处理绑定服务的情况
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
        startHttpServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "HTTP Server Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP Server Running")
            .setContentText("Tap to return to the app.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    fun createAlarm(message: String, hour: Int, minutes: Int) {
        Log.d("createAlarm", "get into createAlarm")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            Log.d("createAlarm", "start Intent")
        }else{
            Log.d("createAlarm", "can not resolveActivity")
        }
//        startActivity(intent)
    }

    private fun startHttpServer() {
        httpServer = object : NanoHTTPD(PORT) {
            override fun serve(session: IHTTPSession): Response {
                // 处理 GET 请求
                if (session.method == Method.GET) {
                    val uri = session.uri
                    Log.d("HTTP Server", "Received request for $uri")
                    createAlarm("test", 12, 30)
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "Hello from the HTTP server!\n")
                }else if(session.method == Method.POST) {
                    try {
                        val files = mutableMapOf<String, String>()
                        // 解析请求体，数据将被填充到 files 映射中
                        session.parseBody(files)
                        // 请求体数据通常在 postData 键下
                        val postData = files["postData"]
                        return newFixedLengthResponse(Response.Status.OK, "text/plain", "Received: $postData \n")
                    } catch (e: IOException) {
                        e.printStackTrace()
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error")
                    } catch (e: ResponseException) {
                        e.printStackTrace()
                        return newFixedLengthResponse(e.status, "text/plain", e.message)
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        try {
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i("HTTP Server", "Successfully started HTTP server on port $PORT")
        } catch (e: IOException) {
            Log.e("HTTP Server", "Error starting HTTP server: ", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (httpServer.isAlive) {
            httpServer.stop()
            Log.i("HTTP Server", "HTTP server stopped")
        }
    }
}