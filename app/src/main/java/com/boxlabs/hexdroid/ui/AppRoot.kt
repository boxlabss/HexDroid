/*
* HexDroidIRC - An IRC Client for Android
* Copyright (C) 2026 boxlabs
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.boxlabs.hexdroid.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.boxlabs.hexdroid.AppScreen
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.data.ThemeMode
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.boxlabs.hexdroid.INTRO_TOUR_VERSION
import com.boxlabs.hexdroid.ui.tour.IntroTourOverlay
import com.boxlabs.hexdroid.ui.tour.IntroTourActionId
import com.boxlabs.hexdroid.ui.tour.LocalTourRegistry
import com.boxlabs.hexdroid.ui.tour.TourRegistry
import com.boxlabs.hexdroid.ui.tour.buildIntroTour

@Composable
fun AppRoot(
    vm: IrcViewModel,
    onExit: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()

	val tourRegistry = remember { TourRegistry() }
	var tourActive by rememberSaveable { mutableStateOf(false) }
	var tourStepIndex by rememberSaveable { mutableStateOf(0) }
	var tourReturnScreen by rememberSaveable { mutableStateOf<AppScreen?>(null) }
	var tourSuppressedThisSession by rememberSaveable { mutableStateOf(false) }

	// Android 17+: request ACCESS_LOCAL_NETWORK when user tries to connect to a LAN server.
	var localNetworkPermissionNetId by remember { mutableStateOf<String?>(null) }
	val requestLocalNetwork = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission()
	) { granted ->
		val netId = localNetworkPermissionNetId
		localNetworkPermissionNetId = null
		if (granted && netId != null) vm.retryAfterLocalNetworkPermission(netId)
		else vm.dismissLocalNetworkWarning()
	}

	// Track whether the welcome screen was shown and completed this session.
	var welcomeCompletedThisSession by rememberSaveable { mutableStateOf(false) }

	// Show the welcome screen on first launch (before the tour).
	// It's gated on settingsLoaded so we don't flash it before prefs are read.
	val showWelcome = state.settingsLoaded &&
		!state.settings.welcomeCompleted &&
		!welcomeCompletedThisSession

	// Keep the step list stable while the tour is running.
	// (Dynamic insertion/removal can cause the tour to "jump" screens unexpectedly.)
	val tourContext = LocalContext.current
	val tourSteps = remember { buildIntroTour(tourContext) }
	val currentTourStep = tourSteps.getOrNull(tourStepIndex).takeIf { tourActive }

	val startTour: (auto: Boolean) -> Unit = { auto ->
		// Remember where the user was so we can return them when the tour ends.
		tourReturnScreen = state.screen
		tourActive = true
		tourStepIndex = 0
		// Mark as seen immediately so it doesn't replay forever if the app is closed mid-tour,
		// and so a user who skips it won't see it again on next startup.
		if (state.settings.introTourSeenVersion < INTRO_TOUR_VERSION) {
			vm.updateSettings { copy(introTourSeenVersion = INTRO_TOUR_VERSION) }
		}
	}

	val finishTour: () -> Unit = {
		tourSuppressedThisSession = true
		tourActive = false
		vm.updateSettings { copy(introTourSeenVersion = INTRO_TOUR_VERSION) }
		tourReturnScreen?.let { vm.goTo(it) }
		tourReturnScreen = null
	}

	// Auto-run on first launch (or after a tour bump).
	LaunchedEffect(state.settingsLoaded, state.settings.introTourSeenVersion) {
		if (state.settingsLoaded && !tourActive && !tourSuppressedThisSession && state.settings.introTourSeenVersion < INTRO_TOUR_VERSION && tourSteps.isNotEmpty()) {
			startTour(true)
		}
	}

	// Keep navigation in sync with the active tour step (fixes "Back" and external navigation).
	LaunchedEffect(tourActive, tourStepIndex, state.screen) {
		if (!tourActive) return@LaunchedEffect
		val desired = currentTourStep?.screen ?: return@LaunchedEffect
		if (state.screen != desired) vm.goTo(desired)
	}

	// Clear stale target rects when the tour step changes, not on every screen change.
	// Clearing on screen change was too aggressive: lazy-column items off-screen never
	// re-register, leaving the registry permanently empty for those steps.
	LaunchedEffect(tourStepIndex) { tourRegistry.targets.clear() }


    // Keep connection state in sync when returning to foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.resyncConnectionsOnResume()
            }
        }
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }


    val themeMode = state.settings.themeMode
    val darkTheme = when (themeMode) {
        ThemeMode.DARK, ThemeMode.MATRIX, ThemeMode.TERMINAL -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }


    // Ensure status/nav bar icon colors follow the in-app theme selection (not just system night mode).
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    DisposableEffect(darkTheme, view) {
        activity?.let { act ->
            val controller = WindowCompat.getInsetsController(act.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            // On pre-API 30, the system bars are painted using the XML theme colours
            // (values/themes.xml hard-codes white for light mode). When the user picks a
            // dark in-app theme those XML colours are never updated, producing white
            // stripes on the status and nav bars. Set them programmatically here so they
            // always match the active in-app theme regardless of OS version.
            @Suppress("DEPRECATION") // statusBarColor/navigationBarColor deprecated in API 35,
            // but this branch only runs on pre-API 30 where they are the correct API.
            if (Build.VERSION.SDK_INT < 30) {
                val barColor = if (darkTheme) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
                act.window.statusBarColor = barColor
                act.window.navigationBarColor = barColor
            }
        }
        onDispose { }
    }

    val baseDensity = LocalDensity.current
    val fontScale = state.settings.fontScale.coerceIn(0.60f, 1.50f)
    val scaledDensity = Density(density = baseDensity.density, fontScale = baseDensity.fontScale * fontScale)

    key(themeMode, state.settings.fontChoice, state.settings.customFontPath) {
        HexDroidIRCTheme(
            themeMode = themeMode,
            fontChoice = state.settings.fontChoice,
            customFontPath = state.settings.customFontPath
        ) {
        Surface(modifier = Modifier.fillMaxSize()) {

        // Welcome screen gate: shown before everything else on first launch.
        if (showWelcome) {
            WelcomeScreen(
                onContinue = { langCode, nick ->
                    vm.completeWelcome(langCode, nick)
                    welcomeCompletedThisSession = true
                }
            )
        } else {

        CompositionLocalProvider(LocalDensity provides scaledDensity, LocalTourRegistry provides tourRegistry) {

	            // Tour back handling: step back, or exit the tour on the first step.
	            BackHandler(enabled = tourActive) {
	                if (tourStepIndex > 0) tourStepIndex -= 1 else finishTour()
	            }

	            // Only intercept back when we have an in-app screen to pop.
	            BackHandler(enabled = !tourActive && state.screen != AppScreen.CHAT) {
                when (state.screen) {
                    AppScreen.NETWORK_EDIT -> vm.cancelEditNetwork()
                    AppScreen.LIST,
                    AppScreen.SETTINGS,
                    AppScreen.TRANSFERS,
                    AppScreen.ABOUT -> vm.backToChat()
                    AppScreen.IGNORE -> vm.goTo(AppScreen.SETTINGS)
                    AppScreen.NETWORKS -> vm.backToChat()
                    else -> vm.backToChat()
                }
            }


            // ── DCC "Send file from nick actions" flow ───────────────────────────────
            // Hoisted here (above the `when`) so that rememberLauncherForActivityResult
            // is called unconditionally in composable scope. Placing it inside a `when`
            // branch would violate the rules of hooks and cause a crash.
            var dccPendingNick by remember { mutableStateOf("") }
            val dccFilePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null && dccPendingNick.isNotBlank()) {
                    vm.sendDccFileFlow(uri, dccPendingNick)
                    dccPendingNick = ""
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {

            when (state.screen) {
                AppScreen.CHAT -> ChatScreen(
                    state = state,
                    onSelectBuffer = vm::openBuffer,
                    onSend = vm::sendInput,
                    onSendReply = vm::sendToBuffer,
                    onSendReaction = { msgId, emoji, remove -> vm.sendReaction(msgId, emoji, remove) },
                    onDisconnect = vm::disconnectActive,
                    onReconnect = vm::reconnectActive,
                    onExit = onExit,
                    onToggleBufferList = vm::toggleBufferList,
                    onToggleNickList = vm::toggleNickList,
                    onToggleChannelsOnly = vm::toggleChannelsOnly,
                    onWhois = vm::whois,
                    onIgnoreNick = vm::ignoreNick,
                    onUnignoreNick = vm::unignoreNick,
					onRefreshNicklist = vm::refreshNicklistForSelectedBuffer,
                    onDccSendFile = { nick ->
                        dccPendingNick = nick
                        dccFilePicker.launch(arrayOf("*/*"))
                    },
                    onDccChat = vm::startDccChat,
                    onOpenList = { vm.goTo(AppScreen.LIST) },
                    onOpenSettings = { vm.goTo(AppScreen.SETTINGS) },
                    onOpenNetworks = { vm.goTo(AppScreen.NETWORKS) },
                    onOpenTransfers = { vm.goTo(AppScreen.TRANSFERS) },
                    onSysInfo = { vm.sendInput("/sysinfo") },
                    onAbout = { vm.goTo(AppScreen.ABOUT) },
                    onUpdateSettings = vm::updateSettings,
                    onReorderNetworks = vm::reorderNetworks,
                    onToggleNetworkExpanded = vm::toggleNetworkExpanded,
                    onTypingChanged = vm::notifyTypingChanged,
                    onMarkRead = vm::markBufferRead,
                    onHighlightConsumed = vm::clearHighlightScroll,
                    onCloseFindOverlay = vm::closeFindOverlay,
                    onFindNavigate = vm::findNavigate,
                    onShareTextConsumed = vm::consumeShareText,
                    tourActive = tourActive,
                    tourTarget = currentTourStep?.target,
                )

                AppScreen.NETWORKS -> NetworksScreen(
                    state = state,
					onBack = vm::backToChat,
                    onSelect = vm::setActiveNetwork,
                    onAdd = vm::startAddNetwork,
                    onEdit = vm::startEditNetwork,
                    onDelete = vm::deleteNetwork,
                    onSetAutoConnect = vm::setNetworkAutoConnect,
                    onConnect = vm::connectNetwork,
                    onDisconnect = vm::disconnectNetwork,
                    onAllowPlaintextConnect = vm::allowPlaintextAndConnect,
                    onDismissPlaintextWarning = vm::dismissPlaintextWarning,
                    onRequestLocalNetworkPermission = { netId ->
                        localNetworkPermissionNetId = netId
                        requestLocalNetwork.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    },
                    onDismissLocalNetworkWarning = vm::dismissLocalNetworkWarning,
                    onOpenSettings = { vm.goTo(AppScreen.SETTINGS) },
                    onReorder = vm::reorderNetworks,
                    onToggleFavourite = vm::toggleFavourite,
                    tourActive = tourActive,
                    tourTarget = currentTourStep?.target,
                )

                AppScreen.NETWORK_EDIT -> NetworkEditScreen(
                    state = state,
                    onCancel = vm::cancelEditNetwork,
                    onSave = vm::saveEditingNetwork
                )

                AppScreen.LIST -> ListScreen(
                    state = state,
                    onBack = vm::backToChat,
                    onRefresh = vm::requestList,
                    onFilterChange = vm::setListFilter,
                    onSortChange = vm::setListSort,
                    onJoin = vm::joinChannel,
                    onOpenSettings = { vm.goTo(AppScreen.SETTINGS) },
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    state = state,
                    onBack = vm::backToChat,
                    onUpdate = vm::updateSettings,
                    onRunTour = {
	                        startTour(false)
                    },
                    onOpenNetworks = { vm.goTo(AppScreen.NETWORKS) },
                    onOpenIgnoreList = vm::openIgnoreList,
                    tourActive = tourActive,
                    tourTarget = currentTourStep?.target,
                    onExportBackup = vm::exportBackup,
                    onImportBackup = vm::importBackup,
                    onClearBackupMessage = vm::clearBackupMessage,
                )

                AppScreen.TRANSFERS -> TransfersScreen(
                    state = state,
                    onBack = vm::backToChat,
                    onAccept = vm::acceptDcc,
                    onReject = vm::rejectDcc,
                    onAcceptChat = vm::acceptDccChat,
                    onRejectChat = vm::rejectDccChat,
                    onSend = vm::sendDccFileFlow,
                    onStartChat = vm::startDccChat,
                    onShareFile = vm::shareFile,
                    onSetDccEnabled = vm::setDccEnabled,
                    onSetDccSendMode = vm::setDccSendMode,
                    onCancelOutgoing = vm::cancelOutgoingDcc
                )

                AppScreen.ABOUT -> AboutScreen(
                    onBack = vm::backToChat
                )
            
                AppScreen.IGNORE -> IgnoreListScreen(
                    state = state,
                    onBack = { vm.goTo(AppScreen.SETTINGS) },
                    onIgnoreNick = vm::ignoreNick,
                    onUnignoreNick = vm::unignoreNick,
                )
}


    val step = currentTourStep
    if (tourActive && step != null) {
        IntroTourOverlay(
            step = step,
            stepIndex = tourStepIndex,
            stepCount = tourSteps.size,
            registry = tourRegistry,
            onBack = if (tourStepIndex > 0) ({ tourStepIndex -= 1 }) else null,
            onNext = {
                val next = tourStepIndex + 1
                if (next >= tourSteps.size) {
	                    finishTour()
	                } else {
	                    tourStepIndex = next
	                }
            },
            onSkip = {
	                finishTour()
            },
            onAction = { actionId ->
                when (actionId) {
                    IntroTourActionId.ADD_AFTERNET -> vm.addAfterNetDefaults()
                }
            },
        )
    }
}

        } // end else (welcome completed)
        }
        }
    }
    }
}