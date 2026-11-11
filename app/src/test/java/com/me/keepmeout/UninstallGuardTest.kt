package com.me.keepmeout

import com.me.keepmeout.service.GuardReason
import com.me.keepmeout.service.GuardScreen
import com.me.keepmeout.service.UninstallGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallGuardTest {

    @Test
    fun installerUninstallerActivityIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.uninstaller.UninstallerActivity",
            texts = emptyList(),
        )
        assertEquals(GuardReason.UNINSTALL, UninstallGuard.detect(screen))
    }

    @Test
    fun installerInstallScreenIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.google.android.packageinstaller",
            className = "com.android.packageinstaller.InstallInstalling",
            texts = listOf("正在安装", "Keep Me Out", "取消", "安装"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun settingsAppInfoUninstallIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.android.settings",
            className = "com.android.settings.SubSettings",
            texts = listOf("Keep Me Out", "应用信息", "卸载", "强行停止"),
        )
        assertEquals(GuardReason.UNINSTALL, UninstallGuard.detect(screen))
    }

    @Test
    fun settingsWifiPageIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings\$NetworkDashboardActivity",
            texts = listOf("WLAN", "网络和互联网", "移动网络"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun settingsDeviceAdminPageIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.android.settings",
            className = "com.android.settings.DeviceAdminSettings",
            texts = listOf("设备管理器", "Keep Me Out"),
        )
        assertEquals(GuardReason.DEVICE_ADMIN, UninstallGuard.detect(screen))
    }

    @Test
    fun settingsAccessibilityPageIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.android.settings",
            className = "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment",
            texts = listOf("无障碍", "Keep Me Out 锁屏"),
        )
        assertEquals(GuardReason.ACCESSIBILITY, UninstallGuard.detect(screen))
    }

    @Test
    fun settingsMainPageMentioningAccessibilityIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings",
            texts = listOf("设置", "网络和互联网", "无障碍", "关于手机"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun storeBrowsePageIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.android.vending",
            className = "com.google.android.finsky.activities.MainActivity",
            texts = listOf("Keep Me Out", "卸载", "打开"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun storeUninstallSheetIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.android.vending",
            className = "com.google.android.finsky.activities.MainActivity",
            texts = listOf("要卸载此应用吗？", "取消", "卸载"),
        )
        assertEquals(GuardReason.UNINSTALL, UninstallGuard.detect(screen))
    }

    @Test
    fun launcherLongPressUninstallIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.google.android.apps.nexuslauncher",
            className = "com.android.launcher3.Launcher",
            texts = listOf("Keep Me Out", "应用信息", "卸载"),
        )
        assertEquals(GuardReason.UNINSTALL, UninstallGuard.detect(screen))
    }

    @Test
    fun launcherAppDrawerMentioningUninstallIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.google.android.apps.nexuslauncher",
            className = "com.android.launcher3.Launcher",
            texts = listOf("卸载助手", "Keep Me Out", "设置", "相机"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun englishUninstallDialogIsBlocked() {
        val screen = GuardScreen(
            packageName = "com.google.android.permissioncontroller",
            className = "com.android.permissioncontroller.permission.ui.UninstallerActivity",
            texts = listOf("Do you want to uninstall this app?", "Cancel", "Uninstall"),
        )
        assertEquals(GuardReason.UNINSTALL, UninstallGuard.detect(screen))
    }

    @Test
    fun runtimePermissionDialogIsAllowed() {
        val screen = GuardScreen(
            packageName = "com.google.android.permissioncontroller",
            className = "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity",
            texts = listOf("Allow Keep Me Out to send notifications?", "Allow", "Don't allow"),
        )
        assertNull(UninstallGuard.detect(screen))
    }

    @Test
    fun candidatePackagesAreRecognized() {
        assertTrue(UninstallGuard.isCandidate("com.android.settings"))
        assertTrue(UninstallGuard.isCandidate("com.android.vending"))
        assertTrue(UninstallGuard.isCandidate("com.google.android.packageinstaller"))
        assertTrue(UninstallGuard.isCandidate("com.google.android.apps.nexuslauncher"))
        assertFalse(UninstallGuard.isCandidate("com.example.notes"))
    }
}
