package com.edit2xml

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

// ---------- Language ----------
object L { var cur by mutableStateOf("en") }

private val S: Map<String, Pair<String, String>> = mapOf(
    "tag" to ("Turn any edit video into an Alight Motion project." to "Her edit videosunu Alight Motion projesine cevir."),
    "signup" to ("Create account" to "Hesap olustur"),
    "login" to ("Log in" to "Giris yap"),
    "email" to ("Email" to "E-posta"),
    "pass" to ("Password (min 6 characters)" to "Sifre (en az 6 karakter)"),
    "toLogin" to ("Already have an account? Log in" to "Hesabin var mi? Giris yap"),
    "toSignup" to ("New here? Create account" to "Yeni misin? Hesap olustur"),
    "projects" to ("Projects" to "Projeler"),
    "converter" to ("Converter" to "Donusturucu"),
    "headline" to ("Make it happen" to "Istedigini gerceklestir"),
    "pick" to ("Select video" to "Video sec"),
    "convert" to ("Convert" to "Donustur"),
    "settings" to ("Settings" to "Ayarlar"),
    "language" to ("Language" to "Dil"),
    "signout" to ("Sign out" to "Cikis yap"),
    "back" to ("Back" to "Geri"),
    "converting" to ("Converting" to "Donusturuluyor"),
    "eta" to ("Estimated time left: " to "Tahmini kalan sure: "),
    "calc" to ("Estimating time..." to "Sure hesaplaniyor..."),
    "empty" to ("No projects yet. Convert a video to create one." to "Henuz proje yok. Bir video donustur."),
    "openam" to ("Open in Alight Motion" to "Alight Motion'da ac"),
    "shareaudio" to ("Share song" to "Sarkiyi paylas"),
    "delete" to ("Delete" to "Sil"),
    "done" to ("Done" to "Tamam"),
    "selected" to ("Selected: " to "Secilen: "),
    "s1" to ("Detecting cuts" to "Kesimler algilaniyor"),
    "s2" to ("Extracting the song" to "Sarki ayiklaniyor"),
    "s3" to ("Analyzing beats" to "Ritim analiz ediliyor"),
    "s4" to ("Writing XML" to "XML yaziliyor"),
    "r_cuts" to ("Cuts detected" to "Algilanan kesim"),
    "r_bpm" to ("Tempo" to "Tempo"),
    "r_hits" to ("Beat markers" to "Ritim isareti"),
    "r_dur" to ("Duration" to "Sure"),
    "r_song" to ("Song" to "Sarki"),
    "r_yes" to ("Extracted" to "Ayiklandi"),
    "r_no" to ("No audio track" to "Ses yok"),
    "e_NOKEY" to ("Server key missing. Build with the FIREBASE_API_KEY secret." to "Sunucu anahtari eksik. FIREBASE_API_KEY secret ile derleyin."),
    "e_EMAIL_EXISTS" to ("This email is already registered." to "Bu e-posta zaten kayitli."),
    "e_INVALID_LOGIN_CREDENTIALS" to ("Wrong email or password." to "E-posta veya sifre hatali."),
    "e_INVALID_EMAIL" to ("Invalid email address." to "Gecersiz e-posta."),
    "e_WEAK_PASSWORD" to ("Password is too weak." to "Sifre cok zayif."),
    "e_net" to ("Network error. Check your connection." to "Ag hatasi. Baglantini kontrol et.")
)

fun t(k: String): String { val p = S[k] ?: return k; return if (L.cur == "tr") p.second else p.first }

// ---------- State ----------
class Conv {
    var uri by mutableStateOf<Uri?>(null)
    var name by mutableStateOf("")
    var phase by mutableIntStateOf(0) // 0 idle, 1 running, 2 done, 3 error
    var p by mutableFloatStateOf(0f)
    var step by mutableStateOf("s1")
    var eta by mutableLongStateOf(-1L)
    var res by mutableStateOf<Analysis?>(null)
    var err by mutableStateOf("")
    var refresh by mutableIntStateOf(0)
}

