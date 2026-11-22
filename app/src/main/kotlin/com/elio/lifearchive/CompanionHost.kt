package com.elio.lifearchive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elio.lifearchive.data.ai.SharedAiRuntime
import com.elio.lifearchive.domain.pet.CompanionRepository
import com.elio.lifearchive.feature.pet.CompanionRoute
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CompanionHost(
    provider: Provider<CompanionRepository>, onBar: (Int) -> Unit, onVisible: (Boolean) -> Unit,
    onOverlay: () -> Unit, onStopOverlay: () -> Unit, onLife: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var retry by remember { mutableIntStateOf(0) }
    val result by produceState<Result<Pair<CompanionRepository, SharedAiRuntime>>?>(null, retry) {
        value = null
        value = try { Result.success(withContext(Dispatchers.IO) {
            val repo = provider.get(); repo.initialize()
            repo to SharedAiRuntime.get(context)
        }) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    val ready = result?.getOrNull()
    if (ready != null) CompanionRoute(ready.first, ready.second, onBar, onOverlay, onStopOverlay, onVisible)
    else Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(if (result == null) "正在打开伙伴…" else "伙伴暂时无法打开，请重试。")
            if (result?.isFailure == true) TextButton(onClick = { retry++ }) { Text("重试") }
            TextButton(onClick = onLife) { Text("返回 Life") }
        }
    }
}
