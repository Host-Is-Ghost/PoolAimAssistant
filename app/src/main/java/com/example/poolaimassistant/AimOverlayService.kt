package com.example.poolaimassistant

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class AimOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var trajectoryView: TrajectoryOverlayView
    private lateinit var floatingWidget: LinearLayout

    private val visionEngine = AutoTableVisionEngine()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isGuidelineEnabled = true
    private var spinModeIndex = 0 // 0 = Stun, 1 = Top Spin, 2 = Backspin

    private val handlerThread = HandlerThread("AimVisionThread").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startMyForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupClickThroughTrajectoryCanvas()
        setupFloatingControlWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun setupClickThroughTrajectoryCanvas() {
        trajectoryView = TrajectoryOverlayView(this)

        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(trajectoryView, canvasParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControlWindow() {
        val widgetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 36
            y = 100
        }

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.argb(215, 18, 24, 36))
            cornerRadius = 42f
            setStroke(3, Color.CYAN)
        }

        floatingWidget = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bgDrawable
            setPadding(16, 10, 16, 10)
        }

        val menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        val toggleLineBtn = Button(this).apply {
            text = "Line: ON"
            textSize = 11f
            setOnClickListener {
                isGuidelineEnabled = !isGuidelineEnabled
                text = if (isGuidelineEnabled) "Line: ON" else "Line: OFF"
                trajectoryView.visibility = if (isGuidelineEnabled) View.VISIBLE else View.INVISIBLE
            }
        }

        val bounceBtn = Button(this).apply {
            text = "Bounce: 2"
            textSize = 11f
            setOnClickListener {
                trajectoryView.maxBounces = (trajectoryView.maxBounces % 3) + 1
                text = "Bounce: ${trajectoryView.maxBounces}"
            }
        }

        val spinBtn = Button(this).apply {
            text = "Spin: Stun"
            textSize = 11f
            setOnClickListener {
                spinModeIndex = (spinModeIndex + 1) % 3
                when (spinModeIndex) {
                    0 -> {
                        trajectoryView.spinFactor = 0.0f
                        text = "Spin: Stun"
                    }
                    1 -> {
                        trajectoryView.spinFactor = 0.95f
                        text = "Spin: Top"
                    }
                    2 -> {
                        trajectoryView.spinFactor = -0.95f
                        text = "Spin: Back"
                    }
                }
            }
        }

        val recalibrateBtn = Button(this).apply {
            text = "Re-Scan"
            textSize = 11f
            setOnClickListener {
                visionEngine.forceRecalibrateTable()
            }
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.RED)
            setOnClickListener { stopSelf() }
        }

        menuContainer.addView(toggleLineBtn)
        menuContainer.addView(bounceBtn)
        menuContainer.addView(spinBtn)
        menuContainer.addView(recalibrateBtn)
        menuContainer.addView(closeBtn)

        val mainBubbleBtn = Button(this).apply {
            text = "🎯"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        mainBubbleBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = widgetParams.x
                    initialY = widgetParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        widgetParams.x = initialX + dx
                        widgetParams.y = initialY + dy
                        windowManager.updateViewLayout(floatingWidget, widgetParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        menuContainer.visibility =
                            if (menuContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        floatingWidget.addView(mainBubbleBtn)
        floatingWidget.addView(menuContainer)
        windowManager.addView(floatingWidget, widgetParams)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, backgroundHandler)

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AimMasterProjection",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (isGuidelineEnabled) {
                    val result = visionEngine.analyzeFrame(image)
                    trajectoryView.updateOverlay(result)
                }
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    private fun startMyForeground() {
        val channelId = "aim_overlay_channel"
        val channel = NotificationChannel(
            channelId, "Aim Master Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Aim Master Pro Active")
            .setContentText("Tap floating 🎯 icon to adjust bounces & spin")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread.quitSafely()
        if (::trajectoryView.isInitialized) windowManager.removeView(trajectoryView)
        if (::floatingWidget.isInitialized) windowManager.removeView(floatingWidget)
    }
}
