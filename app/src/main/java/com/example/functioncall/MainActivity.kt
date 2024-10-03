package com.example.functioncall

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.SearchManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.util.Calendar
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
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
import androidx.core.content.ContextCompat
import com.example.functioncall.ui.theme.FunctionCallTheme

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.gms.actions.NoteIntents
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import android.provider.Settings

private const val TAG = "MainActivity"

class PickRingtone : ActivityResultContract<Int, Uri?>() {
    override fun createIntent(context: Context, input: Int) =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, input)
        }

    override fun parseResult(resultCode: Int, intent: Intent?) : Uri? {
        if (resultCode != Activity.RESULT_OK) {
            return null
        }
        return intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
}

class MyPickContact : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val mimeType = when (input) {
            "PHONE" -> ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
            "EMAIL" -> ContactsContract.CommonDataKinds.Email.CONTENT_TYPE
            "ADDRESS" -> ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE
            else -> ContactsContract.Contacts.CONTENT_TYPE // Default to picking full contact
        }
        return Intent(Intent.ACTION_PICK).setType(mimeType)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}

open class CreateDocumentWithMime() : ActivityResultContract<Map<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Map<String, String>): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(input["mime_type"])
            .putExtra(Intent.EXTRA_TITLE, input["file_name"])
    }


    final override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
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
    var uris: List<String> = listOf()

    val creatDocumentLauncher = registerForActivityResult(CreateDocumentWithMime()) { uri: Uri? ->
        this.uri = uri.toString()
        latch.countDown()
    }

    val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        this.uri = uri.toString()
        latch.countDown()
    }

    val openMultipleDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
        this.uris = uris?.map { it.toString() } ?: emptyList()
        latch.countDown()
    }

    val getMultipleContents = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        this.uris = uris?.map { it.toString() } ?: emptyList()
        latch.countDown()
    }

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

    val getContact = registerForActivityResult(MyPickContact()) {uri: Uri? ->
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

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Log.d(TAG, "Permission denied")
            finish()
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

        if( ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED){
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        functions = Functions(this, functionsMap)
        startHttpServer()
    }

    fun web_search(query: String, engine: String="google") {
        // use google or baidu to search
        // directly open the browser using action view
        val searchUri = when (engine.lowercase()) {
            "google" -> Uri.parse("https://www.google.com/search?q=$query")
            "baidu" -> Uri.parse("https://www.baidu.com/s?wd=$query")
            else -> Uri.parse("https://www.google.com/search?q=$query")
        }
        val intent = Intent(Intent.ACTION_VIEW, searchUri)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun open_settings(setting_type:String = "general"){
        val intent = when (setting_type) {
            "general" -> Intent(Settings.ACTION_SETTINGS)
            "wireless" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "airplane_mode" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "apn" -> Intent(Settings.ACTION_APN_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "locale" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "input_method" -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "internal_storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "memory_card" -> Intent(Settings.ACTION_MEMORY_CARD_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS) // Default to general settings if unknown type
        }

        // Check if there is an activity available to handle this intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun search_location(query: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0,0?q=$query")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
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

    fun ACTION_GET_RINGTONE(): String {
        getRingtone.launch(RingtoneManager.TYPE_ALL)
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }

    fun ACTION_CREATE_DOCUMENT(mime_type: String, initial_name: String): String {
        creatDocumentLauncher.launch(mapOf("mime_type" to mime_type, "file_name" to initial_name))
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }

    fun ACTION_OPEN_DOCUMENT(mime_type: List<String>, allow_multiple: Boolean=false): List<String> {
        if (allow_multiple) {
            openMultipleDocumentLauncher.launch(mime_type.toTypedArray())
            latch.await()
            latch = CountDownLatch(1)
            return uris
        } else {
            openDocumentLauncher.launch(mime_type.toTypedArray())
            latch.await()
            latch = CountDownLatch(1)
            return listOf(uri)
        }
    }

    fun ACTION_GET_CONTENT(mime_type: String, allow_multiple: Boolean=false): List<String> {
        if (allow_multiple) {
            getMultipleContents.launch(mime_type)
            latch.await()
            latch = CountDownLatch(1)
            return uris
        } else {
            getContent.launch(mime_type)
            latch.await()
            latch = CountDownLatch(1)
            return listOf(uri)
        }
    }

    fun ACTION_PICK(data_type: String="ALL"): String {
        getContact.launch(data_type)
        latch.await()
        latch = CountDownLatch(1)
        return uri
    }


    fun ACTION_VIEW_CONTACT(contact_uri: String) {
        val contactUri = Uri.parse(uri)
        val intent = Intent(Intent.ACTION_VIEW, contactUri)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun send_message(phone_number: String, subject: String, body: String, attachments: List<String>? = null) {
        val intent = when {
            attachments.isNullOrEmpty() -> Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone_number") // 只设置短信协议
            }
            attachments.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = "*/*" // 单个附件，使用通用 MIME 类型
                putExtra(Intent.EXTRA_STREAM, Uri.parse(attachments.first()))
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*" // 多个附件，使用通用 MIME 类型
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments.map { Uri.parse(it) }))
            }
        }

        // 设置短信的基本信息
        intent.putExtra("sms_body", body)
        intent.putExtra("subject", subject)

        // 检查是否有应用可以处理这个 Intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(Intent.createChooser(intent, "Choose a messaging app:"))
        }
    }

    fun send_email(
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String>? = null,
        bcc: List<String>? = null,
        attachments: List<String>? = null
    ) {
        // 根据附件数量选择合适的 Intent 动作
        val intent = when {
            attachments.isNullOrEmpty() -> Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:") // 只设置邮件协议
            }
            attachments.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = "*/*" // 单个附件，使用通用 MIME 类型
                putExtra(Intent.EXTRA_STREAM, Uri.parse(attachments.first()))
            }
            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*" // 多个附件，使用通用 MIME 类型
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments.map { Uri.parse(it) }))
            }
        }

        // 设置邮件的基本信息
        intent.putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, body)
        cc?.let { intent.putExtra(Intent.EXTRA_CC, it.toTypedArray()) }
        bcc?.let { intent.putExtra(Intent.EXTRA_BCC, it.toTypedArray()) }

        // 检查是否有应用可以处理这个 Intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(Intent.createChooser(intent, "Choose an Email client:"))
        }
    }

    fun get_contact_info_from_uri(uri: String, key: String): String {
        val contactUri = Uri.parse(uri)

        val dataMap = mapOf(
            "email" to ContactsContract.CommonDataKinds.Email.ADDRESS,
            "phone" to ContactsContract.CommonDataKinds.Phone.NUMBER,
            "address" to ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS
        )
        contentResolver.query(contactUri, null, null, null, null)?.use { cursor->
            if (cursor.moveToFirst()) {
                val column = dataMap[key]
                val dataIdx = cursor.getColumnIndex(column)
                return cursor.getString(dataIdx)
            }
        }
        return ""
    }

    fun dial(phone_number: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phone_number")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    data class ContactDetail(
        val uri: Uri,
        val column: String,
        val selection: String,
        val extraArgs: Array<String>? = null
    )

    @SuppressLint("Range")
    private fun queryContactDetail(uri: Uri, column: String, selection: String, selectionArgs: Array<String>): String {
        contentResolver.query(uri, arrayOf(column), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndex(column))
            }
        }
        return ""
    }

    @SuppressLint("Range")
    fun get_contact_info(name: String, key: String): String {
        val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon().appendPath(name).build()
        val key_lower = key.lowercase()
        val detailsMap = mapOf(
            "email" to ContactDetail(
                uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                column = ContactsContract.CommonDataKinds.Email.ADDRESS,
                selection = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?"
            ),
            "phone" to ContactDetail(
                uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                column = ContactsContract.CommonDataKinds.Phone.NUMBER,
                selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
            ),
//            "company" to ContactDetail(
//                uri = ContactsContract.CommonDataKinds.Organization.CONTENT_URI,
//                column = ContactsContract.CommonDataKinds.Organization.COMPANY,
//                selection = "${ContactsContract.CommonDataKinds.Organization.CONTACT_ID} = ?"
//            ),
            "address" to ContactDetail(
                uri = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                column = ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                selection = "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?"
            )
        )

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val contactId = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts._ID))
                val detail = detailsMap[key_lower]
                if (key_lower == "uri") {
                    cursor.apply {
                        // Gets the lookup key column index
                        val lookupKeyIndex = getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                        // Gets the lookup key value
                        val currentLookupKey = getString(lookupKeyIndex)
                        // Gets the _ID column index
                        val idIndex = getColumnIndex(ContactsContract.Contacts._ID)
                        val currentId = getLong(idIndex)
                        val selectedContactUri = ContactsContract.Contacts.getLookupUri(currentId, currentLookupKey)
                        return selectedContactUri.toString()
                    }
                }
                detail?.let {
                    return queryContactDetail(it.uri, it.column, it.selection, arrayOf(contactId, *(it.extraArgs ?: emptyArray())))
                }
            }
        }
        return ""
    }

    fun ACTION_EDIT_CONTACT(contact_uri: String, contact_info: Map<String, String>) {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            data = Uri.parse(contact_uri)

            // Set the Name, Email, and other contact fields from the map
            contact_info["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            contact_info["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            contact_info["phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            contact_info["company"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            contact_info["address"]?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun ACTION_INSERT_CONTACT(contact_info: Map<String, String>) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE

            // Set the Name, Email, and other contact fields from the map
            contact_info["name"]?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            contact_info["email"]?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            contact_info["phone"]?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            contact_info["company"]?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
            contact_info["address"]?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun ACTION_INSERT_EVENT(
        TITLE: String,
        DESCRIPTION: String,
        EVENT_LOCATION: String?=null,
        EXTRA_EVENT_ALL_DAY: Boolean = false,
        EXTRA_EVENT_BEGIN_TIME: String? = null,
        EXTRA_EVENT_END_TIME: String? = null,
        EXTRA_EMAIL: List<String>? = null
    ) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, TITLE)
            putExtra(CalendarContract.Events.DESCRIPTION, DESCRIPTION)

            putExtra(CalendarContract.Events.ALL_DAY, EXTRA_EVENT_ALL_DAY)

            EVENT_LOCATION?.let {
                putExtra(CalendarContract.Events.EVENT_LOCATION, EVENT_LOCATION)
            }
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

    fun ACTION_SHOW_ALARMS() {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun ACTION_SET_TIMER(
        duration: String,
        EXTRA_MESSAGE: String = "",
        EXTRA_SKIP_UI: Boolean = true
    ) {
        val EXTRA_LENGTH = parseDuration(duration)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, EXTRA_LENGTH)
            putExtra(AlarmClock.EXTRA_MESSAGE, EXTRA_MESSAGE)
            putExtra(AlarmClock.EXTRA_SKIP_UI, EXTRA_SKIP_UI)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    fun parseDuration(input: String): Int {
        // 匹配一个或多个数字，后面可能有任意数量的空格，然后是时间单位的完整形式或简写形式
        val regex = "(\\d+)\\s*(hours?|h|minutes?|m|seconds?|s)".toRegex()
        var totalSeconds = 0

        regex.findAll(input).forEach { matchResult ->
            val (number, unit) = matchResult.destructured
            totalSeconds += when (unit.lowercase()) {
                "hour", "hours", "h" -> number.toInt() * 3600
                "minute", "minutes", "m" -> number.toInt() * 60
                "second", "seconds", "s" -> number.toInt()
                else -> 0
            }
        }

        return totalSeconds
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