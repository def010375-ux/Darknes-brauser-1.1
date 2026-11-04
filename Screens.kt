package com.darknes.tor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ───────────── Общие элементы ───────────── */
@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            title.uppercase(), fontSize = 12.sp, color = Orange, letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF14141A)).padding(14.dp),
            content = content
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/* ───────────── Настройки ───────────── */
@Composable
fun SettingsPage(st: BrowserState, prefs: Prefs, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PageHeader("Настройки", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Поиск") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(prefs.searchEngine == "yandex", { prefs.searchEngine = "yandex" }, { Text("Яндекс") })
                    FilterChip(prefs.searchEngine == "google", { prefs.searchEngine = "google" }, { Text("Google") })
                }
            }
            Section("Стартовая страница") {
                Text("Размытие фона: ${prefs.blur.toInt()}%", color = Color.White)
                Slider(value = prefs.blur, onValueChange = { prefs.blur = it }, valueRange = 0f..100f)
            }
            Section("Сайты") {
                SwitchRow("Версия для ПК в новых вкладках", prefs.desktop) { prefs.desktop = it }
                SwitchRow("Защита от трекеров (новые вкладки)", prefs.tracking) { prefs.tracking = it }
                Text("Размер текста: ${(prefs.fontScale * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = prefs.fontScale, onValueChange = { prefs.fontScale = it },
                    onValueChangeFinished = { st.applyFont() }, valueRange = 0.8f..1.8f
                )
            }
            Section("Данные") {
                OutlinedButton(onClick = { st.clearData() }, modifier = Modifier.fillMaxWidth()) { Text("Очистить данные сайтов") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { st.closeAll(true) }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть все приватные вкладки") }
            }
            Section("О браузере") {
                Text("Darknes Tor 0.1 · движок GeckoView (Firefox)", color = Dim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ───────────── Расширения ───────────── */
@Composable
fun ExtensionsPage(st: BrowserState, onBack: () -> Unit, onOpenCatalog: () -> Unit) {
    var url by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { st.refreshExtensions() }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PageHeader("Расширения", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Каталог Firefox") {
                Text(
                    "Откройте каталог, выберите дополнение и нажмите «Добавить в Firefox» — установка начнётся сама.",
                    color = Dim, fontSize = 13.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenCatalog) { Text("Открыть addons.mozilla.org") }
            }
            Section("Установить по ссылке .xpi") {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text("https://…/файл.xpi") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = { if (url.isNotBlank()) { st.installExtension(url.trim()); url = "" } }) { Text("Установить") }
            }
            Section("Установленные (${st.extensions.size})") {
                if (st.extensions.isEmpty()) Text("Пока ничего не установлено", color = Dim)
                st.extensions.forEach { ext ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ext.metaData?.name ?: ext.id, color = Color.White)
                            Text("v${ext.metaData?.version ?: ""}", fontSize = 12.sp, color = Dim)
                        }
                        Switch(
                            checked = ext.metaData?.enabled ?: false,
                            onCheckedChange = { st.setExtensionEnabled(ext, it) }
                        )
                        IconButton(onClick = { st.removeExtension(ext) }) {
                            Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF6B6B))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/* ───────────── Darknes ID ───────────── */
@Composable
fun AccountPage(auth: Auth, onBack: () -> Unit) {
    var register by remember { mutableStateOf(!auth.hasAccount()) }
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        PageHeader("Darknes ID", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val u = auth.user
            if (u != null) {
                Box(
                    Modifier.size(92.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Orange, Color(0xFFFF4D1F)))),
                    contentAlignment = Alignment.Center
                ) { Text(u.take(1).uppercase(), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.Black) }
                Text(u, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Вы вошли в Darknes ID", color = Dim)
                OutlinedButton(onClick = { auth.logout() }) { Text("Выйти") }
            } else {
                Text(
                    "Аккаунт нужен только приложению Darknes Tor. Без входа браузер работает полностью.",
                    color = Dim, fontSize = 13.sp
                )
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0x22FFFFFF)).padding(4.dp)
                ) {
                    Segment("Вход", !register, Orange, Modifier.weight(1f)) { register = false; err = null }
                    Segment("Регистрация", register, Orange, Modifier.weight(1f)) { register = true; err = null }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Логин") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp) }
                Button(
                    onClick = { err = if (register) auth.register(name, pw) else auth.login(name, pw) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (register) "Создать аккаунт" else "Войти") }
            }
        }
    }
}

/* ───────────── ИИ-ассистент ───────────── */
@Composable
fun AiPage(ai: AiStore, prefs: Prefs, onBack: () -> Unit) {
    val messages = remember { mutableStateListOf<Pair<String, String>>() }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val idx = prefs.aiActive.coerceIn(0, maxOf(0, ai.profiles.size - 1))
    val profile = ai.profiles.getOrNull(idx)

    fun send() {
        val p = profile ?: return
        val text = input.trim()
        if (text.isEmpty() || loading) return
        messages.add("user" to text)
        val history = messages.toList()
        input = ""
        loading = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try { AiClient.chat(p, history) } catch (e: Exception) { "Ошибка: ${e.message}" }
            }
            messages.add("assistant" to reply)
            loading = false
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        PageHeader("ИИ-ассистент", onBack)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ai.profiles.forEachIndexed { i, p ->
                FilterChip(selected = i == idx, onClick = { prefs.aiActive = i }, label = { Text(p.name) })
            }
            AssistChip(onClick = { adding = true }, label = { Text("+ Модель") })
            if (profile != null) TextButton(onClick = { ai.remove(idx); prefs.aiActive = 0 }) { Text("Удалить", color = Color(0xFFFF6B6B)) }
        }
        if (profile == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Добавьте ИИ-модель: адрес OpenAI-совместимого API, ключ и название модели. Можно добавить несколько и переключаться между ними.",
                    color = Dim, fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages.size) { i ->
                    val (role, text) = messages[i]
                    val mine = role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Text(
                            text, color = if (mine) Color.Black else Color.White, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(18.dp))
                                .background(if (mine) Orange else Color(0xFF1A1A22)).padding(12.dp)
                        )
                    }
                }
                if (loading) item { Text("Печатает…", color = Dim) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") }, maxLines = 4, enabled = profile != null,
                shape = RoundedCornerShape(24.dp)
            )
            IconButton(onClick = { send() }, enabled = profile != null && !loading) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Orange)
            }
        }
    }

    if (adding) {
        AddAiDialog(onDismiss = { adding = false }, onSave = { ai.add(it); prefs.aiActive = ai.profiles.size - 1; adding = false })
    }
}

@Composable
fun AddAiDialog(onDismiss: () -> Unit, onSave: (AiProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://api.openai.com/v1") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая ИИ-модель") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") })
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Адрес API (…/v1)") })
                OutlinedTextField(
                    key, { key = it }, singleLine = true, label = { Text("API-ключ") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(model, { model = it }, singleLine = true, label = { Text("Модель") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && url.isNotBlank() && model.isNotBlank())
                    onSave(AiProfile(name.trim(), url.trim(), key.trim(), model.trim()))
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
