package com.elio.lifearchive

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.elio.lifearchive.domain.pet.*
import com.elio.lifearchive.core.live2d.Live2DView
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.*

/** 系统窗口只持有呈现和输入，共用 app 装配的伙伴资料及生成任务。 */
@AndroidEntryPoint
class CompanionOverlayService : Service() {
    @Inject lateinit var repositoryProvider: Provider<CompanionRepository>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: WindowManager? = null
    private var root: LinearLayout? = null
    private var watcher: Job? = null
    private var repository: CompanionRepository? = null
    private var expanded = false
    private var avatar: Live2DView? = null
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            avatar?.setActive(intent?.action != Intent.ACTION_SCREEN_OFF && !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        }
    }
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 180 }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "桌面陪伴", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CompanionOverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, openIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { startForeground(1024, Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("伙伴正在桌面陪伴").setContentText("点击回到伙伴，或停止桌面陪伴。")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()) }
        catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        if (root == null) attach()
        return START_NOT_STICKY
    }

    // 拖动监听在 ACTION_UP 显式调用 performClick，保留 Button 原有无障碍点击语义。
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val wm = getSystemService(WindowManager::class.java)
        window = wm
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val title = Button(this).apply { text = "伙伴 · 展开"; contentDescription = "拖动或展开伙伴" }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val text = TextView(this).apply { setTextColor(Color.rgb(48, 43, 62)); text = "正在打开伙伴…"; maxLines = 8 }
        val bubble = TextView(this).apply {
            setTextColor(Color.rgb(48, 43, 62)); this.text = "今天想聊些什么？"; maxLines = 3
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(Color.rgb(247, 245, 252)); cornerRadius = dp(12).toFloat() }
        }
        body.background = GradientDrawable().apply { setColor(Color.rgb(247, 245, 252)); cornerRadius = dp(18).toFloat() }
        val model = Live2DView(this).also { avatar = it }
        model.onStatus = { error -> if (error != null) { text.text = error; bubble.text = error } }
        model.onTap = { bubble.text = "嗯，我在。展开后可以继续聊。" }
        model.setActive(!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val input = EditText(this).apply { hint = "和伙伴说说话"; maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(16_000)) }
        val controls = LinearLayout(this)
        val send = Button(this).apply { this.text = "发送" }
        val open = Button(this).apply { this.text = "打开"; setOnClickListener { startActivity(openIntent()) } }
        val close = Button(this).apply { this.text = "关闭"; setOnClickListener { stopSelf() } }
        controls.addView(send); controls.addView(open); controls.addView(close)
        body.addView(text, LinearLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(input); body.addView(controls)
        container.addView(title)
        container.addView(bubble, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(model, LinearLayout.LayoutParams(dp(150), dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        container.addView(body)
        title.setOnClickListener {
            expanded = !expanded
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            title.text = if (expanded) "伙伴 · 收起" else "伙伴 · 展开"
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            if (!expanded) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
            updateWindow()
        }
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragX = event.rawX; dragY = event.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - dragX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - container.width).coerceAtLeast(0))
                    params.y = (startY + event.rawY - dragY).toInt().coerceIn(dp(24), (resources.displayMetrics.heightPixels - container.height - dp(24)).coerceAtLeast(dp(24)))
                    updateWindow(); true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - dragX) < dp(8) && kotlin.math.abs(event.rawY - dragY) < dp(8)) view.performClick()
                    true
                }
                else -> false
            }
        }
        send.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            if (repo.state.value.generating) { scope.launch { repo.cancel() }; return@setOnClickListener }
            val message = input.text.toString()
            if (message.isBlank()) return@setOnClickListener
            send.isEnabled = false
            scope.launch {
                try { if (repo.send(message, UUID.randomUUID().toString())) input.setText("") }
                catch (_: Exception) { text.text = "发送未完成，请重试。" }
                finally { send.isEnabled = true }
            }
        }
        try { wm.addView(container, params); root = container }
        catch (_: Exception) { stopSelf(); return }
        scope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { repositoryProvider.get().also { it.initialize() } }
                repository = repo
                repo.state.collect { state ->
                    val last = state.messages.lastOrNull { it.role == MessageRole.COMPANION }
                    text.text = when { last == null -> "今天想聊些什么？"; last.status == ReplyStatus.GENERATING -> "正在想怎么回应你…"; else -> last.error ?: last.text }
                    bubble.text = text.text.take(80)
                    send.text = if (state.generating) "停止" else "发送"
                    model.setCue("${last?.id}:${last?.status}:${last?.emotion}", if (state.generating) "THINKING" else last?.emotion?.name ?: "CALM")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { text.text = "伙伴暂时无法打开，请关闭后重试。" }
        }
        watcher = scope.launch {
            while (isActive) { delay(1000); if (!Settings.canDrawOverlays(this@CompanionOverlayService)) { stopSelf(); break } }
        }
    }
    private fun updateWindow() { root?.let { runCatching { window?.updateViewLayout(it, params) }.onFailure { stopSelf() } } }
    private fun openIntent() = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("open-companion", true)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        watcher?.cancel(); scope.cancel()
        avatar?.setActive(false); avatar?.onStatus = {}; avatar?.onTap = {}; avatar = null
        unregisterReceiver(screenReceiver)
        root?.let { runCatching { window?.removeViewImmediate(it) } }; root = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "companion-overlay"; private const val STOP = "companion.stop" }
}
