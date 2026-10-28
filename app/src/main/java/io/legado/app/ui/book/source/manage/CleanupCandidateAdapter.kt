package io.legado.app.ui.book.source.manage

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckedTextView
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.help.source.SourceCleanup

internal class CleanupCandidateAdapter(
    private val candidates: List<SourceCleanup.Candidate>,
    private val checked: BooleanArray,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<CleanupCandidateAdapter.Holder>() {
    companion object {
        const val PAGE_SIZE = 50
    }

    var page = 0
        private set

    val pageCount get() = (candidates.size + PAGE_SIZE - 1) / PAGE_SIZE

    class Holder(val item: CheckedTextView) : RecyclerView.ViewHolder(item)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val item = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_multiple_choice, parent, false) as CheckedTextView
        return Holder(item)
    }

    override fun getItemCount() = minOf(PAGE_SIZE, candidates.size - page * PAGE_SIZE)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = page * PAGE_SIZE + position
        val candidate = candidates[index]
        holder.item.text = "${candidate.bookSourceName}\n${candidate.bookSourceUrl}\n${candidate.reason}"
        holder.item.isChecked = checked[index]
        holder.item.setOnClickListener {
            checked[index] = !checked[index]
            holder.item.isChecked = checked[index]
            onSelectionChanged()
        }
    }

    fun showPage(value: Int) {
        page = value.coerceIn(0, pageCount - 1)
        notifyDataSetChanged()
    }
}
