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

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.boxlabs.hexdroid.ui.theme.HexDroidIRCTheme
import com.boxlabs.hexdroid.IrcViewModel
import kotlin.math.roundToInt

/**
 * A draggable window that floats above other apps.
 *
 * Unlike picture-in-picture this one takes touches and can hold the keyboard, which is what
 * makes it usable for replying rather than only reading. The cost is the "display over other
 * apps" permission, which the user grants in system settings rather than a dialog.
 *
 * The window is added to the application context, so it outlives the activity and stays up
 * while other apps are in front. Nothing here may hold an activity: [show] takes only an
 * application context and a callback that must not capture one.
 */
object FloatingWindow {

    /** How much of the window is always kept on screen, in dp. */
    private const val MIN_VISIBLE_DP = 72

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    /** True while the window is on screen. */
    val isShowing: Boolean get() = view != null

    /** Whether the user has granted "display over other apps". */
    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    /** Take the user to the system page where the permission is granted. */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Put the window up, or do nothing if it is already there or the permission is missing.
     *
     * [onOpenApp] restores the full app, for the button in the window's header. It is held
     * for the life of the window, so it must not capture an activity.
     */
    fun show(ctx: Context, vm: IrcViewModel, onOpenApp: () -> Unit) {
        val app = ctx.applicationContext
        if (view != null || !hasPermission(app)) return

        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density

        val lp = WindowManager.LayoutParams(
            (300 * density).roundToInt(),
            (380 * density).roundToInt(),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_TOUCH_MODAL is what lets everything outside the window still be tapped;
            // without it the window is modal over the whole screen even though it only
            // covers a corner of it. NOT_FOCUSABLE keeps the keyboard away until the
            // composer asks for it, which is the only way an overlay can raise one.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).roundToInt()
            y = (96 * density).roundToInt()
        }

        val overlayOwner = OverlayOwner().apply { onCreate() }
        val compose = ComposeView(app).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
            setViewTreeViewModelStoreOwner(overlayOwner)
            setContent {
                // Wrapped in the app's theme: a ComposeView added to the window manager
                // inherits nothing, so without this it drew in the stock palette and font.
                val ui by vm.state.collectAsStateWithLifecycle()
                HexDroidIRCTheme(
                    themeMode = ui.settings.themeMode,
                    fontChoice = ui.settings.fontChoice,
                    customFontPath = ui.settings.customFontPath,
                ) {
                    FloatingChat(
                        vm = vm,
                        onDrag = { dx, dy -> moveBy(app, wm, dx, dy) },
                        onResize = { dx, dy -> resizeBy(app, wm, dx, dy, density) },
                        onKeyboard = { wanted -> setFocusable(wm, wanted) },
                        onOpenApp = { hide(app); onOpenApp() },
                        onClose = { hide(app) },
                    )
                }
            }
        }

        view = compose
        owner = overlayOwner
        params = lp
        runCatching { wm.addView(compose, lp) }
            .onFailure { view = null; owner = null; params = null; overlayOwner.onDestroy() }
    }

    /** Take the window down. */
    fun hide(ctx: Context) {
        val v = view ?: return
        val wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        owner?.onDestroy()
        view = null
        owner = null
        params = null
    }

    /** Grow or shrink from the bottom-right corner, within sensible bounds. */
    private fun resizeBy(ctx: Context, wm: WindowManager, dx: Float, dy: Float, density: Float) {
        val v = view ?: return
        val lp = params ?: return
        lp.width = (lp.width + dx.roundToInt()).coerceIn((200 * density).roundToInt(), (600 * density).roundToInt())
        lp.height = (lp.height + dy.roundToInt()).coerceIn((160 * density).roundToInt(), (800 * density).roundToInt())
        clampToScreen(ctx, lp)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun moveBy(ctx: Context, wm: WindowManager, dx: Float, dy: Float) {
        val v = view ?: return
        val lp = params ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        clampToScreen(ctx, lp)
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /**
     * Keep a corner of the title bar reachable.
     *
     * Without this the window can be dragged past an edge and left with no visible way to
     * move or close it.
     */
    private fun clampToScreen(ctx: Context, lp: WindowManager.LayoutParams) {
        val metrics = ctx.resources.displayMetrics
        val margin = (MIN_VISIBLE_DP * metrics.density).roundToInt()
        val visible = minOf(margin, lp.width, lp.height)
        lp.x = lp.x.coerceIn(visible - lp.width, metrics.widthPixels - visible)
        lp.y = lp.y.coerceIn(0, metrics.heightPixels - visible)
    }

    /**
     * Let the window take focus, or give it back.
     *
     * An overlay cannot show a keyboard while it is not focusable, and cannot let touches
     * through to what is behind it while it is, so the flag is flipped around the composer
     * rather than being set one way.
     */
    private fun setFocusable(wm: WindowManager, focusable: Boolean) {
        val v = view ?: return
        val lp = params ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.flags = if (focusable) lp.flags and flag.inv() else lp.flags or flag
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /**
     * The owners a ComposeView needs to run outside an activity.
     *
     * Compose reads its lifecycle, saved state and view-model store from the view tree, and
     * a view added straight to the window manager has none of them.
     */
    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun onCreate() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