val Ink = Color(0xFF0B0F1A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val prefs = getSharedPreferences("s", 0)
        L.cur = prefs.getString("lang", "en") ?: "en"
        setContent {
            var email by remember { mutableStateOf(prefs.getString("email", null)) }
            Box(Modifier.fillMaxSize()) {
                Bg()
                val e = email
                if (e == null) AuthScreen { prefs.edit().putString("email", it).apply(); email = it }
                else Home(e) { prefs.edit().remove("email").apply(); email = null }
            }
        }
    }
}

// ---------- Visual helpers ----------
@Composable
fun tx(s: String, size: Int = 16, w: FontWeight = FontWeight.Normal, c: Color = Color.White,
       m: Modifier = Modifier, al: TextAlign = TextAlign.Start) =
    BasicText(s, m, style = TextStyle(color = c, fontSize = size.sp, fontWeight = w, textAlign = al))

fun Modifier.glass(s: Shape, a: Float = 0.16f): Modifier = this.clip(s)
    .background(Brush.verticalGradient(listOf(Color.White.copy(a + 0.08f), Color.White.copy(a - 0.06f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.5f), Color.White.copy(0.06f))), s)

@Composable
fun Bg() {
    val tr = rememberInfiniteTransition(label = "bg")
    val a by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().background(Ink)) {
        Canvas(Modifier.fillMaxSize().blur(70.dp)) {
            val w = size.width; val h = size.height
            drawCircle(Color(0xFF0A84FF), w * 0.55f, Offset(w * (0.2f + 0.5f * a), h * (0.2f + 0.1f * a)))
            drawCircle(Color(0xFFBF5AF2), w * 0.5f, Offset(w * (0.9f - 0.6f * a), h * (0.5f + 0.2f * a)))
            drawCircle(Color(0xFF30D5C8).copy(alpha = 0.8f), w * 0.45f, Offset(w * (0.3f + 0.3f * a), h * (0.9f - 0.2f * a)))
        }
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                accent: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "sc")
    val inf = rememberInfiniteTransition(label = "sh")
    val sh by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "sh")
    val shape = RoundedCornerShape(20.dp)
    val fill = if (accent) Brush.verticalGradient(listOf(Color(0xFF3D9BFF).copy(0.85f), Color(0xFF0A84FF).copy(0.6f)))
    else Brush.verticalGradient(listOf(Color.White.copy(0.26f), Color.White.copy(0.10f)))
    Box(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (enabled) 1f else 0.4f }
            .shadow(14.dp, shape, ambientColor = Color.White, spotColor = Color.White)
            .clip(shape)
            .background(fill)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.7f), Color.White.copy(0.1f))), shape)
            .drawWithContent {
                drawContent()
                val x = -size.width + sh * size.width * 2.5f
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(0.28f), Color.Transparent),
                    Offset(x, 0f), Offset(x + size.width * 0.6f, size.height)))
            }
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) { tx(text, 17, FontWeight.SemiBold, al = TextAlign.Center) }
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Box(Modifier.glass(RoundedCornerShape(16.dp), 0.18f)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)) { tx(text, 14, FontWeight.SemiBold) }
}

@Composable
fun GlassField(v: String, onV: (String) -> Unit, hint: String, kt: KeyboardType, pw: Boolean = false) {
    BasicTextField(
        value = v, onValueChange = onV, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = kt),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), 0.14f).padding(16.dp)) {
                if (v.isEmpty()) tx(hint, 17, c = Color.White.copy(0.5f))
                inner()
            }
        })
}

// ---------- Auth ----------
fun errText(e: Throwable): String {
    val m = e.message ?: ""
    return if (S.containsKey("e_$m")) t("e_$m") else if (e is IOException) t("e_net") else m
}

