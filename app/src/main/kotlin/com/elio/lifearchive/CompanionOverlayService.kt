package com.elio.lifearchive

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.preferences.PreferencesRepository
import com.elio.lifearchive.core.preferences.UserPreferences
import com.elio.lifearchive.core.preferences.ThemeMode
import com.elio.lifearchive.core.preferences.resolvedDocumentTheme
import com.elio.lifearchive.domain.pet.*
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.*

/** 窗口只管理呈现、输入与权限；会话和生成始终归唯一伙伴运行实例。 */
@AndroidEntryPoint
class CompanionOverlayService : Service() {
    @Inject lateinit var repositoryProvider: Provider<CompanionRepository>
    @Inject lateinit var preferences: PreferencesRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: WindowManager? = null
    private var panel: CompanionOverlayPanel? = null
    private var repository: CompanionRepository? = null
    private var draftWrite: Job? = null
    private var draftVersion = 0L
    private var sending = false
    private var palettePreferences: UserPreferences? = null
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START; x = 20; y = 180
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            panel?.model?.setActive(intent?.action != Intent.ACTION_SCREEN_OFF && !getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopAfterDraft(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌面陪伴", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CompanionOverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, openIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(1024, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("伙伴正在桌面陪伴").setContentText("点击回到伙伴，或停止桌面陪伴。")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build())
        } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (panel == null) attach()
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val wm = getSystemService(WindowManager::class.java); window = wm
        val view = CompanionOverlayPanel(this)
        view.input.isEnabled = false; view.send.isEnabled = false
        view.model.setActive(!getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        view.open.setOnClickListener { startActivity(openIntent()) }
        view.close.setOnClickListener { stopAfterDraft() }
        view.onDraft = { text ->
            val previous = draftWrite; val version = ++draftVersion
            draftWrite = scope.launch {
                try {
                    previous?.join(); repository?.saveDraft(text)
                    if (draftVersion == version && !sending) repository?.state?.value?.let { view.render(it, updateDraft = true, updateBubble = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { view.failure("草稿未保存，请重试。") }
            }
        }
        view.handle.setOnClickListener {
            view.setExpanded(!view.expanded)
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (view.expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (!view.expanded) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.input.windowToken, 0)
            resize(dock = view.expanded)
        }
        view.handle.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragX = event.rawX; dragY = event.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - dragX).toInt(); params.y = (startY + event.rawY - dragY).toInt()
                    resize(); true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - dragX) < dp(8) && kotlin.math.abs(event.rawY - dragY) < dp(8)) target.performClick()
                    else resize(dock = view.expanded)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { resize(dock = view.expanded); true }
                else -> false
            }
        }
        view.send.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            if (repo.state.value.generating) { scope.launch { repo.cancel() }; return@setOnClickListener }
            val text = view.input.text.toString()
            if (text.isBlank() || sending) return@setOnClickListener
            sending = true; view.send.isEnabled = false
            scope.launch {
                var failure: String? = null
                try { draftWrite?.join(); repo.send(text, UUID.randomUUID().toString()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failure = "发送未完成，请重试。" }
                finally {
                    sending = false; view.send.isEnabled = true
                    if (draftWrite?.isActive != true) view.render(repo.state.value)
                    failure?.let(view::failure)
                }
            }
        }
        panel = view
        params.width = minOf(dp(212), safeBounds().width())
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val point = placement(dock = view.expanded)
            if (point.x != params.x || point.y != params.y) { params.x = point.x; params.y = point.y; updateWindow() }
        }
        try { wm.addView(view, params) }
        catch (_: Exception) { panel = null; view.release(); stopSelf(); return }
        scope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { repositoryProvider.get().also { it.initialize() } }
                repository = repo
                view.input.isEnabled = true; view.send.isEnabled = true
                repo.state.collect { view.render(it, updateDraft = draftWrite?.isActive != true && !sending) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { view.failure("伙伴暂时无法打开，请关闭后重试。") }
        }
        scope.launch {
            preferences.preferences.collect { pref ->
                palettePreferences = pref; applyPalette()
            }
        }
        scope.launch { while (isActive) { delay(1000); if (!Settings.canDrawOverlays(this@CompanionOverlayService)) { stopSelf(); break } } }
    }

    private fun safeBounds(): Rect {
        val wm = window ?: getSystemService(WindowManager::class.java)
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(insets.left, insets.top, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
        } else Rect(0, dp(24), resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels - dp(24))
        if (panel?.expanded == true) {
            val frame = Rect(); panel?.getWindowVisibleDisplayFrame(frame)
            if (frame.bottom > bounds.top) bounds.bottom = minOf(bounds.bottom, frame.bottom)
        }
        return bounds
    }
    private fun placement(dock: Boolean = false): OverlayPlacement = overlayPlacement(params.x, params.y, panel?.width ?: params.width, panel?.height ?: 0, safeBounds(), dock)
    private fun resize(dock: Boolean = false) {
        val bounds = safeBounds(); params.width = minOf(dp(if (panel?.expanded == true) 320 else 212), bounds.width())
        val point = overlayPlacement(params.x, params.y, params.width, panel?.height ?: 0, bounds, dock)
        params.x = point.x; params.y = point.y; updateWindow()
    }
    private fun applyPalette() {
        palettePreferences?.let {
            val dark = when (it.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            panel?.palette(DocumentPalette.valueOf(it.resolvedDocumentTheme(dark).name))
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); resize(dock = panel?.expanded == true); applyPalette() }
    private fun updateWindow() { panel?.let { runCatching { window?.updateViewLayout(it, params) }.onFailure { stopSelf() } } }
    private fun stopAfterDraft() { scope.launch { draftWrite?.join(); stopSelf() } }
    private fun openIntent() = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open-companion", true)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        running = false
        scope.cancel(); panel?.release(); unregisterReceiver(screenReceiver)
        panel?.let { runCatching { window?.removeViewImmediate(it) } }; panel = null
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "companion-overlay"
        private const val STOP = "companion.stop"
        @Volatile internal var running = false
        internal fun stopIntent(context: android.content.Context): Intent =
            Intent(context, CompanionOverlayService::class.java).setAction(STOP)
    }
}
