package dev.jwise.rhubarb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
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

    override fun onStart() {
        super.onStart()
        (application as RexApp).run {
            foreground = true; acquire()
            // Chats closed when the app left the screen pick up again on the same connection.
            hub.value?.let { dev.jwise.rhubarb.agent.ChatFollowers.resumeAll(it) }
            // Android stops the service on an app update or force stop; bring it back if it's on.
            if (watchEnabled) runCatching { dev.jwise.rhubarb.service.WatchService.start(this@MainActivity) }
        }
    }
    override fun onStop() {
        (application as RexApp).run {
            foreground = false
            // Chat helpers don't run on the Mac while Rhubarb is off screen (even if alerts keep the
            // connection open); release() also waits for them before dropping the connection.
            hub.value?.let { h -> scope.launch { dev.jwise.rhubarb.agent.ChatFollowers.closeAll(h) } }
            release()
        }
        super.onStop()
    }
}

@Composable
private fun AppNav(deepLink: MutableStateFlow<Pair<String, String>?>) {
    val app = RexApp.instance
    val nav = rememberNavController()
    var hasServer by remember { mutableStateOf(app.serverUrl != null) }
    val link by deepLink.collectAsState()

    // Tablets and unfolded foldables (not a phone on its side): Home and the open pane side by side.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = cfg.screenWidthDp >= 700 && cfg.screenHeightDp >= 600
    var selSession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selBlock by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    fun openPane(s: String, b: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
        if (wide) {
            selSession = s; selBlock = b
            // A pane opened full screen (before the window widened) gives way to the split view.
            if (nav.currentDestination?.route != "home") nav.popBackStack("home", inclusive = false)
        } else nav.navigate("term/${android.net.Uri.encode(s)}/${android.net.Uri.encode(b)}", builder)
    }
    // Narrowed (folded, or split-screen) with a pane open beside Home: open it full screen instead.
    LaunchedEffect(wide) {
        val s = selSession; val b = selBlock
        if (!wide && s != null && b != null) {
            selSession = null; selBlock = null
            nav.navigate("term/${android.net.Uri.encode(s)}/${android.net.Uri.encode(b)}")
        }
    }

    LaunchedEffect(link, hasServer) {
        val l = link ?: return@LaunchedEffect
        if (hasServer) {
            openPane(l.first, l.second) { launchSingleTop = true }
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
                openPane(sid, bid) { popUpTo("home") }
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

    // Material's shared-axis motion: forward slides in from the right, back reverses it. The back
    // gesture scrubs through the same animation (predictive back).
    // Springs from the theme's Expressive motion scheme, so screens move like the components do.
    val ease = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fade = androidx.compose.material3.MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    NavHost(
        nav, startDestination = if (hasServer) "home" else "server",
        enterTransition = { androidx.compose.animation.slideInHorizontally(ease) { it / 6 } + androidx.compose.animation.fadeIn(fade) },
        exitTransition = { androidx.compose.animation.slideOutHorizontally(ease) { -it / 6 } + androidx.compose.animation.fadeOut(fade) },
        popEnterTransition = { androidx.compose.animation.slideInHorizontally(ease) { -it / 6 } + androidx.compose.animation.fadeIn(fade) },
        popExitTransition = { androidx.compose.animation.slideOutHorizontally(ease) { it / 6 } + androidx.compose.animation.fadeOut(fade) },
    ) {
        composable("server") {
            ServerScreen(onSaved = {
                hasServer = true
                if (!nav.popBackStack("home", inclusive = false)) {
                    nav.navigate("home") { popUpTo("server") { inclusive = true } }
                }
            })
        }
        composable("home") {
            val home = @Composable {
                HomeScreen(
                    onOpen = { s, b -> openPane(s, b) },
                    onSettings = { nav.navigate("settings") },
                    onAddServer = { serverSheet = "add" },
                    selected = selBlock.takeIf { wide },
                )
            }
            if (!wide) home()
            else androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(400.dp).fillMaxHeight()) { home() }
                androidx.compose.material3.VerticalDivider()
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).fillMaxHeight()) {
                    val s = selSession; val b = selBlock
                    if (s != null && b != null) androidx.compose.runtime.key(b) {
                        TerminalScreen(sessionId = s, blockId = b, onBack = { selSession = null; selBlock = null })
                    } else EmptyDetail()
                }
            }
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

/** The right side of the split view before a pane is picked. */
@Composable
private fun EmptyDetail() {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(dev.jwise.rhubarb.ui.Sym.Terminal, null, androidx.compose.ui.Modifier.size(48.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
            androidx.compose.material3.Text("Pick a pane to open it here", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

