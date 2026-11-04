package com.browdika.browser

import android.app.DownloadManager
import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible

data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val mimeType: String?,
    val reason: Int
)

class DownloadListAdapter(
    private val context: Context,
    private val onOpen: (DownloadItem) -> Unit,
    private val onDelete: (DownloadItem) -> Unit
) : BaseAdapter() {

    private val items = mutableListOf<DownloadItem>()
    private val inflater = LayoutInflater.from(context)

    fun update(newItems: List<DownloadItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): DownloadItem = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun hasStableIds(): Boolean = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_download, parent, false)
        val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }
        val item = items[position]

        holder.title.text = item.title.ifBlank { context.getString(R.string.download_untitled) }
        holder.status.text = statusText(item)

        val inProgress = item.status == DownloadManager.STATUS_RUNNING ||
            item.status == DownloadManager.STATUS_PENDING ||
            item.status == DownloadManager.STATUS_PAUSED
        holder.progress.isVisible = inProgress
        if (inProgress) {
            if (item.totalBytes > 0) {
                holder.progress.isIndeterminate = false
                holder.progress.max = 100
                holder.progress.progress = percent(item)
            } else {
                holder.progress.isIndeterminate = item.status == DownloadManager.STATUS_RUNNING
                holder.progress.progress = 0
            }
        }

        view.setOnClickListener { onOpen(item) }
        holder.delete.setOnClickListener { onDelete(item) }

        return view
    }

    private fun statusText(item: DownloadItem): String {
        val downloaded = Formatter.formatFileSize(context, item.bytesDownloaded.coerceAtLeast(0))
        val total = if (item.totalBytes > 0) {
            Formatter.formatFileSize(context, item.totalBytes)
        } else {
            null
        }

        return when (item.status) {
            DownloadManager.STATUS_PENDING -> context.getString(R.string.download_status_pending)
            DownloadManager.STATUS_RUNNING -> {
                if (item.totalBytes > 0) {
                    context.getString(
                        R.string.download_status_running_size,
                        percent(item),
                        downloaded,
                        total ?: downloaded
                    )
                } else {
                    context.getString(R.string.download_status_running_unknown, downloaded)
                }
            }
            DownloadManager.STATUS_PAUSED -> {
                if (item.totalBytes > 0) {
                    context.getString(
                        R.string.download_status_paused_size,
                        percent(item),
                        downloaded,
                        total ?: downloaded
                    )
                } else {
                    context.getString(R.string.download_status_paused, downloaded)
                }
            }
            DownloadManager.STATUS_SUCCESSFUL -> context.getString(
                R.string.download_status_successful,
                total ?: downloaded
            )
            DownloadManager.STATUS_FAILED -> context.getString(
                R.string.download_status_failed,
                failureReason(item.reason)
            )
            else -> context.getString(R.string.download_status_unknown)
        }
    }

    private fun percent(item: DownloadItem): Int {
        if (item.totalBytes <= 0) return 0
        return ((item.bytesDownloaded * 100) / item.totalBytes)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun failureReason(reason: Int): String = context.getString(
        when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.download_reason_space
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> R.string.download_reason_device
            DownloadManager.ERROR_CANNOT_RESUME -> R.string.download_reason_resume
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> R.string.download_reason_exists
            DownloadManager.ERROR_FILE_ERROR -> R.string.download_reason_file
            DownloadManager.ERROR_HTTP_DATA_ERROR -> R.string.download_reason_http
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> R.string.download_reason_redirect
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> R.string.download_reason_http_code
            else -> R.string.download_reason_unknown
        }
    )

    private class ViewHolder(view: View) {
        val title: TextView = view.findViewById(R.id.downloadTitle)
        val status: TextView = view.findViewById(R.id.downloadStatus)
        val progress: ProgressBar = view.findViewById(R.id.downloadProgress)
        val delete: ImageButton = view.findViewById(R.id.btnDeleteDownload)
    }
}
