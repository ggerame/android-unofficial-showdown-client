package com.majeur.psclient.util

import android.app.Activity
import android.app.Dialog
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import kotlin.math.max

fun Activity.configureEdgeToEdge() {
    window.configureEdgeToEdge(resources)
}

fun Window.configureEdgeToEdge(resources: Resources) {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    @Suppress("DEPRECATION")
    statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isNavigationBarContrastEnforced = false
    val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    WindowInsetsControllerCompat(this, decorView).apply {
        isAppearanceLightStatusBars = !isNight
        isAppearanceLightNavigationBars = !isNight
    }
}

@Suppress("DEPRECATION")
fun Dialog.resizeForIme(showKeyboard: Boolean = false) {
    val state = if (showKeyboard) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
    else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or state)
}

fun View.applySafeDrawingInsets(includeTop: Boolean = true, includeBottom: Boolean = true,
                                includeIme: Boolean = false) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = if (includeIme && view.hasWindowFocus())
            windowInsets.getInsets(WindowInsetsCompat.Type.ime()) else Insets.NONE
        view.updatePadding(
                left = initialLeft + bars.left,
                top = initialTop + if (includeTop) bars.top else 0,
                right = initialRight + bars.right,
                bottom = initialBottom + max(if (includeBottom) bars.bottom else 0, ime.bottom))
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}
