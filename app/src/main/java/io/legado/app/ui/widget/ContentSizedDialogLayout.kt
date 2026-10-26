package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/** Measures import dialog sections so the list cannot consume the action footer. */
class ContentSizedDialogLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount < 3) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val toolbar = getChildAt(0)
        val content = getChildAt(1)
        val footer = getChildAt(2)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val availableHeight = if (heightMode == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.heightPixels
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }

        measureFixedChild(toolbar, widthMeasureSpec, availableHeight)
        measureFixedChild(footer, widthMeasureSpec, availableHeight)

        val fixedHeight = paddingTop + paddingBottom +
            childHeightWithMargins(toolbar) + childHeightWithMargins(footer)
        val remainingHeight = max(0, availableHeight - fixedHeight)
        val listHeight = min(remainingHeight, maxListHeight())
        measureContent(content, widthMeasureSpec, listHeight)

        var measuredWidth = paddingLeft + paddingRight
        var measuredHeight = paddingTop + paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measuredWidth = max(measuredWidth, paddingLeft + paddingRight + childWidthWithMargins(child))
            measuredHeight += childHeightWithMargins(child)
        }

        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(measuredHeight, heightMeasureSpec)
        )
    }

    private fun measureFixedChild(child: View, parentWidthSpec: Int, availableHeight: Int) {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val childWidthSpec = getChildMeasureSpec(
            parentWidthSpec,
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin,
            lp.width
        )
        val childHeightSpec = getChildMeasureSpec(
            MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST),
            paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin,
            lp.height
        )
        child.measure(childWidthSpec, childHeightSpec)
    }

    private fun measureContent(child: View, parentWidthSpec: Int, maxHeight: Int) {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        val childWidthSpec = getChildMeasureSpec(
            parentWidthSpec,
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin,
            lp.width
        )
        val childHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        child.measure(childWidthSpec, childHeightSpec)
    }

    private fun childWidthWithMargins(child: View): Int {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        return child.measuredWidth + lp.leftMargin + lp.rightMargin
    }

    private fun childHeightWithMargins(child: View): Int {
        val lp = child.layoutParams as ViewGroup.MarginLayoutParams
        return child.measuredHeight + lp.topMargin + lp.bottomMargin
    }

    private fun maxListHeight(): Int =
        (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()

    private companion object {
        const val MAX_HEIGHT_FRACTION = 0.55f
    }
}
