package com.example.playeragent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.playeragent.R

class PlayerAgentNavigationView(
    private val context: Context,
    private val onSetupAction: (SetupAction) -> Unit,
    private val onSafeRepair: () -> Unit,
    private val onRelinkLyrics: () -> Unit
) {
    data class Bindings(
        val root: View,
        val statusTitle: TextView,
        val statusDetail: TextView,
        val serviceSummary: TextView,
        val bleSummary: TextView,
        val iPhoneSummary: TextView,
        val currentAlbumArt: ImageView,
        val currentAlbumArtStatus: TextView,
        val currentSong: TextView,
        val currentArtistAlbum: TextView,
        val currentLyric: TextView,
        val suggestedAction: Button,
        val setupProgress: TextView,
        val accessibilityStatus: TextView,
        val linkHost: LinearLayout,
        val lyricsHost: LinearLayout,
        val artworkHost: LinearLayout,
        val logsHost: LinearLayout
    )

    private val canvas = Color.rgb(6, 9, 14)
    private val surface1 = Color.rgb(15, 21, 31)
    private val surface2 = Color.rgb(24, 34, 48)
    private val primary = Color.rgb(245, 247, 251)
    private val secondary = Color.rgb(167, 176, 189)
    private val quiet = Color.rgb(105, 116, 132)
    private val healthy = Color.rgb(100, 217, 140)
    private val warning = Color.rgb(242, 181, 109)
    private val accent = Color.rgb(120, 219, 195)

    private val pageHost = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(canvas)
    }
    private val homePage = pageScroll()
    private val setupPage = pageScroll()
    private val diagnosticsPage = pageScroll()
    private val setupRows = mutableListOf<SetupRow>()
    private val diagnosticHosts = mutableListOf<LinearLayout>()

    private val statusTitle = titleText(30f)
    private val statusDetail = bodyText()
    private val serviceSummary = summaryValue()
    private val bleSummary = summaryValue()
    private val iPhoneSummary = summaryValue()
    private val setupProgress = bodyText()
    private val accessibilityStatus = bodyText()
    private val lyricReadinessSummary = summaryValue()
    private val artworkReadinessSummary = summaryValue()
    private val currentAlbumArt = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = background(surface2, 18)
        setImageResource(android.R.drawable.ic_media_play)
        contentDescription = context.getString(R.string.home_current_playback)
    }
    private val currentAlbumArtStatus = bodyText()
    private val currentSong = titleText(22f).apply {
        text = context.getString(R.string.home_no_playback)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val currentArtistAlbum = bodyText().apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val currentLyric = bodyText().apply {
        text = context.getString(R.string.home_no_lyric)
        maxLines = 3
    }
    private val suggestedAction = actionButton(
        context.getString(R.string.setup_action),
        onRelinkLyrics
    ).apply { visibility = View.GONE }
    private val linkHost = verticalHost()
    private val lyricsHost = verticalHost()
    private val artworkHost = verticalHost()
    private val logsHost = verticalHost()

    fun build(): Bindings {
        buildHome()
        buildSetup()
        buildDiagnostics()

        val navigation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(surface1)
        }
        navigation.addView(navButton(R.string.nav_home) { showPage(homePage) }, weighted())
        navigation.addView(navButton(R.string.nav_setup) { showPage(setupPage) }, weighted())
        navigation.addView(navButton(R.string.nav_diagnostics) { showPage(diagnosticsPage) }, weighted())

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            addView(pageHost, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(navigation, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        showPage(homePage)
        return Bindings(
            root = root,
            statusTitle = statusTitle,
            statusDetail = statusDetail,
            serviceSummary = serviceSummary,
            bleSummary = bleSummary,
            iPhoneSummary = iPhoneSummary,
            currentAlbumArt = currentAlbumArt,
            currentAlbumArtStatus = currentAlbumArtStatus,
            currentSong = currentSong,
            currentArtistAlbum = currentArtistAlbum,
            currentLyric = currentLyric,
            suggestedAction = suggestedAction,
            setupProgress = setupProgress,
            accessibilityStatus = accessibilityStatus,
            linkHost = linkHost,
            lyricsHost = lyricsHost,
            artworkHost = artworkHost,
            logsHost = logsHost
        )
    }

    fun render(state: PlayerAgentProductUiState) {
        statusTitle.text = state.statusTitle.resolve(context)
        statusDetail.text = state.statusDetail.resolve(context)
        serviceSummary.text = state.serviceSummary.resolve(context)
        bleSummary.text = state.bleSummary.resolve(context)
        iPhoneSummary.text = state.iPhoneSummary.resolve(context)
        lyricReadinessSummary.text = state.lyricSummary.ifBlank {
            context.getString(R.string.summary_unavailable)
        }
        artworkReadinessSummary.text = state.artworkSummary.resolve(context)
        currentAlbumArtStatus.text = context.getString(
            R.string.home_artwork_status,
            state.artworkSummary.resolve(context)
        )
        setupProgress.text = context.getString(
            R.string.home_setup_progress,
            state.completedSetupSteps,
            state.setupSteps.size
        )
        setupRows.zip(state.setupSteps).forEach { (row, step) ->
            row.title.text = step.title.resolve(context)
            row.detail.text = step.detail.resolve(context)
            row.status.text = context.getString(
                if (step.complete) R.string.setup_complete else R.string.setup_pending
            )
            row.status.setTextColor(if (step.complete) healthy else warning)
            row.action.visibility = if (step.complete) View.GONE else View.VISIBLE
            row.action.setOnClickListener { onSetupAction(step.action) }
        }
    }

    fun showSetup() = showPage(setupPage)

    private fun buildHome() {
        val content = homePage.getChildAt(0) as LinearLayout
        addBrand(content)
        content.addView(card().apply {
            addView(statusTitle)
            addView(statusDetail)
        })

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(summaryCard(R.string.home_service, serviceSummary), weighted(4))
            addView(summaryCard(R.string.home_ble, bleSummary), weighted(4))
            addView(summaryCard(R.string.home_iphone, iPhoneSummary), weighted())
        })

        content.addView(card().apply {
            addView(sectionTitle(R.string.home_current_playback))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(currentAlbumArt, LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                    marginEnd = dp(14)
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(currentSong)
                    addView(currentArtistAlbum)
                    addView(currentLyric)
                    addView(currentAlbumArtStatus)
                    addView(suggestedAction)
                }, weighted())
            })
        })

        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(summaryCard(R.string.home_lyrics, lyricReadinessSummary), weighted(4))
            addView(summaryCard(R.string.home_artwork, artworkReadinessSummary), weighted())
        })

        content.addView(card().apply {
            addView(setupProgress)
            addView(actionButton(context.getString(R.string.home_open_setup), ::showSetup))
        })
        content.addView(actionButton(context.getString(R.string.home_check_repair), onSafeRepair).apply {
            minHeight = dp(52)
            background = background(accent, 14)
            setTextColor(Color.rgb(6, 20, 18))
        })
    }

    private fun buildSetup() {
        val content = setupPage.getChildAt(0) as LinearLayout
        addBrand(content)
        content.addView(titleText(24f).apply { text = context.getString(R.string.setup_title) })
        content.addView(bodyText().apply { text = context.getString(R.string.setup_subtitle) })
        repeat(4) {
            val row = SetupRow(
                title = titleText(17f),
                detail = bodyText(),
                status = bodyText(),
                action = actionButton(context.getString(R.string.setup_action)) {}
            )
            setupRows += row
            content.addView(card().apply {
                addView(row.title)
                addView(row.detail)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(row.status, weighted())
                    addView(row.action)
                })
            })
        }
        content.addView(card().apply {
            addView(titleText(17f).apply { text = context.getString(R.string.setup_background_title) })
            addView(bodyText().apply { text = context.getString(R.string.setup_background_detail) })
            addView(accessibilityStatus)
        })
    }

    private fun buildDiagnostics() {
        val content = diagnosticsPage.getChildAt(0) as LinearLayout
        addBrand(content)
        content.addView(titleText(24f).apply { text = context.getString(R.string.diagnostics_title) })
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            R.string.diagnostics_link to linkHost,
            R.string.diagnostics_lyrics to lyricsHost,
            R.string.diagnostics_artwork to artworkHost,
            R.string.diagnostics_logs to logsHost
        ).forEach { (label, host) ->
            diagnosticHosts += host
            tabs.addView(navButton(label) { showDiagnosticHost(host) }, weighted())
        }
        content.addView(tabs)
        diagnosticHosts.forEach { content.addView(it) }
        showDiagnosticHost(linkHost)
    }

    private fun showPage(page: ScrollView) {
        pageHost.removeAllViews()
        (page.parent as? LinearLayout)?.removeView(page)
        pageHost.addView(page, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun showDiagnosticHost(selected: LinearLayout) {
        diagnosticHosts.forEach { it.visibility = if (it === selected) View.VISIBLE else View.GONE }
    }

    private fun addBrand(parent: LinearLayout) {
        parent.addView(titleText(26f).apply { text = context.getString(R.string.app_name) })
        parent.addView(bodyText().apply { text = context.getString(R.string.app_subtitle) })
    }

    private fun pageScroll(): ScrollView {
        return ScrollView(context).apply {
            setBackgroundColor(canvas)
            isFillViewport = true
            addView(verticalHost().apply { setPadding(dp(18), dp(20), dp(18), dp(30)) })
        }
    }

    private fun verticalHost() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = background(surface1, 18)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(7), 0, dp(7)) }
    }

    private fun summaryCard(label: Int, value: TextView) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = background(surface1, 14)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        addView(TextView(context).apply {
            text = context.getString(label)
            textSize = 12f
            setTextColor(quiet)
        })
        addView(value)
    }

    private fun titleText(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(primary)
        setPadding(0, dp(3), 0, dp(5))
    }

    private fun bodyText() = TextView(context).apply {
        textSize = 14f
        setTextColor(secondary)
        setPadding(0, dp(3), 0, dp(6))
    }

    private fun summaryValue() = TextView(context).apply {
        textSize = 15f
        setTextColor(primary)
        maxLines = 2
    }

    private fun sectionTitle(label: Int) = titleText(18f).apply { text = context.getString(label) }

    private fun actionButton(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(primary)
        background = background(surface2, 12)
        setOnClickListener { action() }
    }

    private fun navButton(label: Int, action: () -> Unit) = actionButton(
        context.getString(label),
        action
    ).apply { background = background(surface1, 12) }

    private fun weighted(marginEnd: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        this.marginEnd = dp(marginEnd)
    }

    private fun background(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private data class SetupRow(
        val title: TextView,
        val detail: TextView,
        val status: TextView,
        val action: Button
    )
}

