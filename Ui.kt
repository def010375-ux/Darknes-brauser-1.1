package com.darknes.tor

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import kotlin.math.abs

val Orange = Color(0xFFFF9A1F)
val Violet = Color(0xFF8B5CF6)
val Panel = Color(0xE6101015)
val Hairline = Color(0x22FFFFFF)
val Dim = Color(0x99FFFFFF)

@Composable
fun DarknesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange, onPrimary = Color.Black, secondary = Violet,
            background = Color.Black, surface = Color(0xFF111116), onSurface = Color.White
        ),
        content = content
    )
}

enum class Screen { None, Tabs, Menu, Settings, Extensions, Account, Ai }

/* ───────────── Корневой экран ───────────── */
@Composable
fun BrowserApp(st: BrowserState, prefs: Prefs, auth: Auth, ai: AiStore) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.None) }
    val tab = st.active ?: return
    val start = tab.isStart

    BackHandler {
        when {
            st.fullscreen -> tab.session.exitFullScreen()
            screen != Screen.None -> screen = Screen.None
            tab.canBack -> tab.session.goBack()
            st.tabs.size > 1 -> st.closeTab(tab.id)
            else -> (ctx as? Activity)?.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (start) StartBackground(prefs.blur, tab.private)

        Column(Modifier.fillMaxSize().then(if (st.fullscreen) Modifier else Modifier.systemBarsPadding())) {
            if (!st.fullscreen) AddressBar(st, tab, start)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (start) {
                    StartPage(st, tab, prefs)
                } else {
                    GeckoPane(st, tab)
                    SwitchGhost(tab)
                }
            }
            if (!st.fullscreen) BottomBar(
                st, tab,
                onTabs = { st.capture(tab); screen = Screen.Tabs },
                onMenu = { screen = Screen.Menu }
            )
        }

        // Переключатель вкладок: сворачивание страницы в карточку
        AnimatedVisibility(
            visible = screen == Screen.Tabs,
            enter = fadeIn(tween(220)) + scaleIn(tween(280), initialScale = 0.86f),
            exit = fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.9f)
        ) { TabSwitcher(st) { screen = Screen.None } }

        // Меню
        AnimatedVisibility(
            visible = screen == Screen.Menu,
            enter = fadeIn(tween(160)), exit = fadeOut(tween(140))
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x88000000))
                .pointerInput(Unit) { detectTapGestures { screen = Screen.None } }) {
                Box(
                    Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(bottom = 76.dp)
                        .animateEnterExit(
                            enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(220)),
                            exit = slideOutVertically(tween(180)) { it / 3 } + fadeOut(tween(150))
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    MenuSheet(st, tab, onNav = { screen = it }, onClose = { screen = Screen.None })
                }
            }
        }

        SlidePage(screen == Screen.Settings) { SettingsPage(st, prefs) { screen = Screen.None } }
        SlidePage(screen == Screen.Extensions) {
            ExtensionsPage(st, onBack = { screen = Screen.None }, onOpenCatalog = {
                st.newTab("https://addons.mozilla.org/android/"); screen = Screen.None
            })
        }
        SlidePage(screen == Screen.Account) { AccountPage(auth) { screen = Screen.None } }
        SlidePage(screen == Screen.Ai) { AiPage(ai, prefs) { screen = Screen.None } }
    }

    st.installPrompt?.let { p ->
        val deny = {
            p.result.complete(WebExtension.PermissionPromptResponse(false, false, false))
            st.installPrompt = null
        }
        AlertDialog(
            onDismissRequest = { deny() },
            title = { Text("Установить «${p.name}»?") },
            text = {
                Text(
                    if (p.perms.isEmpty()) "Расширение не запрашивает особых разрешений."
                    else "Расширению нужен доступ:\n• " + p.perms.joinToString("\n• ")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    p.result.complete(WebExtension.PermissionPromptResponse(true, false, false))
                    st.installPrompt = null
                }) { Text("Установить") }
            },
            dismissButton = { TextButton(onClick = { deny() }) { Text("Отмена") } }
        )
    }
}

@Composable
fun SlidePage(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(240)) { it } + fadeOut(tween(200))
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF07070A)).pointerInput(Unit) { detectTapGestures { } }) {
            content()
        }
    }
}

/* ───────────── Страница сайта (GeckoView) ───────────── */
@Composable
fun GeckoPane(st: BrowserState, tab: BrowserTab) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c -> GeckoView(c).also { st.geckoView = it } },
        update = { gv ->
            if (gv.session !== tab.session) {
                gv.releaseSession()
                gv.setSession(tab.session)
            }
        }
    )
}

