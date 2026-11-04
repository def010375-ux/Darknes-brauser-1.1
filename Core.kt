package com.darknes.tor

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.URLUtil
import android.widget.Toast
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.reflect.KProperty

/* ───────────── Движок (один GeckoRuntime на процесс) ───────────── */
object Engine {
    lateinit var app: Context
    lateinit var runtime: GeckoRuntime
    private var ready = false
    fun init(ctx: Context) {
        if (ready) return
        app = ctx.applicationContext
        runtime = GeckoRuntime.create(app)
        ready = true
    }
}

/* ───────────── Настройки (сохраняются сами) ───────────── */
@Suppress("UNCHECKED_CAST")
class Pref<T : Any>(private val sp: SharedPreferences, private val key: String, default: T) {
    private var state by mutableStateOf(((sp.all[key]) as T?) ?: default)
    operator fun getValue(t: Any?, p: KProperty<*>): T = state
    operator fun setValue(t: Any?, p: KProperty<*>, v: T) {
        state = v
        val e = sp.edit()
        when (v) {
            is String -> e.putString(key, v)
            is Float -> e.putFloat(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
        }
        e.apply()
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("darknes", Context.MODE_PRIVATE)
    var searchEngine by Pref(sp, "engine", "yandex")   // yandex | google
    var blur by Pref(sp, "blur", 80f)                  // размытие стартового фона, %
    var desktop by Pref(sp, "desktop", false)          // новые вкладки в версии для ПК
    var tracking by Pref(sp, "tracking", true)         // защита от трекеров (новые вкладки)
    var fontScale by Pref(sp, "font", 1f)              // размер текста на сайтах
    var aiActive by Pref(sp, "ai_active", 0)           // выбранная ИИ-модель
}

/* ───────────── Darknes ID: аккаунт приложения (локально на устройстве) ───────────── */
class Auth(ctx: Context) {
    private val sp = ctx.getSharedPreferences("darknes_id", Context.MODE_PRIVATE)
    var user by mutableStateOf(sp.getString("session", null))
        private set

    private fun hash(pw: String, salt: ByteArray): String {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val h = f.generateSecret(PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).encoded
        return Base64.encodeToString(h, Base64.NO_WRAP)
    }

    fun register(name: String, pw: String): String? {
        if (name.trim().length < 3) return "Логин — минимум 3 символа"
        if (pw.length < 6) return "Пароль — минимум 6 символов"
        if (sp.contains("name")) return "На этом устройстве аккаунт уже создан — войдите"
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("name", name.trim())
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", hash(pw, salt))
            .putString("session", name.trim()).apply()
        user = name.trim()
        return null
    }

    fun login(name: String, pw: String): String? {
        val saved = sp.getString("name", null) ?: return "Аккаунт не найден — зарегистрируйтесь"
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        if (saved != name.trim() || sp.getString("hash", "") != hash(pw, salt)) return "Неверный логин или пароль"
        sp.edit().putString("session", saved).apply()
        user = saved
        return null
    }

    fun hasAccount() = sp.contains("name")

    fun logout() { sp.edit().remove("session").apply(); user = null }
}

/* ───────────── ИИ: несколько моделей на выбор (OpenAI-совместимый API) ───────────── */
data class AiProfile(val name: String, val url: String, val key: String, val model: String)

class AiStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("darknes_ai", Context.MODE_PRIVATE)
    val profiles = mutableStateListOf<AiProfile>()

    init {
        try {
            val a = JSONArray(sp.getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                profiles.add(AiProfile(o.getString("name"), o.getString("url"), o.getString("key"), o.getString("model")))
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        val a = JSONArray()
        profiles.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("key", it.key).put("model", it.model)) }
        sp.edit().putString("list", a.toString()).apply()
    }

    fun add(p: AiProfile) { profiles.add(p); save() }
    fun remove(i: Int) { if (i in profiles.indices) { profiles.removeAt(i); save() } }
}

object AiClient {
    fun chat(p: AiProfile, history: List<Pair<String, String>>): String {
        val msgs = JSONArray()
        history.forEach { (role, text) -> msgs.put(JSONObject().put("role", role).put("content", text)) }
        val body = JSONObject().put("model", p.model).put("messages", msgs).toString()
        val c = URL(p.url.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (p.key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${p.key}")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) return "Ошибка $code: ${text.take(300)}"
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            c.disconnect()
        }
    }
}

/* ───────────── Адрес или поисковый запрос ───────────── */
fun resolveInput(raw: String, engine: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (t.contains("://") || t.startsWith("about:")) return t
    if (!t.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(t)) return "https://$t"
    val q = URLEncoder.encode(t, "UTF-8")
    return if (engine == "google") "https://www.google.com/search?q=$q" else "https://yandex.ru/search/?text=$q"
}

fun hostOf(url: String): String =
    try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }

/* ───────────── Вкладки ───────────── */
class BrowserTab(val id: Int, val private: Boolean, val session: GeckoSession) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var thumb by mutableStateOf<Bitmap?>(null)
    val isStart: Boolean get() = url.isEmpty() || url == "about:blank"
}

