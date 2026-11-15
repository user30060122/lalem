package com.example.lalem

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val updates by lazy { ViewModelProvider(this)[AppUpdateViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[LalemViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = colors) {
                LalemApp(model, updates)
                UpdatePrompt(updates)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updates.check()
    }
}

private val colors = lightColorScheme(
    primary = Color(0xFF28674F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0DD),
    onPrimaryContainer = Color(0xFF153B2B),
    secondary = Color(0xFF8A5433),
    secondaryContainer = Color(0xFFFFE7CD),
    onSecondaryContainer = Color(0xFF442715),
    background = Color(0xFFF8F6EF),
    onBackground = Color(0xFF20312A),
    surface = Color(0xFFFFFCF7),
    onSurface = Color(0xFF20312A),
    surfaceVariant = Color(0xFFEEEFE6),
    onSurfaceVariant = Color(0xFF505E53),
    outline = Color(0xFF747F72)
)
private val dateFormat = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val dateTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private fun localTime(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())

@Composable
private fun LalemApp(model: LalemViewModel, updates: AppUpdateViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let {
            snack.showSnackbar(it)
            model.dismissMessage()
        }
    }
    val user = model.user
    val owner = model.recordOwner
    if (owner == null) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp)) {
                AuthScreen(model)
            }
        }
        return
    }
    var tab by rememberSaveable(owner) { mutableIntStateOf(0) }
    var detailId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val detail = user?.let { model.links.firstOrNull { it.objectId == detailId && it.status == LalemRepository.ACCEPTED } }
    BackHandler(detail != null) { detailId = null }
    val labels = listOf("打卡", "朋友", "统计", "我的")
    val icons = listOf("◉", "♧", "▥", "◎")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index; detailId = null; if (index == 1) model.refresh() },
                        icon = { Text(icons[index], fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            key(tab, detailId) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (detail != null) {
                        FriendDetail(detail, user.objectId) { detailId = null }
                    } else when (tab) {
                        0 -> CheckInScreen(model)
                        1 -> if (user != null) FriendsScreen(model, user.objectId) { detailId = it }
                            else OfflineAccountScreen("朋友", model)
                        2 -> StatisticsScreen(model.records)
                        3 -> if (user != null) ProfileScreen(model, updates) else OfflineAccountScreen("我的", model)
                    }
                }
            }
        }
    }
    if (model.award && updates.available == null) {
        AlertDialog(
            onDismissRequest = model::dismissAward,
            icon = { Trophy(Modifier.size(54.dp), Color(0xFFDC9F33)) },
            title = { Text("本周冠军") },
            text = { Text("你在好友周榜排名第一。") },
            confirmButton = { TextButton(onClick = model::dismissAward) { Text("收下") } }
        )
    }
}

@Composable
private fun UpdatePrompt(updates: AppUpdateViewModel) {
    val context = LocalContext.current
    LaunchedEffect(updates.feedback) {
        updates.feedback?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            updates.clearFeedback()
        }
    }
    val latest = updates.available ?: return
    AlertDialog(
        onDismissRequest = updates::closePrompt,
        title = { Text("发现新版本 ${latest.name}") },
        text = { Text("当前版本 ${BuildConfig.VERSION_NAME}。你可以前往蒲公英下载安装包。") },
        dismissButton = { TextButton(onClick = updates::ignoreVersion) { Text("忽略此版本") } },
        confirmButton = {
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PgyerInstallPage.URL)))
                    updates.closePrompt()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "无法打开蒲公英下载页", Toast.LENGTH_SHORT).show()
                }
            }) { Text("去下载") }
        }
    )
}