/** Плавный переход между сайтами: снимок вкладки растворяется поверх живой страницы. */
@Composable
fun SwitchGhost(tab: BrowserTab) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(tab.id) {
        if (tab.thumb != null) { a.snapTo(1f); a.animateTo(0f, tween(340)) }
    }
    val b = tab.thumb
    if (b != null && a.value > 0.01f) {
        Image(
            bitmap = b.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = a.value
                val s = 1f + 0.05f * (1f - a.value)
                scaleX = s; scaleY = s
            },
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter
        )
    }
}

/* ───────────── Стартовый экран ───────────── */
@Composable
fun StartBackground(blur: Float, priv: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(R.drawable.bg_hole), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth)
        Image(
            painterResource(R.drawable.bg_hole_blur), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillWidth, alpha = (blur / 100f).coerceIn(0f, 1f)
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)))))
        if (priv) Box(Modifier.fillMaxSize().background(Color(0x338B5CF6)))
    }
}

@Composable
fun StartPage(st: BrowserState, tab: BrowserTab, prefs: Prefs) {
    val vis = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        AnimatedVisibility(
            visibleState = vis,
            enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 5 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Darknes Tor", fontSize = 36.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp, color = Color.White)
                Text(
                    if (tab.private) "Приватная вкладка · история не сохраняется"
                    else "Поиск: " + if (prefs.searchEngine == "google") "Google" else "Яндекс",
                    fontSize = 13.sp, color = Dim, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Quick("YouTube", "▶") { st.go("https://www.youtube.com") }
                    Quick("Яндекс", "Я") { st.go("https://yandex.ru") }
                    Quick("Google", "G") { st.go("https://www.google.com") }
                    Quick("GitHub", "⌘") { st.go("https://github.com") }
                    Quick("Wiki", "W") { st.go("https://ru.wikipedia.org") }
                }
            }
        }
    }
}

@Composable
fun Quick(label: String, glyph: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Color(0x33FFFFFF)).border(1.dp, Hairline, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(glyph, fontSize = 20.sp, color = Color.White) }
        Text(label, fontSize = 11.sp, color = Dim, modifier = Modifier.padding(top = 6.dp))
    }
}

/* ───────────── Адресная строка и нижняя панель ───────────── */
@Composable
fun AddressBar(st: BrowserState, tab: BrowserTab, start: Boolean) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var tf by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(tab.url, tab.id, focused) {
        if (!focused) tf = TextFieldValue(if (tab.isStart) "" else hostOf(tab.url))
    }
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(shape)
                .background(if (start) Color(0x66101015) else Panel)
                .border(1.dp, if (tab.private) Violet else Hairline, shape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (tab.private) Icons.Filled.Lock else Icons.Filled.Search, null,
                tint = if (tab.private) Violet else Dim, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = tf, onValueChange = { tf = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { st.go(tf.text); focus.clearFocus() }),
                modifier = Modifier.weight(1f).onFocusChanged { f ->
                    if (f.isFocused && !focused) {
                        val full = if (tab.isStart) "" else tab.url
                        tf = TextFieldValue(full, TextRange(0, full.length))
                    }
                    focused = f.isFocused
                },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (tf.text.isEmpty()) Text("Поиск или адрес", color = Color(0x77FFFFFF), fontSize = 15.sp)
                        inner()
                    }
                }
            )
            if (!start) {
                IconButton(
                    onClick = { if (tab.loading) tab.session.stop() else tab.session.reload() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(if (tab.loading) Icons.Filled.Close else Icons.Filled.Refresh, null, tint = Dim, modifier = Modifier.size(20.dp))
                }
            }
        }
        val frac by animateFloatAsState(if (tab.loading) tab.progress / 100f else 0f, tween(200), label = "progress")
        Box(Modifier.padding(horizontal = 26.dp).fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(if (tab.private) Violet else Orange))
        }
    }
}