class InstallPrompt(
    val name: String,
    val perms: List<String>,
    val result: GeckoResult<WebExtension.PermissionPromptResponse>
)

class BrowserState(private val ctx: Context, val prefs: Prefs) {
    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableIntStateOf(-1)
    var fullscreen by mutableStateOf(false)
    var installPrompt by mutableStateOf<InstallPrompt?>(null)
    val extensions = mutableStateListOf<WebExtension>()
    var geckoView: GeckoView? = null
    private var nextId = 1
    private val main = Handler(Looper.getMainLooper())

    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun newTab(url: String = "", private: Boolean = false): BrowserTab {
        val desk = prefs.desktop
        val st = GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .useTrackingProtection(prefs.tracking)
            .userAgentMode(if (desk) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(if (desk) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        val s = GeckoSession(st)
        val tab = BrowserTab(nextId++, private, s)
        wire(tab)
        s.open(Engine.runtime)
        tabs.add(tab)
        activeId = tab.id
        if (url.isNotEmpty()) { tab.url = url; s.loadUri(url) }
        return tab
    }

    fun closeTab(id: Int) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = tabs[i]
        if (geckoView?.session === t.session) geckoView?.releaseSession()
        tabs.removeAt(i)
        t.session.close()
        if (activeId == id) {
            val next = tabs.lastOrNull { it.private == t.private } ?: tabs.lastOrNull()
            if (next != null) activeId = next.id else newTab()
        }
    }

    fun closeAll(private: Boolean) {
        tabs.filter { it.private == private }.map { it.id }.forEach { closeTab(it) }
        if (tabs.isEmpty()) newTab()
    }

    fun go(input: String) {
        val u = resolveInput(input, prefs.searchEngine)
        if (u.isEmpty()) return
        val t = active ?: newTab()
        t.url = u
        t.session.loadUri(u)
    }

    fun goHome(t: BrowserTab) { t.url = ""; t.title = ""; t.thumb = null; t.session.loadUri("about:blank") }

    fun toggleDesktop(t: BrowserTab) {
        val s = t.session.settings
        val desk = s.userAgentMode == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        s.setUserAgentMode(if (desk) GeckoSessionSettings.USER_AGENT_MODE_MOBILE else GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
        s.setViewportMode(if (desk) GeckoSessionSettings.VIEWPORT_MODE_MOBILE else GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
        t.session.reload()
        toast(if (desk) "Мобильная версия" else "Версия для ПК")
    }

    fun applyFont() {
        try {
            val rs = Engine.runtime.settings
            rs.setAutomaticFontSizeAdjustment(false)
            rs.setFontSizeFactor(prefs.fontScale)
        } catch (_: Exception) { }
    }

    fun clearData() {
        Engine.runtime.storageController.clearData(
            StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.NETWORK_CACHE or
                StorageController.ClearFlags.IMAGE_CACHE or StorageController.ClearFlags.DOM_STORAGES
        )
        tabs.forEach { it.thumb = null }
        toast("Данные сайтов очищены")
    }

    fun capture(tab: BrowserTab) {
        val gv = geckoView ?: return
        if (gv.session !== tab.session || tab.isStart) return
        try {
            gv.capturePixels().accept({ b ->
                if (b != null) tab.thumb = Bitmap.createScaledBitmap(b, maxOf(1, b.width / 3), maxOf(1, b.height / 3), true)
                null
            }, { null })
        } catch (_: Throwable) { }
    }

    fun openInApp(url: String) {
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val chooser = Intent.createChooser(view, "Открыть в приложении")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, MainActivity::class.java)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (_: Exception) { toast("Нет подходящего приложения") }
    }

    fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        ctx.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun download(u: String) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val name = URLUtil.guessFileName(u, null, null)
            dm.enqueue(
                DownloadManager.Request(Uri.parse(u))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            )
            toast("Загрузка: $name")
        } catch (_: Exception) { toast("Не удалось скачать файл") }
    }

