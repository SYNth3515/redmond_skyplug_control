package com.example.rsp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

val GREEN = Color(0xFF2E7D32)
val RED = Color(0xFFC62828)
val DAYS_RU = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

data class Mark(val t: LocalTime, val action: Int, val uid: Int)

/** Which local dates of the month each stored event fires on (events are stored in UTC). */
fun marksFor(evs: List<Ev>, ym: YearMonth): Map<LocalDate, List<Mark>> {
    val out = HashMap<LocalDate, MutableList<Mark>>()
    val zone = ZoneId.systemDefault()
    for (e in evs) {
        if (!e.enabled) continue
        val first = Instant.ofEpochSecond(e.epoch)
        val fu = first.atZone(ZoneOffset.UTC)
        var d = ym.atDay(1).minusDays(2)
        val end = ym.atEndOfMonth().plusDays(2)
        while (!d.isAfter(end)) {
            val inst = d.atTime(fu.toLocalTime()).toInstant(ZoneOffset.UTC)
            val hit = !inst.isBefore(first) && when (e.type) {
                0 -> inst == first
                1 -> true
                2 -> ((e.rules shr (d.dayOfWeek.value - 1)) and 1) == 1
                3 -> d.dayOfMonth == fu.dayOfMonth
                else -> false
            }
            if (hit) {
                val l = inst.atZone(zone)
                if (YearMonth.from(l) == ym) out.getOrPut(l.toLocalDate()) { ArrayList() }.add(Mark(l.toLocalTime(), e.action, e.uid))
            }
            d = d.plusDays(1)
        }
    }
    return out.mapValues { it.value.sortedBy { m -> m.t } }
}

fun nextLocal(h: Int, m: Int, mask: Int): ZonedDateTime {
    val now = ZonedDateTime.now()
    var c = now.toLocalDate().atTime(h, m).atZone(now.zone)
    repeat(8) {
        if (c.isAfter(now) && ((mask shr (c.dayOfWeek.value - 1)) and 1) == 1) return c
        c = c.plusDays(1)
    }
    return c
}

/** Socket stores UTC: if the local time lands on another UTC day, rotate the weekday mask. */
fun utcMask(mask: Int, c: ZonedDateTime): Int {
    val shift = (c.withZoneSameInstant(ZoneOffset.UTC).toLocalDate().toEpochDay() - c.toLocalDate().toEpochDay()).toInt()
    var r = 0
    for (d in 0..6) if (((mask shr d) and 1) == 1) r = r or (1 shl (((d + shift) % 7 + 7) % 7))
    return r
}

fun parseHex(s: String): ByteArray? {
    val t = s.trim().lowercase()
    if (t.length != 16 || !t.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return t.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

data class Sock(val id: String, val name: String, val address: String, val token: String)
data class Found(val address: String, val name: String, val rssi: Int)

fun saveSocks(p: SharedPreferences, l: List<Sock>) {
    val a = JSONArray()
    l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("token", it.token)) }
    p.edit().putString("socks", a.toString()).apply()
}

fun loadSocks(p: SharedPreferences): List<Sock> {
    p.getString("socks", null)?.let { s ->
        val a = JSONArray(s)
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Sock(o.getString("id"), o.getString("name"), o.getString("address"), o.getString("token"))
        }
    }
    val tok = p.getString("tok", null)       // миграция со старой версии с одной розеткой
    if (tok != null) {
        val l = listOf(Sock(UUID.randomUUID().toString(), "Розетка 1", p.getString("addr", "C7:42:8C:EE:CE:8C")!!, tok))
        saveSocks(p, l)
        return l
    }
    return emptyList()
}

fun newToken(): String = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

