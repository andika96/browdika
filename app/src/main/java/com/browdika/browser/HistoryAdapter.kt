package com.browdika.browser

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView
import java.util.Date

class HistoryAdapter(
    private val context: Context,
    private val onOpen: (HistoryItem) -> Unit,
    private val onDelete: (HistoryItem) -> Unit
) : BaseAdapter() {

    private val items = mutableListOf<HistoryItem>()
    private val inflater = LayoutInflater.from(context)

    fun update(newItems: List<HistoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): HistoryItem = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_history, parent, false)
        val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }
        val item = items[position]

        holder.title.text = item.title.ifBlank { displayUrl(item.url) }
        holder.url.text = displayUrl(item.url)
        holder.time.text = formatTime(item.visitedAt)

        view.setOnClickListener { onOpen(item) }
        holder.delete.setOnClickListener { onDelete(item) }

        return view
    }

    private fun displayUrl(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")

    private fun formatTime(visitedAt: Long): String {
        val now = System.currentTimeMillis()
        return if (now - visitedAt < DateUtils.DAY_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(
                visitedAt,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        } else {
            val date = DateFormat.getMediumDateFormat(context).format(Date(visitedAt))
            val time = DateFormat.getTimeFormat(context).format(Date(visitedAt))
            "$date • $time"
        }
    }

    private class ViewHolder(view: View) {
        val title: TextView = view.findViewById(R.id.historyTitle)
        val url: TextView = view.findViewById(R.id.historyUrl)
        val time: TextView = view.findViewById(R.id.historyTime)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteHistory)
    }
}
