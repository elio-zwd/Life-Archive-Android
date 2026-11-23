package com.elio.lifearchive

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import com.elio.lifearchive.core.designsystem.DocumentPalette
import com.elio.lifearchive.core.designsystem.colors
import com.elio.lifearchive.core.live2d.Live2DModelCatalog
import com.elio.lifearchive.core.live2d.Live2DModelSource
import com.elio.lifearchive.core.live2d.Live2DModelSpec
import com.elio.lifearchive.core.preferences.*
import com.elio.lifearchive.domain.pet.*
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** 服务只管理系统窗口；聊天和主动问候分别通过伙伴公开接口提交。 */
@AndroidEntryPoint
class CompanionOverlayService : Service() {
    @Inject lateinit var repositoryProvider: Provider<CompanionRepository>
    @Inject lateinit var preferences: PreferencesRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var panel: CompanionOverlayPanel? = null
    private var repository: CompanionRepository? = null
    private var gesture: CompanionTouchGesture? = null
    private var auxiliary: View? = null
    private var chat: CompanionOverlayChat? = null
    private var dismissJob: Job? = null
    private var greetingJob: Job? = null
    private var draftWrite: Job? = null
    private var draftVersion = 0L
    private var sending = false
    private var palette = DocumentPalette.BLUE
    private var pref: UserPreferences? = null
    private var lastSettings: OverlaySettings? = null
    private var initialized = false
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var lastInteraction = 0L
    private var lastReaction = 0L
    private var lastTouchBubble = 0L
    private var currentCue = ""
    private var orientation = Configuration.ORIENTATION_UNDEFINED
    private var roleBounds = Rect()
    private val params = windowParams(false)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                hideAuxiliary(); greetingJob?.cancel(); gesture?.cancel()
                panel?.visibility = View.INVISIBLE; panel?.model?.setActive(false)
                val now = System.currentTimeMillis()
                scope.launch { safeUpdate { it.copy(screenOffAt = now) } }
            } else if (unlocked()) {
                panel?.visibility = View.VISIBLE; panel?.model?.setActive(true)
                if (intent?.action == Intent.ACTION_USER_PRESENT) greet(CompanionTrigger.RETURN)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        orientation = resources.configuration.orientation
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopAfterDraft(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌面陪伴", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, openIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            startForeground(1024, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("伙伴正在桌面陪伴").setContentText("轻触互动，按住一秒可移动或打开菜单。")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build())
        } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (panel == null) attach()
        return START_NOT_STICKY
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val view = CompanionOverlayPanel(this); panel = view
        placeRole(OverlaySettings())
        view.model.onStatus = { if (it != null) bubble("形象暂时无法显示，可关闭后重试。") }
        view.model.onTap = { react(CompanionTrigger.BODY) }
        view.model.contentDescription = "桌面伙伴，轻触互动，滑动抚摸，按住一秒移动或打开菜单"
        view.model.setOnLongClickListener { showMenu(); true }
        gesture = CompanionTouchGesture(view.model,
            feedback = { view.model.animate().scaleX(1.035f).scaleY(1.035f).setDuration(140).withEndAction {
                view.model.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }.start() },
            stroke = { x, y ->
                if (x.isNaN()) view.model.followTouch(0f, 0f)
                else {
                    view.model.followTouch(x / view.model.width.coerceAtLeast(1) * 2 - 1, 1 - y / view.model.height.coerceAtLeast(1) * 2)
                    react(CompanionTrigger.STROKE)
                }
            },
            tap = { y -> react(if (y < view.model.height * .36f) CompanionTrigger.HEAD else CompanionTrigger.BODY) },
            drag = { dx, dy ->
                dragging = true
                val point = overlayPlacement(dragStartX + dx.toInt(), dragStartY + dy.toInt(), params.width, params.height, safeBounds(), false)
                params.x = point.x; params.y = point.y; updateRole()
            },
            finishDrag = { dragging = false; savePosition() },
            menu = { showMenu() },
            interaction = {
                lastInteraction = SystemClock.elapsedRealtime(); greetingJob?.cancel(); hideAuxiliary()
                dragStartX = params.x; dragStartY = params.y
            },
        )
        view.model.setOnTouchListener(gesture)
        try { wm.addView(view, params) } catch (_: Exception) { panel = null; view.release(); stopSelf(); return }
        scope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { repositoryProvider.get().also { it.initialize() } }
                repository = repo
                repo.state.collect { state ->
                    val first = !initialized
                    Live2DModelCatalog.syncExternal(state.importedModels.map {
                        Live2DModelSpec(it.id, it.displayName, "本地导入模型", Live2DModelSource.Directory(it.directoryPath, it.modelJson))
                    })
                    view.model.setModel(state.profile.modelId)
                    if (!dragging && state.overlay != lastSettings) placeRole(state.overlay)
                    lastSettings = state.overlay
                    chat?.render(state, updateDraft = draftWrite?.isActive != true && !sending)
                    val message = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
                    val cue = message?.id.toString() + ":" + message?.status
                    if (cue != currentCue) {
                        currentCue = cue
                        if (!first) view.model.setCue(cue, if (state.generating) "THINKING" else message?.emotion?.name ?: "CALM")
                    }
                    if (first) {
                        initialized = true
                        view.visibility = if (unlocked()) View.VISIBLE else View.INVISIBLE
                        view.model.setActive(unlocked()); greet(CompanionTrigger.START)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { bubble("伙伴暂时无法打开，请关闭后重试。") }
        }
        scope.launch { preferences.preferences.collect { pref = it; applyPalette() } }
        scope.launch { while (isActive) { delay(1_000); if (!Settings.canDrawOverlays(this@CompanionOverlayService)) { stopSelf(); break } } }
    }

    private fun react(trigger: CompanionTrigger) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReaction < 180) return
        lastReaction = now; panel?.model?.react(trigger.name)
        if (now - lastTouchBubble >= 12_000 && repository != null) {
            lastTouchBubble = now; bubble(repository!!.localReaction(trigger).text)
        }
    }
    private fun greet(trigger: CompanionTrigger) {
        if (!initialized || !unlocked() || auxiliary != null || dragging ||
            (lastInteraction != 0L && SystemClock.elapsedRealtime() - lastInteraction < 60_000)) return
        if (greetingJob?.isActive == true) return
        greetingJob = scope.launch {
            try {
                val reply = repository?.greeting(trigger, System.currentTimeMillis(), LocalTime.now().hour, LocalDate.now().toString())
                if (reply != null && unlocked() && auxiliary == null && !dragging &&
                    (lastInteraction == 0L || SystemClock.elapsedRealtime() - lastInteraction >= 60_000)) {
                    panel?.model?.react("HEAD"); bubble(reply.text)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 主动问候失败保持安静，聊天错误仍在聊天窗中显示。 */ }
        }
    }

    private fun showMenu() {
        val body = card()
        menuItem(body, "聊天", null) { showChat() }
        menuItem(body, "调整大小", null) { showSize() }
        menuItem(body, "安静一小时", null) {
            scope.launch { safeUpdate { it.copy(mutedUntil = System.currentTimeMillis() + 3_600_000) } }; hideAuxiliary()
        }
        menuItem(body, "陪伴设置", null) { showSettings() }
        menuItem(body, "关闭桌面陪伴", null) { stopAfterDraft() }
        showAuxiliary(body, dp(178), false, 8_000)
    }
    private fun showSize() {
        val body = card()
        val value = TextView(this).apply { textSize = 14f; setTextColor(palette.colors.ink.toArgb()) }
        body.addView(value)
        val slider = SeekBar(this).apply { max = 25; progress = (((repository?.state?.value?.overlay?.heightFraction ?: .25f) - .15f) * 100).roundToInt() }
        fun updateLabel(progress: Int) { value.text = "角色大小 · " + (progress + 15) + "%" }
        updateLabel(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabel(progress)
                if (fromUser) { dismissJob?.cancel(); placeRole((lastSettings ?: OverlaySettings()).copy(heightFraction = (progress + 15) / 100f)) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { dismissJob?.cancel() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { scope.launch { safeUpdate { it.copy(heightFraction = (slider.progress + 15) / 100f) } } }
        })
        body.addView(slider); menuItem(body, "完成", null) { hideAuxiliary() }
        showAuxiliary(body, dp(232), false)
    }
    private fun showSettings() {
        val body = card()
        val settings = repository?.state?.value?.overlay ?: OverlaySettings()
        GreetingMode.entries.forEach { mode ->
            menuItem(body, (if (settings.greetingMode == mode) "✓ " else "") + when (mode) {
                GreetingMode.OFF -> "关闭主动发言"; GreetingMode.LOCAL -> "本地短句"; GreetingMode.LOCAL_AI -> "本地＋AI"
            }, null) { scope.launch { safeUpdate { it.copy(greetingMode = mode) }; showSettings() } }
        }
        val quiet = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val start = NumberPicker(this).apply { minValue = 0; maxValue = 23; value = settings.quietStartHour; contentDescription = "安静开始小时"; descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS }
        val end = NumberPicker(this).apply { minValue = 0; maxValue = 23; value = settings.quietEndHour; contentDescription = "安静结束小时"; descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS }
        quiet.addView(start, LinearLayout.LayoutParams(0, dp(110), 1f))
        quiet.addView(TextView(this).apply { text = "至"; setTextColor(palette.colors.ink.toArgb()) })
        quiet.addView(end, LinearLayout.LayoutParams(0, dp(110), 1f))
        body.addView(TextView(this).apply { text = "安静时段（小时，相同表示关闭）"; textSize = 12f; setTextColor(palette.colors.muted.toArgb()) })
        body.addView(quiet)
        menuItem(body, "保存安静时段", null) {
            scope.launch { safeUpdate { it.copy(quietStartHour = start.value, quietEndHour = end.value) } }; hideAuxiliary()
        }
        menuItem(body, "打开伙伴设置", null) { hideAuxiliary(); startActivity(openIntent(settings = true)) }
        showAuxiliary(body, dp(248), false)
    }

    private fun showChat() {
        greetingJob?.cancel()
        val view = CompanionOverlayChat(this)
        view.palette(palette)
        repository?.state?.value?.let { view.render(it) }
        view.input.isEnabled = repository != null; view.send.isEnabled = repository != null && !sending
        view.onDraft = { text ->
            val previous = draftWrite; val version = ++draftVersion
            draftWrite = scope.launch {
                try {
                    previous?.join(); repository?.saveDraft(text)
                    if (draftVersion == version && !sending) repository?.state?.value?.let { chat?.render(it, updateBubble = false) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { chat?.failure("草稿未保存，请重试。") }
            }
        }
        view.collapse.setOnClickListener { hideAuxiliary() }
        view.open.setOnClickListener { hideAuxiliary(); startActivity(openIntent()) }
        view.send.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            if (repo.state.value.generating) { scope.launch { repo.cancel() }; return@setOnClickListener }
            val text = view.input.text.toString()
            if (text.isBlank() || sending) return@setOnClickListener
            sending = true; view.send.isEnabled = false
            scope.launch {
                try { draftWrite?.join(); repo.send(text, UUID.randomUUID().toString()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { chat?.failure("发送未完成，请重试。") }
                finally { sending = false; chat?.send?.isEnabled = true }
            }
        }
        showAuxiliary(view, dp(320), true)
    }
    private fun bubble(text: String) {
        if (!unlocked() || auxiliary != null || dragging) return
        val view = TextView(this).apply {
            this.text = text; textSize = 14f; maxLines = 3; setTextColor(palette.colors.ink.toArgb())
            setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(18); alpha = 0f
        }
        showAuxiliary(view, dp(184), false, 5_000)
        view.animate().alpha(1f).setDuration(180).start()
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(20) }
    private fun menuItem(parent: LinearLayout, title: String, detail: String?, action: () -> Unit) {
        parent.addView(TextView(this).apply {
            text = if (detail == null) title else "$title\n$detail"; textSize = 14f; setTextColor(palette.colors.ink.toArgb())
            setPadding(dp(10), dp(10), dp(10), dp(10)); isClickable = true; isFocusable = true
            contentDescription = title; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun rounded(radius: Int) = GradientDrawable().apply {
        setColor(palette.colors.background.toArgb()); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), palette.colors.line.toArgb())
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showAuxiliary(view: View, width: Int, focusable: Boolean, timeout: Long = 0) {
        hideAuxiliary()
        if (panel == null || !unlocked()) return
        auxiliary = view; chat = view as? CompanionOverlayChat
        val bounds = safeBounds()
        val layout = windowParams(focusable).apply {
            this.width = minOf(width, bounds.width()); height = WindowManager.LayoutParams.WRAP_CONTENT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(layout.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((bounds.height() * .65f).toInt(), View.MeasureSpec.AT_MOST))
        positionAuxiliary(view, layout)
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && auxiliary === view && view !is CompanionOverlayChat) { hideAuxiliary(); true } else false
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (auxiliary === view && positionAuxiliary(view, layout)) runCatching { wm.updateViewLayout(view, layout) }
        }
        try { wm.addView(view, layout) } catch (_: Exception) { auxiliary = null; chat = null; return }
        if (timeout > 0) dismissJob = scope.launch {
            delay(timeout)
            if (auxiliary === view) {
                view.animate().alpha(0f).setDuration(180).start(); delay(180)
                if (auxiliary === view) hideAuxiliary()
            }
        }
    }
    private fun positionAuxiliary(view: View, layout: WindowManager.LayoutParams): Boolean {
        val bounds = safeBounds()
        if (view is CompanionOverlayChat && view.isAttachedToWindow) {
            val visible = Rect(); view.getWindowVisibleDisplayFrame(visible)
            if (visible.height() > dp(160)) bounds.bottom = minOf(bounds.bottom, visible.bottom)
        }
        val h = view.height.takeIf { it > 0 } ?: view.measuredHeight
        val right = params.x + params.width + dp(8)
        val left = params.x - layout.width - dp(8)
        val fitsRight = right + layout.width <= bounds.right
        val fitsLeft = left >= bounds.left
        val x = if (fitsRight) right else if (fitsLeft) left else params.x + params.width / 2 - layout.width / 2
        val y = if (fitsRight || fitsLeft) params.y + dp(8)
            else if (params.y - h - dp(8) >= bounds.top) params.y - h - dp(8)
            else params.y + params.height + dp(8)
        val point = overlayPlacement(x, y, layout.width, h, bounds, false)
        val changed = layout.x != point.x || layout.y != point.y
        layout.x = point.x; layout.y = point.y
        return changed
    }
    private fun hideAuxiliary() {
        dismissJob?.cancel(); dismissJob = null
        auxiliary?.let { view ->
            view.animate().cancel()
            if (view is CompanionOverlayChat) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.input.windowToken, 0)
                view.input.clearFocus(); view.release()
            }
            runCatching { wm.removeViewImmediate(view) }
        }
        auxiliary = null; chat = null
    }
    private fun safeBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) {
        val metrics = wm.currentWindowMetrics
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        Rect(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom)
    } else {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        Rect(0, dp(24), metrics.widthPixels, metrics.heightPixels - dp(24))
    }
    private fun placeRole(settings: OverlaySettings) {
        val bounds = safeBounds()
        roleBounds = Rect(bounds)
        params.height = (bounds.height() * settings.heightFraction).toInt().coerceIn(dp(72).coerceAtMost(bounds.height()), bounds.height())
        params.width = minOf((params.height * .42f).toInt(), bounds.width())
        params.x = bounds.left + ((bounds.width() - params.width) * settings.xFraction).toInt()
        params.y = bounds.top + ((bounds.height() - params.height) * settings.yFraction).toInt()
        updateRole()
    }
    private fun savePosition() {
        val bounds = safeBounds()
        val x = (params.x - bounds.left).toFloat() / (bounds.width() - params.width).coerceAtLeast(1)
        val y = (params.y - bounds.top).toFloat() / (bounds.height() - params.height).coerceAtLeast(1)
        scope.launch { safeUpdate { it.copy(xFraction = x, yFraction = y) } }
    }
    private suspend fun safeUpdate(transform: (OverlaySettings) -> OverlaySettings) {
        try { repository?.updateOverlay(transform) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { bubble("设置未保存，请重试。") }
    }
    private fun updateRole() { panel?.takeIf { it.isAttachedToWindow }?.let { view -> runCatching { wm.updateViewLayout(view, params) }.onFailure { stopSelf() } } }
    private fun applyPalette() {
        pref?.let {
            val dark = when (it.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
            palette = DocumentPalette.valueOf(it.resolvedDocumentTheme(dark).name)
        }
        chat?.palette(palette)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 输入法／硬件键盘也会派发配置变更，不能因此销毁正在输入的小窗。
        if (newConfig.orientation != orientation || safeBounds() != roleBounds) {
            hideAuxiliary(); gesture?.cancel(); dragging = false
            orientation = newConfig.orientation
            placeRole(repository?.state?.value?.overlay ?: OverlaySettings())
        }
        applyPalette()
    }
    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun stopAfterDraft() { scope.launch { draftWrite?.join(); stopSelf() } }
    private fun openIntent(settings: Boolean = false) = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(if (settings) "open-companion-settings" else "open-companion", true)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun windowParams(focusable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH),
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    override fun onDestroy() {
        running = false
        gesture?.cancel(); hideAuxiliary(); scope.cancel(); panel?.release()
        panel?.let { runCatching { wm.removeViewImmediate(it) } }; panel = null
        unregisterReceiver(screenReceiver); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "companion-overlay"
        private const val STOP = "companion.stop"
        @Volatile internal var running = false
        internal fun stopIntent(context: Context): Intent = Intent(context, CompanionOverlayService::class.java).setAction(STOP)
    }
}