@Composable
fun BottomBar(st: BrowserState, tab: BrowserTab, onTabs: () -> Unit, onMenu: () -> Unit) {
    val count = st.tabs.count { it.private == tab.private }
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(shape)
            .background(Panel).border(1.dp, Hairline, shape).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        NavBtn(Icons.AutoMirrored.Filled.ArrowBack, tab.canBack) { tab.session.goBack() }
        NavBtn(Icons.AutoMirrored.Filled.ArrowForward, tab.canForward) { tab.session.goForward() }
        NavBtn(Icons.Filled.Home, !tab.isStart) { st.goHome(tab) }
        IconButton(onClick = onTabs) {
            Box(
                Modifier.size(24.dp).border(2.dp, if (tab.private) Violet else Color.White, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) { Text("$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        }
        NavBtn(Icons.Filled.MoreVert, true) { onMenu() }
    }
}

@Composable
fun NavBtn(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, tint = if (enabled) Color.White else Color(0x44FFFFFF))
    }
}

/* ───────────── Меню ───────────── */
class MenuItem(val label: String, val icon: ImageVector, val action: () -> Unit)

@Composable
fun MenuSheet(st: BrowserState, tab: BrowserTab, onNav: (Screen) -> Unit, onClose: () -> Unit) {
    val items = listOf(
        MenuItem("Новая вкладка", Icons.Filled.Add) { st.newTab(); onClose() },
        MenuItem("Инкогнито", Icons.Filled.Lock) { st.newTab(private = true); onClose() },
        MenuItem("Открыть в приложении", Icons.Filled.PlayArrow) { if (!tab.isStart) st.openInApp(tab.url); onClose() },
        MenuItem("Поделиться", Icons.Filled.Share) { if (!tab.isStart) st.share(tab.url); onClose() },
        MenuItem("Версия для ПК", Icons.Filled.Info) { st.toggleDesktop(tab); onClose() },
        MenuItem("ИИ-ассистент", Icons.Filled.Star) { onNav(Screen.Ai) },
        MenuItem("Расширения", Icons.Filled.Build) { onNav(Screen.Extensions) },
        MenuItem("Darknes ID", Icons.Filled.Person) { onNav(Screen.Account) },
        MenuItem("Настройки", Icons.Filled.Settings) { onNav(Screen.Settings) },
    )
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.padding(12.dp).clip(shape).background(Color(0xF2101015)).border(1.dp, Hairline, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { MenuTile(it, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MenuTile(item: MenuItem, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)).clickable(onClick = item.action).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, null, tint = Orange, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(item.label, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/* ───────────── Переключатель вкладок ───────────── */
@Composable
fun TabSwitcher(st: BrowserState, onClose: () -> Unit) {
    var showPrivate by remember { mutableStateOf(st.active?.private == true) }
    val list = st.tabs.filter { it.private == showPrivate }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xF4050508), Color(0xF40B0714))))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(22.dp))
                    .background(Color(0x22FFFFFF)).padding(4.dp)
            ) {
                Segment("Обычные · ${st.tabs.count { !it.private }}", !showPrivate, Orange, Modifier.weight(1f)) { showPrivate = false }
                Segment("Инкогнито · ${st.tabs.count { it.private }}", showPrivate, Violet, Modifier.weight(1f)) { showPrivate = true }
            }
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (showPrivate) "Нет приватных вкладок" else "Нет вкладок", color = Dim)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { t ->
                        TabCard(
                            t, t.id == st.activeId, Modifier.animateItem(),
                            onOpen = { st.activeId = t.id; onClose() },
                            onClose = { st.closeTab(t.id) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { st.closeAll(showPrivate) }) { Text("Закрыть все", color = Dim) }
                Spacer(Modifier.weight(1f))
                FloatingActionButton(
                    onClick = { st.newTab(private = showPrivate); onClose() },
                    containerColor = if (showPrivate) Violet else Orange, contentColor = Color.Black
                ) { Icon(Icons.Filled.Add, null) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Готово", color = Orange) }
            }
        }
    }
}

@Composable
fun Segment(label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) color else Color.Transparent, tween(220), label = "seg")
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun TabCard(tab: BrowserTab, selected: Boolean, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit) {
    val offX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val limit = with(LocalDensity.current) { 140.dp.toPx() }
    val shape = RoundedCornerShape(20.dp)
    val accent = if (tab.private) Violet else Orange
    Column(
        modifier
            .graphicsLayer {
                translationX = offX.value
                rotationZ = offX.value / 60f
                alpha = 1f - (abs(offX.value) / (limit * 2f)).coerceIn(0f, 1f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offX.value) > limit) {
                                offX.animateTo(if (offX.value < 0) -limit * 3 else limit * 3, tween(160))
                                onClose()
                            } else offX.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                    },
                    onDragCancel = { scope.launch { offX.animateTo(0f) } }
                ) { change, dx ->
                    change.consume()
                    scope.launch { offX.snapTo(offX.value + dx) }
                }
            }
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else Hairline, shape)
            .background(Color(0xFF14141A))
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (tab.isStart) "Новая вкладка" else tab.title.ifEmpty { hostOf(tab.url) },
                color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Close, null, tint = Dim, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(Color(0xFF0A0A0E)), contentAlignment = Alignment.Center) {
            val b = tab.thumb
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            } else {
                Text(if (tab.private) "🕶" else "◉", fontSize = 34.sp, color = accent)
            }
        }
    }
}