    /* ── Расширения (WebExtensions, как в Firefox) ── */
    fun setupExtensions() {
        Engine.runtime.webExtensionController.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                val r = GeckoResult<WebExtension.PermissionPromptResponse>()
                installPrompt = InstallPrompt(
                    extension.metaData?.name ?: "Расширение",
                    (permissions + origins + dataCollectionPermissions).toList(), r
                )
                return r
            }
        })
        refreshExtensions()
    }

    fun refreshExtensions() {
        Engine.runtime.webExtensionController.list().accept({ list ->
            extensions.clear()
            if (list != null) extensions.addAll(list)
            null
        }, { null })
    }

    fun installExtension(url: String) {
        toast("Загружаю расширение…")
        Engine.runtime.webExtensionController.install(url, null).accept({
            refreshExtensions(); toast("Расширение установлено"); null
        }, { e ->
            toast("Не удалось установить: ${e.message}"); null
        })
    }

    fun setExtensionEnabled(ext: WebExtension, on: Boolean) {
        val c = Engine.runtime.webExtensionController
        val r = if (on) c.enable(ext, WebExtensionController.EnableSource.USER)
        else c.disable(ext, WebExtensionController.EnableSource.USER)
        r.accept({ refreshExtensions(); null }, { null })
    }

    fun removeExtension(ext: WebExtension) {
        Engine.runtime.webExtensionController.uninstall(ext).accept({ refreshExtensions(); null }, { null })
    }

    /* ── Подключаем делегаты к сессии ── */
    private fun wire(tab: BrowserTab) {
        val s = tab.session
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) { tab.url = url ?: "" }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canBack = canGoBack }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canForward = canGoForward }

            override fun onLoadRequest(
                session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val u = request.uri
                val scheme = try { Uri.parse(u).scheme ?: "" } catch (_: Exception) { "" }
                if (scheme in setOf("http", "https", "about", "data", "blob", "file", "resource", "moz-extension", "javascript", "view-source")) return null
                // Ссылки вида intent:, market:, youtube: и т.п. — передаём в приложения
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                newTab(uri, tab.private)
                return GeckoResult.fromValue<GeckoSession>(null)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) { tab.loading = true; tab.progress = 0 }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.loading = false; tab.progress = 100
                main.postDelayed({ if (activeId == tab.id) capture(tab) }, 600)
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tab.progress = progress }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) { tab.title = title ?: "" }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) { fullscreen = fullScreen }
            override fun onCloseRequest(session: GeckoSession) { closeTab(tab.id) }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val u = response.uri
                if (u.substringBefore('?').endsWith(".xpi")) installExtension(u) else download(u)
            }
        }
    }
}
