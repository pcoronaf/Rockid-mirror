package com.rokidmirror.sender.computer

import com.rokidmirror.sender.privilege.ShellProtocol
import com.rokidmirror.sender.privilege.WirelessDebugCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerCommandsTest {
    @Test fun launchTargetsTheRequestedDisplay() {
        assertEquals(
            listOf("am", "start", "--display", "7", "-n", "com.example/.Main"),
            ComputerCommands.launch(7, "com.example/.Main").toList(),
        )
    }

    @Test fun tapAndSwipeAndKeyCarryTheDisplayId() {
        assertEquals(listOf("input", "-d", "3", "tap", "100", "200"), ComputerCommands.tap(3, 100, 200).toList())
        assertEquals(
            listOf("input", "-d", "3", "swipe", "10", "20", "30", "40", "120"),
            ComputerCommands.swipe(3, 10, 20, 30, 40, 120).toList(),
        )
        assertEquals(listOf("input", "-d", "3", "keyevent", "4"), ComputerCommands.key(3, 4).toList())
    }

    @Test fun homeLaunchesTheHomeCategory() {
        val args = ComputerCommands.home(2).toList()
        assertTrue(args.containsAll(listOf("--display", "2", "android.intent.category.HOME")))
    }

    @Test fun helperCommandRunsOurEntryPointFromTheApkClasspath() {
        val cmd = WirelessDebugCommands.startHelperCommand("/data/app/base.apk", "com.rokidmirror.sender")
        assertTrue("names the APK on CLASSPATH", cmd.contains("CLASSPATH='/data/app/base.apk'"))
        assertTrue("runs app_process", cmd.contains("app_process /system/bin"))
        assertTrue("invokes our helper main", cmd.contains(ShellProtocol.HELPER_MAIN_CLASS))
        assertTrue("passes the package name", cmd.contains("'com.rokidmirror.sender'"))
        assertTrue("detaches the process", cmd.contains("nohup") && cmd.trimEnd().endsWith("&"))
    }

    @Test fun helperCommandQuotesAwkwardPaths() {
        val cmd = WirelessDebugCommands.startHelperCommand("/data/app/a'b.apk", "pkg")
        assertTrue(cmd.contains("""'/data/app/a'\''b.apk'"""))
    }

    @Test fun providerAuthorityIsDerivedFromThePackage() {
        assertEquals("com.rokidmirror.sender.debug.shellbridge", ShellProtocol.providerAuthority("com.rokidmirror.sender.debug"))
    }
}
