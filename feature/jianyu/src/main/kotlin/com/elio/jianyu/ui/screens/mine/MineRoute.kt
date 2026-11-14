package com.elio.jianyu.ui.screens.mine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.elio.jianyu.ui.lifecycle.sessionCoroutineScope as rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.elio.jianyu.domain.ContextSourceLifecycle
import com.elio.jianyu.domain.JianyuRepository
import com.elio.jianyu.domain.PersonalContextFilter
import com.elio.jianyu.domain.RepositoryResult
import com.elio.jianyu.profile.UserAvatarMutationResult
import com.elio.jianyu.profile.UserAvatarSnapshot
import com.elio.jianyu.network.AiUseCase
import com.elio.jianyu.network.ApiKeyValidationState
import com.elio.jianyu.ui.components.LocalUserAvatarImage
import com.elio.jianyu.ui.components.rememberExpiryClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineRoute(
    ai: com.elio.jianyu.network.AiServices,
    diagnostics: com.elio.jianyu.telemetry.DiagnosticServices,
    avatarRepository: com.elio.jianyu.profile.AvatarRepository,
    repository: JianyuRepository,
    onOpenPersonalContext: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiManagement: () -> Unit,
    onOpenTelemetry: () -> Unit,
) {
    val context = LocalContext.current
    val configurationRepository = remember(context) { ai.configuration }
    val configuration by configurationRepository.configuration.collectAsStateWithLifecycle()
    val selectedModel = configuration.modelFor(AiUseCase.ROUNDTABLE_ANSWER)
    val keyRepository = remember(context, selectedModel.provider) {
        ai.keys(selectedModel.provider)
    }
    val keySummaries by keyRepository.summaries.collectAsStateWithLifecycle()
    val nowMs = rememberExpiryClock(keySummaries.map { it.banExpireTime })
    val telemetryLevel by diagnostics.level.collectAsStateWithLifecycle()

    val avatarFlow = remember(avatarRepository) { avatarRepository.observeAvatar() }
    val avatarSnapshot by avatarFlow.collectAsStateWithLifecycle(
        initialValue = UserAvatarSnapshot(encodedImage = null, revision = 0L),
    )
    val avatarImage = remember(avatarSnapshot.encodedImage, avatarSnapshot.revision) {
        avatarSnapshot.encodedImage?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val scope = rememberCoroutineScope()
    var showAvatarActions by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = avatarRepository.importAvatar(uri.toString())
                Toast.makeText(
                    context,
                    userAvatarMutationMessage(result, importing = true),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    var personalContextCount by remember { mutableStateOf<Int?>(null) }
    var personalContextSummaryLabels by remember { mutableStateOf(emptyList<String>()) }
    var personalContextLoadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        val result = withContext(Dispatchers.IO) {
            repository.listPersonalContexts(
                PersonalContextFilter(
                    lifecycles = setOf(
                        ContextSourceLifecycle.ACTIVE,
                        ContextSourceLifecycle.DISABLED,
                        ContextSourceLifecycle.ARCHIVED,
                    ),
                ),
            )
        }
        when (result) {
            is RepositoryResult.Success -> {
                personalContextCount = result.value.minePersonalContextCount()
                personalContextSummaryLabels = result.value.toMineSummaryLabels()
                personalContextLoadFailed = false
            }
            is RepositoryResult.Failure -> {
                personalContextCount = null
                personalContextSummaryLabels = emptyList()
                personalContextLoadFailed = true
            }
        }
    }

    CompositionLocalProvider(LocalUserAvatarImage provides avatarImage) {
        MineScreen(
            uiState = MineUiState(
                personalContextCount = personalContextCount,
                personalContextSummaryLabels = personalContextSummaryLabels,
                personalContextLoadFailed = personalContextLoadFailed,
                modelDisplayName = selectedModel.displayName,
                availableKeyCount = keySummaries.count { summary ->
                    summary.enabled &&
                        summary.validationState != ApiKeyValidationState.INVALID &&
                        summary.banExpireTime <= nowMs
                },
                telemetryLevel = telemetryLevel,
            ),
            onOpenSettings = onOpenSettings,
            onOpenPersonalContext = onOpenPersonalContext,
            onOpenAbout = onOpenAbout,
            onOpenDataPrivacy = onOpenDataPrivacy,
            onOpenBackup = onOpenBackup,
            onEditAvatar = { showAvatarActions = true },
            onOpenAiManagement = onOpenAiManagement,
            onOpenTelemetry = onOpenTelemetry,
        )
    }

    if (showAvatarActions) {
        ModalBottomSheet(
            onDismissRequest = { showAvatarActions = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(MineTestTags.AVATAR_ACTION_SHEET),
            ) {
                Text(
                    text = "编辑头像",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = {
                        showAvatarActions = false
                        avatarPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MineTestTags.AVATAR_PICK_ACTION),
                ) {
                    Text("从相册选择")
                }
                TextButton(
                    onClick = {
                        showAvatarActions = false
                        scope.launch {
                            val result = avatarRepository.resetToDefault()
                            Toast.makeText(
                                context,
                                userAvatarMutationMessage(result, importing = false),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    enabled = avatarSnapshot.hasCustomAvatar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MineTestTags.AVATAR_RESET_ACTION),
                ) {
                    Text("恢复默认头像")
                }
                TextButton(
                    onClick = { showAvatarActions = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消")
                }
            }
        }
    }
}

private fun userAvatarMutationMessage(
    result: UserAvatarMutationResult,
    importing: Boolean,
): String = when (result) {
    UserAvatarMutationResult.Success -> if (importing) "头像已更新" else "已恢复默认头像"
    UserAvatarMutationResult.InvalidImage -> "无法读取所选图片，请换一张重试"
    UserAvatarMutationResult.SourceUnavailable -> "无法访问所选图片，请重新选择"
    UserAvatarMutationResult.StorageFailure -> "头像保存失败，请重试"
}
