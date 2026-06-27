package com.irozar.ipdfmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.irozar.ipdfmaster.ui.theme.MyApplicationTheme
import com.irozar.ipdfmaster.ui.screens.PdfAppMain
import com.irozar.ipdfmaster.ui.viewmodel.PdfViewModel

class MainActivity : ComponentActivity() {

  private val appUpdateManager: AppUpdateManager by lazy { AppUpdateManagerFactory.create(this) }

  // Result of the Play update screen (user accepted / cancelled / failed). We don't need to act
  // on it for an immediate update; onResume re-checks and re-prompts if it was left unfinished.
  private val updateLauncher: ActivityResultLauncher<IntentSenderRequest> =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val viewModel = PdfViewModel(application)

    setContent {
      MyApplicationTheme {
        PdfAppMain(viewModel)
      }
    }

    // If a newer version is live on Google Play, force an in-app update on launch.
    appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
      if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
      ) {
        startImmediateUpdate(info)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Resume a forced update that was started but not finished (e.g. the user left the screen).
    appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
      if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
        startImmediateUpdate(info)
      }
    }
  }

  private fun startImmediateUpdate(info: AppUpdateInfo) {
    runCatching {
      appUpdateManager.startUpdateFlowForResult(
        info,
        updateLauncher,
        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
      )
    }
  }
}
