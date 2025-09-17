package com.pinview.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.widget.addTextChangedListener
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.pinview.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


class PinEntryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // === Configurable tokens ===
    private var boxSize: Int = dp(56f).toInt()

    private var boxWidth: Int = boxSize   // default to square
    private var boxSpacing: Int = dp(12f).toInt()
    private var cornerRadius: Float = dp(12f)
    private var strokeWidth: Float = dp(2f)
    private var textSizeSp: Float = sp(20f)  // default
    private var bgInactive: Int = ContextCompat.getColor(context, android.R.color.transparent)
    private var bgActive: Int = ContextCompat.getColor(context, android.R.color.transparent)
    private var strokeInactive: Int = ContextCompat.getColor(context, android.R.color.darker_gray)
    private var strokeActive: Int = ContextCompat.getColor(context, android.R.color.holo_blue_dark)
    private var textColor: Int = ContextCompat.getColor(context, android.R.color.black)
    private var pinLength: Int = 4
    private val boxes = mutableListOf<TextView>()
    private val shapes = mutableListOf<Drawable>()
    private val hiddenEdit: EditText = createHiddenEdit()
    private val bgViews = mutableListOf<View>()                   // bg views to invalidate
    private var pinShape: Int = 0  // default "rounded"
    private val successAnimators = mutableListOf<Animator>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(hiddenEdit, LayoutParams(1, 1))

        // read XML attributes
        attrs?.let { loadAttrs(it) }

        buildBoxes()
        setOnClickListener { focusEdit() }
    }

    private fun loadAttrs(attrs: AttributeSet) {
        context.withStyledAttributes(attrs, R.styleable.PinEntryView) {

            boxSize = getDimensionPixelSize(R.styleable.PinEntryView_pinBoxSize, boxSize)
            boxWidth = getDimensionPixelSize(R.styleable.PinEntryView_pinBoxWidth, boxSize)
            boxSpacing = getDimensionPixelSize(R.styleable.PinEntryView_pinSpacing, boxSpacing)
            cornerRadius = getDimension(R.styleable.PinEntryView_pinCornerRadius, cornerRadius)
            strokeWidth = getDimension(R.styleable.PinEntryView_pinStrokeWidth, strokeWidth)
            textSizeSp = getDimension(R.styleable.PinEntryView_pinTextSize, textSizeSp)
            pinShape = getInt(R.styleable.PinEntryView_pinShape, pinShape)

            pinLength = getInt(R.styleable.PinEntryView_pinLength, pinLength)

            // read any explicitly provided colors first (these should "stick")
            if (hasValue(R.styleable.PinEntryView_pinBgInactive))
                bgInactive = getColor(R.styleable.PinEntryView_pinBgInactive, bgInactive)
            if (hasValue(R.styleable.PinEntryView_pinBgActive))
                bgActive   = getColor(R.styleable.PinEntryView_pinBgActive, bgActive)
            if (hasValue(R.styleable.PinEntryView_pinStrokeInactive))
                strokeInactive = getColor(R.styleable.PinEntryView_pinStrokeInactive, strokeInactive)
            if (hasValue(R.styleable.PinEntryView_pinStrokeActive))
                strokeActive   = getColor(R.styleable.PinEntryView_pinStrokeActive, strokeActive)
            if (hasValue(R.styleable.PinEntryView_pinTextColor))
                textColor = getColor(R.styleable.PinEntryView_pinTextColor, textColor)

            // ---- THEME DEFAULTS (only if not set by XML) ----
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

            // Active fill = gray by theme
            if (!hasValue(R.styleable.PinEntryView_pinBgActive)) {
                bgActive = ContextCompat.getColor(context, if (isDark) R.color.colorTransparent else R.color.colorTransparent)
            }
            // Inactive fill = transparent (you can change to a light gray if you prefer)
            if (!hasValue(R.styleable.PinEntryView_pinBgInactive)) {
                bgInactive = ContextCompat.getColor(context, if (isDark) R.color.colorTransparent else R.color.colorTransparent)
            }
            // Inactive stroke = medium gray by theme
            if (!hasValue(R.styleable.PinEntryView_pinStrokeInactive)) {
                strokeInactive = ContextCompat.getColor(context, if (isDark) R.color.colorGrayDark else R.color.colorGrayLight)
            }
            // Active stroke (and text) = white in dark, black in light
            if (!hasValue(R.styleable.PinEntryView_pinStrokeActive)) {
                strokeActive = ContextCompat.getColor(context, if (isDark) R.color.white else R.color.black)
            }
            if (!hasValue(R.styleable.PinEntryView_pinTextColor)) {
                textColor = ContextCompat.getColor(context, if (isDark) R.color.white else R.color.black)
            }

        }
    }

