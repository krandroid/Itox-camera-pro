package com.example

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.databinding.ActivityDualCameraBinding
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DualCameraActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var binding: ActivityDualCameraBinding
    private var cameraProvider: ProcessCameraProvider? = null

    // OpenGL
    private var glRenderer: DualCameraGLRenderer? = null
    private val eglThread = HandlerThread("GLThread").apply { start() }
    private val eglHandler = Handler(eglThread.looper)
    
    private var backTexture: SurfaceTexture? = null
    private var frontTexture: SurfaceTexture? = null
    private var backSurface: Surface? = null
    private var frontSurface: Surface? = null
    
    private var backResolution = Size(1920, 1080)
    private var frontResolution = Size(1280, 720)

    // Frame flags
    private var backFrameReady = false
    private var frontFrameReady = false
    private var backReleased = false
    private var frontReleased = false

    // Mode
    private var isPhotoMode = true
    private val isRecording = AtomicBoolean(false)

    // Recording Encoder
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var recordingInputSurface: Surface? = null

    // Overlay
    private var frontX = 0.7f
    private var frontY = 0.05f
    private var frontW = 0.25f
    private var frontH = 0.25f
    private var isCircle = true
    
    private var permissionsGranted = false

    // Render loop
    private var renderRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDualCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.textureView.surfaceTextureListener = this
        checkPermissions()
    }

    private fun checkPermissions() {
        var perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = perms.plus(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            permissionsGranted = true
            tryStartCamera()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            permissionsGranted = true
            tryStartCamera()
        } else Toast.makeText(this, "Izin diperlukan", Toast.LENGTH_LONG).show()
    }
    
    private fun tryStartCamera() {
        if (!permissionsGranted || glRenderer == null) return
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val previewBack = Preview.Builder()
            .setResolutionSelector(resolution(backResolution))
            .build()
            .apply {
                setSurfaceProvider { request ->
                    eglHandler.post {
                        backReleased = false
                        val texId = glRenderer?.createTexture() ?: return@post
                        backTexture = SurfaceTexture(texId).apply {
                            setDefaultBufferSize(backResolution.width, backResolution.height)
                            setOnFrameAvailableListener {
                                if (!backReleased) backFrameReady = true
                            }
                        }
                        backSurface = Surface(backTexture)
                        request.provideSurface(backSurface!!, { command -> eglHandler.post(command) }) {
                            backReleased = true
                            backSurface?.release()
                            backSurface = null
                            backTexture?.release()
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
                    eglHandler.post {
                        frontReleased = false
                        val texId = glRenderer?.createTexture() ?: return@post
                        frontTexture = SurfaceTexture(texId).apply {
                            setDefaultBufferSize(frontResolution.width, frontResolution.height)
                            setOnFrameAvailableListener {
                                if (!frontReleased) frontFrameReady = true
                            }
                        }
                        frontSurface = Surface(frontTexture)
                        request.provideSurface(frontSurface!!, { command -> eglHandler.post(command) }) {
                            frontReleased = true
                            frontSurface?.release()
                            frontSurface = null
                            frontTexture?.release()
                            frontTexture = null
                            frontFrameReady = false
                        }
                    }
                }
            }

        try {
            // Binding dua kamera sekaligus
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
            provider.bindToLifecycle(listOf(backConfig, frontConfig))
        } catch (e: Exception) {
            Toast.makeText(this, "Dual camera tidak didukung: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewBack)
            } catch (e2: Exception) {
                // Ignore fallback error
            }
        }
    }

    private fun resolution(size: Size) = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
        .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(size, 
            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER))
        .build()

    // TextureView Listener
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        eglHandler.post {
            glRenderer = DualCameraGLRenderer().apply { init(surface, width, height) }
            runOnUiThread { tryStartCamera() }
            startRenderLoop()
        }
        setupButtons()
    }
    
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glRenderer?.outputWidth = width
        glRenderer?.outputHeight = height
    }
    
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderLoop()
        eglHandler.post { glRenderer?.release() }
        return true
    }
    
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun startRenderLoop() {
        renderRunnable = object : Runnable {
            override fun run() {
                if (glRenderer == null) return
                
                // Update tekstur belakang
                if (backFrameReady && backTexture != null && !backReleased) {
                    try {
                        backTexture?.updateTexImage()
                        backFrameReady = false
                    } catch (e: Exception) {
                        backFrameReady = false
                    }
                }
                
                // Update tekstur depan
                if (frontFrameReady && frontTexture != null && !frontReleased) {
                    try {
                        frontTexture?.updateTexImage()
                        frontFrameReady = false
                    } catch (e: Exception) {
                        frontFrameReady = false
                    }
                }

                // Render EGL dengan OpenGL
                glRenderer?.drawFrame(
                    backTexture, frontTexture,
                    frontX, frontY, frontW, frontH, isCircle,
                    recordingInputSurface
                )
                eglHandler.postDelayed(this, 30) // ~30fps
            }
        }
        eglHandler.post(renderRunnable!!)
    }

    private fun stopRenderLoop() {
        renderRunnable?.let { eglHandler.removeCallbacks(it) }
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            if (isPhotoMode) takePhoto()
            else {
                if (isRecording.get()) stopRecording() else startRecording()
            }
        }
        binding.btnSwitchMode.setOnClickListener {
            if (isRecording.get()) return@setOnClickListener
            isPhotoMode = !isPhotoMode
            binding.btnSwitchMode.setImageResource(if (isPhotoMode) R.drawable.ic_video else R.drawable.ic_camera)
            binding.tvRecTime.visibility = if (isPhotoMode) View.INVISIBLE else View.VISIBLE
        }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = if (isPhotoMode) "image/*" else "video/*"
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private fun takePhoto() {
        eglHandler.post {
            // Ambil frame terbaru
            if (backFrameReady && backTexture != null && !backReleased) {
                try {
                    backTexture?.updateTexImage()
                    backFrameReady = false
                } catch (e: Exception) {
                    backFrameReady = false
                }
            }
            if (frontFrameReady && frontTexture != null && !frontReleased) {
                try {
                    frontTexture?.updateTexImage()
                    frontFrameReady = false
                } catch (e: Exception) {
                    frontFrameReady = false
                }
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
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
        runOnUiThread { Toast.makeText(this, "Foto disimpan!", Toast.LENGTH_SHORT).show() }
    }

    private fun startRecording() {
        if (isRecording.get()) return
        eglHandler.post {
            try {
                val outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "dual_${System.currentTimeMillis()}.mp4")
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
                
                // ⚡ PENTING: createInputSurface hanya sekali!
                recordingInputSurface = mediaCodec!!.createInputSurface()
                mediaCodec!!.start()

                mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                
                isRecording.set(true)
                muxerStarted = false
                trackIndex = -1
                
                encodeLoop()
                runOnUiThread { binding.btnCapture.setImageResource(R.drawable.ic_stop) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@DualCameraActivity, "Gagal rekam: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun encodeLoop() {
        eglHandler.post(object : Runnable {
            override fun run() {
                if (!isRecording.get()) {
                    // Bersihkan encoder & muxer
                    mediaCodec?.stop()
                    mediaCodec?.release()
                    mediaCodec = null
                    if (muxerStarted) {
                        try { mediaMuxer?.stop() } catch (_: Exception) {}
                    }
                    mediaMuxer?.release()
                    mediaMuxer = null
                    recordingInputSurface = null
                    runOnUiThread { binding.btnCapture.setImageResource(R.drawable.ic_video) }
                    return
                }

                try {
                    val bufferInfo = MediaCodec.BufferInfo()
                    var outIdx = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10_000)
                    while (outIdx >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (!muxerStarted && mediaCodec!!.outputFormat != null) {
                            trackIndex = mediaMuxer!!.addTrack(mediaCodec!!.outputFormat)
                            mediaMuxer!!.start()
                            muxerStarted = true
                        }
                        if (bufferInfo.size != 0 && muxerStarted) {
                            val buf = mediaCodec!!.getOutputBuffer(outIdx)!!
                            mediaMuxer!!.writeSampleData(trackIndex, buf, bufferInfo)
                        }
                        mediaCodec!!.releaseOutputBuffer(outIdx, false)
                        outIdx = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                eglHandler.postDelayed(this, 10)
            }
        })
    }

    private fun stopRecording() {
        isRecording.set(false)
        recordingInputSurface = null // lepaskan surface agar tidak merender ke sana lagi
        runOnUiThread { Toast.makeText(this, "Video berhasil digabung & disimpan tanpa FFmpeg!", Toast.LENGTH_SHORT).show() }
    }

    private fun showSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Pengaturan")
            setItems(arrayOf(
                "Bentuk Depan (${if (isCircle) "Bulat" else "Kotak"})", 
                "Resolusi"
            )) { _, i ->
                when (i) {
                    0 -> isCircle = !isCircle
                    1 -> {
                        if (backResolution.width == 1920) {
                            backResolution = Size(3840, 2160)
                            frontResolution = Size(1920, 1080)
                        } else {
                            backResolution = Size(1920, 1080)
                            frontResolution = Size(1280, 720)
                        }
                        tryStartCamera()
                    }
                }
            }
        }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording.get()) stopRecording()
        eglThread.quitSafely()
    }
}
