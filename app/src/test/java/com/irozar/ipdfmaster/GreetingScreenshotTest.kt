package com.irozar.ipdfmaster

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.irozar.ipdfmaster.ui.theme.MyApplicationTheme
import com.irozar.ipdfmaster.ui.screens.QuickActionItem
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent { 
      MyApplicationTheme { 
        QuickActionItem(
          label = "Merge PDFs",
          icon = Icons.Outlined.MergeType,
          color = Color(0xFFE8EAF6),
          tint = Color(0xFF3F51B5)
        ) {}
      } 
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
