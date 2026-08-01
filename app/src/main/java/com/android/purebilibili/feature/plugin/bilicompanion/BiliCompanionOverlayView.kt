package com.android.purebilibili.feature.plugin.bilicompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import com.android.purebilibili.R
import com.android.purebilibili.core.plugin.PluginManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

internal data class CompanionViewMetrics(
    val density: Float,
    val petSize: Float,
    val margin: Float,
    val bubbleHeight: Float,
    val floorGap: Float,
    val gravity: Float,
    val jumpImpulse: Float,
    val maxWalkSpeed: Float
) {
    companion object {
        fun from(context: Context, width: Int, height: Int): CompanionViewMetrics {
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val shortSideDp = min(width, height).toFloat() / density
            val petDp = shortSideDp.coerceIn(64f, 96f)
            return CompanionViewMetrics(
                density = density,
                petSize = petDp * density,
                margin = 12f * density,
                bubbleHeight = 40f * density,
                floorGap = 10f * density,
                gravity = 1_080f * density,
                jumpImpulse = 510f * density,
                maxWalkSpeed = 132f * density
            )
        }
    }
}

private class CompanionBoundarySensor(private val density: Float) {
    private val obstacleRects = mutableListOf<Rect>()
    private val rootLocation = IntArray(2)
    var loadingDetected: Boolean = false
        private set

    fun refresh(root: View, ignored: View) {
        obstacleRects.clear()
        loadingDetected = false
        root.getLocationOnScreen(rootLocation)
        collect(root, ignored, root)
        obstacleRects.sortBy { it.top }
    }

    fun resolveFloor(
        x: Float,
        petSize: Float,
        currentTop: Float,
        fallback: Float,
        height: Float
    ): Float {
        val right = x + petSize
        val minimumReachableTop = currentTop - petSize * 0.45f
        return obstacleRects
            .asSequence()
            .filter { rect -> rect.right > x && rect.left < right }
            .map { it.top.toFloat() - petSize }
            .filter { candidate ->
                candidate >= minimumReachableTop && candidate >= density * 4f && candidate < height
            }
            .minOrNull()
            ?.coerceAtMost(fallback)
            ?: fallback
    }

    private fun collect(view: View, ignored: View, root: View) {
        if (view === ignored) return
        if (view !== root && (!view.isShown || view.alpha < 0.05f)) return

        val className = view.javaClass.simpleName
        val description = view.contentDescription?.toString().orEmpty()
        val isLoadingView = className.contains("Progress", ignoreCase = true) ||
            description.contains("加载") || description.contains("loading", ignoreCase = true)

        if (isLoadingView) loadingDetected = true

        if (view !== root && (view.isClickable || view.isFocusable || isLoadingView)) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && rect.width() >= density * 24f && rect.height() >= density * 12f) {
                rect.offset(-rootLocation[0], -rootLocation[1])
                obstacleRects += rect
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collect(view.getChildAt(index), ignored, root)
            }
        }
    }
}