@Composable
fun AuthScreen(onDone: (String) -> Unit) {
    var signup by remember { mutableStateOf(true) }
    var em by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val sc = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp), Arrangement.Center) {
        tx("Edit2XML", 40, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        tx(t("tag"), 16, c = Color.White.copy(0.7f))
        Spacer(Modifier.height(32.dp))
        GlassField(em, { em = it }, t("email"), KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        GlassField(pw, { pw = it }, t("pass"), KeyboardType.Password, pw = true)
        if (err.isNotEmpty()) { Spacer(Modifier.height(10.dp)); tx(err, 14, c = Color(0xFFFF6B6B)) }
        Spacer(Modifier.height(20.dp))
        GlassButton(if (signup) t("signup") else t("login"), Modifier.fillMaxWidth(),
            enabled = !busy && em.isNotBlank() && pw.length >= 6, accent = true) {
            busy = true; err = ""
            sc.launch {
                val r = withContext(Dispatchers.IO) { Auth.call(if (signup) "signUp" else "signInWithPassword", em.trim(), pw) }
                busy = false
                r.onSuccess { onDone(it) }.onFailure { err = errText(it) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { signup = !signup; err = "" },
            contentAlignment = Alignment.Center) {
            tx(if (signup) t("toLogin") else t("toSignup"), 15, FontWeight.Medium, Color.White.copy(0.8f))
        }
    }
}

// ---------- Home ----------
@Composable
fun Home(email: String, onOut: () -> Unit) {
    val ctx = LocalContext.current
    val sc = rememberCoroutineScope()
    val cv = remember { Conv() }
    var tab by remember { mutableIntStateOf(1) }
    var set by remember { mutableStateOf(false) }
    BackHandler(cv.phase == 1) {}
    BackHandler(set) { set = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                tx(if (tab == 0) t("projects") else t("converter"), 30, FontWeight.Bold, m = Modifier.weight(1f))
                Pill(t("settings")) { set = true }
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) Projects(cv) else Converter(cv) { startConv(ctx, cv, sc) }
            }
            NavBar(tab) { tab = it }
        }
        if (set) Settings(email, { set = false }, onOut)
        if (cv.phase == 1) Converting(cv)
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 8.dp, 16.dp, 16.dp)
        .glass(RoundedCornerShape(28.dp), 0.18f).padding(6.dp)) {
        listOf(t("projects"), t("converter")).forEachIndexed { i, s ->
            val a by animateFloatAsState(if (tab == i) 0.3f else 0f, label = "n")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(a))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTab(i) }
                .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                tx(s, 16, FontWeight.SemiBold, Color.White.copy(if (tab == i) 1f else 0.65f))
            }
        }
    }
}

// ---------- Converter ----------
fun nameOf(ctx: Context, u: Uri): String = runCatching {
    ctx.contentResolver.query(u, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
}.getOrNull() ?: "video"

fun fmt(ms: Long): String { val s = ms / 1000; return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s" }

fun startConv(ctx: Context, cv: Conv, sc: CoroutineScope) {
    val u = cv.uri ?: return
    cv.phase = 1; cv.p = 0f; cv.eta = -1; cv.step = "s1"
    val t0 = System.currentTimeMillis()
    val id = cv.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(30) + "_" + (t0 % 100000)
    sc.launch(Dispatchers.Default) {
        try {
            val r = Analyzer.run(ctx.applicationContext, u, id) { p, s ->
                cv.p = p; cv.step = s
                cv.eta = if (p > 0.04f) ((System.currentTimeMillis() - t0) * (1 - p) / p).toLong() else -1L
            }
            cv.res = r; cv.refresh++; cv.phase = 2
        } catch (e: Throwable) {
            cv.err = e.message ?: e.toString(); cv.phase = 3
        }
    }
}

@Composable
fun Converter(cv: Conv, start: () -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) { cv.uri = u; cv.name = nameOf(ctx, u); cv.phase = 0; cv.res = null }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        tx(t("headline"), 32, FontWeight.Bold, al = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        val r = cv.res
        if (cv.phase == 2 && r != null) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stat(t("r_cuts"), "${r.cuts}")
                stat(t("r_dur"), fmt(r.durMs))
                stat(t("r_bpm"), if (r.bpm > 0) "%.0f BPM".format(r.bpm) else "-")
                stat(t("r_hits"), "${r.onsets}")
                stat(t("r_song"), if (r.hasAudio) t("r_yes") else t("r_no"))
            }
            Spacer(Modifier.height(20.dp))
            GlassButton(t("openam"), Modifier.fillMaxWidth(), accent = true) { openAlight(ctx, r.xml) }
            Spacer(Modifier.height(12.dp))
            GlassButton(t("done"), Modifier.fillMaxWidth()) { cv.phase = 0; cv.uri = null; cv.res = null }
        } else {
            if (cv.uri != null) { tx(t("selected") + cv.name, 14, c = Color.White.copy(0.7f), al = TextAlign.Center); Spacer(Modifier.height(12.dp)) }
            GlassButton(t("pick"), Modifier.fillMaxWidth()) { pick.launch("video/*") }
            if (cv.uri != null) {
                Spacer(Modifier.height(12.dp))
                GlassButton(t("convert"), Modifier.fillMaxWidth(), accent = true) { start() }
            }
            if (cv.phase == 3) { Spacer(Modifier.height(14.dp)); tx(cv.err, 14, c = Color(0xFFFF6B6B), al = TextAlign.Center) }
        }
    }
}