private fun buildBoxes() {
    removeAllViews()
    addView(hiddenEdit)
    boxes.clear(); shapes.clear(); bgViews.clear()

    repeat(pinLength) { idx ->
        val slotParams = LayoutParams(boxWidth, boxSize).also {
            if (idx > 0) (it as MarginLayoutParams).marginStart = boxSpacing
        }
        val slot = FrameLayout(context).apply { layoutParams = slotParams }

        // Background (box) — will NOT move during fall animation
        val bgShape = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(cornerRadius).build()
        ).apply {
            fillColor = ColorStateList.valueOf(bgInactive)
            strokeWidth = this@PinEntryView.strokeWidth
            strokeColor = ColorStateList.valueOf(strokeInactive)
        }

        val bgDrawable: Drawable = when (pinShape) {
            0 -> { // rounded
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, cornerRadius)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(bgInactive)
                    strokeWidth = this@PinEntryView.strokeWidth
                    strokeColor = ColorStateList.valueOf(strokeInactive)
                }
            }
            1 -> { // circle
                val r = min(boxWidth, boxSize) / 2f
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, r)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(bgInactive)
                    strokeWidth = this@PinEntryView.strokeWidth
                    strokeColor = ColorStateList.valueOf(strokeInactive)
                }
            }
            2 -> { // rectangle (square corners)
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, 0f)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(bgInactive)
                    strokeWidth = this@PinEntryView.strokeWidth
                    strokeColor = ColorStateList.valueOf(strokeInactive)
                }
            }
            3 -> { // pill (fully rounded)
                val r = boxSize / 2f
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.ROUNDED, r)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(bgInactive)
                    strokeWidth = this@PinEntryView.strokeWidth
                    strokeColor = ColorStateList.valueOf(strokeInactive)
                }
            }
            4 -> { // cut
                MaterialShapeDrawable(
                    ShapeAppearanceModel.builder()
                        .setAllCorners(CornerFamily.CUT, cornerRadius)
                        .build()
                ).apply {
                    fillColor = ColorStateList.valueOf(bgInactive)
                    strokeWidth = this@PinEntryView.strokeWidth
                    strokeColor = ColorStateList.valueOf(strokeInactive)
                }
            }
            5 -> PolygonDrawable(3, -90f, bgInactive, strokeInactive, strokeWidth)          // triangle ↑
            6 -> PolygonDrawable(4, -90f,  bgInactive, strokeInactive, strokeWidth)          // diamond ◇
            7 -> PolygonDrawable(6, 0f,   bgInactive, strokeInactive, strokeWidth)          // hexagon ⬡
            8 -> PolygonDrawable(8, 0f,   bgInactive, strokeInactive, strokeWidth)          // octagon ⯃
            else -> PolygonDrawable(6, 0f, bgInactive, strokeInactive, strokeWidth)
        }

        val bg = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = bgDrawable
        }

        // Foreground dot — this is what we’ll animate to “fall”
        val tv = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            includeFontPadding = false
            // textSizePx: if you added sp() helper, setTextSize(COMPLEX_UNIT_PX, textSizePx)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            background = null // IMPORTANT: background stays on bg view
        }

        slot.addView(bg)
        slot.addView(tv)
        slot.setOnClickListener { focusEdit() } // tap any slot -> focus

        addView(slot)
        boxes += tv
        shapes += bgDrawable
        bgViews += bg
    }
    refreshVisuals()
}

    private fun createHiddenEdit() = EditText(context).apply {
        isCursorVisible = false
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(pinLength))
        alpha = 0f

        // make sure we can get focus on tap
        isFocusable = true
        isFocusableInTouchMode = true

