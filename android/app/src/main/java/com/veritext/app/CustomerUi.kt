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
            // Saved contacts always come through: never show a scam warning for them.
            val contact = Contacts.nameFor(ctx, addr)
            val ms = msgs.map { m ->
                val r = results[m.id]
                Msg(m, if (contact != null && r?.decision == Decision.SUSPICIOUS) VerifyResult(Decision.UNKNOWN, "saved_contact") else r)
            }
            // Strangers and flagged messages count toward the counter (only ones newer than when counting began).
            if (contact == null) ms.forEach { m ->
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
    onSettings: () -> Unit,
    onSwitchRole: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
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
        if (conversations.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (loading) "Reading your messages…" else "No text messages yet.\nVerified businesses will show up here with a blue check.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(conversations, key = { it.address }) { c ->
                    ConversationRow(c) { onOpen(c.address) }
                }
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
                if (r?.decision == Decision.SUSPICIOUS) WarnBadge(15.dp)
            }
            val preview = if (r?.decision == Decision.VERIFIED) Tokens.strip(last.sms.body) else last.sms.body
            Text(preview.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r?.decision == Decision.SUSPICIOUS) Text(reasonText(r.reason), fontSize = 12.sp, color = dangerColor(), fontWeight = FontWeight.SemiBold)
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
            val newest = c?.latest?.result
            if (newest?.decision == Decision.SUSPICIOUS) {
                Surface(color = dangerColor().copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        WarnBadge(22.dp)
                        Text(
                            "This number belongs to ${c.ownerName ?: "a verified business"}, but the latest message could not be verified. " +
                                "It may be a scam — don't tap links or share codes.",
                            fontSize = 13.sp, color = dangerColor(),
                        )
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(msgs, key = { it.sms.id }) { m -> Bubble(m, ctx) }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, ctx: android.content.Context) {
    val r = m.result
    val verified = r?.decision == Decision.VERIFIED
    val suspicious = r?.decision == Decision.SUSPICIOUS
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
                if (r?.decision != Decision.UNKNOWN && (r != null || relevant)) StatusChip(r, retryHint = r == null)
                Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(
            formatWhen(ctx, m.sms.date), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 3.dp),
        )
    }
}
