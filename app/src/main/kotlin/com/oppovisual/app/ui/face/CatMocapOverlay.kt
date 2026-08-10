package com.oppovisual.app.ui.face

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.oppovisual.app.ui.RecognitionUiState

@Composable
fun CatMocapOverlay(
    state: RecognitionUiState,
    modifier: Modifier = Modifier,
    onReadyChanged: (Boolean) -> Unit = {},
    onViewCreated: (CatMocapFilamentView) -> Unit = {},
    onVirtualFaceLayoutChanged: (FaceHeadgearLayout?) -> Unit = {},
    profile: MocapHeadgearProfile = MocapHeadgearProfile.ELDER_SPRITE,
    assetName: String = profile.assetName,
    transparentSurface: Boolean = true,
) {
    var view by remember(profile, assetName) { mutableStateOf<CatMocapFilamentView?>(null) }
    key(profile, assetName) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                CatMocapFilamentView(
                    context,
                    profile = profile,
                    assetName = assetName,
                    transparentSurface = transparentSurface,
                ).also { renderer ->
                    view = renderer
                    renderer.onRendererReady = onReadyChanged
                    renderer.onVirtualFaceLayoutChanged = onVirtualFaceLayoutChanged
                    onViewCreated(renderer)
                }
            },
            update = { renderer -> renderer.update(state) },
        )
    }
    DisposableEffect(profile, assetName) {
        onDispose {
            view?.release()
            view = null
            onVirtualFaceLayoutChanged(null)
            onReadyChanged(false)
        }
    }
}

@Composable
fun ElderSpriteMocapOverlay(
    state: RecognitionUiState,
    modifier: Modifier = Modifier,
    onReadyChanged: (Boolean) -> Unit = {},
    onVirtualFaceLayoutChanged: (FaceHeadgearLayout?) -> Unit = {},
) {
    CatMocapOverlay(
        state = state,
        modifier = modifier,
        onReadyChanged = onReadyChanged,
        onVirtualFaceLayoutChanged = onVirtualFaceLayoutChanged,
        profile = MocapHeadgearProfile.ELDER_SPRITE,
            assetName = "v3_avatar_head_only_mocap.glb",
    )
}
