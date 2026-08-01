package com.android.purebilibili.feature.plugin.bilicompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.purebilibili.core.plugin.PluginManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class CompanionViewMetrics(
    val density: Float,
    val petSize: Float,
    val margin: Float,
    val bubbleHeight: Float,
    val floorGap: Float
) {
    companion object {
        fun from(context: Context, width: Int, height: Int): CompanionViewMetrics {
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val shortSideDp = min(width, height) / density
            val petDp = shortSideDp.coerceIn(52f, 82f)
            return CompanionViewMetrics(
                density = density,
                petSize = petDp * density,
                margin = 12f * density,
                bubbleHeight = 34f * density,
                floorGap = 8f * density
            )
        }
    }
}

internal class BiliCompanionBoundarySensor {
    private val obstacleRects = mutableListOf<Rect>()
    private val rootLocation = IntArray(2)
    var loadingDetected: Boolean = false
        private set

    fun refresh(root: View, ignored: View) {
        obstacleRects.clear()
        loadingDetected = false
        root.getLocationOnScreen(rootLocation)
        collect(root, ignored)
    }

    fun resolveFloor(x: Float, width: Float, fallback: Float, height: Float): Float {
        val right = x + width
        return obstacleRects
            .asSequence()
            .filter { rect -> rect.right > x && rect.left < right && rect.top > width }
            .map { it.top.toFloat() }
            .filter { it < height }
            .minOrNull()
            ?.minus(width)
            ?.coerceAtMost(fallback)
            ?: fallback
    }

    private fun collect(view: View, ignored: View) {
        if (view === ignored || !view.isShown || view.alpha < 0.05f) return
        val isLoadingView = view.javaClass.simpleName.contains("Progress", true) ||
            view.contentDescription?.contains("加载") == true
        if (isLoadingView) loadingDetected = true
        if (view !== ignored && (view.isClickable || view.isFocusable || isLoadingView)) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect)) {
                rect.offset(-rootLocation[0], -rootLocation[1])
                obstacleRects += rect
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), ignored)
        }
    }
}