@Composable
private fun AuthScreen(model: LalemViewModel) {
    var registering by rememberSaveable { mutableStateOf(false) }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    Spacer(Modifier.height(28.dp))
    Panel(accent = true) {
        Text("💩", fontSize = 38.sp)
        Text("拉了么", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("记录日常，和朋友一起打卡", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(26.dp))
    Text(if (registering) "创建账号" else "登录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) },
        Modifier.fillMaxWidth(), label = { Text("手机号") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(password, { password = it.take(64) }, Modifier.fillMaxWidth(),
        label = { Text("密码") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    if (registering) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(nickname, { nickname = it.take(20) }, Modifier.fillMaxWidth(),
            label = { Text("昵称") }, singleLine = true)
    }
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { model.authenticate(phone, password, nickname.takeIf { registering }) },
        enabled = !model.busy && model.configured, modifier = Modifier.fillMaxWidth().height(50.dp)
    ) { Text(if (model.busy) "正在连接…" else if (registering) "注册并登录" else "登录") }
    TextButton(onClick = { registering = !registering }, enabled = !model.busy) {
        Text(if (registering) "已有账号，去登录" else "没有账号，去注册")
    }
    OutlinedButton(onClick = model::startGuest, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
        Text("先离线打卡")
    }
    Text("登录后可同步这部手机的离线打卡。", style = MaterialTheme.typography.bodySmall)
    if (!model.configured) Text("当前构建缺少 Bmob 配置", color = MaterialTheme.colorScheme.error)
}

@Composable
private fun OfflineAccountScreen(title: String, model: LalemViewModel) {
    PageTitle(title)
    Panel {
        Text("当前在本机打卡")
        Text("联网登录后，本机离线记录会归入该账号并同步。")
        Button(onClick = model::leaveGuest, enabled = !model.recordBusy) { Text("登录并同步") }
    }
}

@Composable
private fun CheckInScreen(model: LalemViewModel) {
    val today = LocalDate.now()
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val date = LocalDate.parse(dateText)
    val month = YearMonth.parse(monthText)
    var editor by remember { mutableStateOf<Entry?>(null) }
    PageTitle("打卡", today.format(dateFormat))
    Panel(accent = true) {
        Text("今天已打卡", style = MaterialTheme.typography.titleMedium)
        Text("${model.records.count { it.date == today }} 次", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(model.records.firstOrNull()?.let { "上次 " + localTime(it.time).format(dateTimeFormat) } ?: "还没有打卡记录")
        if (model.pendingCount > 0) {
            val status = if (model.user == null) "已保存在本机 · 登录后可同步" else "已保存在本机 · ${model.pendingCount} 条待同步"
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { editor = Entry("", System.currentTimeMillis(), RecordType.NORMAL, null) },
            enabled = !model.recordBusy, modifier = Modifier.fillMaxWidth()) { Text("现在打卡") }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { monthText = month.minusMonths(1).toString() }) { Text("‹ 上月") }
            Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { monthText = month.plusMonths(1).toString() }) { Text("下月 ›") }
        }
        MonthGrid(month, date, model.records.map { it.date }.toSet()) { dateText = it.toString() }
        TextButton(onClick = { dateText = today.toString(); monthText = YearMonth.from(today).toString() }) { Text("回到今天") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${date.format(dateFormat)}的记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(
            onClick = { editor = Entry("", date.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), RecordType.NORMAL, null) },
            enabled = !model.recordBusy && date <= today
        ) { Text("补记") }
    }
    val entries = model.records.filter { it.date == date }
    if (entries.isEmpty()) Panel { Text("这天还没有记录") }
    entries.forEach { entry -> RecordCard(entry) { if (!model.recordBusy) editor = entry } }
    editor?.let { entry ->
        EntryEditor(entry, model.recordBusy, { editor = null }, { saved -> model.save(saved) { editor = null } },
            { model.delete(entry) { editor = null } })
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, marked: Set<LocalDate>, onSelect: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val offset = month.atDay(1).dayOfWeek.value - 1
    repeat((month.lengthOfMonth() + offset + 6) / 7) { week ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { column ->
                val day = week * 7 + column - offset + 1
                if (day !in 1..month.lengthOfMonth()) {
                    Spacer(Modifier.weight(1f).height(42.dp))
                } else {
                    val date = month.atDay(day)
                    val active = date == selected
                    Column(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onSelect(date) }, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(day.toString(), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.size(4.dp).background(if (date in marked) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryEditor(entry: Entry, busy: Boolean, onClose: () -> Unit, onSave: (Entry) -> Unit, onDelete: () -> Unit) {
    var dateTime by remember(entry) { mutableStateOf(localTime(entry.time).toLocalDateTime()) }
    var type by remember(entry) { mutableStateOf(entry.type) }
    var weight by remember(entry) { mutableStateOf(entry.weight?.toString().orEmpty()) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    if (deleting) {
        AlertDialog(onDismissRequest = { if (!busy) deleting = false }, title = { Text("删除这条打卡？") },
            confirmButton = { TextButton(onClick = onDelete, enabled = !busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = false }, enabled = !busy) { Text("取消") } })
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (entry.id.isBlank()) "记录一次" else "编辑打卡") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        DatePickerDialog(context, { _, year, month, day ->
                            dateTime = LocalDateTime.of(LocalDate.of(year, month + 1, day), dateTime.toLocalTime())
                        }, dateTime.year, dateTime.monthValue - 1, dateTime.dayOfMonth).apply {
                            datePicker.maxDate = System.currentTimeMillis()
                        }.show()
                    }, enabled = !busy) { Text(dateTime.toLocalDate().format(dateFormat)) }
                    TextButton(onClick = {
                        TimePickerDialog(context, { _, hour, minute ->
                            dateTime = dateTime.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                        }, dateTime.hour, dateTime.minute, true).show()
                    }, enabled = !busy) { Text(dateTime.format(timeFormat)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    RecordType.entries.forEach { value ->
                        FilterChip(type == value, { type = value }, label = { Text(value.label) }, enabled = !busy)
                    }
                }
                OutlinedTextField(weight, { weight = it.take(10); error = null }, singleLine = true,
                    label = { Text("重量（斤，选填）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null, supportingText = { error?.let { Text(it) } })
                if (entry.id.isNotBlank()) TextButton(onClick = { deleting = true }, enabled = !busy) {
                    Text("删除记录", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val value = weight.trim().toDoubleOrNull()
                val time = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                when {
                    weight.isNotBlank() && (value == null || !value.isFinite() || value <= 0) -> error = "请输入大于 0 的重量"
                    time > System.currentTimeMillis() -> error = "打卡时间不能晚于现在"
                    else -> onSave(entry.copy(time = time, type = type, weight = value))
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun RecordCard(entry: Entry, onClick: (() -> Unit)? = null) {
    Panel(modifier = if (onClick == null) Modifier else Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localTime(entry.time).format(dateTimeFormat), Modifier.weight(1f))
            Text(entry.type.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        entry.weight?.let { Text("${it} 斤", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FriendsScreen(model: LalemViewModel, myId: String, onDetail: (String) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var weekly by rememberSaveable { mutableStateOf(true) }
    var deleting by remember { mutableStateOf<FriendLink?>(null) }
    PageTitle("朋友")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { adding = true }, enabled = !model.busy) { Text("添加好友") }
        OutlinedButton(onClick = model::refresh, enabled = !model.busy) { Text("刷新") }
    }
    val pending = model.links.filter { it.status == LalemRepository.PENDING }
    if (pending.isNotEmpty()) Text("好友申请", style = MaterialTheme.typography.titleLarge)
    pending.forEach { link ->
        Panel {
            Text(link.otherName(myId).ifBlank { "好友" }, fontWeight = FontWeight.SemiBold)
            Row {
                if (link.recipientId == myId) {
                    TextButton(onClick = { model.accept(link) }, enabled = !model.busy) { Text("接受并共享") }
                    TextButton(onClick = { model.remove(link) }, enabled = !model.busy) { Text("拒绝") }
                } else {
                    Text("等待对方接受", Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = { model.remove(link) }, enabled = !model.busy) { Text("撤回") }
                }
            }
        }
    }
    if (model.rankingEnabled) {
        Panel(accent = true) {
            Text("好友排行", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            PeriodChips(weekly, { weekly = it }, "本周", "本月")
            RankingPodium(model.rankings(weekly))
        }
    }
    val accepted = model.links.filter { it.status == LalemRepository.ACCEPTED }.distinctBy { it.otherId(myId) }
        .sortedByDescending { it.otherId(myId) in model.pinned }
    if (accepted.isEmpty()) Panel { Text("还没有好友", fontWeight = FontWeight.SemiBold); Text("用手机号和好友码发送申请。") }
    accepted.forEach { link ->
        val peer = link.otherId(myId)
        val records = link.otherRecords(myId)
        Panel {
            Text(link.otherName(myId).ifBlank { "好友" }, fontWeight = FontWeight.SemiBold)
            val count = records.count { it.date == LocalDate.now() }
            Text(if (count > 0) "今天已打卡 $count 次" else "今天还没有打卡")
            records.firstOrNull()?.let {
                val days = ChronoUnit.DAYS.between(it.date, LocalDate.now())
                Text(if (days <= 0) "上次打卡：今天" else "上次打卡：${days} 天前")
            }
            Row {
                TextButton(onClick = { onDetail(link.objectId) }) { Text("查看详情") }
                TextButton(onClick = { model.togglePin(peer) }) { Text(if (peer in model.pinned) "取消置顶" else "置顶") }
                TextButton(onClick = { deleting = link }, enabled = !model.busy) { Text("删除") }
            }
        }
    }
    if (adding) AddFriendDialog(model) { adding = false }
    deleting?.let { link ->
        AlertDialog(onDismissRequest = { if (!model.busy) deleting = null }, title = { Text("删除这位好友？") },
            text = { Text("双方将停止共享近期记录。") },
            confirmButton = { TextButton(onClick = { model.remove(link) { deleting = null } }, enabled = !model.busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !model.busy) { Text("取消") } })
    }
}

@Composable
private fun AddFriendDialog(model: LalemViewModel, onClose: () -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!model.busy) onClose() }, title = { Text("添加好友") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FriendTextField(
                    phone, { phone = it.filter { digit -> digit in '0'..'9' }.take(11) },
                    "好友手机号", KeyboardType.Phone, clipboardPhone
                )
                FriendTextField(
                    code, { code = it.uppercase().filter { char -> char in '0'..'9' || char in 'A'..'F' }.take(8) },
                    "8 位好友码", KeyboardType.Ascii, clipboardFriendCode
                )
                Text("对方接受后，双方可以查看彼此近期的打卡时间、状态和重量。")
            }
        },
        confirmButton = { TextButton(onClick = { model.request(phone, code, onClose) }, enabled = !model.busy) { Text("发送申请") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !model.busy) { Text("取消") } })
}

private val clipboardPhone = Regex("""(?:(?:好友)?手机号\s*[:：]\s*)?(?:\+86[ \t-]*)?(1[0-9]{2}[ \t-]*[0-9]{4}[ \t-]*[0-9]{4})""")
private val clipboardFriendCode = Regex("""(?:好友码\s*[:：]\s*)?([0-9a-fA-F]{8})""")

@Composable
private fun FriendTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    clipboardPattern: Regex
) {
    val context = LocalContext.current
    val interactions = remember { MutableInteractionSource() }
    val pasteOnTap by rememberUpdatedState {
        if (value.isEmpty()) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val match = clip.getItemAt(0).text?.lineSequence()
                    ?.mapNotNull { clipboardPattern.matchEntire(it.trim())?.groupValues?.get(1) }
                    ?.distinct()
                    ?.singleOrNull()
                if (match != null) onValueChange(match)
            }
        }
    }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) pasteOnTap()
        }
    }
    OutlinedTextField(
        value, onValueChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, interactionSource = interactions,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard)
    )
}

@Composable
private fun FriendDetail(link: FriendLink, myId: String, onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("‹ 朋友") }
    PageTitle(link.otherName(myId).ifBlank { "好友" }, "近期共享记录")
    val records = link.otherRecords(myId)
    if (records.isEmpty()) Panel { Text("还没有同步的记录") }
    records.forEach { RecordCard(it) }
}

@Composable
private fun ProfileScreen(model: LalemViewModel, updates: AppUpdateViewModel) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    PageTitle("我的")
    Panel(accent = true) {
        Text(model.nickname.ifBlank { "我的账号" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (model.phone.isBlank()) "手机号待补充" else "手机号  ${model.phone}")
        TextButton(onClick = {
            name = model.nickname
            phone = model.phone
            editing = true
        }, enabled = !model.busy) { Text("编辑昵称与手机号") }
    }
    Panel {
        Text("我的好友码", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(model.friendCode.ifBlank { "等待同步" }, Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        val clip = ClipData.newPlainText("好友码", model.friendCode)
                        clip.description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        clipboard.setPrimaryClip(clip)
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                            Toast.makeText(context, "已复制好友码", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = model.friendCode.isNotBlank()
            ) { Text("复制") }
        }
        Text("把手机号和好友码交给你想添加的好友。")
        TextButton(onClick = model::refreshCode, enabled = !model.busy) { Text("刷新好友码") }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("好友排行榜", fontWeight = FontWeight.SemiBold)
                Text("在朋友页显示周榜和月榜", style = MaterialTheme.typography.bodySmall)
            }
            Switch(model.rankingEnabled, model::setRanking)
        }
    }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("版本 ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { updates.check(manual = true) }, enabled = !updates.checking) {
                Text(if (updates.checking) "正在检查…" else "检查更新")
            }
        }
    }
    OutlinedButton(onClick = model::logout, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!model.busy) editing = false },
            title = { Text("编辑个人资料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth(),
                        label = { Text("昵称") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it.filter { digit -> digit in '0'..'9' }.take(11) },
                        Modifier.fillMaxWidth(), label = { Text("手机号") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    Text("更换后，用新手机号登录和添加好友。", style = MaterialTheme.typography.bodySmall)
                    model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { model.editProfile(name, phone) { editing = false } },
                    enabled = !model.busy && name.isNotBlank() &&
                        (phone.matches(Regex("1[0-9]{10}")) || (phone.isEmpty() && model.phone.isEmpty()))) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !model.busy) { Text("取消") } }
        )
    }
}

@Composable
internal fun PeriodChips(first: Boolean, onChange: (Boolean) -> Unit, firstLabel: String, secondLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(first, { onChange(true) }, label = { Text(firstLabel) })
        FilterChip(!first, { onChange(false) }, label = { Text(secondLabel) })
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