fun UiTextKey.resolve(context: Context): String = context.getString(
    when (this) {
        UiTextKey.STATUS_CONTROLLABLE -> R.string.status_controllable
        UiTextKey.STATUS_CONTROLLABLE_DETAIL -> R.string.status_controllable_detail
        UiTextKey.STATUS_RECOVERING -> R.string.status_recovering
        UiTextKey.STATUS_RECOVERING_DETAIL -> R.string.status_recovering_detail
        UiTextKey.STATUS_SETUP_REQUIRED -> R.string.status_setup_required
        UiTextKey.STATUS_SETUP_REQUIRED_DETAIL -> R.string.status_setup_required_detail
        UiTextKey.STATUS_ACTION_REQUIRED -> R.string.status_action_required
        UiTextKey.STATUS_SERVICE_STOPPED_DETAIL -> R.string.status_service_stopped_detail
        UiTextKey.STATUS_BLE_ERROR_DETAIL -> R.string.status_ble_error_detail
        UiTextKey.STATUS_WAITING_IPHONE -> R.string.status_waiting_iphone
        UiTextKey.STATUS_WAITING_IPHONE_DETAIL -> R.string.status_waiting_iphone_detail
        UiTextKey.SUMMARY_RUNNING -> R.string.summary_running
        UiTextKey.SUMMARY_NOT_RUNNING -> R.string.summary_not_running
        UiTextKey.SUMMARY_NOT_STARTED -> R.string.summary_not_started
        UiTextKey.SUMMARY_STARTING -> R.string.summary_starting
        UiTextKey.SUMMARY_ADVERTISING -> R.string.summary_advertising
        UiTextKey.SUMMARY_CONNECTED -> R.string.summary_connected
        UiTextKey.SUMMARY_SUBSCRIBED -> R.string.summary_subscribed
        UiTextKey.SUMMARY_HEALTHY -> R.string.summary_healthy
        UiTextKey.SUMMARY_SUSPECT -> R.string.summary_suspect
        UiTextKey.SUMMARY_RECOVERING -> R.string.summary_recovering
        UiTextKey.SUMMARY_ERROR -> R.string.summary_error
        UiTextKey.SUMMARY_CONTROLLABLE -> R.string.summary_controllable
        UiTextKey.SUMMARY_CONNECTING -> R.string.summary_connecting
        UiTextKey.SUMMARY_NOT_CONNECTED -> R.string.summary_not_connected
        UiTextKey.ARTWORK_READY -> R.string.artwork_ready
        UiTextKey.ARTWORK_LOADING -> R.string.artwork_loading
        UiTextKey.ARTWORK_UNAVAILABLE -> R.string.summary_unavailable
        UiTextKey.SETUP_NOTIFICATION_TITLE -> R.string.setup_notification_title
        UiTextKey.SETUP_NOTIFICATION_DETAIL -> R.string.setup_notification_detail
        UiTextKey.SETUP_FILE_TITLE -> R.string.setup_file_title
        UiTextKey.SETUP_FILE_DETAIL -> R.string.setup_file_detail
        UiTextKey.SETUP_ACCESSIBILITY_TITLE -> R.string.setup_accessibility_title
        UiTextKey.SETUP_ACCESSIBILITY_DETAIL -> R.string.setup_accessibility_detail
        UiTextKey.SETUP_RUNTIME_TITLE -> R.string.setup_runtime_title
        UiTextKey.SETUP_RUNTIME_DETAIL -> R.string.setup_runtime_detail
        UiTextKey.NOTIFICATION_CONTROLLABLE_TITLE -> R.string.notification_controllable_title
        UiTextKey.NOTIFICATION_CONTROLLABLE_DETAIL -> R.string.notification_controllable_detail
        UiTextKey.NOTIFICATION_RECOVERING_TITLE -> R.string.notification_recovering_title
        UiTextKey.NOTIFICATION_RECOVERING_DETAIL -> R.string.notification_recovering_detail
        UiTextKey.NOTIFICATION_STOPPED_TITLE -> R.string.notification_stopped_title
        UiTextKey.NOTIFICATION_STOPPED_DETAIL -> R.string.notification_stopped_detail
        UiTextKey.NOTIFICATION_ACTION_TITLE -> R.string.notification_action_title
        UiTextKey.NOTIFICATION_ACTION_DETAIL -> R.string.notification_action_detail
        UiTextKey.NOTIFICATION_WAITING_TITLE -> R.string.notification_waiting_title
        UiTextKey.NOTIFICATION_WAITING_DETAIL -> R.string.notification_waiting_detail
    }
)
