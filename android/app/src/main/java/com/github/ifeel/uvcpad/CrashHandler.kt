package com.github.ifeel.uvcpad

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Global uncaught exception handler (for diagnostics; depends on no third-party crash library).
 *
 * Background: after the MS2130 capture card was plugged in, the app crashed silently with
 * no output at all (adb unavailable), making the crash point impossible to locate. This
 * handler turns "silent crashes" into observable events:
 * 1. Every exception not handled by try-catch is captured and the full stack trace + device
 *    info + logcat tail are written to getExternalFilesDir(null)/crash/crash_yyyyMMdd_HHmmss_SSS.txt;
 *    on Android 11+ a copy is also written to the system Downloads directory
 *    (MediaStore.Downloads, retrievable without adb);
 * 2. If an Activity is still alive, a full-screen dialog (large red text) shows the exception
 *    class name + message + full stack trace (truncated when too long; R3 fix: no more
 *    scrollable display) so the user can read/photograph it directly; the process exits when
 *    "Exit" is tapped or after a few seconds.
 * 3. If the Activity is dead, only the log file is written and the process exits immediately.
 *
 * Threading model (still works when the main thread crashes):
 * - When the main thread crashes, the main Looper is already dead; posting the dialog/delayed
 *   exit to the main thread would never run and the process would hang into an ANR. The dialog
 *   and the delayed exit are therefore posted to the Looper of a dedicated
 *   HandlerThread("crash-ui");
 * - A plain daemon Thread is also started as a fallback (exits after an 8s sleep), decoupled
 *   from the dialog thread, guaranteeing the process always exits even if the crash-ui Looper
 *   misbehaves;
 * - The crashing thread (usually the main thread) blocks with a bound until the fallback exit,
 *   preventing ART from ending the process before the dialog is shown.
 *
 * Registered from Hdmi2mpApplication.onCreate; MainActivity maintains a weak reference to the
 * active Activity. Every path in this handler is wrapped in try-catch with an exit fallback —
 * it can never crash a second time.
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val CRASH_DIR = "crash"
    // Keep the dialog up for a few seconds so the user can read/photograph it, then auto-exit
    private const val AUTO_EXIT_DELAY_MS = 8000L
    // Extra grace for the fallback exit thread (its sleep duration, decoupled from the dialog thread)
    private const val EXIT_GRACE_MS = 2000L
    // logcat fallback: capture the last N lines; bounded read wait so a stuck logcat cannot stall crash handling
    private const val LOGCAT_TAIL_LINES = 500
    private const val LOGCAT_READ_TIMEOUT_MS = 3000L
    private const val MAX_LOGCAT_BYTES = 200_000

    private var appContext: Context? = null

    // The system default handler replaced by this one: chained once before exiting (M3 fix) to
    // keep the system's "app stopped" reporting (handleApplicationCrash → system dialog),
    // so replacing the handler never makes the system lose crash records entirely
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val chained = AtomicBoolean(false)

    // Static weak reference to the live Activity: used only to show the crash dialog; if the Activity is dead, only the file is written
    private val activeActivityRef = AtomicReference<WeakReference<Activity>>(null)

    // Dedicated Looper thread for crash UI: when the main thread crashes its Looper is dead,
    // so the dialog and the delayed exit are both posted here (P1-H1 fix)
    private var crashHandler: Handler? = null
    private var crashThread: HandlerThread? = null

    /** Call from Application.onCreate to register this as the global default uncaught exception handler */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (crashThread == null) {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(this)
            crashThread = HandlerThread("crash-ui").apply { start() }
            crashHandler = Handler(crashThread!!.looper)
        }
    }

    /** MainActivity passes itself in onCreate/onResume and clears it with null in onDestroy */
    fun setActiveActivity(activity: Activity?) {
        if (activity == null) {
            activeActivityRef.set(null)
        } else {
            activeActivityRef.set(WeakReference(activity))
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 1. Assemble the full log: device info + Java stack trace + logcat tail (P1-H2 native-clue fallback)
            val log = buildCrashLog(thread, throwable)
            val fullLog = log + "\n\n==== logcat tail (last $LOGCAT_TAIL_LINES lines) ====\n" + dumpLogcat()
            // 2. Write to disk: app private directory (primary copy) + system Downloads (P2-M1: retrievable without adb on Android 11+)
            val crashFile = writeCrashFile(fullLog)
            writeCrashToMediaStore(fullLog)

            val handler = crashHandler
            val activity = activeActivityRef.get()?.get()
            if (handler != null && activity != null && !activity.isFinishing && !activity.isDestroyed) {
                // 3a. Both the dialog and the delayed exit are posted to the dedicated crash-ui Looper thread (P1-H1)
                handler.post {
                    try {
                        showCrashDialog(activity, throwable, crashFile)
                    } catch (e: Exception) {
                        // A dialog show failure (e.g. abnormal window state) must not crash a second time; exit directly
                        exitProcess(thread, throwable)
                    }
                }
                handler.postDelayed({ exitProcess(thread, throwable) }, AUTO_EXIT_DELAY_MS)
            } else {
                // 3b. Activity is dead and cannot show the dialog: the log is already on disk; exit immediately
                exitProcess(thread, throwable)
                return
            }
            // 3c. Independent fallback thread: guarantees the process exits even if the crash-ui Looper misbehaves (P1-H1)
            Thread {
                try {
                    Thread.sleep(AUTO_EXIT_DELAY_MS + EXIT_GRACE_MS)
                } catch (e: InterruptedException) {
                    // Ignored: exit when the deadline is reached
                }
                exitProcess(thread, throwable)
            }.apply { isDaemon = true }.start()
            // 3d. The crashing thread blocks with a bound: prevents ART from ending the process
            //     before the dialog is shown; the fallback thread / the end of this method
            //     exits when the deadline passes
            blockUntilExit(thread, throwable)
        } catch (e: Throwable) {
            // The handler itself must never throw, preventing an infinite "crash while handling a crash" loop
            exitProcess(thread, throwable)
        }
    }

    /** Assembles the crash log: time + device info + crash nature + thread + full stack trace */
    private fun buildCrashLog(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val ctx = appContext
        return buildString {
            append("==== uvcpad crash log ====\n")
            append("time: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            append("package: ").append(ctx?.packageName ?: "unknown").append('\n')
            append("device: ").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n')
            append("sdk: ")
            append(Build.VERSION.SDK_INT)
            append(" (Android ").append(Build.VERSION.RELEASE).append(')').append('\n')
            append("hardware: ").append(Build.HARDWARE).append(" / ").append(Build.DEVICE).append('\n')
            append("crash nature: Java uncaught exception on thread \"").append(thread.name)
            append("\"; native crashes (SIGSEGV/SIGABRT) do not reach this handler; ")
            append("use the logcat tail below to locate the cause\n")
            append("exception: ")
            append(throwable.javaClass.name)
            append(": ").append(throwable.message ?: "(no message)").append('\n')
            append("stacktrace:\n").append(sw.toString())
        }
    }

    /**
     * logcat fallback (P1-H2): captures the process's most recent log lines at crash time and
     * appends them to the crash file. Native-layer errors such as libuvc/UVC open failures
     * appear only in logcat, invisible in the Java stack trace; apps on Android 4.1+ can only
     * read their own process's log lines, but libuvc's logs happen to belong to this process.
     * Runtime.exec + stream reads are all bounded (join timeout + destroy/destroyForcibly),
     * so crash handling is never blocked a second time.
     */
    private fun dumpLogcat(): String {
        val sb = StringBuilder()
        try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-t", LOGCAT_TAIL_LINES.toString()))
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        while (sb.length < MAX_LOGCAT_BYTES) {
                            val line = r.readLine() ?: break
                            sb.append(line).append('\n')
                        }
                    }
                } catch (e: Exception) {
                    // Ignore read failures: keep whatever has been read so far
                }
            }
            reader.isDaemon = true
            reader.start()
            // Bounded wait for the read stream: give up on timeout if logcat hangs, never stalling crash handling
            reader.join(LOGCAT_READ_TIMEOUT_MS)
            // Reap the process: logcat -d is a non-interactive command that exits by itself after destroy;
            // on API 26+ add a short-timeout waitFor to confirm reaping (waitFor(timeout) is API 26+)
            if (Build.VERSION.SDK_INT >= 26) {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } else {
                process.destroy()
            }
        } catch (e: Exception) {
            return "(logcat dump unavailable: ${e.javaClass.simpleName})"
        }
        return if (sb.isEmpty()) "(logcat dump empty)" else sb.toString()
    }

    /** Writes the crash log under the external private directory crash/ (falls back to the internal filesDir when unavailable) */
    private fun writeCrashFile(log: String): File? {
        val ctx = appContext ?: return null
        return try {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val dir = File(base, CRASH_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "crash_$stamp.txt")
            file.writeText(log)
            file
        } catch (e: Exception) {
            // A write failure must not block the exit flow
            null
        }
    }

    /**
     * P2-M1: on Android 11+ the private-directory log cannot be retrieved without adb, so a
     * copy is also written to the MediaStore Downloads directory (app-owned files on API 29+
     * need no permission); the user can retrieve it from the system "Files/Downloads" app.
     * Failures degrade silently (the primary copy stays in the private directory).
     */
    private fun writeCrashToMediaStore(log: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val ctx = appContext ?: return
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "uvcpad_crash_$stamp.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/uvcpad")
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(log.toByteArray(Charsets.UTF_8))
                } ?: resolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Roll back the empty record on write failure
                try {
                    resolver.delete(uri, null, null)
                } catch (e2: Exception) {
                    // Ignored
                }
            }
        } catch (e: Exception) {
            // MediaStore write failure degrades silently: the primary copy remains in the app's private directory
        }
    }

    /** Full-screen red large-font dialog: exception class name + message + full stack trace (truncated) + log file path */
    @SuppressLint("SetTextI18n")
    private fun showCrashDialog(activity: Activity, throwable: Throwable, crashFile: File?) {
        // Concatenate the full stack trace (R3 fix: the dialog no longer uses a ScrollView —
        // creating/laying out a ScrollView on the crash-ui HandlerThread's non-main thread
        // triggered a ScrollBarDrawable.mutate() NPE second crash; replaced with a static
        // FrameLayout+TextView whose height is capped by maxLines truncation)
        val stackLines = buildString {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            append(sw.toString())
        }

        // ---- Build the dialog content programmatically (no new layout resources, keep it minimal) ----
        val title = TextView(activity).apply {
            text = "App crashed (diagnostic)"
            setTextColor(Color.parseColor("#FFFF5252"))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val head = TextView(activity).apply {
            text = "${throwable.javaClass.name}\n${throwable.message ?: "(no message)"}"
            setTextColor(Color.parseColor("#FFFF5252"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPaddingRelative(8.dp(activity), 0, 8.dp(activity), 0)
        }
        // Static stack display: non-scrollable TextView (no ScrollView), height capped by
        // maxLines+ellipsize truncation; scrollbars explicitly disabled to rule out
        // non-main-thread ScrollBarDrawable crashes
        val stack = TextView(activity).apply {
            text = stackLines
            setTextColor(Color.parseColor("#FFFF6E6E"))
            textSize = 15f
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setMaxLines(14)
            ellipsize = TextUtils.TruncateAt.END
            setPaddingRelative(8.dp(activity), 0, 8.dp(activity), 0)
        }
        val stackFrame = FrameLayout(activity).apply {
            addView(stack, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val fileInfo = TextView(activity).apply {
            val loc = if (crashFile != null) crashFile.absolutePath else "write failed"
            val extra = if (Build.VERSION.SDK_INT >= 29) {
                "\nAlso saved to system Downloads: Download/uvcpad/ (no adb needed; retrieve via the system Files app)"
            } else {
                ""
            }
            text = "Crash log: $loc$extra"
            setTextColor(Color.parseColor("#FFB0B0B0"))
            textSize = 12f
            setPaddingRelative(8.dp(activity), 0, 8.dp(activity), 4.dp(activity))
        }
        val exitBtn = Button(activity).apply {
            text = "Exit"
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#CCD32F2F"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { exitProcess(Thread.currentThread(), throwable) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2111111"))
            setPadding(16.dp(activity), 16.dp(activity), 16.dp(activity), 16.dp(activity))
            addView(title, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(head, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val stackParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
            addView(stackFrame, stackParams)
            addView(fileInfo, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(exitBtn)
        }

        val dialog = Dialog(activity).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(content)
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        dialog.show()
    }

    /** Bounded blocking of the crashing thread: gives the dialog time to show, then exits at the deadline (bounded, never hangs forever) */
    private fun blockUntilExit(thread: Thread, throwable: Throwable) {
        val deadline = System.currentTimeMillis() + AUTO_EXIT_DELAY_MS + EXIT_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                break
            }
        }
        exitProcess(thread, throwable)
    }

    /**
     * Exits the process (reachable from multiple threads: dialog button / delayed callback /
     * fallback thread / crashing thread). Chains the replaced system handler once before
     * exiting to preserve the system's crash reporting (handleApplicationCrash → system
     * "app stopped" record), then falls back to killProcess.
     */
    private fun exitProcess(thread: Thread, throwable: Throwable) {
        if (chained.compareAndSet(false, true)) {
            try {
                previousHandler?.uncaughtException(thread, throwable)
            } catch (e: Throwable) {
                // A failed chain call must not block the exit
            }
        }
        Process.killProcess(Process.myPid())
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()
}
