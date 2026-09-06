package com.sulav.proxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.OutputStreamWriter

data class Traffic(
    val method: String,
    val endpoint: String,
    val status: Int,
    val requestHex: String,
    val responseHex: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SulavProxyApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SulavProxyApp() {
    val context = LocalContext.current

    var url by remember { mutableStateOf("http://127.0.0.1:9000/") }
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8080") }
    var running by remember { mutableStateOf(false) }
    var showHex by remember { mutableStateOf(false) }
    var traffic by remember { mutableStateOf(listOf<Traffic>()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val proxyServer = remember { mutableStateOf<ProxyServer?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer ->
                        writer.write("""{"serverLoginUrl":"http://$host:$port/"}""")
                    }
                }
                statusMessage = "Exported localconfig"
            } catch (e: Exception) {
                statusMessage = "Export failed: ${e.message}"
            }
        }
    }

    fun startProxy() {
        val portNum = port.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            statusMessage = "Invalid port"
            return
        }
        val server = ProxyServer(
            listenHost = host,
            listenPort = portNum,
            targetUrl = url,
            onEvent = { event ->
                traffic = traffic + Traffic(
                    method = event.method,
                    endpoint = event.endpoint,
                    status = event.status,
                    requestHex = event.requestHex,
                    responseHex = event.responseHex
                )
            },
            onError = { message ->
                statusMessage = message
            }
        )
        server.start()
        proxyServer.value = server
        running = true
        statusMessage = "Listening on $host:$port -> $url"
    }

    fun stopProxy() {
        proxyServer.value?.stop()
        proxyServer.value = null
        running = false
        statusMessage = "Stopped"
    }

    DisposableEffect(Unit) {
        onDispose { proxyServer.value?.stop() }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF090B10),
            surface = Color(0xFF11141B),
            primary = Color(0xFF7C5CFF)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sulav Proxy") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0D1016)
                    )
                )
            },
            snackbarHost = {
                statusMessage?.let {
                    Snackbar(modifier = Modifier.padding(8.dp)) { Text(it) }
                }
            }
        ) { pad ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Proxy Configuration", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Forward requests to (target URL)") },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Listen Host") },
                            enabled = !running,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it },
                            label = { Text("Listen Port") },
                            enabled = !running,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (running) stopProxy() else startProxy() },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (running) "Stop Proxy" else "Start Proxy") }
                        OutlinedButton(
                            enabled = !running,
                            onClick = {
                                url = "http://127.0.0.1:9000/"
                                host = "127.0.0.1"
                                port = "8080"
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reset") }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("LocalConfig", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("""{"serverLoginUrl":"http://$host:$port/"}""")
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { exportLauncher.launch("localconfig.json") }) {
                                Text("Export localconfig")
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text("All Traffic (${traffic.size})") }
                        )
                        FilterChip(
                            selected = showHex,
                            onClick = { showHex = !showHex },
                            label = { Text("HEX") }
                        )
                        OutlinedButton(onClick = { traffic = emptyList() }) {
                            Text("Clear All")
                        }
                    }
                }
                items(traffic.reversed()) { item ->
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("${item.method}  ${item.endpoint}")
                            Text("Status: ${item.status}")
                            if (showHex) {
                                Spacer(Modifier.height(6.dp))
                                Text("Request HEX: ${item.requestHex}")
                                Text("Response HEX: ${item.responseHex}")
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Owner", style = MaterialTheme.typography.titleMedium)
                            Text("Telegram: @sulavji")
                            Text("YouTube: @ExeSasukeYT")
                        }
                    }
                }
            }
        }
    }
}
