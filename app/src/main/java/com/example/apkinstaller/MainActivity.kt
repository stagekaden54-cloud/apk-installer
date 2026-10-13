package com.example.apkinstaller

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var apkInfoText: TextView
    private lateinit var pickButton: Button
    private lateinit var installButton: Button
    private lateinit var openAppButton: Button
    private lateinit var settingsButton: Button

    private var apkFile: File? = null
    private var targetPackage: String? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android needs the user to tap "Install" on its own screen.
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        this@MainActivity.startActivity(confirm)
                    }
                }

                PackageInstaller.STATUS_SUCCESS -> {
                    statusText.text = "Installed. You can open the app or its settings below."
                    openAppButton.isEnabled = true
                    settingsButton.isEnabled = true
                    pickButton.isEnabled = true
                }

                else -> {
                    statusText.text = "Install did not finish: $message"
                    pickButton.isEnabled = true
                    installButton.isEnabled = apkFile != null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        apkInfoText = findViewById(R.id.apkInfoText)
        pickButton = findViewById(R.id.pickButton)
        installButton = findViewById(R.id.installButton)
        openAppButton = findViewById(R.id.openAppButton)
        settingsButton = findViewById(R.id.settingsButton)

        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }

        pickButton.setOnClickListener { pickApk() }
        installButton.setOnClickListener { installApk() }
        openAppButton.setOnClickListener { openInstalledApp() }
        settingsButton.setOnClickListener { openAppSettings() }
    }

    override fun onDestroy() {
        unregisterReceiver(resultReceiver)
        super.onDestroy()
    }

    // ---- Step 1: pick an APK -------------------------------------------------

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_APK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        copyAndInspect(uri)
    }

    private fun copyAndInspect(uri: Uri) {
        statusText.text = "Reading file..."
        installButton.isEnabled = false
        openAppButton.isEnabled = false
        settingsButton.isEnabled = false

        Thread {
            try {
                val dest = File(cacheDir, "picked.apk")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Could not open that file" }
                    dest.outputStream().use { out -> input.copyTo(out) }
                }

                val info = if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageArchiveInfo(
                        dest.absolutePath,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(dest.absolutePath, 0)
                }

                runOnUiThread {
                    if (info == null) {
                        statusText.text = "That file is not a valid APK."
                        apkInfoText.text = ""
                    } else {
                        apkFile = dest
                        targetPackage = info.packageName
                        statusText.text = "APK ready. Tap Install."
                        apkInfoText.text =
                            "Package: ${info.packageName}\nVersion: ${info.versionName}"
                        installButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Could not read the file: ${e.message}" }
            }
        }.start()
    }

    // ---- Step 2: install -----------------------------------------------------

    private fun installApk() {
        val file = apkFile ?: return

        // Android requires the user to allow this app to install other apps (once).
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Allow this app to install apps, then come back and tap Install again.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        statusText.text = "Installing..."
        installButton.isEnabled = false
        pickButton.isEnabled = false

        Thread {
            try {
                val installer = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
                targetPackage?.let { params.setAppPackageName(it) }

                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }

                    val callback = Intent(ACTION_INSTALL_RESULT).setPackage(packageName)
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= 31) {
                        flags = flags or PendingIntent.FLAG_MUTABLE
                    }
                    val pending = PendingIntent.getBroadcast(this, sessionId, callback, flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Install failed: ${e.message}"
                    pickButton.isEnabled = true
                    installButton.isEnabled = true
                }
            }
        }.start()
    }

    // ---- Steps 3 and 4: open the app / its settings --------------------------

    private fun openInstalledApp() {
        val pkg = targetPackage ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "That app has no launch screen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        val pkg = targetPackage ?: return
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null)
            )
        )
    }

    companion object {
        private const val REQUEST_PICK_APK = 1001
        private const val ACTION_INSTALL_RESULT = "com.example.apkinstaller.INSTALL_RESULT"
    }
}
