package ru.chatgpt.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val text: String)

private fun encodeMessages(messages: List<ChatMessage>): String {
    val array = JSONArray()
    messages.forEach { array.put(JSONObject().put("role", it.role).put("text", it.text)) }
    return array.toString()
}
private fun decodeMessages(raw: String?): List<ChatMessage> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ChatMessage(item.getString("role"), item.getString("text"))
        }
    } catch (_: Exception) { emptyList() }
}
private fun archiveCurrentChat(prefs: android.content.SharedPreferences, messages: List<ChatMessage>) {
    if (messages.none { it.role == "user" }) return
    val archived = try {
        val raw = prefs.getString("archive", null)
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Exception) { JSONArray() }
    archived.put(JSONArray(encodeMessages(messages)))
    val trimmed = JSONArray()
    val start = (archived.length() - 30).coerceAtLeast(0)
    for (i in start until archived.length()) trimmed.put(archived.getJSONArray(i))
    prefs.edit().putString("archive", trimmed.toString()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChatScreen() }
    }
}

private val page = Color(0xFF090A0D)
private val panel = Color(0xFF191B20)
private val textMain = Color(0xFFE8E5DF)
private val muted = Color(0xFF9B9BA1)
private val green = Color(0xFFB8B3A8)
private val runeRed = Color(0xFF8F2929)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("veles_settings", android.content.Context.MODE_PRIVATE) }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(decodeMessages(prefs.getString("messages", null)))
            if (isEmpty()) add(ChatMessage("assistant", "Привет! Я ChatGPT. Рад общаться с тобой. Чем займёмся?"))
        }
    }
    var input by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
    var settingsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        prefs.edit().putString("messages", encodeMessages(messages.toList())).apply()
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = page, surface = panel, primary = green,
        onBackground = textMain, onSurface = textMain
    )) {
        Scaffold(
            containerColor = page,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("ChatGPT ВЕЛЕС", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = textMain)
                            Text("Славянский ИИ-помощник", color = muted, fontSize = 11.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.Menu, "Меню", tint = textMain)
                        }
                    },
                    actions = {
                        IconButton(onClick = { archiveCurrentChat(prefs, messages.toList()); messages.clear(); messages.add(ChatMessage("assistant", "Новый чат. Чем могу помочь?")) }) {
                            Icon(Icons.Default.Add, "Новый чат", tint = textMain)
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Default.Settings, "Настройки", tint = textMain)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = page)
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().background(page).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(panel, RoundedCornerShape(26.dp))
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Напишите сообщение", color = muted) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = textMain,
                                unfocusedTextColor = textMain,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            maxLines = 5
                        )
                        IconButton(
                            onClick = {
                                val prompt = input.trim()
                                if (prompt.isNotEmpty() && !busy) {
                                    input = ""
                                    messages.add(ChatMessage("user", prompt))
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val answer = requestChat(endpoint, apiKey, model, messages.toList())
                                        if (answer.first != null) messages.add(ChatMessage("assistant", answer.first!!))
                                        else error = answer.second
                                        busy = false
                                    }
                                }
                            },
                            enabled = input.isNotBlank() && !busy,
                            modifier = Modifier.size(42.dp).background(if (input.isNotBlank()) green else Color(0xFF555555), CircleShape)
                        ) {
                            Icon(Icons.Default.Send, "Отправить", tint = Color.White)
                        }
                    }
                    Text(
                        "ВЕЛЕС · Неофициальный клиент. Для ответов требуется API.",
                        color = muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
                    )
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(messages) { message ->
                        val user = message.role == "user"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
                        ) {
                            if (!user) {
                                Box(Modifier.size(30.dp).background(green, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("ᛉ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            Surface(
                                color = if (user) panel else Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Text(message.text, color = textMain, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(if (user) 13.dp else 2.dp))
                            }
                        }
                    }
                    if (busy) item { Text("Печатает…", color = muted, modifier = Modifier.padding(start = 40.dp)) }
                    error?.let { e -> item { Text(e, color = Color(0xFFFF8A80), fontSize = 12.sp) } }
                }
                LaunchedEffect(messages.size, busy) {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
                }
            }
        }

        if (menuOpen) {
            Dialog(
                onDismissRequest = { menuOpen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    color = Color(0xE916171B),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF77736B))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(Color(0xFF292A2E), CircleShape), contentAlignment = Alignment.Center) {
                                Text("ᛉ", color = runeRed, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("ВЕЛЕС", color = textMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Путь мудрости и знаний", color = muted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        listOf("✧   Новый чат", "◷   История диалогов", "⚙   Настройки", "ᛉ   Славянская тема", "ⓘ   О приложении").forEachIndexed { index, label ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    menuOpen = false
                                    when (index) {
                                        0 -> { archiveCurrentChat(prefs, messages.toList()); messages.clear(); messages.add(ChatMessage("assistant", "Новый чат. Чем могу помочь?")) }
                                        1 -> historyOpen = true
                                        2 -> settingsOpen = true
                                        4 -> error = "ChatGPT ВЕЛЕС — независимый клиент для Android."
                                    }
                                }.padding(vertical = 14.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) { Text(label, color = textMain, fontSize = 16.sp) }
                            if (index != 4) HorizontalDivider(color = Color(0x447C7770))
                        }
                        Text("ТЁМНОЕ СТЕКЛО  ·  СЛАВЯНСКИЙ СТИЛЬ", color = muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
                    }
                }
            }
        }


        if (historyOpen) {
            AlertDialog(
                onDismissRequest = { historyOpen = false }, containerColor = panel,
                title = { Text("История диалогов", color = textMain) },
                text = {
                    val archive = remember(historyOpen) {
                        try { prefs.getString("archive", null)?.let { JSONArray(it) } ?: JSONArray() }
                        catch (_: Exception) { JSONArray() }
                    }
                    if (archive.length() == 0) Text("Сохранённых предыдущих диалогов пока нет.", color = muted)
                    else Column {
                        for (i in archive.length() - 1 downTo 0) {
                            val saved = decodeMessages(archive.getJSONArray(i).toString())
                            TextButton(onClick = { messages.clear(); messages.addAll(saved); historyOpen = false }) {
                                Text("Диалог ${i + 1} · ${saved.count { it.role == "user" }} сообщений", color = textMain)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("Закрыть") } }
            )
        }

        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                containerColor = panel,
                title = { Text("Подключение API") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Настройки сохраняются на этом устройстве. Не передавай API-ключ другим людям.", color = muted, fontSize = 12.sp)
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("OpenAI API key") }, singleLine = true)
                        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("URL API") }, singleLine = true)
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Модель") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { prefs.edit().putString("api_key", apiKey.trim()).putString("endpoint", endpoint.trim()).putString("model", model.trim()).apply(); settingsOpen = false }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { apiKey = ""; prefs.edit().remove("api_key").apply(); settingsOpen = false }) { Text("Удалить ключ") } }
            )
        }
    }
}

private suspend fun requestChat(
    endpoint: String,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>
): Pair<String?, String?> = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext Pair(
        "Интерфейс готов, но API ещё не подключён. Откройте ⚙ Настройки и добавьте ключ OpenAI API. Учтите, что доступность API и оплаты в России может быть ограничена.",
        null
    )
    try {
        val url = URL(endpoint)
        require(url.protocol == "https") { "Разрешены только HTTPS-адреса API." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val messageArray = JSONArray()
        messages.takeLast(30).forEach {
            messageArray.put(JSONObject().put("role", it.role).put("content", it.text))
        }
        val payload = JSONObject().put("model", model).put("messages", messageArray)
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) {
            val detail = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
            Pair(null, "Ошибка API ($code): ${detail ?: "проверьте ключ, доступ и адрес сервера"}")
        } else {
            val answer = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            Pair(answer, null)
        }
    } catch (e: Exception) {
        Pair(null, "Не удалось подключиться: ${e.localizedMessage ?: "проверьте интернет и настройки API"}")
    }
}
