package dev.avery.muon.baselineprofile

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry

/** Match the installed target before grants/reset. Commit metadata prevents accidental build mixing;
 * it is a declared CI source label, not independent authentication of every compiler/dependency.
 */
internal fun checkBenchmarkIdentity(packages: PackageManager, target: ApplicationInfo) {
    check(target.packageName == "dev.avery.muon.benchmark") { "Unexpected Benchmark target" }
    val helper = InstrumentationRegistry.getInstrumentation().context.packageName
    check(packages.checkSignatures(helper, target.packageName) == PackageManager.SIGNATURE_MATCH) {
        "Install the matching signed Benchmark target and helper pair"
    }
    check(BuildConfig.BENCHMARK_COMMIT.matches(Regex("[0-9a-f]{40}"))) {
        "Use a source-labelled CI pair, or explicitly set MUON_BENCHMARK_COMMIT for local tools"
    }
    check(target.metaData?.getString("dev.avery.muon.benchmark.commit") == "git:" + BuildConfig.BENCHMARK_COMMIT) {
        "Benchmark target and helper come from different source builds; install both from one artifact"
    }
}