@Composable
fun stat(a: String, b: String) {
    Row(Modifier.fillMaxWidth()) {
        tx(a, 16, c = Color.White.copy(0.7f), m = Modifier.weight(1f))
        tx(b, 16, FontWeight.SemiBold)
    }
}

@Composable
fun Converting(cv: Conv) {
    Box(Modifier.fillMaxSize().background(Ink.copy(0.6f)).systemBarsPadding().padding(28.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            tx(t("converting"), 30, FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            tx(t(cv.step), 16, c = Color.White.copy(0.7f))
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).glass(RoundedCornerShape(7.dp), 0.12f)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(cv.p.coerceIn(0.02f, 1f)).clip(RoundedCornerShape(7.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF64B5FF), Color(0xFFBF5AF2)))))
            }
            Spacer(Modifier.height(10.dp))
            tx("${(cv.p * 100).toInt()}%", 15)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
            tx(if (cv.eta >= 0) t("eta") + fmt(cv.eta) else t("calc"), 15, c = Color.White.copy(0.75f))
        }
    }
}

// ---------- Projects ----------
fun openAlight(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)
    val pkg = "com.alightcreative.motion"
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/xml").addFlags(flags).setPackage(pkg))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_SEND).setType("text/xml").putExtra(Intent.EXTRA_STREAM, uri).addFlags(flags).setPackage(pkg))
        } catch (e2: Exception) {
            val l = ctx.packageManager.getLaunchIntentForPackage(pkg)
            runCatching {
                if (l != null) ctx.startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

fun shareAudio(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)
    val i = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun Projects(cv: Conv) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val files = remember(tick, cv.refresh) {
        File(ctx.filesDir, "projects").listFiles { f -> f.extension == "xml" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    if (files.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            tx(t("empty"), 16, c = Color.White.copy(0.6f), al = TextAlign.Center)
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(files) { f ->
            val audio = File(f.parentFile, f.nameWithoutExtension + ".m4a")
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(18.dp)) {
                tx(f.nameWithoutExtension, 18, FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                tx("${maxOf(1L, f.length() / 1024)} KB", 13, c = Color.White.copy(0.6f))
                Spacer(Modifier.height(14.dp))
                GlassButton(t("openam"), Modifier.fillMaxWidth(), accent = true) { openAlight(ctx, f) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (audio.exists()) GlassButton(t("shareaudio"), Modifier.weight(1f)) { shareAudio(ctx, audio) }
                    GlassButton(t("delete"), Modifier.weight(1f)) { f.delete(); audio.delete(); tick++ }
                }
            }
        }
    }
}

// ---------- Settings ----------
@Composable
fun Settings(email: String, onBack: () -> Unit, onOut: () -> Unit) {
    val ctx = LocalContext.current
    fun setLang(l: String) { L.cur = l; ctx.getSharedPreferences("s", 0).edit().putString("lang", l).apply() }
    Column(Modifier.fillMaxSize().background(Ink.copy(0.94f)).systemBarsPadding().padding(24.dp)) {
        tx(t("settings"), 32, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        tx(email, 15, c = Color.White.copy(0.6f))
        Spacer(Modifier.height(28.dp))
        tx(t("language"), 16, FontWeight.SemiBold, Color.White.copy(0.8f))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("English", Modifier.weight(1f), accent = L.cur == "en") { setLang("en") }
            GlassButton("Turkce", Modifier.weight(1f), accent = L.cur == "tr") { setLang("tr") }
        }
        Spacer(Modifier.height(28.dp))
        GlassButton(t("signout"), Modifier.fillMaxWidth()) { onOut() }
        Spacer(Modifier.weight(1f))
        GlassButton(t("back"), Modifier.fillMaxWidth(), accent = true) { onBack() }
    }
}
