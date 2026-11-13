package dev.jwise.rhubarb

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.jwise.rhubarb.service.Notifier
import dev.jwise.rhubarb.ui.HomeScreen
import dev.jwise.rhubarb.ui.IncomingShare
import dev.jwise.rhubarb.ui.ShareInbox
import dev.jwise.rhubarb.ui.ShareTargetSheet
import dev.jwise.rhubarb.ui.RexTheme
import dev.jwise.rhubarb.ui.SettingsScreen
import dev.jwise.rhubarb.ui.AddServerSheet
import dev.jwise.rhubarb.ui.ServerScreen
import dev.jwise.rhubarb.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val deepLink = MutableStateFlow<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { RexTheme { AppNav(deepLink) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val s = i?.getStringExtra(Notifier.EXTRA_SESSION); val b = i?.getStringExtra(Notifier.EXTRA_BLOCK)
        if (s != null && b != null) deepLink.value = s to b
        IncomingShare.from(i)?.let { ShareInbox.waiting.value = it }
    }

    override fun onStart() { super.onStart(); (application as RexApp).run { foreground = true; acquire() } }
    override fun onStop() { (application as RexApp).run { foreground = false; release() }; super.onStop() }
}

@Composable
private fun AppNav(deepLink: MutableStateFlow<Pair<String, String>?>) {
    val app = RexApp.instance
    val nav = rememberNavController()
    var hasServer by remember { mutableStateOf(app.serverUrl != null) }
    val link by deepLink.collectAsState()

    LaunchedEffect(link, hasServer) {
        val l = link ?: return@LaunchedEffect
        if (hasServer) {
            nav.navigate("term/${l.first}/${l.second}") { launchSingleTop = true }
            deepLink.value = null
        }
    }

    // Something shared from another app: pick a tab once there's a server, then open it there.
    val share by ShareInbox.waiting.collectAsState()
    share?.takeIf { hasServer }?.let { sh ->
        ShareTargetSheet(
            sh,
            onPick = { sid, bid ->
                ShareInbox.deliver(bid, sh)
                nav.navigate("term/$sid/$bid") { popUpTo("home") }
            },
            onDismiss = { ShareInbox.waiting.value = null },
        )
    }

    // "add" from Home's host list, "change" from Settings; null when the sheet is closed.
    var serverSheet by remember { mutableStateOf<String?>(null) }
    serverSheet?.let { mode ->
        AddServerSheet(
            title = if (mode == "change") "Change server" else "Add server",
            initialUrl = if (mode == "change") RexApp.instance.serverUrl.orEmpty() else "",
            onDismiss = { serverSheet = null },
            onSaved = {
                serverSheet = null
                hasServer = true
                nav.popBackStack("home", inclusive = false)
            },
        )
    }

    NavHost(nav, startDestination = if (hasServer) "home" else "server") {
        composable("server") {
            ServerScreen(onSaved = {
                hasServer = true
                if (!nav.popBackStack("home", inclusive = false)) {
                    nav.navigate("home") { popUpTo("server") { inclusive = true } }
                }
            })
        }
        composable("home") {
            HomeScreen(
                onOpen = { s, b -> nav.navigate("term/$s/$b") },
                onSettings = { nav.navigate("settings") },
                onAddServer = { serverSheet = "add" },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, onChangeServer = { serverSheet = "change" })
        }
        composable("term/{sid}/{bid}") { e ->
            TerminalScreen(
                sessionId = e.arguments!!.getString("sid")!!,
                blockId = e.arguments!!.getString("bid")!!,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