internal class BiliCompanionOverlayView(
    context: Context,
    private val onVideoClick: (String) -> Unit
) : View(context) {
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private val boundarySensor = CompanionBoundarySensor(context.resources.displayMetrics.density)
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRect = RectF()
    private val spriteBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.pet_sprites)
    private val spriteHandler = Handler(Looper.getMainLooper())
    private var currentFrame = 0
    private val totalFrames = 6
    private val frameInterval = 100L
    private val updateFrameRunnable = object : Runnable {
        override fun run() {
            if (!isTalkBackActive()) {
                currentFrame = (currentFrame + 1) % totalFrames
                invalidate()
            }
            spriteHandler.postDelayed(this, frameInterval)
        }
    }
    private var rootForBoundary: View? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var metrics = CompanionViewMetrics.from(context, 1, 1)
    private var state = BiliCompanionState()
    private var enabled = false
    private var hostVisible = true
    private var hostFullscreen = false
    private var centerX = 0f
    private var centerY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNanos = 0L
    private var lastBoundaryRefreshNanos = 0L
    private var targetX = Float.NaN
    private var nextDecisionAtMs = 0L
    private var walkPhase = 0f
    private var landingImpact = 0f
    private var airborne = true
    private var dragging = false
    private var interactiveGesture = false
    private var scaleInProgress = false
    private var fingerFollowActive = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private val random = Random(0xB1C0_2026)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onLongPress(event: MotionEvent) {
            fingerFollowActive = true
            BiliCompanionRuntime.setFingerFollow(true)
            announceForAccessibility("已开启手指追随")
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            BiliCompanionRuntime.setCompact(!state.isCompact)
            announceForAccessibility(if (state.isCompact) "电子桌宠已展开" else "电子桌宠已收起")
            return true
        }

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val bvid = state.speechBvid
            if (bvid != null && pointInBubble(event.x, event.y)) {
                performClick()
                BiliCompanionRuntime.clearSpeech()
                onVideoClick(bvid)
            } else {
                performClick()
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
            val nextScale = (state.scale * detector.scaleFactor).coerceIn(0.72f, 1.6f)
            BiliCompanionRuntime.setScale(nextScale)
            clampCenter(metrics.petSize * nextScale)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaleInProgress = false
        }
    })

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setElevation(this, 6f * resources.displayMetrics.density)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        updateAccessibilityDescription()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spriteHandler.removeCallbacks(updateFrameRunnable)
        spriteHandler.post(updateFrameRunnable)
    }

    override fun onDetachedFromWindow() {
        spriteHandler.removeCallbacks(updateFrameRunnable)
        unbindBoundaryRoot()
        super.onDetachedFromWindow()
    }

    fun setCompanionEnabled(value: Boolean) {
        enabled = value
        visibility = if (value && hostVisible && state.overlayEnabled) VISIBLE else INVISIBLE
        if (value) lastFrameNanos = 0L
        invalidate()
    }

    fun setHostVisible(value: Boolean) {
        hostVisible = value
        visibility = if (value && enabled && state.overlayEnabled) VISIBLE else INVISIBLE
        if (value) lastFrameNanos = 0L
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
        unbindBoundaryRoot()
        rootForBoundary = root
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            boundarySensor.refresh(root, this)
            invalidate()
        }.also { root.viewTreeObserver.addOnGlobalLayoutListener(it) }
        scrollListener = ViewTreeObserver.OnScrollChangedListener {
            boundarySensor.refresh(root, this)
            invalidate()
        }.also { root.viewTreeObserver.addOnScrollChangedListener(it) }
        root.post { boundarySensor.refresh(root, this) }
    }

    fun unbindBoundaryRoot() {
        val root = rootForBoundary
        if (root != null && root.viewTreeObserver.isAlive) {
            layoutListener?.let(root.viewTreeObserver::removeOnGlobalLayoutListener)
            scrollListener?.let(root.viewTreeObserver::removeOnScrollChangedListener)
        }
        rootForBoundary = null
        layoutListener = null
        scrollListener = null
    }

    fun updateState(next: BiliCompanionState) {
        val oldSize = currentPetSize()
        state = next
        val newSize = currentPetSize()
        if (oldSize > 0f && newSize > 0f && abs(oldSize - newSize) > 0.5f) {
            clampCenter(newSize)
        }
        visibility = if (enabled && hostVisible && next.overlayEnabled) VISIBLE else INVISIBLE
        updateAccessibilityDescription()
        if (next.speechBvid != null) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        metrics = CompanionViewMetrics.from(context, width, height)
        val size = currentPetSize()
        if (centerX == 0f && centerY == 0f) {
            centerX = width - metrics.margin - size / 2f
            centerY = metrics.margin + size / 2f
        }
        clampCenter(size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!enabled || !hostVisible || width <= 0 || height <= 0) return

        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            0f
        } else {
            ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        }
        lastFrameNanos = now

        if (now - lastBoundaryRefreshNanos > 900_000_000L) {
            rootForBoundary?.let { boundarySensor.refresh(it, this) }
            lastBoundaryRefreshNanos = now
        }

        val talkBackActive = isTalkBackActive()
        if (!talkBackActive && !hostFullscreen && !state.isFullscreen) {
            advancePhysics(deltaSeconds)
        }
        drawCompanion(canvas)

        if (!talkBackActive && !state.isCompact) {
            postInvalidateOnAnimation()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!enabled || !hostVisible) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            interactiveGesture = pointInInteractiveRegion(event.x, event.y)
            if (!interactiveGesture) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            dragOffsetX = event.x - centerX
            dragOffsetY = event.y - centerY
            dragging = false
        }
        if (!interactiveGesture) return false
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactiveGesture) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_MOVE && !scaleInProgress && !state.isCompact) {
            val nextX = event.x - dragOffsetX
            val nextY = event.y - dragOffsetY
            val movementThreshold = if (fingerFollowActive) 0f else metrics.density * 2f
            if (abs(nextX - centerX) > movementThreshold || abs(nextY - centerY) > movementThreshold) {
                dragging = true
                centerX = nextX
                centerY = nextY
                velocityX = 0f
                velocityY = 0f
                airborne = true
                targetX = Float.NaN
                clampCenter(currentPetSize())
                invalidate()
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            fingerFollowActive = false
            BiliCompanionRuntime.setFingerFollow(false)
            parent?.requestDisallowInterceptTouchEvent(false)
            interactiveGesture = false
            dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (state.isCompact) {
            BiliCompanionRuntime.setCompact(false)
            announceForAccessibility("电子桌宠已展开")
        } else {
            announceForAccessibility(state.speech)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ImageButton"
        info.isClickable = true
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.text = contentDescription
    }

    private fun advancePhysics(deltaSeconds: Float) {
        if (dragging || state.isCompact || deltaSeconds <= 0f) return

        val size = currentPetSize()
        val fallback = (height - size - metrics.floorGap).coerceAtLeast(metrics.margin)
        val currentTop = centerY - size / 2f
        val floor = boundarySensor.resolveFloor(
            x = centerX - size / 2f,
            petSize = size,
            currentTop = currentTop,
            fallback = fallback,
            height = height.toFloat()
        )

        if (!airborne && abs(currentTop - floor) > metrics.density * 3f) {
            airborne = true
        }

        if (airborne) {
            velocityY += metrics.gravity * deltaSeconds
            centerX += velocityX * deltaSeconds
            centerY += velocityY * deltaSeconds
            val nextTop = centerY - size / 2f
            if (nextTop >= floor && velocityY >= 0f) {
                centerY = floor + size / 2f
                landingImpact = (abs(velocityY) / metrics.jumpImpulse).coerceIn(0f, 1f)
                velocityY = 0f
                airborne = false
                nextDecisionAtMs = System.currentTimeMillis() + random.nextLong(420L, 1_250L)
            }
        } else {
            if (System.currentTimeMillis() >= nextDecisionAtMs || targetX.isNaN()) {
                chooseNextAction(size)
            }
            val direction = sign(targetX - centerX)
            val acceleration = 520f * metrics.density
            velocityX = (velocityX + direction * acceleration * deltaSeconds)
                .coerceIn(-metrics.maxWalkSpeed, metrics.maxWalkSpeed)
            if (abs(targetX - centerX) < size * 0.2f) {
                velocityX *= 0.82f
            }
            centerX += velocityX * deltaSeconds
            centerY = floor + size / 2f
            walkPhase += abs(velocityX) * deltaSeconds / size * 10f
        }

        if (centerX - size / 2f <= metrics.margin || centerX + size / 2f >= width - metrics.margin) {
            centerX = centerX.coerceIn(
                metrics.margin + size / 2f,
                (width - metrics.margin - size / 2f).coerceAtLeast(metrics.margin + size / 2f)
            )
            velocityX *= -0.72f
            targetX = Float.NaN
        }
        landingImpact = (landingImpact - deltaSeconds * 3.5f).coerceAtLeast(0f)
    }

    private fun chooseNextAction(size: Float) {
        val minCenter = metrics.margin + size / 2f
        val maxCenter = (width - metrics.margin - size / 2f).coerceAtLeast(minCenter)
        targetX = minCenter + random.nextFloat() * (maxCenter - minCenter)
        nextDecisionAtMs = System.currentTimeMillis() + random.nextLong(1_400L, 4_800L)

        val jumpChance = when (state.mood) {
            CompanionMood.PARTY -> 0.42f
            CompanionMood.STUDY -> 0.18f
            CompanionMood.BORED -> 0.08f
        }
        if (random.nextFloat() < jumpChance) {
            velocityY = -metrics.jumpImpulse * (0.82f + random.nextFloat() * 0.24f)
            velocityX = sign(targetX - centerX) * metrics.maxWalkSpeed * 0.82f
            airborne = true
        }
    }

    private fun drawCompanion(canvas: Canvas) {
        val size = currentPetSize()
        val top = centerY - size / 2f
        val left = centerX - size / 2f
        val floor = boundarySensor.resolveFloor(
            x = left,
            petSize = size,
            currentTop = top,
            fallback = (height - size - metrics.floorGap).coerceAtLeast(metrics.margin),
            height = height.toFloat()
        )

        drawContactShadow(canvas, size, floor, top)
        if (!state.isCompact && state.speech.isNotBlank()) {
            drawSpeechBubble(canvas, left, top, size)
        }

        drawSprite(canvas, size, top, resolveSpriteMotion())
        if (boundarySensor.loadingDetected && !state.isCompact) drawBroom(canvas, left, top, size)
        if (!state.isCompact) drawFullness(canvas, left, top, size)
    }

    private enum class SpriteMotion {
        IDLE,
        WALK,
        RUN,
        JUMP
    }

    private fun resolveSpriteMotion(): SpriteMotion = when {
        airborne -> SpriteMotion.JUMP
        boundarySensor.loadingDetected -> SpriteMotion.RUN
        abs(velocityX) > metrics.density * 8f -> SpriteMotion.WALK
        else -> SpriteMotion.IDLE
    }

    private fun drawSprite(canvas: Canvas, size: Float, top: Float, motion: SpriteMotion) {
        val source = spriteSourceRect(motion, currentFrame)
        val destinationHeight = size
        val destinationWidth = destinationHeight * source.width().toFloat() / source.height().coerceAtLeast(1)
        val bob = if (motion == SpriteMotion.IDLE) sin(walkPhase * 0.35f) * size * 0.012f else 0f
        val stretch = (abs(velocityY) / metrics.jumpImpulse).coerceIn(0f, 1f)
        val squashY = (1f - stretch * 0.06f + landingImpact * 0.1f).coerceIn(0.9f, 1.08f)
        val squashX = (1.04f - (squashY - 1f) * 0.55f).coerceIn(0.94f, 1.1f)
        val destination = RectF(
            centerX - destinationWidth / 2f,
            top + bob,
            centerX + destinationWidth / 2f,
            top + destinationHeight + bob
        )

        canvas.save()
        canvas.scale(
            if (velocityX < -metrics.density) -1f else 1f,
            1f,
            destination.centerX(),
            destination.centerY()
        )
        canvas.scale(squashX, squashY, destination.centerX(), destination.bottom)
        canvas.drawBitmap(spriteBitmap, source, destination, spritePaint)
        canvas.restore()
    }

    private fun spriteSourceRect(motion: SpriteMotion, frame: Int): Rect {
        val bitmapWidth = spriteBitmap.width.toFloat()
        val bitmapHeight = spriteBitmap.height.toFloat()
        // animationrole.txt defines the sheet's 18x5 grid. The exported sheet also
        // contains labels and gutters, so these calibrated strips preserve full frames.
        val unitWidth = bitmapWidth / 18f
        val unitHeight = bitmapHeight / 5f
        val sequenceWidth = bitmapWidth / 14f
        val sequenceHeight = unitHeight * 1.12f
        val safeFrame = frame % totalFrames

        val (left, top, width, height) = when (motion) {
            SpriteMotion.WALK -> SpriteSlice(
                left = unitWidth * 0.4f + safeFrame * sequenceWidth,
                top = bitmapHeight * 0.49f,
                width = sequenceWidth,
                height = sequenceHeight
            )
            SpriteMotion.RUN -> SpriteSlice(
                left = bitmapWidth * 0.55f + safeFrame * sequenceWidth,
                top = bitmapHeight * 0.49f,
                width = sequenceWidth,
                height = sequenceHeight
            )
            SpriteMotion.IDLE -> SpriteSlice(
                left = unitWidth * 0.25f + (safeFrame % 3) * sequenceWidth,
                top = bitmapHeight * 0.75f,
                width = sequenceWidth,
                height = unitHeight * 1.12f
            )
            SpriteMotion.JUMP -> SpriteSlice(
                left = bitmapWidth * 0.265f + (safeFrame % 3) * bitmapWidth * 0.095f,
                top = bitmapHeight * 0.735f,
                width = bitmapWidth * 0.095f,
                height = unitHeight * 1.12f
            )
        }

        val sourceLeft = left.roundToInt().coerceIn(0, spriteBitmap.width - 1)
        val sourceTop = top.roundToInt().coerceIn(0, spriteBitmap.height - 1)
        val sourceRight = (left + width).roundToInt().coerceIn(sourceLeft + 1, spriteBitmap.width)
        val sourceBottom = (top + height).roundToInt().coerceIn(sourceTop + 1, spriteBitmap.height)
        return Rect(sourceLeft, sourceTop, sourceRight, sourceBottom)
    }

    private data class SpriteSlice(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    private fun drawContactShadow(canvas: Canvas, size: Float, floor: Float, top: Float) {
        val distance = (floor - top).coerceAtLeast(0f)
        val opacity = (0x60 - (distance / size * 0x35).toInt()).coerceIn(0x18, 0x60)
        shadowPaint.style = Paint.Style.FILL
        shadowPaint.color = (opacity shl 24) or 0x001B2430
        shadowPaint.setShadowLayer(
            7f * metrics.density,
            0f,
            2f * metrics.density,
            0x55000000
        )
        val shadowWidth = size * (0.24f - (distance / size).coerceIn(0f, 0.6f) * 0.07f)
        canvas.drawOval(
            centerX - shadowWidth,
            floor + size * 0.88f,
            centerX + shadowWidth,
            floor + size * 0.98f,
            shadowPaint
        )
        shadowPaint.clearShadowLayer()
    }

    private fun drawSpeechBubble(canvas: Canvas, left: Float, top: Float, size: Float) {
        val density = metrics.density
        val maxWidth = min(width * 0.72f, 300f * density)
        val text = state.speech.take(18)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 12f * density
        val actionWidth = if (state.speechBvid != null) 42f * density else 0f
        val bubbleWidth = min(
            maxWidth,
            max(120f * density, paint.measureText(text) + 28f * density + actionWidth)
        )
        val bubbleLeft = (centerX - bubbleWidth / 2f).coerceIn(
            metrics.margin,
            (width - bubbleWidth - metrics.margin).coerceAtLeast(metrics.margin)
        )
        val bubbleTop = (top - metrics.bubbleHeight - 9f * density).coerceAtLeast(metrics.margin)
        bubbleRect.set(
            bubbleLeft,
            bubbleTop,
            bubbleLeft + bubbleWidth,
            bubbleTop + metrics.bubbleHeight
        )

        paint.style = Paint.Style.FILL
        paint.color = 0xF7FFFFFF.toInt()
        paint.setShadowLayer(7f * density, 0f, 2f * density, 0x48000000)
        canvas.drawRoundRect(bubbleRect, 17f * density, 17f * density, paint)
        paint.clearShadowLayer()

        val tail = Path().apply {
            moveTo(centerX - 7f * density, bubbleRect.bottom - 1f * density)
            lineTo(centerX, top - 2f * density)
            lineTo(centerX + 7f * density, bubbleRect.bottom - 1f * density)
            close()
        }
        paint.color = 0xF7FFFFFF.toInt()
        canvas.drawPath(tail, paint)

        paint.color = 0xFF263238.toInt()
        paint.textSize = 12f * density
        canvas.drawText(text, bubbleRect.left + 14f * density, bubbleRect.top + 25f * density, paint)
        if (state.speechBvid != null) {
            paint.color = 0xFFE45C78.toInt()
            paint.textSize = 10f * density
            canvas.drawText("打开", bubbleRect.right - 34f * density, bubbleRect.top + 25f * density, paint)
        }
    }

    private fun drawFullness(canvas: Canvas, left: Float, top: Float, size: Float) {
        val y = top + size + metrics.density * 5f
        paint.style = Paint.Style.FILL
        paint.color = 0x3DFFFFFF
        canvas.drawRoundRect(left + size * 0.1f, y, left + size * 0.9f, y + 4f * metrics.density, 3f * metrics.density, 3f * metrics.density, paint)
        paint.color = 0xFFFFC857.toInt()
        canvas.drawRoundRect(left + size * 0.1f, y, left + size * (0.1f + 0.8f * state.fullness), y + 4f * metrics.density, 3f * metrics.density, 3f * metrics.density, paint)
    }

    private fun drawBroom(canvas: Canvas, left: Float, top: Float, size: Float) {
        val sweep = sin(walkPhase * 0.45f) * size * 0.18f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(metrics.density, size * 0.018f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF9A6A43.toInt()
        canvas.drawLine(left + size * 0.62f, top + size * 0.8f, left + size * 0.5f + sweep, top + size * 1.08f, paint)
        paint.color = 0xFFE7B65C.toInt()
        canvas.drawLine(left + size * 0.28f + sweep, top + size * 1.08f, left + size * 0.68f + sweep, top + size * 1.08f, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun pointInInteractiveRegion(eventX: Float, eventY: Float): Boolean {
        return pointInPet(eventX, eventY) || pointInBubble(eventX, eventY)
    }

    private fun pointInPet(eventX: Float, eventY: Float): Boolean {
        val size = currentPetSize()
        val left = centerX - size / 2f
        val top = centerY - size / 2f
        return eventX in left..(left + size) && eventY in top..(top + size + metrics.density * 8f)
    }

    private fun pointInBubble(eventX: Float, eventY: Float): Boolean = bubbleRect.contains(eventX, eventY)

    private fun currentPetSize(): Float {
        val compactFactor = if (state.isCompact) 0.7f else 1f
        return metrics.petSize * state.scale * compactFactor
    }

    private fun clampCenter(size: Float) {
        if (width <= 0 || height <= 0) return
        val minCenterX = metrics.margin + size / 2f
        val maxCenterX = (width - metrics.margin - size / 2f).coerceAtLeast(minCenterX)
        val minCenterY = metrics.margin + size / 2f
        val maxCenterY = (height - metrics.floorGap - size / 2f).coerceAtLeast(minCenterY)
        centerX = centerX.coerceIn(minCenterX, maxCenterX)
        centerY = centerY.coerceIn(minCenterY, maxCenterY)
    }

    private fun isTalkBackActive(): Boolean =
        accessibilityManager?.isEnabled == true && accessibilityManager.isTouchExplorationEnabled

    private fun announceForAccessibility(message: String) {
        contentDescription = "Bili-Companion电子桌宠，$message"
        if (isShown) super.announceForAccessibility(message)
    }

    private fun updateAccessibilityDescription() {
        contentDescription = "Bili-Companion电子桌宠，${resolveMoodLabel(state.mood)}，${state.speech}"
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
