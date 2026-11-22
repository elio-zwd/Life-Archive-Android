package com.elio.lifearchive

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elio.jianyu.ui.JianyuEntry
import com.elio.jianyu.domain.LifeKnowledge
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
internal fun DualBusinessHost(
    model: BusinessHostViewModel,
    positions: SwitchPositionStore,
    lifeDark: Boolean,
    lifeKnowledge: LifeKnowledge? = null,
    petContent: @Composable ((Int) -> Unit, (Boolean) -> Unit) -> Unit = { _, _ -> },
    lifeContent: @Composable ((Boolean) -> Unit, (Int) -> Unit) -> Unit,
) {
    val selected by model.selected.collectAsStateWithLifecycle()
    val jianyu by model.jianyu.collectAsStateWithLifecycle()
    val position by positions.positions.collectAsStateWithLifecycle(SwitchPosition())
    val holder = rememberSaveableStateHolder()
    var lifeSwitchAllowed by remember { mutableStateOf(false) }
    var jianyuSwitchAllowed by remember { mutableStateOf(false) }
    var lifeBar by remember { mutableIntStateOf(0) }
    var jianyuBar by remember { mutableIntStateOf(0) }
    var petBar by remember { mutableIntStateOf(0) }
    var petVisible by remember { mutableStateOf(false) }
    var enteredPet by remember { mutableStateOf(false) }
    var jianyuDark by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    var enteredJianyu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val activeDark = if (selected == Business.JIANYU && jianyu is JianyuHostState.Ready) jianyuDark else lifeDark
    SideEffect {
        activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !activeDark
                isAppearanceLightNavigationBars = !activeDark
            }
        }
    }
    if (selected == Business.JIANYU) SideEffect { enteredJianyu = true }
    if (selected == Business.PET) SideEffect { enteredPet = true }
    val ready = jianyu as? JianyuHostState.Ready
    var lastEpoch by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ready?.session?.contentEpoch) {
        ready?.session?.contentEpoch?.let { epoch ->
            if (lastEpoch != null && lastEpoch != epoch) holder.removeState("jianyu-$lastEpoch")
            lastEpoch = epoch
        }
    }
    BackHandler(selected == Business.JIANYU && ready == null) { model.select(Business.LIFE) }

    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }) {
        holder.SaveableStateProvider("life") {
            RetainedBusinessPane(selected == Business.LIFE) {
                lifeContent({ lifeSwitchAllowed = it }, { lifeBar = it })
            }
        }
        if (enteredJianyu || selected == Business.JIANYU) {
            if (ready != null) {
                holder.SaveableStateProvider("jianyu-${ready.session.contentEpoch}") {
                    RetainedBusinessPane(selected == Business.JIANYU) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides ready.uiOwner) {
                            JianyuEntry(ready.session, selected == Business.JIANYU,
                                onTopLevelChanged = { jianyuSwitchAllowed = it },
                                onBottomBarMeasured = { jianyuBar = it }, onDarkThemeChanged = { jianyuDark = it },
                                lifeKnowledge = lifeKnowledge)
                        }
                    }
                }
            } else if (selected == Business.JIANYU) {
                JianyuStatus(jianyu, model::retryJianyu) { model.select(Business.LIFE) }
            }
        }
        if (enteredPet || selected == Business.PET) {
            holder.SaveableStateProvider("pet") {
                RetainedBusinessPane(selected == Business.PET) {
                    petContent({ petBar = it }, { petVisible = it })
                }
            }
        }
        val bar = when (selected) { Business.LIFE -> lifeBar; Business.JIANYU -> jianyuBar; Business.PET -> petBar }
        val allowed = when (selected) { Business.LIFE -> lifeSwitchAllowed; Business.JIANYU -> ready != null && jianyuSwitchAllowed; Business.PET -> true }
        if (allowed && windowFocused && bar > 0 && size.width > 0 && size.height > 0) {
            val margin = with(density) { 12.dp.roundToPx() }
            val bounds = SwitchBounds.create(size.width, size.height, buttonSize,
                safe.getLeft(density, direction), safe.getTop(density), safe.getRight(density, direction),
                maxOf(safe.getBottom(density), WindowInsets.ime.getBottom(density)), bar, margin)
            BusinessSwitcher(selected, bounds, position, onPosition = { newPosition ->
                scope.launch { try { positions.save(newPosition) } catch (_: IOException) { /* 位置保留在本次界面，磁盘恢复后再保存。 */ } }
            }, onSelect = { target ->
                focus.clearFocus(force = true)
                keyboard?.hide()
                model.select(target)
            }, onMeasured = { buttonSize = it }, companionVisible = petVisible)
        }
    }
}

@Composable
private fun RetainedBusinessPane(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { VisibilityLifecycleOwner(parent.lifecycle) }
    DisposableEffect(parent, owner) {
        parent.lifecycle.addObserver(owner)
        onDispose { parent.lifecycle.removeObserver(owner); owner.destroy() }
    }
    SideEffect { owner.setActive(active) }
    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(if (active) 0 else -placeable.width - 1, 0) }
        }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })) { content() }
    }
}

internal class VisibilityLifecycleOwner(private val parent: Lifecycle) : LifecycleOwner, LifecycleEventObserver {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var active = false
    fun setActive(value: Boolean) { active = value; update() }
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) = update()
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    private fun update() {
        val state = parent.currentState
        val target = if (!active && state.isAtLeast(Lifecycle.State.CREATED)) Lifecycle.State.CREATED else state
        if (target != Lifecycle.State.INITIALIZED || registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = target
    }
}

@Composable
private fun JianyuStatus(state: JianyuHostState, retry: () -> Unit, life: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(when (state) {
                JianyuHostState.Failed -> "见域暂时无法打开，请重试。"
                JianyuHostState.Maintenance -> "见域正在处理数据，请稍候…"
                else -> "正在打开见域…"
            })
            if (state == JianyuHostState.Failed) TextButton(onClick = retry) { Text("重试") }
            TextButton(onClick = life) { Text("返回 Life") }
        }
    }
}
