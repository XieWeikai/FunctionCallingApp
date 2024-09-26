package com.example.functioncall

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.icu.util.Calendar
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.functioncall.ui.theme.FunctionCallTheme

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions

private const val TAG = "MainActivity"

class PickRingtone : ActivityResultContract<Int, Uri?>() {
    override fun createIntent(context: Context, ringtoneType: Int) =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, ringtoneType)
        }

    override fun parseResult(resultCode: Int, intent: Intent?) : Uri? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }
        return intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var httpServer: NanoHTTPD
    private val PORT = 8080
    private lateinit var functions: Functions
    val functionsMap: Map<String, KFunction<*>> = this::class.memberFunctions
        .associateBy { it.name }

    var latch = CountDownLatch(1)
    var uri = ""
    val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        this.uri = uri.toString()
        var name = "未知文件"
        val cursor = uri?.let { contentResolver.query(it, null, null, null, null) }
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        Log.d("getContent", "select filename: $name")
        latch.countDown()

        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val getContact = registerForActivityResult(ActivityResultContracts.PickContact()) {uri: Uri? ->
        this.uri = uri.toString()
        latch.countDown()
    }

    val getRingtone = registerForActivityResult(PickRingtone()){uri: Uri? ->
        this.uri = uri.toString()
        latch.countDown()
    }

    val takePicLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success->
        if (success){
            Log.d(TAG, "takePicLauncher done!")
        }
        latch.countDown()
    }

    val takeVideoLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success->
        if (success){
            Log.d(TAG, "takeVideoLauncher done!")
        }
        latch.countDown()
    }

    fun INTENT_ACTION_VIDEO_CAMERA() {
        val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun INTENT_ACTION_STILL_IMAGE_CAMERA() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun ACTION_VIDEO_CAPTURE(): String {
        val videoURI = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues())
        videoURI?.let { uri ->
            takeVideoLauncher.launch(uri)
        }
        latch.await()
        latch = CountDownLatch(1)
        return videoURI.toString()
    }

    fun ACTION_IMAGE_CAPTURE(): String {
        val photoURI = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues())
        photoURI?.let { uri ->
            takePicLauncher.launch(uri)
        }
        latch.await()
        latch = CountDownLatch(1)
        return photoURI.toString()
    }

    fun pickRingtone(): String {
        getRingtone.launch(RingtoneManager.TYPE_ALL)
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }

    fun content(mime: String): String{
        getContent.launch(mime)
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }

    fun pickContact(): String {
        getContact.launch(null)
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }

    fun viewContact(uri: String) {
        val contactUri = Uri.parse(uri)
        Log.d("viewContact", "receive uri: $contactUri")
        val intent = Intent(Intent.ACTION_VIEW, contactUri)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "on create here")

        enableEdgeToEdge()
        setContent {
            FunctionCallTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        functions = Functions(this, functionsMap)
        startHttpServer()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun ACTION_INSERT_EVENT(
        TITLE: String,
        DESCRIPTION: String,
        EVENT_LOCATION: String,
        EXTRA_EVENT_ALL_DAY: Boolean = false,
        EXTRA_EVENT_BEGIN_TIME: String? = null,
        EXTRA_EVENT_END_TIME: String? = null,
        EXTRA_EMAIL: List<String>? = null
    ) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, TITLE)
            putExtra(CalendarContract.Events.DESCRIPTION, DESCRIPTION)
            putExtra(CalendarContract.Events.EVENT_LOCATION, EVENT_LOCATION)
            putExtra(CalendarContract.Events.ALL_DAY, EXTRA_EVENT_ALL_DAY)

            // Convert ISO 8601 string times to milliseconds since epoch
            EXTRA_EVENT_BEGIN_TIME?.let {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, iso8601ToMillis(it))
            }
            EXTRA_EVENT_END_TIME?.let {
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, iso8601ToMillis(it))
            }

            // If there are emails, add them as attendees
            EXTRA_EMAIL?.let {
                putExtra(Intent.EXTRA_EMAIL, it.toTypedArray())
            }
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun iso8601ToMillis(dateTime: String): Long {
        val formatter = java.time.format.DateTimeFormatter.ISO_DATE_TIME
        val localDateTime = java.time.LocalDateTime.parse(dateTime, formatter)
        val zonedDateTime = localDateTime.atZone(java.time.ZoneId.systemDefault())
        return zonedDateTime.toInstant().toEpochMilli()
    }

    fun ACTION_SHOW_ALARMS(
        EXTRA_LENGTH: Int,
        EXTRA_MESSAGE: String = "",
        EXTRA_SKIP_UI: Boolean = true
    ) {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun ACTION_SET_TIMER(
        EXTRA_LENGTH: Int,
        EXTRA_MESSAGE: String = "",
        EXTRA_SKIP_UI: Boolean = true
    ) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, EXTRA_LENGTH)
            putExtra(AlarmClock.EXTRA_MESSAGE, EXTRA_MESSAGE)
            putExtra(AlarmClock.EXTRA_SKIP_UI, EXTRA_SKIP_UI)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun ACTION_SET_ALARM(
        EXTRA_HOUR: Int,
        EXTRA_MINUTES: Int,
        EXTRA_MESSAGE: String = "",
        EXTRA_DAYS: List<String>? = null,
        EXTRA_RINGTONE: String? = null,
        EXTRA_VIBRATE: Boolean = false,
        EXTRA_SKIP_UI: Boolean = true
    ) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, EXTRA_HOUR)
            putExtra(AlarmClock.EXTRA_MINUTES, EXTRA_MINUTES)
            putExtra(AlarmClock.EXTRA_MESSAGE, EXTRA_MESSAGE)
            putExtra(AlarmClock.EXTRA_SKIP_UI, EXTRA_SKIP_UI)
            putExtra(AlarmClock.EXTRA_VIBRATE, EXTRA_VIBRATE)

            // Handle days of the week; this part is not directly supported by the standard alarm intent,
            // so usually alarms are set for the next occurrence of the specified time.
            EXTRA_DAYS?.let {
                val dayList = ArrayList<Int>()
                it.forEach{day->
                    dayList.add(dayOfWeekToInt(day))
                }
                putExtra(AlarmClock.EXTRA_DAYS, dayList)
            }
            EXTRA_RINGTONE?.let {
                putExtra(AlarmClock.EXTRA_RINGTONE, EXTRA_RINGTONE)
            }
        }

        if (intent.resolveActivity(packageManager) != null){
            startActivity(intent)
        }
    }

    fun dayOfWeekToInt(day: String): Int {
        return when (day.lowercase()) {
            "monday" -> Calendar.MONDAY
            "tuesday" -> Calendar.TUESDAY
            "wednesday" -> Calendar.WEDNESDAY
            "thursday" -> Calendar.THURSDAY
            "friday" -> Calendar.FRIDAY
            "saturday" -> Calendar.SATURDAY
            "sunday" -> Calendar.SUNDAY
            else -> throw IllegalArgumentException("$day is not an illegal day of a week")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (httpServer.isAlive) {
            httpServer.stop()
            Log.i("HTTP Server", "HTTP server stopped")
        }
    }

    private fun startHttpServer() {
        httpServer = object : NanoHTTPD(PORT) {
            override fun serve(session: IHTTPSession): Response {
                // 处理 GET 请求
                if (session.method == Method.GET) {
                    val uri = session.uri
                    Log.d("HTTP Server", "Received request for $uri")
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "Hello from the HTTP server!\n")
                }else if(session.method == Method.POST) {
                    try {
                        val files = mutableMapOf<String, String>()
                        // 解析请求体，数据将被填充到 files 映射中
                        session.parseBody(files)
                        // 请求体数据通常在 postData 键下
                        val jsonStr = files["postData"]
                        Log.d("HTTP Server", "Received call: $jsonStr")
                        val objectMapper = jacksonObjectMapper()
                        val functionCall: FunctionCall? = jsonStr?.let { objectMapper.readValue(it) }
                        val res = functionCall?.let { functions.execute(it) }
                        Log.d("Http Server", "call result: $res")
                        val jsonRes = objectMapper.writeValueAsString(res)
                        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonRes)
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
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello!!!  $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    FunctionCallTheme {
        Greeting("Xwk")
    }
}