@SuppressLint("MissingPermission")
suspend fun scanSockets(ctx: Context): List<Found> {
    val sc = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner ?: return emptyList()
    val found = LinkedHashMap<String, Found>()
    val cb = object : ScanCallback() {
        override fun onScanResult(t: Int, r: ScanResult) {
            val n = r.device.name ?: r.scanRecord?.deviceName ?: ""
            val u = n.uppercase()
            if (u.contains("RSP") || u.contains("REDMOND") || u.contains("R4S") || r.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true)
                found[r.device.address] = Found(r.device.address, n, r.rssi)
        }
    }
    sc.startScan(cb); delay(8000); sc.stopScan(cb)
    return found.values.sortedByDescending { it.rssi }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("rsp", Context.MODE_PRIVATE) }
    val socks = remember { mutableStateListOf<Sock>().also { it.addAll(loadSocks(prefs)) } }
    var selId by remember { mutableStateOf(prefs.getString("sel", "") ?: "") }
    val cur: Sock? = socks.firstOrNull { it.id == selId } ?: socks.firstOrNull()
    val eventsMap = remember { mutableStateMapOf<String, List<Ev>>() }
    val infoMap = remember { mutableStateMapOf<String, String>() }
    var log by remember { mutableStateOf(if (socks.isEmpty()) "Добавьте розетку кнопкой «＋ Розетка»" else "Нажмите «Обновить», чтобы прочитать календарь розетки") }
    var busy by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var showAdd by remember { mutableStateOf(false) }
    var showAddSock by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val mutex = remember { Mutex() }
    val events = cur?.let { eventsMap[it.id] } ?: emptyList()
    val info = cur?.let { infoMap[it.id] } ?: ""
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    fun update(s: Sock) {
        val i = socks.indexOfFirst { it.id == s.id }
        if (i >= 0) { socks[i] = s; saveSocks(prefs, socks.toList()) }
    }
    fun select(id: String) { selId = id; prefs.edit().putString("sel", id).apply() }

    suspend fun <T> session(s: Sock?, block: suspend Rsp.() -> T): T? = mutex.withLock {
        if (s == null) { log = "Сначала добавьте розетку кнопкой «＋ Розетка»"; return@withLock null }
        val tok = parseHex(s.token)
        if (tok == null) { log = "${s.name}: токен должен быть 16 hex-символов (см. настройки)"; return@withLock null }
        busy = true
        val r = Rsp(ctx, tok)
        try {
            if (!r.open(s.address)) { log = "${s.name}: нет связи или токен не принят. Закройте другие приложения и нажмите «Спарить»."; null }
            else r.block()
        } catch (e: Exception) { log = "${s.name}: ошибка: ${e.message}"; null }
        finally { r.close(); busy = false }
    }

    fun refresh() = scope.launch {
        val s = cur
        session(s) { readCalendar() }?.let { (i, e) ->
            if (s != null) { eventsMap[s.id] = e; infoMap[s.id] = i; log = "${s.name}: событий в розетке ${e.size}" }
        }
    }
    fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02x".format(it) } ?: "нет ответа"

    fun pair() = scope.launch {
        val s = cur ?: run { log = "Сначала добавьте розетку"; return@launch }
        val tok = parseHex(s.token) ?: run { log = "Неверный токен"; return@launch }
        mutex.withLock {
            busy = true
            val end = System.currentTimeMillis() + 180_000
            log = "${s.name}: зажмите кнопку на розетке, пока не замигает светодиод (до 3 минут)…"
            var ok = false
            while (!ok && System.currentTimeMillis() < end) {
                val r = Rsp(ctx, tok)
                ok = try { r.open(s.address) } catch (e: Exception) { false }
                r.close()
                if (!ok) delay(500)
            }
            busy = false
            log = if (ok) "${s.name}: токен принят розеткой. Теперь нажмите «Обновить»." else "${s.name}: не удалось спарить за 3 минуты."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Redmond RSP-103S", style = MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { menuOpen = true }, enabled = socks.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text((cur?.name ?: "Нет розеток") + "  ▾", maxLines = 1)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    socks.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = { select(s.id); menuOpen = false })
                    }
                }
            }
            Button(onClick = { showAddSock = true }, enabled = !busy) { Text("＋ Розетка") }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(log, style = MaterialTheme.typography.bodyMedium)
        if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Обновить", !busy && cur != null) { refresh() }
            Btn("Спарить", !busy && cur != null) { pair() }
            Btn("Статус", !busy && cur != null) { scope.launch { session(cur) { status() }?.let { r -> log = "${cur?.name}: фрейм состояния (без расшифровки): ${hex(r)}" } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Включить", !busy && cur != null) {
                confirm = "Включить «${cur?.name}» сейчас? Всё, что в неё подключено (например, чайник), начнёт работать." to {
                    scope.launch { session(cur) { power(true) }?.let { r -> log = "${cur?.name}: включено, ответ: ${hex(r)}" } }
                }
            }
            Btn("Выключить", !busy && cur != null) { scope.launch { session(cur) { power(false) }?.let { r -> log = "${cur?.name}: выключено, ответ: ${hex(r)}" } } }
            Btn("Синхр. время", !busy && cur != null) {
                scope.launch {
                    val off = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
                    session(cur) { syncTime(off) }?.let { log = "${cur?.name}: время и часовой пояс обновлены" }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Блок. кнопки", !busy && cur != null) { scope.launch { session(cur) { lock(true) }?.let { r -> log = "${cur?.name}: блокировка вкл, ответ: ${hex(r)}" } } }
            Btn("Разблок.", !busy && cur != null) { scope.launch { session(cur) { lock(false) }?.let { r -> log = "${cur?.name}: блокировка выкл, ответ: ${hex(r)}" } } }
        }

        // ---- calendar of the selected socket ----
        val marks = remember(events, month) { marksFor(events, month) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                    Text(
                        month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru")).replaceFirstChar { it.uppercase() } + " " + month.year,
                        Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth()) {
                    DAYS_RU.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                val rows = (offset + month.lengthOfMonth() + 6) / 7
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0..6) {
                            val n = r * 7 + c - offset + 1
                            val day = if (n in 1..month.lengthOfMonth()) month.atDay(n) else null
                            DayCell(day, day == LocalDate.now(), day == selected, day?.let { marks[it] } ?: emptyList(), Modifier.weight(1f).aspectRatio(1f)) {
                                if (day != null) selected = day
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(GREEN, "включение"); Legend(RED, "выключение")
                }
            }
        }

        Text("${cur?.name ?: "—"}: события на ${selected.dayOfMonth}.${"%02d".format(selected.monthValue)}.${selected.year}", style = MaterialTheme.typography.titleSmall)
        val dayMarks = marks[selected] ?: emptyList()
        if (dayMarks.isEmpty()) Text("Нет событий", style = MaterialTheme.typography.bodySmall)
        dayMarks.forEach { m ->
            val ev = events.firstOrNull { it.uid == m.uid }
            val rep = when (ev?.type) { 0 -> "один раз"; 1 -> "каждый день"; 2 -> "по дням: " + DAYS_RU.filterIndexed { i, _ -> ((ev.rules shr i) and 1) == 1 }.joinToString(","); 3 -> "каждый месяц"; else -> "" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (m.action == 3) GREEN else RED))
                Text("  %02d:%02d  %s  (%s)".format(m.t.hour, m.t.minute, if (m.action == 3) "включить" else "выключить", rep), Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = {
                    val s = cur
                    confirm = "Удалить событие uid=${m.uid} у «${s?.name}» (все его повторы)?" to {
                        scope.launch { session(s) { removeEvent(m.uid) }?.let { log = "Удалено"; refresh() } }
                    }
                }) { Text("Удалить") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Добавить", !busy && cur != null) { showAdd = true }
            Btn("Тест +2 мин", !busy && cur != null) {
                val s = cur
                confirm = "«${s?.name}» ВКЛЮЧИТСЯ один раз через 2 минуты. Выньте чайник из розетки!" to {
                    val ep = (Instant.now().epochSecond / 60) * 60 + 120
                    scope.launch { session(s) { addEvent(ep, 0, 3, 0) }?.let { log = "${s?.name}: тест $it. Потом удалите событие."; refresh() } }
                }
            }
            Btn("Очистить всё", !busy && cur != null) {
                val s = cur
                confirm = "Удалить ВСЕ события из розетки «${s?.name}»?" to {
                    scope.launch { session(s) { clearAll() }?.let { log = "Календарь очищен"; refresh() } }
                }
            }
        }

        TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Скрыть настройки" else "Настройки выбранной розетки") }
        if (showSettings && cur != null) {
            OutlinedTextField(cur.name, { update(cur.copy(name = it)) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cur.address, { update(cur.copy(address = it.uppercase().trim())) }, label = { Text("BLE-адрес розетки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cur.token, { update(cur.copy(token = it.trim().lowercase())) }, label = { Text("Токен (16 hex)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = parseHex(cur.token) == null)
            Text("Если розетка уже спарена скриптами, вставьте тот же токен из rsp103s_token.hex, и спаривание не понадобится.", style = MaterialTheme.typography.bodySmall)
            val c = cur
            OutlinedButton(enabled = !busy, onClick = {
                confirm = "Удалить «${c.name}» из приложения? Сама розетка и её расписание не изменятся." to {
                    socks.removeAll { it.id == c.id }; eventsMap.remove(c.id); infoMap.remove(c.id)
                    saveSocks(prefs, socks.toList()); select(socks.firstOrNull()?.id ?: "")
                    log = "Розетка удалена из приложения"
                }
            }) { Text("Удалить эту розетку из приложения") }
        }
        Text("Время в розетке хранится в UTC и не знает о переходе на зимнее/летнее время: после перехода события нужно пересоздать.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showContacts = true }, modifier = Modifier.fillMaxWidth()) { Text("Контакты") }
    }

    if (showContacts) ContactsDialog { showContacts = false }

    if (showAdd) AddDialog(selected, { showAdd = false }) { ep, type, action, rules ->
        showAdd = false
        val s = cur
        scope.launch { session(s) { addEvent(ep, type, action, rules) }?.let { log = "${s?.name}: событие $it"; refresh() } }
    }
    if (showAddSock) AddSockDialog(ctx, socks.map { it.address }.toSet(), "Розетка ${socks.size + 1}", { showAddSock = false }) { s ->
        showAddSock = false
        socks.add(s); saveSocks(prefs, socks.toList()); select(s.id)
        log = "«${s.name}» добавлена. Если розетка ещё не спарена с этим токеном, нажмите «Спарить» и зажмите кнопку на розетке, затем «Обновить»."
    }
    confirm?.let { (msg, act) ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { confirm = null; act() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } })
    }
}

@Composable
fun AddSockDialog(ctx: Context, existing: Set<String>, defaultName: String, onDismiss: () -> Unit, onSave: (Sock) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(defaultName) }
    var addr by remember { mutableStateOf("") }
    var tok by remember { mutableStateOf(newToken()) }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая розетка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !scanning, onClick = {
                    scope.launch {
                        scanning = true; msg = "Поиск 8 секунд…"
                        found = scanSockets(ctx)
                        scanning = false
                        msg = if (found.isEmpty()) "Ничего не найдено. Закройте другие приложения, поднесите телефон ближе." else "Нажмите на нужную розетку:"
                    }
                }) { Text("Найти поблизости") }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                found.forEach { f ->
                    val used = f.address in existing
                    TextButton(enabled = !used, onClick = { addr = f.address }) {
                        Text("${f.name.ifBlank { "без имени" }}  ${f.address}  ${f.rssi} дБм" + if (used) "  (уже добавлена)" else "")
                    }
                }
                OutlinedTextField(addr, { addr = it.uppercase().trim() }, label = { Text("BLE-адрес (AA:BB:CC:DD:EE:FF)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tok, { tok = it.trim().lowercase() }, label = { Text("Токен (16 hex)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), isError = parseHex(tok) == null)
                Text("Для новой розетки оставьте сгенерированный токен и после добавления нажмите «Спарить». Если розетка уже спарена другим токеном (например, скриптом), вставьте его.", style = MaterialTheme.typography.bodySmall)
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> msg = "Введите название"
                    !Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(addr) -> msg = "Неверный адрес: нужен формат AA:BB:CC:DD:EE:FF"
                    addr in existing -> msg = "Эта розетка уже добавлена"
                    parseHex(tok) == null -> msg = "Токен должен быть 16 hex-символов"
                    else -> onSave(Sock(UUID.randomUUID().toString(), name.trim(), addr, tok))
                }
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun RowScope.Btn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(text, maxLines = 1) }
}