internal class BiliCompanionOverlayView(
    context: Context,
    private val onVideoClick: (String) -> Unit
) : View(context) {
    private val displayMetrics = context.resources.displayMetrics
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private val boundarySensor = BiliCompanionBoundarySensor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRect = Rect()
    private var rootForBoundary: View? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var metrics = CompanionViewMetrics.from(context, 1, 1)
    private var state = BiliCompanionState()
    private var enabled = false
    private var hostVisible = true
    private var hostFullscreen = false
    private var x = 0f
    private var y = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNanos = 0L
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragging = false
    private var interactiveGesture = false
    private var scaleInProgress = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onLongPress(event: MotionEvent) {
            BiliCompanionRuntime.setFingerFollow(true)
            announceForAccessibility("已开启手指追随")
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            BiliCompanionRuntime.setCompact(true)
            announceForAccessibility("电子桌宠已收起")
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val bvid = state.speechBvid
            if (bvid != null && pointInBubble(event.x, event.y)) {
                BiliCompanionRuntime.clearSpeech()
                onVideoClick(bvid)
            } else if (state.isCompact) {
                BiliCompanionRuntime.setCompact(false)
                announceForAccessibility("电子桌宠已展开")
            } else {
                announceForAccessibility(state.speech)
            }
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleInProgress = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            BiliCompanionRuntime.setScale(state.scale * detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaleInProgress = false
        }
    })

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setElevation(this, 4f * displayMetrics.density)
        setWillNotDraw(false)
        contentDescription = "Bili-Companion电子桌宠，${state.speech}"
    }

    fun setCompanionEnabled(value: Boolean) {
        enabled = value
        visibility = if (value && hostVisible && state.overlayEnabled) VISIBLE else INVISIBLE
        invalidate()
    }

    fun setHostVisible(value: Boolean) {
        hostVisible = value
        visibility = if (value && enabled && state.overlayEnabled) VISIBLE else INVISIBLE
        invalidate()
    }

    fun setHostFullscreen(value: Boolean) {
        hostFullscreen = value
        if (value && state.compactOnFullscreen) {
            BiliCompanionRuntime.setCompact(true)
        }
        invalidate()
    }

    fun bindBoundaryRoot(root: View) {
        rootForBoundary = root
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            boundarySensor.refresh(root, this)
            invalidate()
        }.also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        root.post { boundarySensor.refresh(root, this) }
    }

    fun unbindBoundaryRoot() {
        val root = rootForBoundary
        val listener = layoutListener
        if (root != null && listener != null && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        rootForBoundary = null
        layoutListener = null
    }

    fun updateState(next: BiliCompanionState) {
        state = next
        visibility = if (enabled && hostVisible && next.overlayEnabled) VISIBLE else INVISIBLE
        contentDescription = "Bili-Companion电子桌宠，${resolveMoodLabel(next.mood)}，${next.speech}"
        if (next.speechBvid != null) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        metrics = CompanionViewMetrics.from(context, width, height)
        if (x == 0f && y == 0f) {
            x = (width - metrics.petSize - metrics.margin).coerceAtLeast(metrics.margin)
            y = metrics.margin
        } else {
            x = x.coerceIn(metrics.margin, (width - metrics.petSize - metrics.margin).coerceAtLeast(metrics.margin))
            y = y.coerceIn(metrics.margin, (height - metrics.petSize - metrics.floorGap).coerceAtLeast(metrics.margin))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || !hostVisible || width <= 0 || height <= 0) return
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        if (!isTalkBackActive() && !hostFullscreen && !state.isFullscreen) advancePhysics(deltaSeconds)
        drawCompanion(canvas)
        postInvalidateOnAnimation()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || !hostVisible) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            interactiveGesture = pointInInteractiveRegion(event.x, event.y)
            if (!interactiveGesture) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            dragOffsetX = event.x - x
            dragOffsetY = event.y - y
            dragging = false
        }
        if (!interactiveGesture) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactiveGesture) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleInProgress && state.fingerFollow) {
            x = (event.x - dragOffsetX).coerceIn(metrics.margin, (width - metrics.petSize - metrics.margin).coerceAtLeast(metrics.margin))
            y = (event.y - dragOffsetY).coerceIn(metrics.margin, (height - metrics.petSize - metrics.floorGap).coerceAtLeast(metrics.margin))
            dragging = true
            velocityX = 0f
            velocityY = 0f
            invalidate()
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleInProgress && !state.isCompact) {
            val nextX = (event.x - dragOffsetX).coerceIn(
                metrics.margin,
                (width - metrics.petSize - metrics.margin).coerceAtLeast(metrics.margin)
            )
            val nextY = (event.y - dragOffsetY).coerceIn(
                metrics.margin,
                (height - metrics.petSize - metrics.floorGap).coerceAtLeast(metrics.margin)
            )
            val dx = nextX - x
            val dy = nextY - y
            if (abs(dx) > metrics.density * 2f || abs(dy) > metrics.density * 2f) {
                dragging = true
                x = nextX
                y = nextY
                velocityX = dx * 18f
                velocityY = dy * 18f
                invalidate()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            BiliCompanionRuntime.setFingerFollow(false)
            parent?.requestDisallowInterceptTouchEvent(false)
            interactiveGesture = false
            dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        BiliCompanionRuntime.setCompact(!state.isCompact)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageButton"
        info.isClickable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.text = "Bili-Companion电子桌宠，${resolveMoodLabel(state.mood)}，${state.speech}"
    }

    private fun advancePhysics(deltaSeconds: Float) {
        if (dragging || state.isCompact) return
        val floor = boundarySensor.resolveFloor(
            x = x,
            width = metrics.petSize,
            fallback = height - metrics.petSize - metrics.floorGap,
            height = height.toFloat()
        )
        velocityX += if (velocityX >= 0f) 10f * metrics.density * deltaSeconds else -10f * metrics.density * deltaSeconds
        x += velocityX * deltaSeconds
        y += velocityY * deltaSeconds
        velocityY += 580f * metrics.density * deltaSeconds
        if (x <= metrics.margin || x >= width - metrics.petSize - metrics.margin) {
            x = x.coerceIn(metrics.margin, (width - metrics.petSize - metrics.margin).coerceAtLeast(metrics.margin))
            velocityX *= -0.86f
        }
        if (y >= floor) {
            y = floor
            velocityY = -min(abs(velocityY) * 0.62f, 260f * metrics.density)
        }
        if (y <= metrics.margin) {
            y = metrics.margin
            velocityY = abs(velocityY) * 0.4f
        }
    }

    private fun drawCompanion(canvas: Canvas) {
        canvas.save()
        canvas.scale(state.scale, state.scale, x + metrics.petSize / 2f, y + metrics.petSize / 2f)
        val size = if (state.isCompact) metrics.petSize * 0.72f else metrics.petSize
        val drawX = x + (metrics.petSize - size) / 2f
        val drawY = if (state.isCompact) y + metrics.petSize - size else y
        if (!state.isCompact && state.speech.isNotBlank()) drawSpeechBubble(canvas, drawX, drawY, size)

        paint.style = Paint.Style.FILL
        paint.color = when (state.mood) {
            CompanionMood.PARTY -> 0xFFFF8FA3.toInt()
            CompanionMood.STUDY -> 0xFF7AC7C4.toInt()
            CompanionMood.BORED -> 0xFF9AA6B2.toInt()
        }
        canvas.drawCircle(drawX + size / 2f, drawY + size / 2f, size * 0.42f, paint)
        paint.color = 0xFFFFE7C2.toInt()
        canvas.drawCircle(drawX + size * 0.34f, drawY + size * 0.39f, size * 0.08f, paint)
        canvas.drawCircle(drawX + size * 0.66f, drawY + size * 0.39f, size * 0.08f, paint)
        paint.color = 0xFF30343B.toInt()
        canvas.drawCircle(drawX + size * 0.35f, drawY + size * 0.4f, size * 0.035f, paint)
        canvas.drawCircle(drawX + size * 0.65f, drawY + size * 0.4f, size * 0.035f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.035f)
        canvas.drawArc(drawX + size * 0.37f, drawY + size * 0.49f, drawX + size * 0.63f, drawY + size * 0.72f, 20f, 140f, false, paint)

        when (state.mood) {
            CompanionMood.PARTY -> drawSunglasses(canvas, drawX, drawY, size)
            CompanionMood.STUDY -> drawGlasses(canvas, drawX, drawY, size)
            CompanionMood.BORED -> drawSleepMarks(canvas, drawX, drawY, size)
        }
        if (state.isEating) {
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFD166.toInt()
            canvas.drawCircle(drawX + size * 0.84f, drawY + size * 0.31f, size * 0.06f, paint)
        }
        if (boundarySensor.loadingDetected && !state.isCompact) {
            drawBroom(canvas, drawX, drawY, size)
        }
        drawFullness(canvas, drawX, drawY, size)
        canvas.restore()
    }

    private fun drawSpeechBubble(canvas: Canvas, drawX: Float, drawY: Float, size: Float) {
        val maxWidth = min(width * 0.68f, 300f * metrics.density)
        val text = state.speech.take(22)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * metrics.density
        val textWidth = paint.measureText(text)
        val bubbleWidth = min(maxWidth, max(96f * metrics.density, textWidth + 24f * metrics.density))
        val left = (drawX + size / 2f - bubbleWidth / 2f).coerceIn(metrics.margin, width - bubbleWidth - metrics.margin)
        val top = (drawY - metrics.bubbleHeight - 6f * metrics.density).coerceAtLeast(metrics.margin)
        bubbleRect.set(left.toInt(), top.toInt(), (left + bubbleWidth).toInt(), (top + metrics.bubbleHeight).toInt())
        paint.style = Paint.Style.FILL
        paint.color = 0xF9FFFFFF.toInt()
        canvas.drawRoundRect(bubbleRect.left.toFloat(), bubbleRect.top.toFloat(), bubbleRect.right.toFloat(), bubbleRect.bottom.toFloat(), 16f * metrics.density, 16f * metrics.density, paint)
        paint.color = 0xFF263238.toInt()
        canvas.drawText(text, left + 12f * metrics.density, top + 21f * metrics.density, paint)
        if (state.speechBvid != null) {
            paint.color = 0xFFE85D75.toInt()
            paint.textSize = 9f * metrics.density
            canvas.drawText("点击查看", left + bubbleWidth - 48f * metrics.density, top + 21f * metrics.density, paint)
        }
    }

    private fun drawFullness(canvas: Canvas, drawX: Float, drawY: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, 2f * metrics.density)
        paint.color = 0x66FFFFFF
        canvas.drawRoundRect(drawX, drawY + size + metrics.density, drawX + size, drawY + size + 5f * metrics.density, 3f * metrics.density, 3f * metrics.density, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFD166.toInt()
        canvas.drawRoundRect(drawX, drawY + size + metrics.density, drawX + size * state.fullness, drawY + size + 5f * metrics.density, 3f * metrics.density, 3f * metrics.density, paint)
    }

    private fun drawSunglasses(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF30343B.toInt()
        canvas.drawRoundRect(x + size * 0.2f, y + size * 0.34f, x + size * 0.47f, y + size * 0.48f, size * 0.05f, size * 0.05f, paint)
        canvas.drawRoundRect(x + size * 0.53f, y + size * 0.34f, x + size * 0.8f, y + size * 0.48f, size * 0.05f, size * 0.05f, paint)
        canvas.drawRect(x + size * 0.46f, y + size * 0.38f, x + size * 0.54f, y + size * 0.42f, paint)
    }

    private fun drawGlasses(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.025f)
        paint.color = 0xFF4A5D67.toInt()
        canvas.drawOval(x + size * 0.2f, y + size * 0.33f, x + size * 0.47f, y + size * 0.5f, paint)
        canvas.drawOval(x + size * 0.53f, y + size * 0.33f, x + size * 0.8f, y + size * 0.5f, paint)
        canvas.drawLine(x + size * 0.47f, y + size * 0.4f, x + size * 0.53f, y + size * 0.4f, paint)
    }

    private fun drawSleepMarks(canvas: Canvas, x: Float, y: Float, size: Float) {
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * 0.18f
        paint.color = 0xFF425466.toInt()
        canvas.drawText("呼", x + size * 0.75f, y + size * 0.18f, paint)
        canvas.drawText("呼", x + size * 0.88f, y + size * 0.05f, paint)
    }

    private fun drawBroom(canvas: Canvas, x: Float, y: Float, size: Float) {
        val progress = ((System.nanoTime() / 12_000_000L) % 100L) / 100f
        val sweep = (progress * 2f - 1f) * size * 0.34f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size * 0.035f)
        paint.color = 0xFFB7791F.toInt()
        canvas.drawLine(x + size * 0.5f, y + size * 0.78f, x + size * 0.5f + sweep, y + size * 0.98f, paint)
        paint.color = 0xFFFFD166.toInt()
        canvas.drawLine(x + size * 0.27f + sweep, y + size * 0.98f, x + size * 0.73f + sweep, y + size * 0.98f, paint)
    }

    private fun pointInInteractiveRegion(eventX: Float, eventY: Float): Boolean {
        if (state.isCompact) {
            val compactSize = metrics.petSize * 0.72f
            val compactX = x + (metrics.petSize - compactSize) / 2f
            return eventX in compactX..(compactX + compactSize) && eventY in (y + metrics.petSize - compactSize)..(y + metrics.petSize)
        }
        return pointInPet(eventX, eventY) || pointInBubble(eventX, eventY)
    }

    private fun pointInPet(eventX: Float, eventY: Float): Boolean =
        eventX in x..(x + metrics.petSize) && eventY in y..(y + metrics.petSize + 8f * metrics.density)

    private fun pointInBubble(eventX: Float, eventY: Float): Boolean =
        !bubbleRect.isEmpty && bubbleRect.contains(eventX.toInt(), eventY.toInt())

    private fun isTalkBackActive(): Boolean =
        accessibilityManager?.isEnabled == true && accessibilityManager.isTouchExplorationEnabled

    private fun announceForAccessibility(message: String) {
        contentDescription = "Bili-Companion电子桌宠，$message"
        if (isShown) super.announceForAccessibility(message)
    }

    private fun resolveMoodLabel(mood: CompanionMood): String = when (mood) {
        CompanionMood.PARTY -> "派对模式"
        CompanionMood.STUDY -> "学习模式"
        CompanionMood.BORED -> "休息模式"
    }
}

