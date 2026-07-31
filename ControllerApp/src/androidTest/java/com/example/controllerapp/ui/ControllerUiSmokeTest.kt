package com.example.controllerapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.controllerapp.ControllerApplication
import com.example.controllerapp.ControllerViewModel
import org.junit.Rule
import org.junit.Test

class ControllerUiSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playerAndFullLyricsNavigationRender() {
        setControllerContent()

        compose.onNodeWithText("QQ 音乐").assertIsDisplayed()
        compose.onNodeWithTag("compact_lyrics").assertIsDisplayed().performClick()
        compose.onNodeWithText("完整歌词").assertIsDisplayed()
    }

    @Test
    fun historySettingsAndHealthRoutesRenderFromDailyMenu() {
        setControllerContent()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("播放历史").performClick()
        compose.onNodeWithText("播放历史").assertIsDisplayed()
        compose.onNodeWithText("最近播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("性能模式").assertIsDisplayed()
        compose.onNodeWithText("本地封面增强").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("调试").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("调试工具").performClick()
        compose.onNodeWithText("启动 RFCOMM 兼容模式")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("停止 RFCOMM 并恢复 BLE")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()

        compose.onNodeWithContentDescription("打开菜单").performClick()
        compose.onNodeWithText("系统健康").performClick()
        compose.onNodeWithText("系统健康").assertIsDisplayed()
    }

    private fun setControllerContent() {
        val application =
            ApplicationProvider.getApplicationContext<ControllerApplication>()
        val viewModel = ControllerViewModel(application)
        compose.setContent {
            ControllerTheme {
                ControllerApp(viewModel)
            }
        }
    }
}
