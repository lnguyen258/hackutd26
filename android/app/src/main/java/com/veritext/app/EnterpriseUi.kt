package com.veritext.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val SAMPLE = "Your data usage exceeds monthly limit, add more credit to continue using service."
private const val TAMPERED = "Your account is suspended. Pay now at http://t-mobile-billing.example to restore service."

private val BRAND_COLORS = listOf("#E20074", "#0A66C2", "#15803D", "#C2410C", "#334155")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnterpriseScreen(onSettings: () -> Unit, onSwitchRole: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var apiKey by remember { mutableStateOf(prefs.apiKey) }
    var org by remember { mutableStateOf<OrgInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Poll our verification status (pending -> approved happens on the admin console).
    LaunchedEffect(apiKey) {
        val key = apiKey ?: return@LaunchedEffect
        while (true) {
            try {
                org = withContext(Dispatchers.IO) { Api.me(prefs.serverUrl, key) }
                error = null
            } catch (e: ApiException) {
                if (e.status == 401) { // the registry was reset
                    prefs.apiKey = null; apiKey = null; org = null
                    error = "The registry no longer knows this business (it was reset). Please apply again."
                    return@LaunchedEffect
                }
                error = e.message
            } catch (_: IOException) {
                error = "Can't reach the registry at ${prefs.serverUrl}"
            }
            delay(if (org?.status == "pending") 1500 else 4000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Business console", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    TextButton(onClick = onSettings) { Text("Server") }
                    TextButton(onClick = onSwitchRole) { Text("Role") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val o = org
            when {
                apiKey == null -> ApplyForm(error) { key -> prefs.apiKey = key; apiKey = key; org = null; error = null }
                o == null -> Centered { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(error ?: "Contacting registry…") }
                o.status == "pending" -> PendingCard(o)
                o.status == "approved" -> Console(prefs, apiKey!!, o)
                else -> RejectedCard(o) { prefs.apiKey = null; apiKey = null; org = null }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun ApplyForm(error: String?, onApplied: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("T-Mobile") }
    var domain by remember { mutableStateOf("t-mobile.com") }
    var number by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(BRAND_COLORS[0]) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf(error) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Get your business verified", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Apply for the blue check. Once the registry approves you, texts you send show up as verified on customers' phones — and anyone imitating your number without a valid code is flagged.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Business name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(domain, { domain = it }, label = { Text("Website domain") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(
            number, { number = it }, label = { Text("This phone's number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("The number customers receive texts from, e.g. +1 214 555 0100") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Text("Brand color", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BRAND_COLORS.forEach { hex ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(parseColor(hex)))
                        .clickable { color = hex }
                        .then(if (color == hex) Modifier.padding(0.dp) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { if (color == hex) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        err?.let { Text(it, color = dangerColor()) }
        Button(
            enabled = !busy && name.isNotBlank() && domain.isNotBlank() && number.filter { it.isDigit() }.length >= 10,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                busy = true; err = null
                scope.launch {
                    try {
                        val key = withContext(Dispatchers.IO) { Api.apply(prefs.serverUrl, name.trim(), domain.trim(), color, number.trim()) }
                        onApplied(key)
                    } catch (e: ApiException) {
                        err = e.message
                    } catch (_: IOException) {
                        err = "Can't reach the registry at ${prefs.serverUrl}"
                    } finally { busy = false }
                }
            },
        ) { Text(if (busy) "Submitting…" else "Apply for verification") }
    }
}

@Composable
private fun PendingCard(o: OrgInfo) {
    Centered {
        Avatar(o.name, Color(o.color), 72.dp, 30.sp)
        Spacer(Modifier.height(16.dp))
        Text(o.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(o.domain, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text("Application under review", fontWeight = FontWeight.SemiBold)
        Text(
            "The registry operator is checking your business. This screen updates automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RejectedCard(o: OrgInfo, onRestart: () -> Unit) {
    Centered {
        WarnBadge(48.dp)
        Spacer(Modifier.height(14.dp))
        Text("${o.name} is ${o.status}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Your messages will no longer show as verified.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRestart) { Text("Start a new application") }
    }
}

@Composable
private fun Console(prefs: Prefs, apiKey: String, org: OrgInfo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(SAMPLE) }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingAction?.invoke() else log.add(0, "✗ SMS permission denied")
        pendingAction = null
    }

    fun run(label: String, block: suspend () -> String) {
        val go = {
            busy = true
            scope.launch {
                try {
                    log.add(0, "✓ $label — ${withContext(Dispatchers.IO) { block() }}")
                } catch (e: ApiException) {
                    log.add(0, "✗ $label — ${e.message}")
                } catch (e: IOException) {
                    log.add(0, "✗ $label — can't reach registry")
                } catch (e: Exception) {
                    log.add(0, "✗ $label — ${e.message}")
                } finally { busy = false }
            }
            Unit
        }
        if (SmsSender.hasPermission(ctx)) go() else { pendingAction = go; permLauncher.launch(Manifest.permission.SEND_SMS) }
    }

    val dest = to.trim()
    val ready = dest.filter { it.isDigit() }.length >= 7 && text.isNotBlank() && !busy

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Identity card
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(org.name, Color(org.color), 48.dp)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(org.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        VerifiedBadge(18.dp)
                    }
                    Text("${org.domain} · sending as ${org.senders.joinToString()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedTextField(
            to, { to = it }, label = { Text("Customer's phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        OutlinedTextField(text, { text = it }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())

        Button(
            enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                run("Verified message sent to $dest") {
                    val body = Api.issueToken(prefs.serverUrl, apiKey, text.trim())
                    SmsSender.send(ctx, dest, body)
                    "code ${Tokens.code(body)}"
                }
            },
        ) { Text("Send verified message") }

        Text("Attack simulations (from this same number)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(
            enabled = ready, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = dangerColor()),
            onClick = {
                run("Spoof (no code) sent to $dest") {
                    SmsSender.send(ctx, dest, text.trim())
                    "customer should see a warning"
                }
            },
        ) { Text("Scammer: same text, no verification code") }
        OutlinedButton(
            enabled = ready, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = dangerColor()),
            onClick = {
                run("Tampered message sent to $dest") {
                    val real = Api.issueToken(prefs.serverUrl, apiKey, text.trim())
                    SmsSender.send(ctx, dest, "$TAMPERED\n\nvt:${Tokens.code(real)}")
                    "real code, different text"
                }
            },
        ) { Text("Scammer: reuse a real code on a phishing text") }

        if (log.isNotEmpty()) {
            Text("Activity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            log.take(12).forEach { Text(it, fontSize = 13.sp, color = if (it.startsWith("✗")) dangerColor() else MaterialTheme.colorScheme.onSurface) }
        }
    }
}