internal object BiliCompanionOverlayController {
    private data class Attachment(
        val root: ViewGroup,
        val overlay: BiliCompanionOverlayView,
        val stateJob: Job
    )

    private val attachments = mutableMapOf<LifecycleOwner, Attachment>()

    fun attach(
        lifecycleOwner: LifecycleOwner,
        root: ViewGroup,
        onVideoClick: (String) -> Unit
    ) {
        detach(lifecycleOwner)
        val overlay = BiliCompanionOverlayView(root.context, onVideoClick)
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.bindBoundaryRoot(root)
        val stateJob = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    BiliCompanionRuntime.state.collect { state -> overlay.updateState(state) }
                }
                launch {
                    PluginManager.pluginsFlow.collect { plugins ->
                        val enabled = plugins.firstOrNull { it.plugin.id == "bili_companion" }?.enabled == true
                        overlay.setCompanionEnabled(enabled)
                    }
                }
            }
        }
        attachments[lifecycleOwner] = Attachment(root, overlay, stateJob)
    }

    fun setHostVisible(lifecycleOwner: LifecycleOwner, visible: Boolean) {
        attachments[lifecycleOwner]?.overlay?.setHostVisible(visible)
    }

    fun setFullscreen(lifecycleOwner: LifecycleOwner, fullscreen: Boolean) {
        attachments[lifecycleOwner]?.overlay?.setHostFullscreen(fullscreen)
    }

    fun detach(lifecycleOwner: LifecycleOwner) {
        val attachment = attachments.remove(lifecycleOwner) ?: return
        attachment.stateJob.cancel()
        attachment.overlay.unbindBoundaryRoot()
        attachment.root.removeView(attachment.overlay)
    }
}
