package com.veritext.app

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Msg(val sms: Sms, val result: VerifyResult?)

data class Conversation(
    val address: String,
    val title: String,
    val ownerName: String?,
    val color: Int?,
    val domain: String?,
    val contactName: String? = null, // set when the sender is saved in the user's Contacts
    val blocked: Boolean = false,    // untrusted stranger or flagged scam: warned, listed under Blocked, counted
    val messages: List<Msg>, // newest first
) {
    val latest get() = messages.first()
}

class InboxViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private var generation = 0

    init {
        viewModelScope.launch { Bus.tick.collect { delay(1200); refresh() } }
    }

    fun refresh() {
        val gen = ++generation
        viewModelScope.launch {
            _loading.value = true
            Verifier.refreshDirectory(ctx)
            val inbox = withContext(Dispatchers.IO) { SmsRepo.readInbox(ctx) }

            // First pass: whatever is already cached or needs no network. Second pass: ask the server.
            val results = HashMap<Long, VerifyResult?>()
            for (m in inbox) {
                results[m.id] = Verifier.cached(ctx, m.address, m.body)
                    ?: if (!Verifier.relevant(ctx, m.address, m.body)) VerifyResult(Decision.UNKNOWN, "not_in_registry") else null
            }
            if (gen == generation) _conversations.value = build(inbox, results)

            for (m in inbox) {
                if (results[m.id] != null) continue
                results[m.id] = Verifier.verify(ctx, m.address, m.body)
                if (gen != generation) return@launch
                _conversations.value = build(inbox, results)
            }
            if (gen == generation) _loading.value = false
        }
    }

    private fun build(inbox: List<Sms>, results: Map<Long, VerifyResult?>): List<Conversation> =
        inbox.groupBy { Tokens.normalize(it.address) }.values.map { msgs ->
            val addr = msgs.first().address
            // Saved contacts and allowed senders always come through: never warn, never block, never count.
            val contact = Contacts.nameFor(ctx, addr)
            val trusted = contact != null || Allowlist.has(ctx, addr)
            val ms = msgs.map { m ->
                val r = results[m.id]
                Msg(m, if (trusted && r?.decision == Decision.SUSPICIOUS) VerifyResult(Decision.UNKNOWN, "trusted_sender") else r)
            }
            // One rule drives the counter, the Blocked list and the thread warning.
            val latestDecision = ms.first().result?.decision
            val blocked = !trusted && latestDecision != null && latestDecision != Decision.VERIFIED &&
                (latestDecision == Decision.SUSPICIOUS || Contacts.hasPermission(ctx))
            if (!trusted) ms.forEach { m ->
                val d = m.result?.decision
                if (d != null && d != Decision.VERIFIED && (d == Decision.SUSPICIOUS || Contacts.hasPermission(ctx))) {
                    Blocked.record(ctx, m.sms.address, m.sms.body, m.sms.date)
                }
            }
            val verifiedOrg = ms.firstNotNullOfOrNull { it.result?.takeIf { r -> r.decision == Decision.VERIFIED } }
            val dir = Verifier.ownerOf(ctx, addr)
            val name = verifiedOrg?.orgName ?: dir?.takeIf { it.status == "approved" }?.name
            Conversation(
                address = addr,
                title = contact ?: name ?: addr,
                contactName = contact,
                blocked = blocked,
                ownerName = name,
                color = verifiedOrg?.color,
                domain = verifiedOrg?.domain,
                messages = ms,
            )
        }.sortedByDescending { it.latest.sms.date }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: InboxViewModel,
    onOpen: (String) -> Unit,
    onOpenBlocked: () -> Unit,
    onSettings: () -> Unit,
    onSwitchRole: () -> Unit,
) {
    val allConversations by vm.conversations.collectAsState()
    val conversations = allConversations.filter { !it.blocked }
    val blockedThreads = allConversations.count { it.blocked }
    val loading by vm.loading.collectAsState()
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Messages", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                        val caught by Blocked.count(LocalContext.current).collectAsState()
                        ScamCounter(caught)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    TextButton(onClick = { vm.refresh() }) { Text(if (loading) "Checking…" else "Refresh") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Server settings") }, onClick = { menu = false; onSettings() })
                            DropdownMenuItem(text = { Text("Switch role") }, onClick = { menu = false; onSwitchRole() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        val appCtx = LocalContext.current
        var listenerOn by remember { mutableStateOf(NotificationAccess.isEnabled(appCtx)) }
        var later by remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { listenerOn = NotificationAccess.isEnabled(appCtx) }

        Column(Modifier.fillMaxSize().padding(pad)) {
            if (blockedThreads > 0) BlockedRow(blockedThreads, onOpenBlocked)
            if (!listenerOn && !later) {
                AccessBanner(onEnable = { NotificationAccess.openSettings(appCtx) }, onLater = { later = true })
            }
            if (conversations.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading) "Reading your messages…" else "No trusted messages yet.\nVerified businesses and saved contacts show up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(conversations, key = { it.address }) { c ->
                        ConversationRow(c) { onOpen(c.address) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(threads: Int, onClick: () -> Unit) {
    // Small pill, not a banner: stays out of the way of the real conversations.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Surface(
            color = dangerColor().copy(alpha = 0.12f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.clickable(onClick = onClick),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WarnBadge(14.dp)
                Text("$threads blocked", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dangerColor(), maxLines = 1, softWrap = false)
                Text("›", fontSize = 15.sp, color = dangerColor())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedScreen(vm: InboxViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val blocked = vm.conversations.collectAsState().value.filter { it.blocked }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back", fontSize = 16.sp) } },
                title = { Text("Blocked", fontWeight = FontWeight.Bold, color = dangerColor()) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        if (blocked.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nothing blocked.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(blocked, key = { it.address }) { c -> ConversationRow(c) { onOpen(c.address) } }
            }
        }
    }
}

@Composable
private fun AccessBanner(onEnable: () -> Unit, onLater: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Catch every scam text", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Chat-style (RCS) texts skip the SMS inbox. Turn on notification access so the counter can see who texted. " +
                    "VeriText reads only the sender, never the message.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onLater) { Text("Later") }
                TextButton(onClick = onEnable) { Text("Turn on", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val last = c.latest
    val r = last.result
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(c.title, if (c.ownerName != null) Color(c.color ?: 0xFFE20074.toInt()) else MaterialTheme.colorScheme.onSurfaceVariant, 48.dp, 20.sp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(c.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r?.decision == Decision.VERIFIED) VerifiedBadge(15.dp)
                if (c.blocked) WarnBadge(15.dp)
            }
            val preview = if (r?.decision == Decision.VERIFIED) Tokens.strip(last.sms.body) else last.sms.body
            Text(preview.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.blocked) Text(
                if (r?.decision == Decision.SUSPICIOUS) reasonText(r.reason) else "Blocked · unknown sender",
                fontSize = 12.sp, color = dangerColor(), fontWeight = FontWeight.SemiBold,
            )
        }
        Text(formatWhen(ctx, last.sms.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(vm: InboxViewModel, address: String, onBack: () -> Unit) {
    val all by vm.conversations.collectAsState()
    val ctx = LocalContext.current
    val norm = Tokens.normalize(address)
    val c = all.firstOrNull { Tokens.normalize(it.address) == norm }
    val msgs = c?.messages?.reversed().orEmpty() // oldest first
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back", fontSize = 16.sp) } },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c?.title ?: address, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (c?.messages?.any { it.result?.decision == Decision.VERIFIED } == true) VerifiedBadge(16.dp)
                        }
                        val sub = when {
                            c?.domain != null -> "${c.domain} · Verified business"
                            c?.ownerName != null -> "Verified business · ${c.address}"
                            c?.contactName != null -> "Saved contact · ${c.address}"
                            else -> c?.address ?: address
                        }
                        Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (c?.blocked == true) {
                Surface(color = dangerColor().copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            WarnBadge(22.dp)
                            Text(
                                if (c.latest.result?.decision == Decision.SUSPICIOUS && c.ownerName != null)
                                    "This number belongs to ${c.ownerName}, but the latest message could not be verified. It may be a scam. Don't tap links or share codes."
                                else
                                    "Blocked: this sender isn't a saved contact or a verified business. Don't tap links or share codes.",
                                fontSize = 13.sp, color = dangerColor(),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { Allowlist.add(ctx, c.address); vm.refresh() }) {
                                Text("Not a scam. Allow sender", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(msgs, key = { it.sms.id }) { m -> Bubble(m, ctx, c?.blocked == true) }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, ctx: android.content.Context, blocked: Boolean) {
    val r = m.result
    val verified = r?.decision == Decision.VERIFIED
    val suspicious = r?.decision == Decision.SUSPICIOUS || (blocked && r?.decision == Decision.UNKNOWN)
    val text = if (verified) Tokens.strip(m.sms.body) else m.sms.body
    val bg = when {
        suspicious -> dangerColor().copy(alpha = 0.10f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val relevant = Verifier.relevant(ctx, m.sms.address, m.sms.body)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 5.dp),
            color = bg,
            border = if (suspicious) BorderStroke(1.dp, dangerColor()) else null,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (blocked && r?.decision == Decision.UNKNOWN) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WarnBadge(14.dp)
                        Text("Blocked · unknown sender", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = dangerColor())
                    }
                } else if (r?.decision != Decision.UNKNOWN && (r != null || relevant)) StatusChip(r, retryHint = r == null)
                Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(
            formatWhen(ctx, m.sms.date), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 3.dp),
        )
    }
}
