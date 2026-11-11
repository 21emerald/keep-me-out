package com.me.keepmeout.service

/** Why the guard decided a screen is dangerous. */
enum class GuardReason { UNINSTALL, DEVICE_ADMIN, ACCESSIBILITY }

/** Snapshot of the screen the accessibility service is looking at. */
data class GuardScreen(
    val packageName: String,
    val className: String?,
    val texts: List<String>,
)

/**
 * Pure decision engine for uninstall protection. Kept free of Android imports so it
 * can be unit tested on the JVM.
 *
 * The guard only blocks uninstall surfaces. Install screens are deliberately allowed:
 * they contain the word "安装"/"Install" but never "卸载"/"Uninstall".
 */
object UninstallGuard {

    val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.huawei.android.packageinstaller",
        "com.vivo.packageinstaller",
        "com.iqoo.packageinstaller",
        "com.transsion.packageinstaller",
    )

    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.settings",
        "com.samsung.android.settings",
    )

    val STORE_PACKAGES = setOf(
        "com.android.vending",
        "com.google.android.finsky",
        "com.huawei.appmarket",
        "com.xiaomi.market",
        "com.oppo.market",
        "com.vivo.appstore",
    )

    val OEM_MANAGER_PACKAGES = setOf(
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.huawei.systemmanager",
        "com.samsung.android.lool",
        "com.vivo.abe",
        "com.iqoo.secure",
    )

    val LAUNCHER_PACKAGES = setOf(
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.coloros.launcher",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.transsion.XOSLauncher",
    )

    private val CANDIDATE_PACKAGES: Set<String> =
        INSTALLER_PACKAGES + SETTINGS_PACKAGES + STORE_PACKAGES + OEM_MANAGER_PACKAGES + LAUNCHER_PACKAGES

    private val UNINSTALL_TERMS = listOf(
        "卸载", "解除安装", "移除应用",
        "Uninstall", "Remove app", "Desinstalar", "Désinstaller",
        "アンインストール", "제거", "Удалить",
    )

    private val DEVICE_ADMIN_TERMS = listOf(
        "设备管理", "裝置管理員", "设备管理员",
        "Device admin", "Device administrators", "Device administration",
    )

    private val DEVICE_ADMIN_ACTION_TERMS = listOf(
        "停用", "激活", "Deactivate", "Activate", "Remove admin",
    )

    private val ACCESSIBILITY_TERMS = listOf(
        "无障碍", "無障礙", "Accessibility",
    )

    private val ACCESSIBILITY_PAGE_TERMS = listOf(
        "已下载的应用", "已安装的服务", "已启用的服务", "无障碍服务",
        "Downloaded apps", "Downloaded services", "Installed services", "Accessibility service",
    )

    private val STORE_CONFIRM_TERMS = listOf("取消", "Cancel")

    private val APP_INFO_TERMS = listOf(
        "应用信息", "应用详情", "应用设置",
        "App info", "App details", "App settings",
    )

    fun isCandidate(packageName: String): Boolean =
        packageName in CANDIDATE_PACKAGES || isSettingsPackage(packageName)

    fun detect(screen: GuardScreen): GuardReason? {
        val pkg = screen.packageName
        val className = screen.className.orEmpty()
        val texts = screen.texts
        return when {
            isSettingsPackage(pkg) -> detectSettings(className, texts)
            pkg in INSTALLER_PACKAGES -> detectInstaller(className, texts)
            pkg in STORE_PACKAGES -> detectStore(texts)
            pkg in LAUNCHER_PACKAGES -> detectLauncher(texts)
            pkg in OEM_MANAGER_PACKAGES -> detectOemManager(texts)
            else -> null
        }
    }

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg in SETTINGS_PACKAGES || pkg.endsWith(".settings")

    private fun detectSettings(className: String, texts: List<String>): GuardReason? {
        val lower = className.lowercase()
        if ("deviceadmin" in lower) return GuardReason.DEVICE_ADMIN
        if ("accessibility" in lower) return GuardReason.ACCESSIBILITY
        if (containsAny(texts, DEVICE_ADMIN_TERMS) && containsAny(texts, DEVICE_ADMIN_ACTION_TERMS)) {
            return GuardReason.DEVICE_ADMIN
        }
        if (containsAny(texts, ACCESSIBILITY_TERMS) && containsAny(texts, ACCESSIBILITY_PAGE_TERMS)) {
            return GuardReason.ACCESSIBILITY
        }
        return uninstallReason(texts)
    }

    private fun detectInstaller(className: String, texts: List<String>): GuardReason? {
        if (className.contains("Uninstaller", ignoreCase = true)) return GuardReason.UNINSTALL
        // Install screens say "安装"/"Install"; only an uninstall confirmation carries "卸载"/"Uninstall".
        return uninstallReason(texts)
    }

    private fun detectStore(texts: List<String>): GuardReason? {
        if (!containsAny(texts, UNINSTALL_TERMS)) return null
        // A store app page shows "卸载" next to "打开"/"更新"; the confirmation sheet also
        // shows "取消". Requiring the cancel wording avoids blocking store browsing.
        return if (containsAny(texts, STORE_CONFIRM_TERMS)) GuardReason.UNINSTALL else null
    }

    private fun detectLauncher(texts: List<String>): GuardReason? {
        // The long-press menu contains both "App info" and "Uninstall". Requiring the
        // app-info wording avoids matching an app-drawer entry whose name contains
        // "卸载". The system uninstall dialog is the fallback when a launcher uses a
        // drag-to-uninstall target with no app-info label.
        if (containsAny(texts, UNINSTALL_TERMS) && containsAny(texts, APP_INFO_TERMS)) {
            return GuardReason.UNINSTALL
        }
        return null
    }

    private fun detectOemManager(texts: List<String>): GuardReason? {
        if (containsAny(texts, DEVICE_ADMIN_TERMS) && containsAny(texts, DEVICE_ADMIN_ACTION_TERMS)) {
            return GuardReason.DEVICE_ADMIN
        }
        if (containsAny(texts, ACCESSIBILITY_TERMS) && containsAny(texts, ACCESSIBILITY_PAGE_TERMS)) {
            return GuardReason.ACCESSIBILITY
        }
        return uninstallReason(texts)
    }

    private fun uninstallReason(texts: List<String>): GuardReason? =
        if (containsAny(texts, UNINSTALL_TERMS)) GuardReason.UNINSTALL else null

    private fun containsAny(texts: List<String>, terms: List<String>): Boolean =
        texts.any { text ->
            terms.any { term -> text.contains(term, ignoreCase = true) }
        }
}
