package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.*
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityDualCameraBinding
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DualCameraActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityDualCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraProvider: ProcessCameraProvider

    // OpenGL
    private var glRenderer: DualCameraGLRenderer? = null
    private var eglThread: HandlerThread? = null
    private var eglHandler: Handler? = null

    // Kamera
    private var backTexture: SurfaceTexture? = null
    private var frontTexture: SurfaceTexture? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    private var backTextureId = 0
    private var frontTextureId = 0
    private var backResolution = Size(1920, 1080)
    private var frontResolution = Size(1280, 720)

    // Mode
    private var isPhotoMode = true
    private var isRecording = AtomicBoolean(false)

    // Perekaman
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var recordingStartTime = 0L

    // Overlay depan
    private var frontViewportWidth = 0.25f  // proporsi lebar layar
    private var frontViewportHeight = 0.25f
    private var frontViewportX = 0.7f  // pojok kanan atas
    private var frontViewportY = 0.05f
    private var isCircle = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        eglThread = HandlerThread("EGLThread").apply { start() }
        eglHandler = Handler(eglThread!!.looper)

        binding.textureView.surfaceTextureListener = this

        checkPermissions()
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val ungranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isEmpty()) {
            initCamera()
        } else {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initCamera()
        } else {
            Toast.makeText(this, "Izin diperlukan", Toast.LENGTH_LONG).show()
        }
    }

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun bindUseCases() {
        cameraProvider.unbindAll()

        // Belakang
        val previewBack = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(backResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
                    ).build()
            ).build().apply {
                setSurfaceProvider { request ->
                    eglHandler?.post {
                        // Buat texture di thread GL
                        backTextureId = glRenderer?.createTexture() ?: return@post
                        backTexture = SurfaceTexture(backTextureId).apply {
                            setDefaultBufferSize(backResolution.width, backResolution.height)
                        }
                        backSurface = Surface(backTexture)
                        request.provideSurface(backSurface!!, Executor { command -> eglHandler?.post(command) }) {
                            backSurface?.release()
                            backSurface = null
                            backTexture?.release()
                            backTexture = null
                        }
                    }
                }
            }

        // Depan
        val previewFront = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(frontResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)
                    ).build()
            ).build().apply {
                setSurfaceProvider { request ->
                    eglHandler?.post {
                        frontTextureId = glRenderer?.createTexture() ?: return@post
                        frontTexture = SurfaceTexture(frontTextureId).apply {
                            setDefaultBufferSize(frontResolution.width, frontResolution.height)
                        }
                        frontSurface = Surface(frontTexture)
                        request.provideSurface(frontSurface!!, Executor { command -> eglHandler?.post(command) }) {
                            frontSurface?.release()
                            frontSurface = null
                            frontTexture?.release()
                            frontTexture = null
                        }
                    }
                }
            }

        try {
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, previewBack
            )
            cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_FRONT_CAMERA, previewFront
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Perangkat tidak mendukung dua kamera bersamaan", Toast.LENGTH_LONG).show()
        }
    }

    // TextureView Listener
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        glRenderer = DualCameraGLRenderer()
        glRenderer?.init(surface, width, height)
        startRenderingLoop()
        setupButtons()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderingLoop()
        glRenderer?.release()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private var renderingJob: Job? = null
    private fun startRenderingLoop() {
        renderingJob?.cancel()
        renderingJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                glRenderer?.drawFrame(
                    backTexture, frontTexture,
                    frontViewportX, frontViewportY,
                    frontViewportWidth, frontViewportHeight,
                    isCircle,
                    mediaCodec?.createInputSurface()  // jika recording, render juga ke input surface
                )
                // Update texture kamera
                backTexture?.updateTexImage()
                frontTexture?.updateTexImage()
                delay(16) // roughly 60fps
            }
        }
    }
    private fun stopRenderingLoop() {
        renderingJob?.cancel()
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isPhotoMode) takePhoto() else toggleRecording()
        }
        binding.btnSwitchMode.setOnClickListener {
            isPhotoMode = !isPhotoMode
            binding.btnSwitchMode.setImageResource(
                if (isPhotoMode) R.drawable.ic_video else R.drawable.ic_camera
            )
        }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun takePhoto() {
        eglHandler?.post {
            val bitmap = glRenderer?.captureFrame()
            bitmap?.let { savePhoto(it) }
            runOnUiThread { Toast.makeText(this, "Foto disimpan", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun savePhoto(bitmap: Bitmap) {
        val filename = "aistudio_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording.get()) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (isRecording.get()) return
        eglHandler?.post {
            try {
                val outputFile = File(
                    getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "aistudio_${System.currentTimeMillis()}.mp4"
                )
                val width = glRenderer?.outputWidth ?: 1920
                val height = glRenderer?.outputHeight ?: 1080

                mediaCodec = MediaCodec.createEncoderByType("video/avc")
                val format = MediaFormat.createVideoFormat("video/avc", width, height)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = mediaCodec!!.createInputSurface()
                mediaCodec!!.start()

                mediaMuxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                isRecording.set(true)
                recordingStartTime = System.nanoTime()

                runOnUiThread {
                    binding.btnCapture.setImageResource(R.drawable.ic_stop)
                    Toast.makeText(this, "Merekam...", Toast.LENGTH_SHORT).show()
                }

                encodeLoop()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal rekam: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun encodeLoop() {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        eglHandler?.post(object : Runnable {
            override fun run() {
                if (!isRecording.get()) {
                    codec.stop()
                    codec.release()
                    muxer.stop()
                    muxer.release()
                    mediaCodec = null
                    mediaMuxer = null
                    return
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(outIdx, false)
                        } else {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted) {
                                muxer.writeSampleData(trackIndex, buf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }
                eglHandler?.postDelayed(this, 10)
            }
        })
    }

    private fun stopRecording() {
        isRecording.set(false)
        runOnUiThread {
            binding.btnCapture.setImageResource(R.drawable.ic_video)
            Toast.makeText(this, "Video disimpan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            type = if (isPhotoMode) "image/*" else "video/*"
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(android.content.Intent.createChooser(intent, "Pilih galeri"))
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pengaturan")
        val items = arrayOf("Bentuk kamera depan", "Resolusi")
        builder.setItems(items) { _, which ->
            when (which) {
                0 -> {
                    isCircle = !isCircle
                    Toast.makeText(this, if (isCircle) "Bulat" else "Kotak", Toast.LENGTH_SHORT).show()
                }
                1 -> showResolutionDialog()
            }
        }
        builder.show()
    }

    private fun showResolutionDialog() {
        // Placeholder
        Toast.makeText(this, "Resolution settings placeholder", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) stopRecording()
        cameraExecutor.shutdown()
        eglThread?.quitSafely()
    }
}
