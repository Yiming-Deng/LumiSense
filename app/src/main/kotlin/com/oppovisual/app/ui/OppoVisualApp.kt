package com.oppovisual.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun OppoVisualApp(mainViewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRequested = true
        cameraGranted = granted
    }

    when {
        !settings.onboardingAccepted -> OnboardingScreen(onContinue = mainViewModel::acceptOnboarding)
        !cameraGranted -> PermissionScreen(
            permanentlyDenied = permissionRequested,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    },
                )
            },
        )
        else -> MainNavigation(mainViewModel)
    }
}

@Composable
internal fun OnboardingScreen(onContinue: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    SystemBarAppearance(
        darkStatusBarIcons = !isDark,
        navigationBarColor = MaterialTheme.colorScheme.background,
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .testTag("onboarding_screen")
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                OnboardingIntroduction(
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                    compact = true,
                )
                Column(
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    AssuranceList(compact = true)
                    OnboardingContinueButton(onContinue)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    OnboardingIntroduction()
                    AssuranceList()
                    Spacer(Modifier.height(20.dp))
                }
                OnboardingContinueButton(onContinue)
            }
        }
    }
}

@Composable
private fun OnboardingIntroduction(modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 28.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(
                Icons.Outlined.CameraAlt,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(30.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("灵映", style = MaterialTheme.typography.displaySmall)
            Text(
                "端侧视觉手势识别",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "相机画面只在设备本地用于实时识别。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
        )
    }
}

@Composable
private fun AssuranceList(compact: Boolean = false) {
    Column {
        AssuranceRow(Icons.Outlined.Lock, "本地处理", "识别数据不离开设备", compact)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AssuranceRow(Icons.Outlined.CloudOff, "默认离线", "应用不申请网络权限", compact)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AssuranceRow(Icons.Outlined.NoPhotography, "不留存画面", "不保存照片或视频", compact)
    }
}

@Composable
private fun OnboardingContinueButton(onContinue: () -> Unit) {
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("onboarding_continue"),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text("开始设置")
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun AssuranceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    SystemBarAppearance(
        darkStatusBarIcons = !isDark,
        navigationBarColor = MaterialTheme.colorScheme.background,
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("permission_screen")
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PermissionIntroduction(permanentlyDenied, Modifier.weight(1f))
                PermissionActions(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = onRequest,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                PermissionIntroduction(permanentlyDenied)
                PermissionActions(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = onRequest,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun PermissionIntroduction(permanentlyDenied: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
            Icon(
                Icons.Outlined.CameraAlt,
                contentDescription = null,
                modifier = Modifier.padding(16.dp).size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("允许使用相机", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (permanentlyDenied) {
                    "相机权限尚未开启。你可以再次请求，或在系统设置中手动授权。"
                } else {
                    "前置摄像头用于实时手势识别。画面不会保存，也不会上传。"
                },
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun PermissionActions(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("permission_request"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(if (permanentlyDenied) "再次请求权限" else "允许相机")
        }
        if (permanentlyDenied) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("permission_settings"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开系统设置")
            }
        }
    }
}

@Composable
private fun MainNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "recognition") {
        composable("recognition") {
            RecognitionScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate("settings") },
                onOpenDiagnostics = { navController.navigate("diagnostics") },
            )
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = navController::popBackStack)
        }
        composable("diagnostics") {
            DiagnosticsScreen(viewModel = viewModel, onBack = navController::popBackStack)
        }
    }
}
