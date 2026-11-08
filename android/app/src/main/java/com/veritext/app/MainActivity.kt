package com.veritext.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var openAddress by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openAddress = intent?.getStringExtra("address")
        setContent {
            VeriTextTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    Root(openAddress, onConsumedAddress = { openAddress = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openAddress = intent.getStringExtra("address")
    }
}

private enum class Screen { SETUP, MAIN, SETTINGS }

@Composable
private fun Root(deepLinkAddress: String?, onConsumedAddress: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var role by remember { mutableStateOf(prefs.role) }
    var screen by remember { mutableStateOf(if (prefs.role == null) Screen.SETUP else Screen.MAIN) }

    when {
        screen == Screen.SETTINGS -> {
            BackHandler { screen = if (role == null) Screen.SETUP else Screen.MAIN }
            SettingsScreen(onDone = { screen = if (role == null) Screen.SETUP else Screen.MAIN })
        }
        role == null || screen == Screen.SETUP -> SetupScreen(
            onPick = { r -> prefs.role = r; role = r; screen = Screen.MAIN },
        )
        role == "enterprise" -> {
            EnterpriseScreen(
                onSettings = { screen = Screen.SETTINGS },
                onSwitchRole = { prefs.role = null; role = null; screen = Screen.SETUP },
            )
        }
        else -> CustomerHome(
            deepLinkAddress, onConsumedAddress,
            onSettings = { screen = Screen.SETTINGS },
            onSwitchRole = { prefs.role = null; role = null; screen = Screen.SETUP },
        )
    }
}

@Composable
private fun CustomerHome(deepLink: String?, onConsumed: () -> Unit, onSettings: () -> Unit, onSwitchRole: () -> Unit) {
    val ctx = LocalContext.current
    val vm: InboxViewModel = viewModel()
    var granted by remember { mutableStateOf(SmsRepo.hasReadPermission(ctx)) }
    var openThread by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[Manifest.permission.READ_SMS] == true || SmsRepo.hasReadPermission(ctx)
        if (granted) vm.refresh()
    }

    // Ask once for Contacts so saved numbers always come through.
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    LaunchedEffect(granted) {
        val prefs = Prefs(ctx)
        if (granted && !Contacts.hasPermission(ctx) && prefs.string("contactsAsked") == null) {
            prefs.putString("contactsAsked", "1")
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    LaunchedEffect(deepLink) { if (deepLink != null) { openThread = deepLink; onConsumed() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = SmsRepo.hasReadPermission(ctx)
        if (granted) vm.refresh()
    }

    if (!granted) {
        PermissionGate {
            val perms = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(perms.toTypedArray())
        }
        return
    }

    val t = openThread
    if (t != null) {
        BackHandler { openThread = null }
        ThreadScreen(vm, t, onBack = { openThread = null })
    } else {
        InboxScreen(vm, onOpen = { openThread = it }, onSettings = onSettings, onSwitchRole = onSwitchRole)
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VerifiedBadge(64.dp)
        Spacer(Modifier.height(20.dp))
        Text("Verify your texts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "VeriText reads incoming SMS so it can check whether a business that texts you is really who it says it is. " +
                "Only messages from registered businesses or carrying a VeriText code are sent to the registry — everything else stays on your phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Allow SMS access") }
    }
}

@Composable
private fun SetupScreen(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(24.dp))
        VerifiedBadge(56.dp)
        Text("VeriText", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Texts from real businesses, with a check you can trust.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        ServerField()
        Spacer(Modifier.height(8.dp))
        RoleCard("I'm a customer", "Get a verified inbox. Real businesses show a verified check; imitators get flagged.") {
            Prefs(ctx).role = "customer"; onPick("customer")
        }
        RoleCard("I'm a business (demo)", "Apply for verification, then send verified texts — and simulate a scammer.") {
            Prefs(ctx).role = "enterprise"; onPick("enterprise")
        }
    }
}

@Composable
private fun RoleCard(title: String, body: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Server URL editor with a live reachability check. */
@Composable
private fun ServerField() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var status by remember { mutableStateOf("Checking…") }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(url) {
        ok = null; status = "Checking…"
        delay(500)
        try {
            withContext(Dispatchers.IO) { Api.health(url.trim()) }
            prefs.serverUrl = url
            ok = true; status = "Connected to registry"
        } catch (e: Exception) {
            ok = false; status = "Can't reach registry — same Wi-Fi? Server running?"
        }
    }
    OutlinedTextField(
        url, { url = it }, label = { Text("Registry server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        supportingText = { Text(status, color = when (ok) { true -> verifiedColor(); false -> dangerColor(); null -> MaterialTheme.colorScheme.onSurfaceVariant }) },
    )
}

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Server settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        ServerField()
        Row { TextButton(onClick = onDone) { Text("Done") } }
    }
}
