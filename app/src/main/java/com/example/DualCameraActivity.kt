package com.example

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityDualCameraBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DualCameraActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityDualCameraBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cameraProvider: ProcessCameraProvider

    // OpenGL
    private var glRenderer: DualCameraGLRenderer? = null
    private var eglThread: HandlerThread? = null
    private var eglHandler: Handler? = null

    // Textures
    private var backTexture: SurfaceTexture? = null
    private var frontTexture: SurfaceTexture? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    private var backTextureId = 0
    private var frontTextureId = 0
    private var backResolution = Size(1920, 1080)
    private var frontResolution = Size(1280, 720)

    // Frame flags
    private var backFrameReady = false
    private var frontFrameReady = false

    // Mode
    private var isPhotoMode = true
    private val isRecording = AtomicBoolean(false)

    // Recording
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1

    // Overlay
    private var frontX = 0.7f
    private var frontY = 0.05f
    private var frontW = 0.25f
    private var frontH = 0.25f
    private var isCircle = true

    // Render loop
    private var renderRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eglThread = HandlerThread("GLThread").apply { start() }
        eglHandler = Handler(eglThread!!.looper)
        binding.textureView.surfaceTextureListener = this
        checkPermissions()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initCamera()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) initCamera()
        else Toast.makeText(this, "Izin diperlukan", Toast.LENGTH_LONG).show()
    }

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        cameraProvider.unbindAll()

        val previewBack = Preview.Builder()
            .setResolutionSelector(resolution(backResolution))
            .build()
            .apply {
                setSurfaceProvider { request ->
                    eglHandler?.post {
                        backTextureId = glRenderer?.createTexture() ?: return@post
                        backTexture = SurfaceTexture(backTextureId).apply {
                            setDefaultBufferSize(backResolution.width, backResolution.height)
                            setOnFrameAvailableListener { backFrameReady = true }
                        }
                        backSurface = Surface(backTexture)
                        request.provideSurface(backSurface!!, { command -> eglHandler?.post(command!!) }) {
                            backSurface?.release()
                            backSurface = null
                            backTexture = null
                            backFrameReady = false
                        }
                    }
                }
            }

        val previewFront = Preview.Builder()
            .setResolutionSelector(resolution(frontResolution))
            .build()
            .apply {
                setSurfaceProvider { request ->
                    eglHandler?.post {
                        frontTextureId = glRenderer?.createTexture() ?: return@post
                        frontTexture = SurfaceTexture(frontTextureId).apply {
                            setDefaultBufferSize(frontResolution.width, frontResolution.height)
                            setOnFrameAvailableListener { frontFrameReady = true }
                        }
                        frontSurface = Surface(frontTexture)
                        request.provideSurface(frontSurface!!, { command -> eglHandler?.post(command!!) }) {
                            frontSurface?.release()
                            frontSurface = null
                            frontTexture = null
                            frontFrameReady = false
                        }
                    }
                }
            }

        try {
            // ⚡ Binding dua kamera sekaligus
            val cameraProvider = cameraProvider
            val backConfig = androidx.camera.core.ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_BACK_CAMERA,
                androidx.camera.core.UseCaseGroup.Builder().addUseCase(previewBack).build(),
                this
            )
            val frontConfig = androidx.camera.core.ConcurrentCamera.SingleCameraConfig(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                androidx.camera.core.UseCaseGroup.Builder().addUseCase(previewFront).build(),
                this
            )
            cameraProvider.bindToLifecycle(listOf(backConfig, frontConfig))
        } catch (e: Exception) {
            Toast.makeText(this, "Dual camera tidak didukung: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewBack)
            } catch (e2: Exception) {
                // Ignore fallback error
            }
        }
    }

    private fun resolution(size: Size) = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(size, androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
        .build()

    // TextureView Listener
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        glRenderer = DualCameraGLRenderer()
        glRenderer?.init(surface, width, height)
        startRenderLoop()
        setupButtons()
    }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderLoop()
        glRenderer?.release()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun startRenderLoop() {
        renderRunnable = object : Runnable {
            override fun run() {
                if (glRenderer == null) return
                // Update texture di GL thread
                if (backFrameReady && backTexture != null) {
                    backTexture?.updateTexImage()
                    backFrameReady = false
                }
                if (frontFrameReady && frontTexture != null) {
                    frontTexture?.updateTexImage()
                    frontFrameReady = false
                }

                // Render
                glRenderer?.drawFrame(
                    backTexture, frontTexture,
                    frontX, frontY, frontW, frontH, isCircle,
                    mediaCodec?.createInputSurface()
                )
                eglHandler?.postDelayed(this, 30) // 30fps
            }
        }
        eglHandler?.post(renderRunnable!!)
    }

    private fun stopRenderLoop() {
        renderRunnable?.let { eglHandler?.removeCallbacks(it) }
        renderRunnable = null
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isPhotoMode) takePhoto() else toggleRecording()
        }
        binding.btnSwitchMode.setOnClickListener {
            isPhotoMode = !isPhotoMode
            binding.btnSwitchMode.setImageResource(if (isPhotoMode) R.drawable.ic_video else R.drawable.ic_camera)
        }
        binding.btnGallery.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = if (isPhotoMode) "image/*" else "video/*"
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(android.content.Intent.createChooser(intent, "Galeri"))
        }
        binding.btnSettings.setOnClickListener { showSettings() }
    }

    private fun takePhoto() {
        eglHandler?.post {
            // Ambil frame terbaru
            if (backFrameReady && backTexture != null) {
                backTexture?.updateTexImage()
                backFrameReady = false
            }
            if (frontFrameReady && frontTexture != null) {
                frontTexture?.updateTexImage()
                frontFrameReady = false
            }
            val bitmap = glRenderer?.captureFrame()
            bitmap?.let { savePhoto(it) }
        }
    }

    private fun savePhoto(bitmap: Bitmap) {
        val filename = "dualcamera_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
        runOnUiThread { Toast.makeText(this, "Foto disimpan", Toast.LENGTH_SHORT).show() }
    }

    private fun toggleRecording() {
        if (isRecording.get()) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (isRecording.get()) return
        eglHandler?.post {
            try {
                val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dualcamera_${System.currentTimeMillis()}.mp4")
                val width = glRenderer?.outputWidth ?: 1920
                val height = glRenderer?.outputHeight ?: 1080

                mediaCodec = MediaCodec.createEncoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                }
                mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec!!.start()

                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                isRecording.set(true)
                encodeLoop()
                runOnUiThread { binding.btnCapture.setImageResource(R.drawable.ic_stop) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@DualCameraActivity, "Gagal rekam: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun encodeLoop() {
        eglHandler?.post(object : Runnable {
            override fun run() {
                if (!isRecording.get()) {
                    mediaCodec?.stop(); mediaCodec?.release(); mediaCodec = null
                    mediaMuxer?.stop(); mediaMuxer?.release(); mediaMuxer = null
                    runOnUiThread { binding.btnCapture.setImageResource(R.drawable.ic_video) }
                    return
                }
                val bufferInfo = MediaCodec.BufferInfo()
                val outIdx = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = mediaMuxer!!.addTrack(mediaCodec!!.outputFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val buf = mediaCodec!!.getOutputBuffer(outIdx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            mediaCodec!!.releaseOutputBuffer(outIdx, false)
                        } else {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted) mediaMuxer!!.writeSampleData(trackIndex, buf, bufferInfo)
                            mediaCodec!!.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
                eglHandler?.postDelayed(this, 10)
            }
        })
    }

    private fun stopRecording() {
        isRecording.set(false)
        runOnUiThread { Toast.makeText(this, "Video disimpan", Toast.LENGTH_SHORT).show() }
    }

    private fun showSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Pengaturan")
            setItems(arrayOf("Bentuk kamera depan (${if (isCircle) "Bulat" else "Kotak"})", "Resolusi")) { _, i ->
                if (i == 0) { isCircle = !isCircle; Toast.makeText(this@DualCameraActivity, if (isCircle) "Bulat" else "Kotak", Toast.LENGTH_SHORT).show() }
                else showResolutionDialog()
            }
        }.show()
    }

    private fun showResolutionDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Pilih Resolusi")
            setItems(arrayOf("Belakang 4K, Depan 1080p", "Belakang 1080p, Depan 720p")) { _, i ->
                when (i) {
                    0 -> { backResolution = Size(3840, 2160); frontResolution = Size(1920, 1080) }
                    1 -> { backResolution = Size(1920, 1080); frontResolution = Size(1280, 720) }
                }
                initCamera()
            }
        }.show()
    }

    override fun onDestroy() {
        if (isRecording.get()) stopRecording()
        cameraExecutor.shutdown()
        eglThread?.quitSafely()
        super.onDestroy()
    }
}
