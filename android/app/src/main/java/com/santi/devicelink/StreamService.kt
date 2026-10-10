package com.santi.devicelink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.*
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class StreamService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var mode = "screen"
        const val NID = 4101
        const val CH = "dlstream"
    }

    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var ws: WsServer? = null
    private var http: HttpBridge? = null
    private val h = Handler(Looper.getMainLooper())
    private var scrW = 720; private var scrH = 1280
    private var lastJpeg: ByteArray? = null
    private var pixBuf: IntArray? = null
    private var orientationListener: OrientationEventListener? = null
    private var batteryPct = -1

    // camera
    private var camDevice: CameraDevice? = null
    private var camReader: ImageReader? = null
    private var camThread: HandlerThread? = null

    // touch stroke accumulation
    private var strokePts: ArrayList<FloatArray>? = null
    private var strokeT0 = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { if (mode == "screen") grabScreen() else grabCam() } catch (_: Exception) {}
            h.postDelayed(this, 115)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "DeviceLink", NotificationManager.IMPORTANCE_LOW))
        try { ws = WsServer(8765, { msg -> handle(msg) }, { h.post { sendMeta() } }).also { it.start() } } catch (_: Exception) {}
        try { http = HttpBridge(8766) { appContextForAssets() }.also { it.isDaemon = true; it.start() } } catch (_: Exception) {}
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryPct = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    private fun appContextForAssets(): String =
        try { assets.open("viewer.html").bufferedReader().use { it.readText() } }
        catch (e: Exception) { "<h3>viewer.html missing</h3>" }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startAsForegroundWith(true)
        if (mp == null) {
            val code = intent?.getIntExtra("code", 0) ?: 0
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("data")
            if (data == null || code == 0) { stopSelf(); return START_NOT_STICKY }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = mpm.getMediaProjection(code, data)
            mp?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { h.post { stopSelf() } }
            }, h)
            createDisplay()
            startOrientationWatcher()
        }
        running = true
        h.removeCallbacks(tick); h.postDelayed(tick, 100)
        return START_STICKY
    }

    private fun startAsForegroundWith(proj: Boolean) {
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("DeviceLink — streaming")
            .setContentText("Remote connected. Browser se control kar sakte ho.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (mode == "cam") type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NID, n, type)
            } else startForeground(NID, n)
        } catch (_: Exception) {}
    }

    private fun displayMetrics(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = wm.defaultDisplay
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(dm)
        var w = dm.widthPixels; var hh = dm.heightPixels
        @Suppress("DEPRECATION")
        val rot = d.rotation
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) { val t = w; w = hh; hh = t }
        return Pair(w.coerceAtMost(2000), hh.coerceAtMost(3000))
    }

    private fun createDisplay() {
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        val (w, hh) = displayMetrics()
        scrW = w; scrH = hh
        reader = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
        val density = resources.displayMetrics.densityDpi
        vd = mp?.createVirtualDisplay("dl", w, hh, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader!!.surface, null, h)
    }

    private fun startOrientationWatcher() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                if (!running) return
                val (w, hh) = displayMetrics()
                if (w != scrW || hh != scrH) { createDisplay(); sendMeta() }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun grabScreen() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val w = img.width; val hh = img.height
            if (pixBuf == null || pixBuf!!.size < w * hh) pixBuf = IntArray(w * hh)
            val pix = pixBuf!!
            val last = buf.limit() / 4
            for (r in 0 until hh) {
                val base = r * stride / 4
                if (base + w <= last) {
                    buf.position(base * 4)
                    buf.asIntBuffer().get(pix, r * w, w)
                }
            }
            val full = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            full.setPixels(pix, 0, w, 0, 0, w, hh)
            val maxDim = 620
            val scale = maxDim.toFloat() / maxOf(w, hh)
            val out: Bitmap = if (scale < 1f) {
                val m = Matrix().apply { postScale(scale, scale) }
                Bitmap.createBitmap(full, 0, 0, w, hh, m, true)
            } else full
            val bos = ByteArrayOutputStream(60 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 46, bos)
            lastJpeg = bos.toByteArray()
            ws?.bcast(lastJpeg!!)
            if (out !== full) out.recycle()
            full.recycle()
        } catch (_: Exception) {} finally { try { img.close() } catch (_: Exception) {} }
    }

    // ---------------- camera ----------------
    private fun startCam(on: Boolean) {
        if (!on) {
            mode = "screen"
            try { camDevice?.close() } catch (_: Exception) {}
            camDevice = null
            try { camReader?.close() } catch (_: Exception) {}
            camReader = null
            camThread?.quitSafely(); camThread = null
            startAsForegroundWith(true)
            sendMeta(); return
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            InputService.inst?.mainToast("Camera permission nahi — app se do")
            return
        }
        mode = "cam"
        startAsForegroundWith(true)
        try {
            camThread = HandlerThread("cam").also { it.start() }
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.first()
            camReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            camReader!!.setOnImageAvailableListener({ r ->
                val im = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val bb = im.planes[0].buffer
                    val bytes = ByteArray(bb.remaining()); bb.get(bytes)
                    ws?.bcast(bytes)
                } catch (_: Exception) {} finally { try { im.close() } catch (_: Exception) {} }
            }, Handler(camThread!!.looper))
            @Suppress("MissingPermission")
            cm.openCamera(id, camCb, Handler(camThread!!.looper))
        } catch (e: Exception) { mode = "screen" }
        sendMeta()
    }

    @Suppress("unused")
    private val camCb = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) { camDevice = d; openSession() }
        override fun onDisconnected(d: CameraDevice) { try { d.close() } catch (_: Exception) {}; camDevice = null }
        override fun onError(d: CameraDevice, e: Int) { try { d.close() } catch (_: Exception) {}; camDevice = null; mode = "screen" }
    }

    private fun openSession() {
        try {
            val devs = camDevice ?: return
            @Suppress("DEPRECATION")
            devs.createCaptureSession(listOf(camReader!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        val b = devs.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(camReader!!.surface)
                        session.setRepeatingRequest(b.build(), null, Handler(camThread!!.looper))
                    } catch (_: Exception) {}
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, Handler(camThread!!.looper))
        } catch (_: Exception) {}
    }

    private fun grabCam() { if (camDevice == null) { mode = "screen" } }

    // ---------------- messages ----------------
    private fun handle(msg: String) {
        val o = try { JSONObject(msg) } catch (_: Exception) { return }
        val acc = InputService.inst
        when (o.optString("t")) {
            "ping" -> ws?.bcastTxt(JSONObject().put("t", "pong").put("id", o.opt("id")).toString())
            "down" -> { strokePts = arrayListOf(floatArrayOf(o.getDouble("x").toFloat() * scrW, o.getDouble("y").toFloat() * scrH)); strokeT0 = System.currentTimeMillis() }
            "move" -> strokePts?.add(floatArrayOf(o.getDouble("x").toFloat() * scrW, o.getDouble("y").toFloat() * scrH))
            "up" -> {
                val pts = strokePts ?: return
                val dt = System.currentTimeMillis() - strokeT0
                val first = pts.first(); val last = pts.last()
                val dist = Math.hypot((last[0] - first[0]).toDouble(), (last[1] - first[1]).toDouble())
                if (pts.size <= 2 && dist < 14 && dt < 200) acc?.tap(last[0], last[1])
                else acc?.stroke(pts, dt)
                strokePts = null
            }
            "key" -> when (o.optString("v")) {
                "back" -> acc?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> acc?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> acc?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notif" -> acc?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "power" -> acc?.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            }
            "text" -> acc?.typeText(o.optString("v"))
            "cam" -> h.post { startCam(o.optBoolean("on")) }
            "wake" -> try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "dl:w").acquire(1500)
            } catch (_: Exception) {}
            "bri" -> try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, o.getInt("v").coerceIn(10, 255))
            } catch (_: Exception) { InputService.inst?.mainToast("Settings modify permission do") }
            "rot" -> try {
                Settings.System.putString(contentResolver, "user_rotation", o.getInt("v").toString())
            } catch (_: Exception) {}
            "open" -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.getString("v"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            "apps" -> h.post { sendApps() }
            "launch" -> try {
                val li = packageManager.getLaunchIntentForPackage(o.getString("pkg"))
                if (li != null) startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            "shot" -> h.post { lastJpeg?.let { saveShot(it) } }
            "info" -> sendMeta()
        }
    }

    private fun sendApps() {
        try {
            val arr = JSONArray()
            val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            for (ri in list.take(60)) {
                arr.put(JSONObject().put("n", ri.loadLabel(packageManager).toString()).put("p", ri.activityInfo.packageName))
            }
            ws?.bcastTxt(JSONObject().put("t", "appList").put("a", arr).toString())
        } catch (_: Exception) {}
    }

    private fun sendMeta() {
        val m = JSONObject()
            .put("t", "meta").put("model", android.os.Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("w", scrW).put("h", scrH).put("mode", mode).put("battery", batteryPct)
        ws?.bcastTxt(m.toString())
    }

    private fun saveShot(bytes: ByteArray) {
        try {
            val vals = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "dl-" + System.currentTimeMillis() + ".jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeviceLink")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals)
            uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
            InputService.inst?.mainToast("Screenshot saved → Pictures/DeviceLink")
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(tick)
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { camDevice?.close() } catch (_: Exception) {}
        try { camReader?.close() } catch (_: Exception) {}
        try { ws?.stop() } catch (_: Exception) {}
        try { http?.closeAll() } catch (_: Exception) {}
        orientationListener?.disable()
        super.onDestroy()
    }
}
