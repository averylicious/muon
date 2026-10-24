package dev.avery.muon.baselineprofile

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val MEASUREMENT_TARGET = "dev.avery.muon.benchmark"
private const val NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Matched cold first-frame experiment on the SAME minified APK, not library-ready/scroll timing.
 * API37+ with denied LAN access keeps fresh-start discovery and server responses out of both modes.
 * Requires explicit consent to erase ONLY the disposable Benchmark package, before compilation.
 * Never clears data after Macrobenchmark applies its selected compilation/profile mode.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private var armed = false

    @Before fun prepareIsolatedTarget() {
        check(BuildConfig.BENCHMARK_MINIFIED) { "Install measurement-mode tools and target" }
        check(Build.VERSION.SDK_INT >= 37) { "Controlled no-network startup requires Android API37+" }
        check(InstrumentationRegistry.getArguments().getString("muonAllowBenchmarkDataReset") == "true") {
            "This experiment erases only Muon Benchmark. Explicitly pass muonAllowBenchmarkDataReset=true"
        }
        val packages = instrumentation.context.packageManager
        val app = packages.getApplicationInfo(MEASUREMENT_TARGET, PackageManager.GET_META_DATA)
        checkBenchmarkIdentity(packages, app)
        check(app.targetSdkVersion >= 37 && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            app.metaData?.getString("dev.avery.muon.benchmark.mode") == "measurement") {
            "The target must be the matching API37+, non-debuggable, minified measurement APK"
        }
        check(instrumentation.context.getSystemService(android.os.UserManager::class.java)?.isSystemUser == true &&
            device.executeShellCommand("am get-current-user").trim() == "0") {
            "This experiment supports only owner-user instrumentation with that user active"
        }
        armed = true
        check(device.executeShellCommand("pm clear --user current $MEASUREMENT_TARGET").trim() == "Success") {
            "Could not reset the disposable Benchmark target"
        }
        device.executeShellCommand("pm revoke --user current $MEASUREMENT_TARGET $NETWORK_PERMISSION")
        check(packages.checkPermission(NETWORK_PERMISSION, MEASUREMENT_TARGET) == PackageManager.PERMISSION_DENIED) {
            "Benchmark LAN permission must be denied for this controlled startup experiment"
        }
    }

    @Test fun coldStartupWithoutAheadOfTimeCompilation() = measure(CompilationMode.None())

    @Test fun coldStartupWithRequiredShippedProfile() = measure(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0))

    private fun measure(compilation: CompilationMode) = rule.measureRepeated(
        packageName = MEASUREMENT_TARGET,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.pkg(MEASUREMENT_TARGET).text("Server address")), 10_000)) {
            "Expected the fresh Connect screen, not a live or saved library"
        }
        check(instrumentation.context.packageManager.checkPermission(NETWORK_PERMISSION,
            MEASUREMENT_TARGET) == PackageManager.PERMISSION_DENIED) { "Benchmark LAN permission changed" }
    }

    @After fun stopOnlyTheBenchmarkTarget() {
        if (armed) device.executeShellCommand("am force-stop --user current $MEASUREMENT_TARGET")
    }
}
