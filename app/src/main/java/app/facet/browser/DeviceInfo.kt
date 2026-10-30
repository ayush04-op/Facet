package app.facet.browser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import java.text.DateFormat
import java.util.Date

/** One labelled line of device or build information. */
class InfoLine(val label: String, val value: String)

object DeviceInfo {
    fun bytes(n: Long): String {
        val mb = n / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    fun engineVersion(ctx: Context): String = try {
        val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, android.content.pm.PackageManager.GET_META_DATA)
        "GeckoView " + (ai.metaData?.getString("app.facet.gecko") ?: "unknown")
    } catch (e: Exception) { "GeckoView" }

    fun build(ctx: Context): List<InfoLine> {
        val out = ArrayList<InfoLine>()
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            out.add(InfoLine("Version", pi.versionName ?: "?"))
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            out.add(InfoLine("Build number", code.toString()))
            out.add(InfoLine("Installed", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(pi.lastUpdateTime))))
        } catch (e: Exception) {
            out.add(InfoLine("Version", "unknown"))
        }
        out.add(InfoLine("Engine", engineVersion(ctx)))
        out.add(InfoLine("Package", ctx.packageName))
        return out
    }

    fun device(ctx: Context): List<InfoLine> {
        val out = ArrayList<InfoLine>()
        out.add(InfoLine("Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"))
        out.add(InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SECURITY_PATCH.isNotEmpty()) {
            out.add(InfoLine("Security patch", Build.VERSION.SECURITY_PATCH))
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        out.add(InfoLine("Processor", abi))

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        out.add(InfoLine("Memory free", "${bytes(mi.availMem)} of ${bytes(mi.totalMem)}"))

        try {
            val st = StatFs(ctx.filesDir.path)
            out.add(InfoLine("Storage free", bytes(st.availableBytes)))
        } catch (e: Exception) { /* skip */ }

        val bat: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0) out.add(InfoLine("Battery", "${level * 100 / scale}%" + if (charging) ", charging" else ""))
        }
        out.add(InfoLine("Network", network(ctx)))
        return out
    }

    private fun network(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(n) ?: return "Offline"
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Online"
            }
            if (cm.isActiveNetworkMetered) "$kind (metered)" else kind
        } catch (e: Exception) { "Unknown" }
    }

    /** Short sentence about whether this device can take the update right now. */
    fun readiness(ctx: Context): Pair<Boolean, String> {
        val problems = ArrayList<String>()
        if (network(ctx) == "Offline") problems.add("no connection")
        try {
            if (StatFs(ctx.cacheDir.path).availableBytes < 300L * 1024 * 1024) problems.add("low storage")
        } catch (e: Exception) { /* ignore */ }
        if (!Updater.canInstall(ctx)) problems.add("install permission needed")
        return if (problems.isEmpty()) Pair(true, "Ready to update")
        else Pair(false, problems.joinToString(", ").replaceFirstChar { it.uppercase() })
    }
}
