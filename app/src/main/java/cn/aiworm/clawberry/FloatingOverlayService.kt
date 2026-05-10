package clawberry.aiworm.cn

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import clawberry.aiworm.cn.voice.MicCaptureManager
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOverlayService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var wm: WindowManager? = null
  private var bubbleView: BubbleView? = null
  private var stateJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    if (!Settings.canDrawOverlays(this)) {
      stopSelf()
      return
    }

    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    wm = windowManager

    val bubble = BubbleView(this)
    bubbleView = bubble

    val sizePx = dpToPx(64)
    val params = WindowManager.LayoutParams(
      sizePx,
      sizePx,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = 0
      y = dpToPx(300)
    }

    bubble.onTap = {
      val i = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
      }
      startActivity(i)
    }

    bubble.onLongPress = { MicCaptureManager.toggleActiveMic() }

    bubble.onDrag = { dx, dy ->
      val bounds = windowManager.currentWindowMetrics.bounds
      params.x = (params.x + dx).coerceIn(0, bounds.width() - sizePx)
      params.y = (params.y + dy).coerceIn(0, bounds.height() - sizePx)
      runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    bubble.onDragEnd = {
      val bounds = windowManager.currentWindowMetrics.bounds
      params.x = if (params.x + sizePx / 2 < bounds.width() / 2) {
        0
      } else {
        bounds.width() - sizePx
      }
      runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    windowManager.addView(bubble, params)

    val runtime = (application as NodeApp).peekRuntime()
    stateJob = scope.launch {
      val connectedFlow = runtime?.isConnected ?: MutableStateFlow(false)
      combine(
        connectedFlow,
        MicCaptureManager.globalMicIsListening,
        MicCaptureManager.globalMicEnabled,
      ) { connected, listening, micEnabled ->
        Triple(connected, listening, micEnabled)
      }.collect { (connected, listening, micEnabled) ->
        bubble.isConnected = connected
        bubble.isMicEnabled = micEnabled
        bubble.isListening = listening
        bubble.invalidate()
      }
    }
  }

  override fun onDestroy() {
    stateJob?.cancel()
    scope.cancel()
    bubbleView?.let { v -> runCatching { wm?.removeView(v) } }
    bubbleView = null
    wm = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun dpToPx(dp: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics)
      .toInt()

  companion object {
    fun start(context: Context) {
      if (!Settings.canDrawOverlays(context)) return
      context.startService(Intent(context, FloatingOverlayService::class.java))
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, FloatingOverlayService::class.java))
    }
  }

  // ── Bubble View ─────────────────────────────────────────────────────────────

  inner class BubbleView(context: Context) : View(context) {
    var isConnected: Boolean = false
    var isMicEnabled: Boolean = false
    var isListening: Boolean = false
    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onDrag: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = dp(2.5f)
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = dp(2f)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeWidth = dp(1.7f)
      color = Color.WHITE
    }

    private val closeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 1_400
      repeatCount = ValueAnimator.INFINITE
      repeatMode = ValueAnimator.RESTART
      addUpdateListener { animator ->
        pulseProgress = animator.animatedValue as Float
        if (isListening) invalidate()
      }
      start()
    }

    private var pulseProgress = 0f
    private var iconBitmap: Bitmap? = null

    // Touch state
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressed = false

    private val longPressRunnable = Runnable {
      longPressed = true
      onLongPress()
    }

    init {
      val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_round)
        ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
      if (drawable != null) {
        val sz = dpInt(44)
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawable.setBounds(0, 0, sz, sz)
        drawable.draw(c)
        iconBitmap = bmp
      }
    }

    override fun onDetachedFromWindow() {
      super.onDetachedFromWindow()
      pulseAnimator.cancel()
      removeCallbacks(longPressRunnable)
    }

    override fun onDraw(canvas: Canvas) {
      val cx = width / 2f
      val cy = height / 2f
      val radius = minOf(cx, cy) - dp(4f)

      // Pulse ring when listening
      if (isListening) {
        val pulseRadius = radius * (1.05f + pulseProgress * 0.55f)
        val alpha = ((1f - pulseProgress) * 180f).toInt().coerceIn(0, 255)
        pulsePaint.color = Color.argb(alpha, 14, 165, 233)
        canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
      }

      // Background
      bgPaint.color = if (isMicEnabled) Color.argb(224, 229, 57, 53) else Color.argb(224, 29, 93, 216)
      canvas.drawCircle(cx, cy, radius, bgPaint)

      // Border – colour signals connection + mic state
      borderPaint.color = when {
        isListening -> Color.rgb(14, 165, 233)   // listening pulse
        isMicEnabled -> Color.rgb(255, 255, 255) // same emphasis as active mic button
        isConnected -> Color.rgb(34, 197, 94)
        else -> Color.argb(120, 140, 150, 165)   // grey: offline
      }
      canvas.drawCircle(cx, cy, radius, borderPaint)

      // App icon
      iconBitmap?.let { bmp ->
        canvas.drawBitmap(bmp, cx - bmp.width / 2f, cy - bmp.height / 2f, iconPaint)
      }

      // Mic-state badge: mirrors voice-tab semantics (enabled => "MicOff" slash).
      val badgeR = dp(11f)
      val badgeCx = cx + radius * 0.58f
      val badgeCy = cy + radius * 0.58f
      badgePaint.color = if (isMicEnabled) Color.rgb(229, 57, 53) else Color.rgb(14, 165, 233)
      canvas.drawCircle(badgeCx, badgeCy, badgeR, badgePaint)

      val bodyRect = RectF(badgeCx - dp(3f), badgeCy - dp(4.8f), badgeCx + dp(3f), badgeCy + dp(1f))
      canvas.drawRoundRect(bodyRect, dp(2.2f), dp(2.2f), micGlyphPaint)
      canvas.drawLine(badgeCx, badgeCy + dp(1f), badgeCx, badgeCy + dp(4.2f), micGlyphPaint)
      canvas.drawLine(badgeCx - dp(2.4f), badgeCy + dp(4.2f), badgeCx + dp(2.4f), badgeCy + dp(4.2f), micGlyphPaint)
      if (isMicEnabled) {
        // Diagonal slash = same intent as MicOff icon in VoiceTab.
        canvas.drawLine(
          badgeCx - dp(4.6f),
          badgeCy + dp(4.6f),
          badgeCx + dp(4.6f),
          badgeCy - dp(4.6f),
          micGlyphPaint,
        )
      }

      // Close overlay removed; long press now toggles mic (see onLongPress callback)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX
          downY = event.rawY
          moved = false
          longPressed = false
          postDelayed(longPressRunnable, 500)
          return true
        }

        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - downX).toInt()
          val dy = (event.rawY - downY).toInt()
          if (!moved && (abs(dx) > 8 || abs(dy) > 8)) {
            moved = true
            removeCallbacks(longPressRunnable)
          }
          if (moved) {
            onDrag(dx, dy)
            downX = event.rawX
            downY = event.rawY
          }
          return true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          removeCallbacks(longPressRunnable)
          when {
            moved -> onDragEnd()
            longPressed -> { /* long press already handled — don't bring app to front */ }
            else -> onTap()
          }
          return true
        }
      }
      return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float =
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
      )

    private fun dpInt(value: Int): Int = dp(value.toFloat()).toInt()
  }
}
