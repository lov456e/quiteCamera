package com.example.quietcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executor

/**
 * QuietCam: Camera2 API로 직접 만든 카메라. 셔터음/녹화 시작·종료음을
 * 재생하는 코드(MediaActionSound 등)를 일절 쓰지 않는다.
 * 기기의 모든 카메라(논리 + 숨은 물리 카메라)를 순서대로 전환해 쓸 수 있다.
 */
class MainActivity : Activity() {

    data class Cam(val label: String, val logicalId: String, val physicalId: String?)

    private lateinit var cm: CameraManager
    private lateinit var tv: TextureView
    private lateinit var shutter: Button
    private lateinit var camBtn: Button
    private lateinit var modeBtn: Button

    private val cams = mutableListOf<Cam>()
    private var idx = 0
    private var video = false
    private var recording = false
    private var recorded = false
    private var sensor = 90
    private var front = false

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recUri: android.net.Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var pv: Surface? = null

    private var previewSize = Size(1440, 1080)
    private var jpegSize = Size(4000, 3000)
    private var videoSize = Size(1920, 1080)

    private val thread = HandlerThread("cam").apply { start() }
    private val handler = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executor { handler.post(it) }

    // ───────────── UI ─────────────
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CAMERA_SERVICE) as CameraManager

        tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (ready()) reopen() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) { configureTransform() }
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        camBtn = Button(this).apply {
            textSize = 12f
            setOnClickListener { if (!recording && cams.isNotEmpty()) { idx = (idx + 1) % cams.size; reopen() } }
        }
        shutter = Button(this).apply { text = "●"; textSize = 22f; setOnClickListener { onShutter() } }
        modeBtn = Button(this).apply {
            setOnClickListener { if (!recording) { video = !video; reopen() } }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 96)
        }
        val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(camBtn, lp()); bar.addView(shutter, lp()); bar.addView(modeBtn, lp())

        val root = FrameLayout(this)
        root.addView(tv, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!hasPerm()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        else if (tv.isAvailable && ready()) reopen()
    }

    override fun onPause() { close(); super.onPause() }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (tv.isAvailable && ready()) reopen()
    }

    private fun hasPerm() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ready(): Boolean {
        if (!hasPerm()) return false
        if (cams.isEmpty()) loadCams()
        return cams.isNotEmpty()
    }

    private fun toast(m: String) = ui.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    // ───────────── 카메라 목록 (논리 + 숨은 물리 카메라) ─────────────
    private fun usable(c: CameraCharacteristics) =
        c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true

    private fun label(c: CameraCharacteristics, id: String): String {
        val facing = when (c[CameraCharacteristics.LENS_FACING]) {
            CameraCharacteristics.LENS_FACING_FRONT -> "전면"
            CameraCharacteristics.LENS_FACING_BACK -> "후면"
            else -> "외부"
        }
        val f = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()
        val s = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val eq = if (f != null && s != null)
            (f * 43.27 / Math.hypot(s.width.toDouble(), s.height.toDouble())).toInt() else null
        return "$facing${if (eq != null) " ${eq}mm" else ""}\n[$id]"
    }

    private fun loadCams() {
        cams.clear()
        val ids = cm.cameraIdList
        for (id in ids) {
            val c = cm.getCameraCharacteristics(id)
            if (usable(c)) cams += Cam(label(c, id), id, null)
        }
        for (id in ids) {
            val base = cm.getCameraCharacteristics(id)
            for (p in base.physicalCameraIds) if (p !in ids) {
                val pc = runCatching { cm.getCameraCharacteristics(p) }.getOrDefault(base)
                cams += Cam(label(pc, "$id→$p"), id, p)
            }
        }
    }

    // ───────────── 카메라 열기 / 세션 ─────────────
    private fun pick(a: Array<Size>?, w: Int, h: Int, max: Int): Size {
        val l = a?.filter { it.width * it.height <= max } ?: emptyList()
        return l.filter { it.width * h == it.height * w }.maxByOrNull { it.width * it.height }
            ?: l.maxByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    @SuppressLint("MissingPermission")
    private fun reopen() {
        close()
        val cam = cams.getOrNull(idx) ?: return
        camBtn.text = cam.label
        modeBtn.text = if (video) "영상" else "사진"

        val base = cm.getCameraCharacteristics(cam.logicalId)
        val c = cam.physicalId?.let { p -> runCatching { cm.getCameraCharacteristics(p) }.getOrNull() } ?: base
        sensor = base[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90
        front = base[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return

        if (video) {
            videoSize = pick(map.getOutputSizes(MediaRecorder::class.java), 16, 9, 1920 * 1080)
            previewSize = pick(map.getOutputSizes(SurfaceTexture::class.java), 16, 9, 1280 * 720)
        } else {
            jpegSize = pick(map.getOutputSizes(ImageFormat.JPEG), 4, 3, 12_500_000)
            previewSize = pick(map.getOutputSizes(SurfaceTexture::class.java), 4, 3, 1440 * 1080)
        }

        runCatching {
            cm.openCamera(cam.logicalId, exec, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession(cam) }
                override fun onDisconnected(d: CameraDevice) { d.close() }
                override fun onError(d: CameraDevice, e: Int) { d.close(); toast("카메라를 열 수 없음 (오류 $e)") }
            })
        }.onFailure { toast("카메라 열기 실패: ${it.message}") }
    }

    private fun startSession(cam: Cam) {
        val d = device ?: return
        val st = tv.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val p = Surface(st)
        pv = p
        val out = mutableListOf(p)
        if (video) {
            prepareRecorder()
            out += recorder!!.surface
        } else {
            val r = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            reader = r
            out += r.surface
        }
        val cfgs = out.map { s ->
            OutputConfiguration(s).also { o -> cam.physicalId?.let { o.setPhysicalCameraId(it) } }
        }
        val sc = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, cfgs, exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    rb.addTarget(p)
                    rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    s.setRepeatingRequest(rb.build(), null, handler)
                    ui.post { configureTransform() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("이 카메라는 이 모드로 열 수 없음 → 다른 카메라/모드를 선택하세요")
                }
            }
        )
        d.createCaptureSession(sc)
    }

    // ───────────── 촬영 / 녹화 (소리 재생 코드 없음) ─────────────
    private fun orient(): Int {
        val r = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensor + r) % 360 else (sensor - r + 360) % 360
    }

    private fun onShutter() {
        if (!video) capturePhoto() else if (!recording) startRec() else stopRec()
    }

    private fun capturePhoto() {
        val d = device ?: return
        val s = session ?: return
        val r = reader ?: return
        r.setOnImageAvailableListener({ rd ->
            rd.acquireNextImage()?.use { img ->
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                savePhoto(bytes)
            }
        }, handler)
        val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        rb.addTarget(r.surface)
        rb.set(CaptureRequest.JPEG_ORIENTATION, orient())
        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        s.capture(rb.build(), null, handler)
    }

    private fun savePhoto(bytes: ByteArray) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "QC_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuietCam")
        }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(u)?.use { it.write(bytes) }
        toast("사진 저장됨")
    }

    private fun prepareRecorder() {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "QC_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/QuietCam")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        recUri = u
        recorded = false
        val pfd = contentResolver.openFileDescriptor(u, "w")!!
        recPfd = pfd
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncodingBitRate(16_000_000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOrientationHint(orient())
            prepare()
        }
    }

    private fun startRec() {
        val d = device ?: return
        val s = session ?: return
        val r = recorder ?: return
        val p = pv ?: return
        val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        rb.addTarget(p)
        rb.addTarget(r.surface)
        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        s.setRepeatingRequest(rb.build(), null, handler)
        r.start()
        recording = true
        recorded = true
        shutter.text = "■"
    }

    private fun stopRec() {
        recording = false
        shutter.text = "●"
        runCatching { session?.stopRepeating(); session?.abortCaptures() }
        runCatching { recorder?.stop() }
        toast("영상 저장됨")
        reopen()
    }

    private fun close() {
        if (recording) {
            recording = false
            shutter.text = "●"
            runCatching { session?.stopRepeating(); session?.abortCaptures() }
            runCatching { recorder?.stop() }
        }
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        runCatching { recorder?.release() }; recorder = null
        runCatching { recPfd?.close() }; recPfd = null
        if (!recorded) recUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        recUri = null
        recorded = true
    }

    // ───────────── 미리보기 회전/비율 보정 ─────────────
    private fun configureTransform() {
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rot = display?.rotation ?: 0
        val m = Matrix()
        val cx = vw / 2f
        val cy = vh / 2f
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            val vr = RectF(0f, 0f, vw, vh)
            val br = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            br.offset(cx - br.centerX(), cy - br.centerY())
            m.setRectToRect(vr, br, Matrix.ScaleToFit.FILL)
            val s = maxOf(vh / previewSize.height, vw / previewSize.width)
            m.postScale(s, s, cx, cy)
            m.postRotate(90f * (rot - 2), cx, cy)
        } else {
            val a = previewSize.height.toFloat() / previewSize.width   // 세로 화면 기준 콘텐츠 비율
            val v = vw / vh
            if (a > v) m.postScale(a / v, 1f, cx, cy) else m.postScale(1f, v / a, cx, cy)
            if (rot == Surface.ROTATION_180) m.postRotate(180f, cx, cy)
        }
        tv.setTransform(m)
    }
}
