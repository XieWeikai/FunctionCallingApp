package com.example.functioncall

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.functioncall.ui.theme.FunctionCallTheme

import com.example.functioncall.FunctionCallService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions

private const val TAG = "MainActivity"

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
        latch.countDown()
    }

    fun content(mime: String): String{
        getContent.launch(mime)
        latch.await()
        latch = CountDownLatch(1)
        return uri
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

//        Intent(this, FunctionCallService::class.java).also {
//            startService(it)
//            Log.d(TAG, "FunctionCallService started")
//        }
    }


    fun add(a: Int, b: Int): Int{
        return a + b
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
                        val postData = files["postData"]
                        Log.d("HTTP Server", "Received call: $postData")
                        val res = postData?.let { functions.execute(it) }
                        Log.d("Http Server", "call result: $res")
                        val objectMapper = jacksonObjectMapper()
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