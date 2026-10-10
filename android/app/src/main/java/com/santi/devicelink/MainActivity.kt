package com.santi.devicelink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val mpm by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }
    private lateinit var statusTv: TextView
    private lateinit var ipTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "DeviceLink Remote"
            textSize = 26f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Model: ${Build.MODEL} (${Build.DEVICE})\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"
            textSize = 13f
            gravity = Gravity.CENTER
        })

        ipTv = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }
        ipTv.text = "Browser pe kholo:  http://" + wifiIp() + ":8766\n(dono device ek hi WiFi pe ho)"
        ipTv.textSize = 15f
        root.addView(ipTv)

        root.addView(Button(this).apply {
            text = "1) Start screen streaming (tap Allow)"
            setOnClickListener { startStream() }
        })
        root.addView(Button(this).apply {
            text = "2) Enable Input (Accessibility) — taps ke liye"
            setOnClickListener {
                if (!accOn()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("DeviceLink Input ko ON karo")
            }
        })
        root.addView(Button(this).apply {
            text = "3) Battery optimization OFF (MIUI zarrori)"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "4) Copy link"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dl", "http://" + wifiIp() + ":8766"))
                toast("Link copied")
            }
        })

        statusTv = TextView(this).apply { textSize = 14f; setPadding(0, pad, 0, 0) }
        root.addView(statusTv)

        val scr = ScrollView(this)
        scr.addView(root)
        setContentView(scr)

        val perms = ArrayList(arrayOf(android.Manifest.permission.CAMERA))
        if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 11)

        if (!Settings.System.canWrite(this)) {
            statusTv.text = "Tip: brightness/rotate controls ke liye app ko 'modify system settings' do (Settings ▸ Apps ▸ DeviceLink)."
        }
    }

    private fun startStream() {
        if (!accOn()) { toast("Pehle step 2 (Accessibility) ON karo"); }
        try { startActivityForResult(mpm.createScreenCaptureIntent(), 22) }
        catch (e: Exception) { toast("Projection error: ${e.message}") }
    }

    @Deprecated("deprecated api")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 22 && resultCode == RESULT_OK && data != null) {
            val si = Intent(this, StreamService::class.java)
            si.action = "START"
            si.putExtra("code", resultCode)
            si.putExtra("data", data)
            ContextCompat.startForegroundService(this, si)
            statusTv.text = "STREAMING LIVE ✔  —  laptop/phone browser me kholo:\nhttp://" + wifiIp() + ":8766"
        } else if (requestCode == 22) {
            statusTv.text = "Screen-share cancel hua. Dobara try karo."
        }
    }

    private fun accOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains("$packageName/.InputService") || enabled.contains("$packageName/com.santi.devicelink.InputService")
    }

    private fun wifiIp(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (!a.isLoopbackAddress && a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress!!
                }
            }
        } catch (_: Exception) {}
        return "phone-ip"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
