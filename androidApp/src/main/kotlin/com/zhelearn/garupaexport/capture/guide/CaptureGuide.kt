package com.zhelearn.garupaexport.capture.guide

import androidx.annotation.StringRes
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.capture.CapturePhase
import com.zhelearn.garupaexport.capture.CaptureState
import com.zhelearn.garupaexport.capture.ProxyStatus
import com.zhelearn.garupaexport.profile.ProfileState

/** Shared presentation rules for the native screen and system notifications. No persisted step number. */
internal enum class GuideStage(@param:StringRes val title: Int, @param:StringRes val description: Int, val step: Int) {
    Welcome(R.string.guide_welcome_title, R.string.guide_welcome_body, 0),
    Preparing(R.string.guide_preparing_title, R.string.guide_preparing_body, 1),
    Starting(R.string.guide_starting_title, R.string.guide_starting_body, 1),
    SettingProxy(R.string.guide_auto_title, R.string.guide_auto_body, 2),
    ConfigureProxy(R.string.guide_manual_title, R.string.guide_manual_body, 2),
    Verifying(R.string.guide_verifying_title, R.string.guide_verifying_body, 3),
    OpenGame(R.string.guide_game_title, R.string.guide_game_body, 4),
    Receiving(R.string.guide_receiving_title, R.string.guide_receiving_body, 4),
    Generating(R.string.guide_generating_title, R.string.guide_generating_body, 4),
    Complete(R.string.guide_complete_title, R.string.guide_complete_body, 4),
    ConversionFailed(R.string.guide_conversion_failed_title, R.string.guide_conversion_failed_body, 4),
    StartFailed(R.string.guide_start_failed_title, R.string.guide_start_failed_body, 1),
    Stopping(R.string.proxy_stopping, R.string.guide_stopping_body, 0),
}

internal fun guideStage(capture: CaptureState, profile: ProfileState): GuideStage = when {
    capture.status == ProxyStatus.Stopping -> GuideStage.Stopping
    profile is ProfileState.Ready -> GuideStage.Complete
    profile is ProfileState.Failed -> GuideStage.ConversionFailed
    profile == ProfileState.Parsing -> GuideStage.Generating
    capture.status == ProxyStatus.Stopped -> if (capture.error == null) GuideStage.Welcome else GuideStage.StartFailed
    else -> when (capture.phase) {
        CapturePhase.Idle, CapturePhase.PreparingData -> GuideStage.Preparing
        CapturePhase.StartingProxy -> GuideStage.Starting
        CapturePhase.SettingProxy -> GuideStage.SettingProxy
        CapturePhase.AwaitingProxy -> GuideStage.ConfigureProxy
        CapturePhase.VerifyingProxy -> GuideStage.Verifying
        CapturePhase.WaitingForGame -> GuideStage.OpenGame
        CapturePhase.Receiving -> GuideStage.Receiving
    }
}