//        addTextChangedListener(onTextChanged = { s, _, _, _ ->
//            updateBoxesWith(s?.toString().orEmpty())
//        })
        var lastLen = 0
        addTextChangedListener(
            beforeTextChanged = { s, _, _, _ -> lastLen = s?.length ?: 0 },
            onTextChanged = { s, _, _, _ ->
                val curr = s?.length ?: 0
                // for animate dot while typing
//                val isDeleting = curr < lastLen
//                if (changedIdx >= 0) animateKeyFeedback(changedIdx, entering = !isDeleting)

                updateBoxesWith(s?.toString().orEmpty()) // this calls refreshVisuals()
            },
            afterTextChanged = { }
        )

        // when focus gained/lost, recompute highlight
        setOnFocusChangeListener { _, _ -> refreshVisuals() }

        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val current = text?.length ?: 0
                // after deleting, force refresh so highlight moves back
                updateBoxesWith(text?.toString().orEmpty())
            }
            false
        }
    }
    private fun updateBoxesWith(pin: String) {
        boxes.forEachIndexed { index, tv ->
            tv.text = if (index < pin.length) "•" else ""
        }
        refreshVisuals()
    }
    fun afterTextChanged(afterTextChanged: (String) -> Unit) {
        this.hiddenEdit.addTextChangedListener {
            afterTextChanged.invoke(it.toString())
        }
    }

    // --- tiny key feedback per box (optional but nice) ---
    private fun animateKeyFeedback(index: Int, entering: Boolean) {
        val tv = boxes.getOrNull(index) ?: return
        tv.animate().cancel()
        if (entering) {
            tv.scaleX = 0.50f
            tv.scaleY = 0.50f
            tv.alpha = 0.9f
            tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
        } else {
            tv.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80L)
                .withEndAction { tv.animate().scaleX(1f).scaleY(1f).setDuration(80L).start() }
                .start()
        }
    }
    private fun animateState(i: Int, strokeActiveOn: Boolean, fillActiveOn: Boolean = strokeActiveOn) {
        val d = shapes[i]
        val toFill   = if (fillActiveOn)   bgActive     else bgInactive
        val toStroke = if (strokeActiveOn) strokeActive else strokeInactive

        when (d) {
            is MaterialShapeDrawable -> {
                d.fillColor = ColorStateList.valueOf(bgInactive)
                d.strokeColor = ColorStateList.valueOf(toStroke)

            }
            is PolygonDrawable -> {
                d.fillColorInt = toFill
                d.strokeColorInt = toStroke
                d.invalidateSelf()
            }
        }
        bgViews[i].invalidate()
    }
    fun clearPin() {
        // stop any pending view animations
        boxes.forEach { it.animate().cancel() }

        // clear text/dots
        hiddenEdit.setText("")
        boxes.forEach { it.text = "" }

        // reset all boxes to INACTIVE colors immediately (avoid lingering active tint)
        // reset backgrounds depending on the drawable type
        shapes.forEachIndexed { i, d ->
            when (d) {
                is MaterialShapeDrawable -> {
                    d.fillColor = ColorStateList.valueOf(bgInactive)
                    d.strokeColor = ColorStateList.valueOf(strokeInactive) // <-- method, not property
                }
                is PolygonDrawable -> { // your inner class
                    d.fillColorInt = bgInactive
                    d.strokeColorInt = strokeInactive
                    d.invalidateSelf()
                }
            }
            bgViews.getOrNull(i)?.invalidate()
        }

        // ensure focus + caret at start (so first box becomes active per your logic)
        focusEdit()                 // requests focus + shows IME + (you already call refreshVisuals() there)
        hiddenEdit.setSelection(0)  // caret at first slot
        refreshVisuals()            // force one more redraw now that caret is at 0
    }
    fun getPin(): String = hiddenEdit.text?.toString().orEmpty()
    fun shakeAndClear() {
        // optional haptic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            performHapticFeedback(
                HapticFeedbackConstants.CONFIRM,
                0
            )
        } else {
            performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                0
            )
        }

        val amp = dp(8f)
        val seq = floatArrayOf(0f, amp, -amp, amp * 0.7f, -amp * 0.7f, amp * 0.4f, -amp * 0.4f, 0f)
        ObjectAnimator.ofFloat(this, "translationX", *seq).apply {
            duration = 300L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fallDots() // fall dots
                }
            })
        }.start()
    }
    private fun fallDots() {
        val filled = boxes.mapIndexedNotNull { i, tv -> if (tv.text.isNotEmpty()) i else null }
        if (filled.isEmpty()) { clearPin(); return }

        val fallDistance = dp(40f)
        val duration = 250L
        val lastIndex = filled.last()

        filled.forEachIndexed { pos, idx ->
            val tv = boxes[idx]
            tv.animate()
                .translationY(fallDistance)
                .alpha(0f)
                .setStartDelay((pos * 40L)) // stagger just the text views
                .setDuration(duration)
                .withEndAction {
                    tv.translationY = 0f
                    tv.alpha = 1f
                    tv.text = "" // clear only text
                    if (idx == lastIndex) clearPin() // fresh state after final dot falls
                }
                .start()
        }
    }
    private fun refreshVisuals() {
        val len = hiddenEdit.text?.length ?: 0

        // no active box until user taps (focus present)
        if (!hiddenEdit.hasFocus()) {
            boxes.forEachIndexed { i, _ -> animateState(i, false) }
            return
        }

        // for hold cursor
//        boxes.forEachIndexed { i, _ ->
////            val active = i == len.coerceAtMost(pinLength - 1)
//            val active = (i < len) || (i == len.coerceAtMost(pinLength - 1))
//            animateState(i, active)
//        }

        val sel = hiddenEdit.selectionEnd.coerceIn(0, pinLength - 1)
        val activeIndex = when {
            len <= 0 -> 0                      // empty but focused → first box
            sel >= len -> (len - 1).coerceAtLeast(0)   // deleting or cursor after text → last filled
            else -> sel                                  // caret inside text
        }

        boxes.forEachIndexed { i, _ ->
            val fillOn = i < len                 // filled boxes get fill
            val strokeOn = i == activeIndex      // ONLY one box gets active stroke
            animateState(i, strokeOn, fillOn)
        }
    }
    private fun focusEdit() {
        hiddenEdit.requestFocus()
        hiddenEdit.post {
            hiddenEdit.setSelection(hiddenEdit.text?.length ?: 0)

            // Show soft keyboard
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(hiddenEdit, InputMethodManager.SHOW_IMPLICIT)

            refreshVisuals()
        }
    }
    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private class PolygonDrawable(
        private val sides: Int,
        private val rotationDeg: Float,
        fill: Int,
        stroke: Int,
        private val strokeWidthPx: Float
    ) : Drawable() {
        private val path = Path()
        private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        var fillColorInt: Int = fill
            set(v) { field = v; invalidateSelf() }
        var strokeColorInt: Int = stroke
            set(v) { field = v; invalidateSelf() }

        init {
            paintFill.color = fillColorInt
            paintStroke.color = strokeColorInt
            paintStroke.strokeWidth = strokeWidthPx
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            buildPath(bounds)
        }
        private fun buildPath(b: Rect) {
            path.reset()
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val r  = (minOf(b.width(), b.height()) / 2f) - (strokeWidthPx / 2f)
            val step = (2 * Math.PI / sides)
            val start = Math.toRadians(rotationDeg.toDouble())
            for (i in 0 until sides) {
                val ang = start + i * step
                val x = cx + (r * cos(ang)).toFloat()
                val y = cy + (r * sin(ang)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }

        override fun draw(canvas: Canvas) {
            paintFill.color = fillColorInt
            paintStroke.color = strokeColorInt
            canvas.drawPath(path, paintFill)
            if (strokeWidthPx > 0f) canvas.drawPath(path, paintStroke)
        }

        override fun setAlpha(alpha: Int) {
            paintFill.alpha = alpha
            paintStroke.alpha = alpha
        }
        override fun getAlpha(): Int = paintFill.alpha

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paintFill.colorFilter = colorFilter
            paintStroke.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun startSuccessLoop() {
        stopSuccessLoop() // clean slate

        val len = (hiddenEdit.text?.length ?: 0).coerceAtMost(pinLength)
        if (len <= 0) return

        // loop a soft wave across filled dots only
        repeat(len) { i ->
            val tv = boxes[i]

            val sx = ObjectAnimator.ofFloat(tv, SCALE_X, 1f, 1.35f, 1f).apply {
                duration = 360L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = i * 80L // phase shift = wave
            }
            val sy = ObjectAnimator.ofFloat(tv, SCALE_Y, 1f, 1.35f, 1f).apply {
                duration = 360L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = i * 80L
            }
            val a = ObjectAnimator.ofFloat(tv, ALPHA, 1f, 0.9f, 1f).apply {
                duration = 360L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = i * 80L
            }

            // store and start
            successAnimators += sx; successAnimators += sy; successAnimators += a
            sx.start(); sy.start(); a.start()
        }
    }
    fun stopSuccessLoop() {
        successAnimators.forEach { it.cancel() }
        successAnimators.clear()
        // restore baseline so nothing looks “stuck”
        boxes.forEach {
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha  = 1f
        }
    }

}

// --- Declare attributes directly here (no attrs.xml needed) ---
//private object R {
//    object Styleable {
//        val PinEntryView = intArrayOf(
//            android.R.attr.layout_width // dummy
//        )
//        const val PinEntryView_pinBoxSize = 0
//        const val PinEntryView_pinSpacing = 1
//        const val PinEntryView_pinCornerRadius = 2
//        const val PinEntryView_pinStrokeWidth = 3
//        const val PinEntryView_pinBgInactive = 4
//        const val PinEntryView_pinBgActive = 5
//        const val PinEntryView_pinStrokeInactive = 6
//        const val PinEntryView_pinStrokeActive = 7
//        const val PinEntryView_pinTextColor = 8
//        const val PinEntryView_pinLength = 9
//        const val PinEntryView_pinShape = 10
//    }
//}
