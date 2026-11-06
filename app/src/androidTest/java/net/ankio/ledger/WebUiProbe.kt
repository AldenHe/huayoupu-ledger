package net.ankio.ledger

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import net.ankio.ledger.ui.MainActivity
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Call from the instrumentation thread: UI callbacks never wait on their own thread. */
internal class WebUiProbe(private val scenario: ActivityScenario<MainActivity>) {
    fun value(expression: String, timeoutMs: Long = 10_000): String {
        val callback = CountDownLatch(1)
        val result = AtomicReference<String>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1)
        // ActivityScenario.onActivity first waits for global UI idleness. An intentional
        // CSS loading spinner may continuously schedule frames, so dispatch the small
        // WebView query directly and wait only for its asynchronous JS callback.
        var dispatched = false
        do {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
                if (activity != null) {
                    dispatched = true
                    activity.webView.evaluateJavascript(expression) { output ->
                        result.set(output)
                        callback.countDown()
                    }
                }
            }
            // Activity launch/recreation can briefly have no RESUMED activity. Wait on
            // the instrumentation thread without blocking lifecycle work on main.
            if (!dispatched) SystemClock.sleep(10)
        } while (!dispatched && SystemClock.elapsedRealtime() < deadline)
        check(dispatched) { "No resumed MainActivity for WebView evaluation: $expression" }
        check(callback.await((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
            "WebView did not finish evaluation: $expression"
        }
        return result.get()
    }

    fun run(script: String) { value("(()=>{$script;return true})()") }

    fun text(expression: String): String = JSONArray("[${value(expression)}]").getString(0)

    fun await(condition: String, timeoutMs:Long=10_000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        do {
            last = value("Boolean($condition)", deadline - SystemClock.elapsedRealtime())
            if (last == "true") return
            // Every iteration yields to a real asynchronous WebView evaluation callback.
        } while (SystemClock.elapsedRealtime() < deadline)
        error("WebView condition timed out ($last): $condition")
    }

    fun ready() = await("window.LedgerUI && document.readyState==='complete' && document.querySelector('#month h1')")
}
