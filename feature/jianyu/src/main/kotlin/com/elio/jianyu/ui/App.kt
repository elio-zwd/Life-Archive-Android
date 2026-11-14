package com.elio.jianyu.ui

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.runtime.SideEffect
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.elio.jianyu.ui.lifecycle.sessionViewModel as viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elio.jianyu.JianyuServices as JianyuAppRuntime
import com.elio.jianyu.skill.catalog.OfficialSkillCatalogRuntimeResult
import com.elio.jianyu.skill.catalog.OfficialSkillUseRequest
import com.elio.jianyu.ui.automation.JianyuAutomationTags
import com.elio.jianyu.ui.components.JianyuNavigationIcons
import com.elio.jianyu.ui.navigation.AppDestination
import com.elio.jianyu.ui.navigation.AppNavHost
import com.elio.jianyu.ui.navigation.JianyuNavigationRoutes
import com.elio.jianyu.ui.navigation.navigateToIssue
import com.elio.jianyu.ui.navigation.navigateToSecondary
import com.elio.jianyu.ui.navigation.navigateToSkillDetail
import com.elio.jianyu.ui.navigation.navigateToSkillFavorites
import com.elio.jianyu.ui.navigation.navigateToSkillRecent
import com.elio.jianyu.ui.navigation.navigateToSkillSearch
import com.elio.jianyu.ui.navigation.navigateToTopLevel
import com.elio.jianyu.ui.screens.execution.AudioEnabledIssueExecutionRoute
import com.elio.jianyu.ui.screens.issues.IssuesRoute
import com.elio.jianyu.ui.screens.mine.MineRoute
import com.elio.jianyu.ui.screens.mine.PersonalContextRoute
import com.elio.jianyu.ui.screens.mine.DataPrivacyRoute
import com.elio.jianyu.ui.screens.mine.BackupRoute
import com.elio.jianyu.ui.screens.resources.ResourcesRoute
import com.elio.jianyu.ui.screens.settings.AiManagementRoute
import com.elio.jianyu.ui.screens.settings.AboutRoute
import com.elio.jianyu.ui.screens.settings.SettingsRoute
import com.elio.jianyu.ui.screens.settings.TelemetryRoute
import com.elio.jianyu.ui.screens.skills.OfficialSkillNavigationRoute
import com.elio.jianyu.ui.screens.skills.SkillRoleDetailRoute
import com.elio.jianyu.ui.screens.skills.SkillRoleFavoritesRoute
import com.elio.jianyu.ui.screens.skills.SkillRoleRecentRoute
import com.elio.jianyu.ui.screens.skills.SkillRoleSearchRoute
import com.elio.jianyu.viewmodel.ConversationSession as RoundtableViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun JianyuContent(
    viewModel: RoundtableViewModel,
    appRuntime: JianyuAppRuntime,
    onOfficialSkillUseRequested: (OfficialSkillUseRequest) -> Unit = {},
    onTopLevelChanged: (Boolean) -> Unit = {},
    onBottomBarMeasured: (Int) -> Unit = {},
) {
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()

    var dialogDetailVisible by remember { mutableStateOf(false) }
    val navController = rememberNavController()
    val hostActivity = androidx.activity.compose.LocalActivity.current as? ComponentActivity
    var initialIntentHandled by rememberSaveable { mutableStateOf(false) }
    var pendingSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSkillIntent by rememberSaveable { mutableStateOf<String?>(null) }
    val onUseOfficialSkill: (OfficialSkillUseRequest) -> Unit = { request ->
        pendingSkillId = request.skillId
        pendingSkillIntent = request.intent
        onOfficialSkillUseRequested(request)
        navController.navigateToTopLevel(AppDestination.HOME)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoutePattern = backStackEntry?.destination?.route
    val currentDestination = AppDestination.fromRoutePattern(currentRoutePattern)
        ?: if (currentRoutePattern == null) AppDestination.startDestination else null
    val currentTopLevel = currentDestination?.takeIf { it.showsBottomNavigation }
    SideEffect { onTopLevelChanged(currentTopLevel != null && (currentTopLevel != AppDestination.HOME || !dialogDetailVisible)) }
    // 外部 URI 要等 NavHost 建立首个目的地后再消费，避免空 back stack 竞态。
    LaunchedEffect(navController, currentRoutePattern) {
        if (currentRoutePattern != null && !initialIntentHandled) {
            initialIntentHandled = true
            hostActivity?.intent?.let(navController::handleDeepLink)
        }
    }
    val contentWindowInsets = WindowInsets(0, 0, 0, 0)
    com.elio.jianyu.ui.components.JianyuBackgroundAtmosphere {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag(JianyuAutomationTags.App.CONTENT_ROOT)
                .semantics { testTagsAsResourceId = true },
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = contentWindowInsets,
            bottomBar = {
                if (currentTopLevel != null) {
                    AppBottomNavigation(
                        currentDestination = currentTopLevel,
                        onDestinationSelected = navController::navigateToTopLevel,
                        onMeasured = onBottomBarMeasured,
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                AppNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                    homeContent = {
                        com.elio.jianyu.ui.screens.dialog.DialogRoute(
                            preferencesStore = appRuntime.preferences,
                            avatarRepository = appRuntime.avatars,
                            readingRepository = appRuntime.readingState,
                            onDetailChanged = { dialogDetailVisible = it },
                            viewModel = viewModel,
                            officialSkillCatalog = (
                                appRuntime.officialSkillCatalogRuntimeResult
                                    as? OfficialSkillCatalogRuntimeResult.Success
                                )?.runtime?.catalog,
                        )
                    },
                    issuesContent = { deepLinkedIssueId, deepLinkedStageId ->
                        var deepLinkConsumed by rememberSaveable(
                            deepLinkedIssueId,
                            deepLinkedStageId,
                        ) { mutableStateOf(false) }
                        // Navigation 的嵌套深链默认会跳过 Issue 根页；先消费一次参数再进入详情，保留返回链。
                        LaunchedEffect(deepLinkedIssueId, deepLinkedStageId) {
                            if (deepLinkedIssueId != null && !deepLinkConsumed) {
                                deepLinkConsumed = true
                                navController.navigateToIssue(
                                    issueId = deepLinkedIssueId,
                                    stageId = deepLinkedStageId,
                                )
                            }
                        }
                        IssuesRoute(
                            repository = appRuntime.repository,
                            lifecycleRuntime = appRuntime.lifecycleRuntime,
                            onOpenIssue = navController::navigateToIssue,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = {
                                navController.navigateToSecondary(AppDestination.SETTINGS)
                            },
                        )
                    },
                    issueContent = { issueId, stageId ->
                        AudioEnabledIssueExecutionRoute(
                            repository = appRuntime.repository,
                            coordinator = appRuntime.executionCoordinator,
                            collaborationCoordinator = appRuntime.collaborationCoordinator,
                            stageResultService = appRuntime.stageResultService,
                            audioRuntime = appRuntime.audioWorkspace,
                            issueId = issueId,
                            stageId = stageId,
                            onBack = { navController.popBackStack() },
                            onOpenStage = navController::navigateToIssue,
                        )
                    },
                    skillsContent = {
                        OfficialSkillNavigationRoute(
                            presentationLoader = appRuntime.rolePresentation,
                            repository = appRuntime.repository,
                            runtimeResult = appRuntime.officialSkillCatalogRuntimeResult,
                            onOpenSettings = {
                                navController.navigateToSecondary(AppDestination.SETTINGS)
                            },
                            onUseSkill = onUseOfficialSkill,
                            onOpenSkillDetail = navController::navigateToSkillDetail,
                            onNavigateToSearch = navController::navigateToSkillSearch,
                            onNavigateToFavorites = navController::navigateToSkillFavorites,
                            onNavigateToRecent = navController::navigateToSkillRecent,
                        )
                    },
                    skillSearchContent = {
                        SkillRoleSearchRoute(
    presentationLoader = appRuntime.rolePresentation,
                            runtimeResult = appRuntime.officialSkillCatalogRuntimeResult,
                            onBack = { navController.popBackStack() },
                            onOpenSkillDetail = navController::navigateToSkillDetail,
                        )
                    },
                    skillFavoritesContent = {
                        SkillRoleFavoritesRoute(
    presentationLoader = appRuntime.rolePresentation,
                            runtimeResult = appRuntime.officialSkillCatalogRuntimeResult,
                            onBack = { navController.popBackStack() },
                            onOpenSkillDetail = navController::navigateToSkillDetail,
                            onBrowseAllRoles = {
                                navController.popBackStack(AppDestination.SKILLS.routePattern, inclusive = false)
                            },
                        )
                    },
                    skillRecentContent = {
                        val runtime = (
                            appRuntime.officialSkillCatalogRuntimeResult
                                as? OfficialSkillCatalogRuntimeResult.Success
                            )?.runtime
                        SkillRoleRecentRoute(
    presentationLoader = appRuntime.rolePresentation,
                            runtimeResult = appRuntime.officialSkillCatalogRuntimeResult,
                            onBack = { navController.popBackStack() },
                            onOpenSkillDetail = navController::navigateToSkillDetail,
                            onStartNewConversation = { selectedSkillId ->
                                val success = viewModel.createNewSessionWithSkillRole(selectedSkillId)
                                if (success) {
                                    runtime?.preferences?.recordSkillUsed(
                                        selectedSkillId,
                                        System.currentTimeMillis(),
                                    )
                                    navController.navigateToTopLevel(AppDestination.HOME)
                                }
                                success
                            },
                        )
                    },
                    skillDetailContent = { skillId ->
                        val runtime = (
                            appRuntime.officialSkillCatalogRuntimeResult
                                as? OfficialSkillCatalogRuntimeResult.Success
                            )?.runtime
                        SkillRoleDetailRoute(
    presentationLoader = appRuntime.rolePresentation,
                            runtimeResult = appRuntime.officialSkillCatalogRuntimeResult,
                            skillId = skillId,
                            canAddToCurrentConversation = currentSessionId != null,
                            onBack = { navController.popBackStack() },
                            onStartNewConversation = { selectedSkillId ->
                                val success = viewModel.createNewSessionWithSkillRole(selectedSkillId)
                                if (success) {
                                    runtime?.preferences?.recordSkillUsed(
                                        selectedSkillId,
                                        System.currentTimeMillis(),
                                    )
                                }
                                success
                            },
                            onAddToCurrentConversation = { selectedSkillId ->
                                val success = viewModel.addSkillRoleToCurrentSessionAwait(selectedSkillId)
                                if (success) {
                                    runtime?.preferences?.recordSkillUsed(
                                        selectedSkillId,
                                        System.currentTimeMillis(),
                                    )
                                }
                                success
                            },
                            onConversationReady = {
                                navController.navigateToTopLevel(AppDestination.HOME)
                            },
                        )
                    },
                    resourcesContent = { tab ->
                        ResourcesRoute(
                            materialReader = appRuntime.materials,
                            repository = appRuntime.repository,
                            skillKnowledgeRepository = appRuntime.skillKnowledgeRepository,
                            initialTab = tab,
                            onOpenSettings = {
                                navController.navigateToSecondary(AppDestination.SETTINGS)
                            },
                            onOpenIssue = navController::navigateToIssue,
                            onUseSkillKnowledgeInConversation = { selection ->
                                val success = viewModel.addSkillKnowledgeToCurrentConversation(selection)
                                if (success) {
                                    navController.navigateToTopLevel(AppDestination.HOME)
                                }
                                success
                            },
                        )
                    },
                    mineContent = {
                        MineRoute(
                            ai = appRuntime.ai,
                            diagnostics = appRuntime.diagnostics,
                            avatarRepository = appRuntime.avatars,
                            repository = appRuntime.repository,
                            onOpenPersonalContext = {
                                navController.navigateToSecondary(AppDestination.PERSONAL_CONTEXT)
                            },
                            onOpenAbout = {
                                navController.navigateToSecondary(AppDestination.ABOUT)
                            },
                            onOpenDataPrivacy = {
                                navController.navigateToSecondary(AppDestination.DATA_PRIVACY)
                            },
                            onOpenBackup = {
                                navController.navigateToSecondary(AppDestination.BACKUP_RESTORE)
                            },
                            onOpenSettings = {
                                navController.navigateToSecondary(AppDestination.SETTINGS)
                            },
                            onOpenAiManagement = {
                                navController.navigateToSecondary(AppDestination.API_KEYS)
                            },
                            onOpenTelemetry = {
                                navController.navigateToSecondary(AppDestination.TELEMETRY)
                            },
                        )
                    },
                    settingsContent = {
                        SettingsRoute(
                            preferencesStore = appRuntime.preferences,
                            onBack = { navController.popBackStack() },
                            onOpenAiManagement = {
                                navController.navigateToSecondary(AppDestination.API_KEYS)
                            },
                            onOpenTelemetry = {
                                navController.navigateToSecondary(AppDestination.TELEMETRY)
                            },
                        )
                    },
                    aiManagementContent = {
                        AiManagementRoute(
                            ai = appRuntime.ai,
                            currentSessionId = currentSessionId,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    telemetryContent = {
                        TelemetryRoute(
                            diagnostics = appRuntime.diagnostics,
                            currentSessionId = currentSessionId,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    personalContextContent = {
                        PersonalContextRoute(
                            repository = appRuntime.repository,
                            onBack = { navController.popBackStack() },
                        )
                    },
                    aboutContent = {
                        AboutRoute(onBack = { navController.popBackStack() })
                    },
                    dataPrivacyContent = {
                        val officialSkillPreferences = (
                            appRuntime.officialSkillCatalogRuntimeResult
                                as? OfficialSkillCatalogRuntimeResult.Success
                            )?.runtime?.preferences
                        DataPrivacyRoute(
                            maintenance = appRuntime.maintenance,
                            repository = appRuntime.repository,
                            onBack = { navController.popBackStack() },
                            onOpenBackup = {
                                navController.navigateToSecondary(AppDestination.BACKUP_RESTORE)
                            },
                            onOpenTelemetry = {
                                navController.navigateToSecondary(AppDestination.TELEMETRY)
                            },
                            officialSkillPreferences = officialSkillPreferences,
                            onPrepareForLocalDataDeletion = viewModel::prepareForLocalDataDeletion,
                            onClearConversationPreferences = viewModel::clearLocalPreferencesAfterDataDeletion,
                        )
                    },
                    backupContent = {
                        BackupRoute(
                            backup = appRuntime.backup,
                            repository = appRuntime.repository,
                            onBack = { navController.popBackStack() },
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun AppBottomNavigation(
    currentDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    onMeasured: (Int) -> Unit = {},
) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        NavigationBar(
            // 比 Material 默认规格低一档，仍高于 48dp 的单项最小触控面积；
            // 底部系统手势安全区继续由 NavigationBar 默认 Insets 处理。
            modifier = Modifier
                .height(CompactNavigationBarHeight)
                .onSizeChanged { onMeasured(it.height) }
                .testTag(AppTestTags.BOTTOM_NAVIGATION),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            AppDestination.topLevelDestinations.forEach { destination ->
                val icon = when (destination) {
                    AppDestination.HOME -> Icons.Default.Home
                    AppDestination.SKILLS -> JianyuNavigationIcons.Skills
                    AppDestination.RESOURCES -> JianyuNavigationIcons.Resources
                    AppDestination.MINE -> Icons.Default.Person
                    else -> error("非一级目的地不能显示在底部导航")
                }
                NavigationBarItem(
                    selected = currentDestination == destination,
                    onClick = { onDestinationSelected(destination) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = destination.label,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier
                        .semantics { contentDescription = destination.label }
                        .testTag(AppTestTags.destination(destination)),
                )
            }
        }
    }
}


object AppTestTags {
    const val BOTTOM_NAVIGATION = JianyuAutomationTags.App.BOTTOM_NAVIGATION

    fun destination(destination: AppDestination): String =
        JianyuAutomationTags.Navigation.destination(destination.testTagSuffix)
}

private val CompactNavigationBarHeight = 72.dp

/**
 * App 根宿主观察 Runtime 世代；维护期间移除全部数据库消费者，重开后使用全新的
 * ViewModelStore 与 NavController，旧 ViewModel 不会跨世代继续持有失效 DAO。
 */
