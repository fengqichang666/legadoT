package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** RecyclerView for content-sized import dialogs, capped so their actions remain visible. */
class ContentSizedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val requestedHeight = if (mode == MeasureSpec.UNSPECIFIED) {
            maxHeight()
        } else {
            minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight())
        }
        val cappedHeightSpec = MeasureSpec.makeMeasureSpec(requestedHeight, MeasureSpec.AT_MOST)

        if (adapter == null || adapter?.itemCount == 0) {
            setMeasuredDimension(
                View.resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                View.resolveSize(suggestedMinimumHeight, cappedHeightSpec)
            )
            return
        }
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }

    private fun maxHeight(): Int =
        (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()

    private companion object {
        const val MAX_HEIGHT_FRACTION = 0.55f
    }
}
