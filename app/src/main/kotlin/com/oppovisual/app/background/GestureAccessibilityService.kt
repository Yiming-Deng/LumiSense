package com.oppovisual.app.background

import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.oppovisual.app.recognition.ConfirmedGestureEvent
import com.oppovisual.app.recognition.ScaleEventParameter
import com.oppovisual.core.GestureId
import com.oppovisual.core.ProductInteractionStatus
import com.oppovisual.core.ProductScaleStatus
import kotlin.math.abs
import kotlin.math.ln

class GestureAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDispatchMs = Long.MIN_VALUE
    private var overlayText: android.widget.TextView? = null
    private var overlayDetail: android.widget.TextView? = null
    private var overlayMark: android.widget.TextView? = null
    private var overlayRoot: LinearLayout? = null
    private var overlayWindowManager: WindowManager? = null
    private var readyMessage: String? = null
    private var eventMessage: String? = null
    private var lastStatusLog: String? = null
    private var lastOverlayMode: String? = null
    private var statusAnimator: ValueAnimator? = null
    private var scaleValueAnimator: ValueAnimator? = null
    private var liveScaleFactor: Float? = null
    private var liveScaleRateRadiusPerMs = 0f
    private var displayedScaleFactor = 1f
    private var liveScaleStatus = ProductScaleStatus.IDLE
    private val statusLock = Any()
    private var pendingStatus: PendingStatus? = null
    private var statusRunnablePosted = false
    private data class LivePinchSession(
        var currentRadius: Float,
        var rateRadiusPerMs: Float = 0f,
        var inFlight: Boolean = false,
        var strokeTargetRadius: Float = 0f,
        var strokeWillContinue: Boolean = true,
        var finalEvent: ConfirmedGestureEvent? = null,
        var closing: Boolean = false,
        var firstStroke: GestureDescription.StrokeDescription? = null,
        var secondStroke: GestureDescription.StrokeDescription? = null,
    )

    private var livePinchSession: LivePinchSession? = null
    private val applyPendingStatusRunnable = Runnable {
        val status = synchronized(statusLock) {
            statusRunnablePosted = false
            pendingStatus.also { pendingStatus = null }
        }
        status?.let { updateStatusNow(it.interactionStatus, it.scaleStatus) }
    }
    private val clearEventRunnable = Runnable {
        eventMessage = null
        renderOverlay()
    }

    private data class PendingStatus(
        val interactionStatus: ProductInteractionStatus,
        val scaleStatus: ProductScaleStatus,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BackgroundGestureControl.setAccessibilityConnected(true)
        Log.i(TAG, "accessibility_connected=true")
    }

    override fun onDestroy() {
        clearOverlay()
        if (instance === this) instance = null
        BackgroundGestureControl.setAccessibilityConnected(false)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun updateStatus(
        interactionStatus: ProductInteractionStatus,
        scaleStatus: ProductScaleStatus,
    ) {
        synchronized(statusLock) {
            pendingStatus = PendingStatus(interactionStatus, scaleStatus)
            if (statusRunnablePosted) return
            statusRunnablePosted = true
        }
        mainHandler.post(applyPendingStatusRunnable)
    }

    private fun updateStatusNow(
        interactionStatus: ProductInteractionStatus,
        scaleStatus: ProductScaleStatus,
    ) {
        // A terminal pair event can be rejected by a foreground safety guard;
        // still clear the visual state so the overlay cannot get stuck.
        if (scaleStatus == ProductScaleStatus.IDLE && liveScaleFactor != null) {
            clearLiveScaleFeedback()
        }
        val statusKey = "$interactionStatus/$scaleStatus"
        if (statusKey != lastStatusLog) {
            lastStatusLog = statusKey
            Log.i(TAG, "status=$statusKey")
        }
        readyMessage = when {
            scaleStatus == ProductScaleStatus.READY -> "双手就绪"
            interactionStatus == ProductInteractionStatus.READY -> "手势就绪"
            else -> null
        }
        renderOverlay()
    }

    private fun showEvent(event: ConfirmedGestureEvent) {
        mainHandler.post { showEventNow(event) }
    }

    private fun showEventNow(event: ConfirmedGestureEvent) {
        // Pair scale is already injected continuously. Its terminal event only
        // releases the active pointers and must not create a second feedback beat.
        if (event.gesture == GestureId.TWO_HAND_ZOOM) return
        eventMessage = overlayEventLabel(event.gesture)
        renderOverlay()
        mainHandler.removeCallbacks(clearEventRunnable)
        mainHandler.postDelayed(clearEventRunnable, EVENT_DISPLAY_MS)
    }

    private fun renderOverlay() {
        val isLiveScale = liveScaleFactor != null && liveScaleStatus in setOf(
            ProductScaleStatus.ADJUSTING,
            ProductScaleStatus.PAUSED,
        )
        val message = if (isLiveScale) {
            scaleTitle(displayedScaleFactor, liveScaleStatus)
        } else {
            eventMessage ?: readyMessage
        }
        if (message == null) {
            hideOverlay()
            return
        }
        val isEvent = eventMessage != null
        val mode = when {
            isLiveScale -> "scale:${scaleDirection(displayedScaleFactor)}:$liveScaleStatus"
            isEvent -> "event:$message"
            else -> "ready:$message"
        }
        val root = overlayRoot ?: createOverlay().also { overlayRoot = it }
        val textView = requireNotNull(overlayText)
        val detailView = requireNotNull(overlayDetail)
        val mark = requireNotNull(overlayMark)
        mark.text = "•"
        textView.text = message
        detailView.visibility = View.GONE
        if (isLiveScale) {
            mark.text = scaleMark(displayedScaleFactor)
        }
        root.visibility = View.VISIBLE
        if (root.parent == null) {
            runCatching {
                overlayWindowManager?.addView(root, overlayLayoutParams())
            }
        }
        if (mode != lastOverlayMode) {
            lastOverlayMode = mode
            animateOverlay(root, mark, isEvent, isLiveScale)
        }
    }

    private fun createOverlay(): LinearLayout {
        overlayWindowManager = getSystemService(WindowManager::class.java)
        val mark = android.widget.TextView(this).apply {
            setTextColor(Color.rgb(102, 216, 198))
            textSize = OVERLAY_MARK_SIZE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val text = android.widget.TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = OVERLAY_TEXT_SIZE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        val detail = android.widget.TextView(this).apply {
            setTextColor(Color.rgb(159, 244, 223))
            textSize = OVERLAY_DETAIL_SIZE_SP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            visibility = View.GONE
        }
        overlayMark = mark
        overlayText = text
        overlayDetail = detail
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 16, 20, 24))
                setStroke(dp(1), Color.argb(120, 102, 216, 198))
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(4).toFloat()
            addView(mark, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(text, LinearLayout.LayoutParams(
                dp(72),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) })
            addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) })
        }
    }

    private fun scaleDirection(factor: Float): Int = when {
        liveScaleRateRadiusPerMs > 0f -> 1
        liveScaleRateRadiusPerMs < 0f -> -1
        factor > SCALE_NEUTRAL_EPSILON -> 1
        factor < 1f / SCALE_NEUTRAL_EPSILON -> -1
        else -> 0
    }

    private fun scaleTitle(factor: Float, status: ProductScaleStatus): String = when (status) {
        ProductScaleStatus.PAUSED -> "\u4fdd\u6301\u500d\u7387"
        else -> when (scaleDirection(factor)) {
            1 -> "\u6b63\u5728\u653e\u5927"
            -1 -> "\u6b63\u5728\u7f29\u5c0f"
            else -> "\u53cc\u624b\u653e\u7f29"
        }
    }

    private fun scaleDetail(factor: Float, status: ProductScaleStatus): String {
        if (status == ProductScaleStatus.PAUSED) return "\u53cc\u624b\u4fdd\u6301\u5373\u53ef\u7ee7\u7eed"
        val speed = when {
            abs(liveScaleRateRadiusPerMs) < livePinchStopRate() -> "\u56de\u5230\u8d77\u70b9\u5373\u53ef\u6682\u505c"
            abs(liveScaleRateRadiusPerMs) < livePinchMaxRate() * 0.42f -> "\u7f13\u6162"
            abs(liveScaleRateRadiusPerMs) < livePinchMaxRate() * 0.76f -> "\u5e73\u7a33"
            else -> "\u52a0\u901f"
        }
        return when (scaleDirection(factor)) {
            1 -> "\u53cc\u624b\u5411\u5916\u62c9\u5f00 · $speed"
            -1 -> "\u53cc\u624b\u5411\u5185\u9760\u62e2 · $speed"
            else -> speed
        }
    }

    private fun scaleMark(factor: Float): String = when (scaleDirection(factor)) {
        1 -> "+"
        -1 -> "-"
        else -> "\u2194"
    }

    private fun overlayEventLabel(gesture: GestureId): String = when (gesture) {
        GestureId.SWIPE_LEFT -> "\u5de6\u6ed1\u89e6\u53d1"
        GestureId.SWIPE_RIGHT -> "\u53f3\u6ed1\u89e6\u53d1"
        GestureId.SWIPE_UP -> "\u4e0a\u6ed1\u89e6\u53d1"
        GestureId.SWIPE_DOWN -> "\u4e0b\u6ed1\u89e6\u53d1"
        GestureId.ZOOM_IN -> "\u653e\u5927\u89e6\u53d1"
        GestureId.ZOOM_OUT -> "\u7f29\u5c0f\u89e6\u53d1"
        else -> "\u624b\u52bf\u89e6\u53d1"
    }

    private fun eventMark(message: String): String = when {
        message.contains(GestureId.SWIPE_LEFT.displayName) -> "←"
        message.contains(GestureId.SWIPE_RIGHT.displayName) -> "→"
        message.contains(GestureId.SWIPE_UP.displayName) -> "↑"
        message.contains(GestureId.SWIPE_DOWN.displayName) -> "↓"
        message.contains("放大") -> "＋"
        message.contains("缩小") -> "−"
        else -> "✓"
    }

    private fun animateOverlay(root: View, mark: View, event: Boolean, liveScale: Boolean) {
        statusAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
            mark.alpha = 1f
            mark.scaleX = 1f
            mark.scaleY = 1f
            return
        }
        root.animate().cancel()
        root.alpha = 0f
        root.scaleX = if (event) 0.92f else 0.96f
        root.scaleY = root.scaleX
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(if (event) 160L else 220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        if (!event && !liveScale) {
            statusAnimator = ValueAnimator.ofFloat(0.82f, 1.12f).apply {
                duration = 1_200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val value = it.animatedValue as Float
                    mark.scaleX = value
                    mark.scaleY = value
                    mark.alpha = 0.65f + (value - 0.82f) * 0.9f
                }
                start()
            }
        } else if (event) {
            mark.scaleX = 0.7f
            mark.scaleY = 0.7f
            mark.animate().scaleX(1f).scaleY(1f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            mark.alpha = 1f
            mark.scaleX = 1f
            mark.scaleY = 1f
        }
    }

    private fun overlayLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(88)
    }

    private fun hideOverlay() {
        statusAnimator?.cancel()
        statusAnimator = null
        lastOverlayMode = null
        overlayRoot?.let { root ->
            root.animate().cancel()
            if (root.parent != null) {
                runCatching { overlayWindowManager?.removeView(root) }
            }
            root.visibility = View.GONE
        }
    }

    private fun clearOverlay() {
        mainHandler.removeCallbacks(clearEventRunnable)
        scaleValueAnimator?.cancel()
        scaleValueAnimator = null
        liveScaleFactor = null
        liveScaleStatus = ProductScaleStatus.IDLE
        readyMessage = null
        eventMessage = null
        hideOverlay()
        overlayText = null
        overlayDetail = null
        overlayMark = null
        overlayRoot = null
        overlayWindowManager = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dispatch(event: ConfirmedGestureEvent) {
        val enqueuedAtMs = android.os.SystemClock.uptimeMillis()
        mainHandler.postAtFrontOfQueue { dispatchNow(event, enqueuedAtMs) }
    }

    private fun dispatchAndUpdate(
        event: ConfirmedGestureEvent,
        interactionStatus: ProductInteractionStatus,
        scaleStatus: ProductScaleStatus,
    ) {
        val enqueuedAtMs = android.os.SystemClock.uptimeMillis()
        mainHandler.postAtFrontOfQueue {
            mainHandler.removeCallbacks(applyPendingStatusRunnable)
            synchronized(statusLock) {
                pendingStatus = null
                statusRunnablePosted = false
            }
            // Inject first so overlay rendering cannot delay the system command.
            dispatchNow(event, enqueuedAtMs)
            updateStatusNow(interactionStatus, scaleStatus)
        }
    }

    private fun dispatchNow(event: ConfirmedGestureEvent, enqueuedAtMs: Long) {
        val packageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        val now = android.os.SystemClock.uptimeMillis()
        val operation = backgroundOperationFor(event.gesture) ?: run {
            Log.w(TAG, "event=${event.gesture.name} has_no_background_mapping")
            return
        }
        if (!canInjectNow()) {
            Log.w(TAG, "event=${event.gesture.name} blocked package=$packageName operation=$operation")
            return
        }
        if (operation != BackgroundGestureOperation.TWO_HAND_PINCH &&
            lastDispatchMs != Long.MIN_VALUE && now - lastDispatchMs < GLOBAL_DISPATCH_GAP_MS
        ) {
            Log.w(TAG, "event=${event.gesture.name} blocked cooldown")
            return
        }
        if (operation == BackgroundGestureOperation.TWO_HAND_PINCH) {
            val factor = event.scaleParameter?.scaleFactor ?: return
            val accepted = finishLivePinch(event)
            // The terminal decoder event only ends the continuous pinch. Clear
            // its live indicator here so it cannot remain visible until a later
            // camera frame happens to arrive.
            clearLiveScaleFeedback()
            Log.i(
                TAG,
                "event=${event.gesture.name} operation=$operation final_live_scale=$factor " +
                    "accepted=$accepted queue_ms=${(now - enqueuedAtMs).coerceAtLeast(0L)} " +
                    "confirmed_to_dispatch_ms=${(now - event.confirmedTimestampMs).coerceAtLeast(0L)}",
            )
            if (accepted) {
                lastDispatchMs = now
            }
            return
        }
        val description = when (operation) {
            BackgroundGestureOperation.SWIPE_LEFT -> swipe(horizontal = true, positive = false, systemUi = packageName == "com.android.systemui")
            BackgroundGestureOperation.SWIPE_RIGHT -> swipe(horizontal = true, positive = true, systemUi = packageName == "com.android.systemui")
            BackgroundGestureOperation.SWIPE_UP -> swipe(horizontal = false, positive = false, systemUi = packageName == "com.android.systemui")
            BackgroundGestureOperation.SWIPE_DOWN -> swipe(horizontal = false, positive = true, systemUi = packageName == "com.android.systemui")
            BackgroundGestureOperation.ZOOM_IN -> pinch(
                outward = true,
                factor = event.scaleParameter?.scaleFactor ?: 1.55f,
            )
            BackgroundGestureOperation.ZOOM_OUT -> {
                val factor = event.scaleParameter?.scaleFactor ?: (1f / 1.55f)
                pinch(outward = false, factor = factor)
            }
            BackgroundGestureOperation.TWO_HAND_PINCH -> {
                val factor = event.scaleParameter?.scaleFactor ?: return
                pinch(outward = factor >= 1f, factor = factor)
            }
        }
        val accepted = dispatchGesture(description, null, null)
        Log.i(
            TAG,
            "event=${event.gesture.name} operation=$operation dispatch_accepted=$accepted " +
                "queue_ms=${(now - enqueuedAtMs).coerceAtLeast(0L)} " +
                "confirmed_to_dispatch_ms=${(now - event.confirmedTimestampMs).coerceAtLeast(0L)}",
        )
        if (accepted) {
            lastDispatchMs = now
            BackgroundGestureControl.reportGesture(event.gesture)
            showEventNow(event)
        }
    }

    /** Keep one continuous two-finger accessibility stroke alive while scaling. */
    private fun updateLiveScaleNow(parameter: ScaleEventParameter?, status: ProductScaleStatus) {
        if (status == ProductScaleStatus.IDLE) {
            clearLiveScaleFeedback()
            closeLivePinch()
            return
        }
        val factor = parameter?.scaleFactor ?: return
        val wasPaused = liveScaleStatus == ProductScaleStatus.PAUSED
        if (status == ProductScaleStatus.ADJUSTING) {
            if (wasPaused) rebaseLivePinch() else updateLivePinchRate(factor)
            liveScaleRateRadiusPerMs = livePinchSession?.rateRadiusPerMs ?: 0f
        } else if (status == ProductScaleStatus.PAUSED) {
            // A missing or released hand must freeze the pinch immediately;
            // retaining its old rate would keep changing the foreground app.
            livePinchSession?.rateRadiusPerMs = 0f
            liveScaleRateRadiusPerMs = 0f
        }
        updateLiveScaleFeedback(factor, status)
    }

    private fun updateLiveScaleFeedback(factor: Float, status: ProductScaleStatus) {
        val target = factor.coerceIn(0.1f, 10f)
        liveScaleFactor = target
        liveScaleStatus = status
        val start = displayedScaleFactor
        scaleValueAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            displayedScaleFactor = target
            renderOverlay()
            return
        }
        scaleValueAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = LIVE_SCALE_FEEDBACK_DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                displayedScaleFactor = it.animatedValue as Float
                renderOverlay()
            }
            start()
        }
    }

    private fun clearLiveScaleFeedback() {
        scaleValueAnimator?.cancel()
        scaleValueAnimator = null
        liveScaleFactor = null
        liveScaleRateRadiusPerMs = 0f
        displayedScaleFactor = 1f
        liveScaleStatus = ProductScaleStatus.IDLE
        renderOverlay()
    }

    /**
     * Interpret pair separation as a signed pinch speed, not a destination.
     * Returning the hands to their initial separation therefore stops zooming
     * instead of leaving a virtual pointer distance that still has to catch up.
     */
    private fun updateLivePinchRate(targetFactor: Float) {
        val requestedRate = livePinchRateFor(targetFactor)
        val session = livePinchSession
        if (session == null) {
            val neutralRadius = radiusForScale(1f)
            if (requestedRate == 0f) return
            val created = LivePinchSession(
                currentRadius = neutralRadius,
                rateRadiusPerMs = requestedRate,
            )
            livePinchSession = created
            dispatchLivePinchSegment(created, willContinue = true)
            return
        }
        val directionChanged = requestedRate * session.rateRadiusPerMs < 0f
        val alpha = if (directionChanged) LIVE_PINCH_REVERSE_RATE_ALPHA else LIVE_PINCH_RATE_ALPHA
        session.rateRadiusPerMs += (requestedRate - session.rateRadiusPerMs) * alpha
        if (abs(session.rateRadiusPerMs) < livePinchStopRate()) {
            session.rateRadiusPerMs = 0f
        }
        if (!session.inFlight && !session.closing) {
            dispatchLivePinchSegment(session, willContinue = true)
        }
    }

    private fun rebaseLivePinch() {
        val session = livePinchSession ?: return
        session.rateRadiusPerMs = 0f
    }

    private fun finishLivePinch(event: ConfirmedGestureEvent): Boolean {
        val session = livePinchSession ?: run {
            // The final event is bookkeeping only. Do not synthesize a new
            // discrete pinch if no continuous session was active.
            BackgroundGestureControl.reportGesture(event.gesture)
            return true
        }
        // Freeze at the last accepted segment. Releasing must not add a final
        // scale jump after the user has already stopped moving their hands.
        session.rateRadiusPerMs = 0f
        session.finalEvent = event
        session.closing = true
        if (!session.inFlight) dispatchLivePinchSegment(session, willContinue = false)
        return true
    }

    private fun closeLivePinch() {
        val session = livePinchSession ?: return
        session.rateRadiusPerMs = 0f
        session.closing = true
        session.finalEvent = null
        if (!session.inFlight) dispatchLivePinchSegment(session, willContinue = false)
    }

    private fun dispatchLivePinchSegment(session: LivePinchSession, willContinue: Boolean) {
        if (session.inFlight) return
        if (!canInjectNow()) {
            livePinchSession = null
            return
        }
        val from = session.currentRadius
        // Start with a longer segment so Android sees a genuine pinch without
        // adding an artificial first-frame distance jump.
        val durationMs = if (session.firstStroke == null && willContinue) {
            LIVE_PINCH_INITIAL_SEGMENT_MS
        } else {
            LIVE_PINCH_SEGMENT_MS
        }
        val requestedTo = if (willContinue) {
            from + session.rateRadiusPerMs * durationMs
        } else {
            from
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val maxStep = width * LIVE_PINCH_MAX_RATE_RATIO_PER_SECOND *
            durationMs / 1_000f
        val steppedTarget = (from + (requestedTo - from).coerceIn(-maxStep, maxStep))
            .coerceIn(width * 0.035f, width * 0.28f)
        val to = steppedTarget
        val height = resources.displayMetrics.heightPixels.toFloat()
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val firstPath = Path().apply {
            moveTo(centerX - from, centerY)
            lineTo(centerX - to, centerY)
        }
        val secondPath = Path().apply {
            moveTo(centerX + from, centerY)
            lineTo(centerX + to, centerY)
        }
        val first = session.firstStroke?.continueStroke(
            firstPath,
            0L,
            durationMs,
            willContinue,
        ) ?: GestureDescription.StrokeDescription(
            firstPath,
            0L,
            durationMs,
            willContinue,
        )
        val second = session.secondStroke?.continueStroke(
            secondPath,
            0L,
            durationMs,
            willContinue,
        ) ?: GestureDescription.StrokeDescription(
            secondPath,
            0L,
            durationMs,
            willContinue,
        )
        session.inFlight = true
        session.strokeTargetRadius = to
        session.strokeWillContinue = willContinue
        session.firstStroke = first
        session.secondStroke = second
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(first).addStroke(second).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onLivePinchSegmentCompleted(session)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onLivePinchSegmentCancelled(session)
                }
            },
            null,
        )
        if (!accepted) {
            session.inFlight = false
            mainHandler.postDelayed({
                if (livePinchSession === session && !session.inFlight) {
                    dispatchLivePinchSegment(session, session.closing.not())
                }
            }, LIVE_PINCH_RETRY_MS)
        }
    }

    private fun onLivePinchSegmentCompleted(session: LivePinchSession) {
        if (livePinchSession !== session) return
        session.inFlight = false
        session.currentRadius = session.strokeTargetRadius
        if (session.strokeWillContinue) {
            dispatchLivePinchSegment(session, willContinue = !session.closing)
            return
        }
        val event = session.finalEvent
        livePinchSession = null
        if (event != null) {
            BackgroundGestureControl.reportGesture(event.gesture)
            showEventNow(event)
        }
    }

    private fun onLivePinchSegmentCancelled(session: LivePinchSession) {
        if (livePinchSession !== session) return
        session.inFlight = false
        val event = session.finalEvent
        livePinchSession = null
        if (event != null) {
            Log.w(TAG, "live_pair_scale cancelled before final release")
        }
    }

    private fun radiusForScale(scaleFactor: Float): Float {
        val width = resources.displayMetrics.widthPixels.toFloat()
        // A real pinch changes the distance between two held pointers. Keep a
        // fixed neutral radius and apply the pair-distance ratio linearly;
        // logarithmic remapping makes the fingers appear to jump near neutral.
        return (width * 0.10f * scaleFactor.coerceIn(0.1f, 10f))
            .coerceIn(width * 0.035f, width * 0.28f)
    }

    private fun livePinchRateFor(factor: Float): Float {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val offset = factor.coerceIn(0.1f, 10f) - 1f
        val magnitude = (abs(offset) - LIVE_PINCH_RATE_DEAD_ZONE).coerceAtLeast(0f)
        if (magnitude == 0f) return 0f
        val normalized = (magnitude / LIVE_PINCH_FULL_RATE_OFFSET).coerceIn(0f, 1f)
        // Fine control near neutral and a gentle climb towards the capped rate.
        val eased = normalized * (0.35f + 0.65f * normalized)
        val sign = if (offset < 0f) -1f else 1f
        return sign * width * LIVE_PINCH_MAX_RATE_RATIO_PER_SECOND * eased / 1_000f
    }

    private fun livePinchStopRate(): Float =
        resources.displayMetrics.widthPixels * LIVE_PINCH_STOP_RATE_RATIO_PER_SECOND / 1_000f

    private fun livePinchMaxRate(): Float =
        resources.displayMetrics.widthPixels * LIVE_PINCH_MAX_RATE_RATIO_PER_SECOND / 1_000f

    private fun canInjectNow(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return false
        val packageName = rootInActiveWindow?.packageName?.toString().orEmpty()
        // Background control is global: every foreground app and SystemUI can
        // receive the mapped gesture. Keep only the self, lock-screen, and
        // password-field guards so the controller cannot operate its own UI or
        // a sensitive text surface.
        if (packageName == applicationContext.packageName) return false
        return rootInActiveWindow?.containsPasswordField() != true
    }

    private fun swipe(
        horizontal: Boolean,
        positive: Boolean,
        systemUi: Boolean = false,
    ): GestureDescription {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val centerX = width * 0.5f
        // The notification shade reserves its lower edge for the gesture
        // navigation area. Start and finish farther apart there so an upward
        // swipe actually scrolls the shade instead of becoming a tap.
        val centerY = if (systemUi) height * 0.52f else height * 0.52f
        val span = when {
            horizontal -> width * if (systemUi) 0.58f else 0.48f
            systemUi -> height * 0.64f
            else -> height * 0.42f
        }
        val duration = if (systemUi) SYSTEM_UI_SWIPE_DURATION_MS else SWIPE_DURATION_MS
        val startX = if (horizontal) centerX - if (positive) span / 2f else -span / 2f else centerX
        val endX = if (horizontal) centerX + if (positive) span / 2f else -span / 2f else centerX
        val startY = if (horizontal) centerY else if (systemUi) {
            if (positive) height * 0.22f else height * 0.86f
        } else {
            centerY - if (positive) span / 2f else -span / 2f
        }
        val endY = if (horizontal) centerY else if (systemUi) {
            if (positive) height * 0.86f else height * 0.22f
        } else {
            centerY + if (positive) span / 2f else -span / 2f
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
    }

    private fun pinch(
        outward: Boolean,
        factor: Float,
        durationMs: Long = PINCH_DURATION_MS,
    ): GestureDescription {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        // A very small pinch is frequently interpreted as two taps by launchers
        // and media apps. Use a comfortable two-finger span and a longer stroke
        // so the injected gesture is recognized as a pinch consistently.
        val magnitude = (0.14f + abs(ln(factor.coerceIn(0.1f, 10f))) * 0.12f)
            .coerceIn(0.18f, 0.30f)
        val innerRadius = width * 0.105f
        val outerRadius = width * magnitude
        val startRadius = if (outward) innerRadius else outerRadius
        val endRadius = if (outward) outerRadius else innerRadius
        val first = Path().apply {
            moveTo(centerX - startRadius, centerY)
            lineTo(centerX - endRadius, centerY)
        }
        val second = Path().apply {
            moveTo(centerX + startRadius, centerY)
            lineTo(centerX + endRadius, centerY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(second, 0, durationMs))
            .build()
    }

    private fun AccessibilityNodeInfo.containsPasswordField(): Boolean {
        if (isPassword) return true
        for (index in 0 until childCount) {
            val child = getChild(index) ?: continue
            try {
                if (child.containsPasswordField()) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    companion object {
        private const val GLOBAL_DISPATCH_GAP_MS = 500L
        private const val EVENT_DISPLAY_MS = 900L
        private const val OVERLAY_TEXT_SIZE_SP = 15f
        private const val OVERLAY_DETAIL_SIZE_SP = 13f
        private const val OVERLAY_MARK_SIZE_SP = 20f
        // Keep regular system gestures long enough for TikTok-style targets to
        // recognize them, while live pair updates use the shorter duration below.
        private const val SWIPE_DURATION_MS = 180L
        private const val PINCH_DURATION_MS = 320L
        private const val LIVE_PINCH_SEGMENT_MS = 36L
        private const val LIVE_PINCH_INITIAL_SEGMENT_MS = 96L
        private const val LIVE_PINCH_RETRY_MS = 12L
        private const val SYSTEM_UI_SWIPE_DURATION_MS = 300L
        private const val LIVE_SCALE_FEEDBACK_DURATION_MS = 120L
        private const val LIVE_PINCH_RATE_DEAD_ZONE = 0.06f
        private const val LIVE_PINCH_FULL_RATE_OFFSET = 0.65f
        private const val LIVE_PINCH_MAX_RATE_RATIO_PER_SECOND = 0.08f
        private const val LIVE_PINCH_STOP_RATE_RATIO_PER_SECOND = 0.006f
        private const val LIVE_PINCH_RATE_ALPHA = 0.24f
        private const val LIVE_PINCH_REVERSE_RATE_ALPHA = 0.46f
        private const val SCALE_NEUTRAL_EPSILON = 1.015f
        private const val TAG = "GestureAccessibility"

        @Volatile
        private var instance: GestureAccessibilityService? = null

        fun dispatch(event: ConfirmedGestureEvent): Boolean {
            val service = instance ?: return false
            service.dispatch(event)
            return true
        }

        fun dispatchAndUpdate(
            event: ConfirmedGestureEvent,
            interactionStatus: ProductInteractionStatus,
            scaleStatus: ProductScaleStatus,
        ): Boolean {
            val service = instance ?: return false
            service.dispatchAndUpdate(event, interactionStatus, scaleStatus)
            return true
        }

        fun updateInteractionStatus(
            interactionStatus: ProductInteractionStatus,
            scaleStatus: ProductScaleStatus,
        ) {
            instance?.updateStatus(interactionStatus, scaleStatus)
        }

        fun updateLiveScale(
            parameter: ScaleEventParameter?,
            scaleStatus: ProductScaleStatus,
        ) {
            instance?.mainHandler?.postAtFrontOfQueue {
                instance?.updateLiveScaleNow(parameter, scaleStatus)
            }
        }

        fun clearStatusOverlay() {
            instance?.mainHandler?.post { instance?.clearOverlay() }
        }
    }
}