@Composable
fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun DayCell(day: LocalDate?, today: Boolean, sel: Boolean, marks: List<Mark>, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.padding(2.dp).clip(shape)
            .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(if (today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(enabled = day != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (day != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(day.dayOfMonth.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
                marks.take(3).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(if (it.action == 3) GREEN else RED)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDialog(day: LocalDate, onDismiss: () -> Unit, onSave: (Long, Int, Int, Int) -> Unit) {
    var hh by remember { mutableStateOf("05") }
    var mm by remember { mutableStateOf("00") }
    var action by remember { mutableStateOf(3) }
    var repeat by remember { mutableStateOf(1) } // 0 once on selected day, 1 daily, 2 weekdays selection
    var days by remember { mutableStateOf(setOf(0, 1, 2, 3, 4)) }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое событие") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(hh, { hh = it.take(2).filter(Char::isDigit) }, Modifier.width(72.dp), singleLine = true, label = { Text("час") })
                    Text("  :  ")
                    OutlinedTextField(mm, { mm = it.take(2).filter(Char::isDigit) }, Modifier.width(72.dp), singleLine = true, label = { Text("мин") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(action == 3, { action = 3 }, { Text("Включить") })
                    FilterChip(action == 4, { action = 4 }, { Text("Выключить") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(repeat == 0, { repeat = 0 }, { Text("Один раз") })
                    FilterChip(repeat == 1, { repeat = 1 }, { Text("Каждый день") })
                    FilterChip(repeat == 2, { repeat = 2 }, { Text("По дням") })
                }
                if (repeat == 0) Text("Дата: ${day.dayOfMonth}.${"%02d".format(day.monthValue)}.${day.year} (выбрана в календаре)", style = MaterialTheme.typography.bodySmall)
                if (repeat == 2) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    DAYS_RU.forEachIndexed { i, n ->
                        FilterChip(i in days, { days = if (i in days) days - i else days + i }, { Text(n, maxLines = 1) }, modifier = Modifier.weight(1f))
                    }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hh.toIntOrNull(); val m = mm.toIntOrNull()
                if (h == null || m == null || h > 23 || m > 59) { err = "Неверное время"; return@TextButton }
                when {
                    repeat == 0 -> {
                        val z = day.atTime(h, m).atZone(ZoneId.systemDefault())
                        if (!z.isAfter(ZonedDateTime.now())) { err = "Это время уже прошло"; return@TextButton }
                        onSave(z.toEpochSecond(), 0, action, 0)
                    }
                    repeat == 1 || days.size == 7 -> onSave(nextLocal(h, m, 127).toEpochSecond(), 1, action, 0)
                    days.isEmpty() -> err = "Выберите хотя бы один день"
                    else -> {
                        val mask = days.fold(0) { a, d -> a or (1 shl d) }
                        val c = nextLocal(h, m, mask)
                        onSave(c.toEpochSecond(), 2, action, utcMask(mask, c))
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ContactsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Контакты") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Версия от 24.10.2026")
                ContactLink("Проверить обновления на GitHub", "https://github.com/SYNth3515/redmond_skyplug_control", "https://github.com/SYNth3515/redmond_skyplug_control")
                ContactLink("Telegram автора", "https://t.me/SYNthes1s", "https://t.me/SYNthes1s")
                ContactLink("Почта", "mailto:yakov-shurigin@ya.ru", "yakov-shurigin@ya.ru")
                ContactLink("Поддержать автора", "https://yoomoney.ru/to/4100119665987017/0", "https://yoomoney.ru/to/4100119665987017/0")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
fun ContactLink(label: String, url: String, shown: String) {
    val uri = LocalUriHandler.current
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            shown,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { try { uri.openUri(url) } catch (_: Exception) {} }
        )
    }
}
