package io.legado.app.ui.book.info

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isWebFile
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.BookDownload
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.exists
import io.legado.app.utils.launch
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页和正文页共用保存设置与确认流程，下载任务仍由服务接管。 */
class BookDownloadDialog(
    private val activity: AppCompatActivity,
    private val model: () -> BookInfoViewModel
) {
    private var preparing = false
    private val directorySelect = activity.registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let {
            AppConfig.bookDownloadTreeUri = it.toString()
            show()
        }
    }

    fun show() {
        val viewModel = model()
        val book = viewModel.getBook(false) ?: return
        if (book.isLocal || book.isImage || book.isWebFile) return
        if (preparing || viewModel.downloadStarting.value == true || BookDownload.isRunning(book.bookUrl)) return
        val directory = AppConfig.bookDownloadTreeUri
        if (directory.isNullOrBlank()) {
            selectDirectory()
            return
        }
        val chapters = viewModel.chapterListData.value
        preparing = true
        activity.lifecycleScope.launch {
            try {
                val writable = withContext(IO) {
                    runCatching { FileDoc.fromDir(directory).let { it.exists() && it.checkWrite() } }.getOrDefault(false)
                }
                if (!writable) {
                    activity.toastOnUi("下载目录不可写，请重新选择")
                    selectDirectory()
                    return@launch
                }
                val count = withContext(IO) {
                    (chapters ?: appDb.bookChapterDao.getChapterList(book.bookUrl)).count { !it.isVolume }
                }
                val chapterText = if (count == 0) "目录将在下载前获取" else "$count 章"
                val format = if (book.isAudio) "逐章原始音频格式（不支持 HLS）" else viewModel.downloadFormat.uppercase()
                activity.alert(R.string.download_whole_book) {
                    setMessage("书籍：${book.name}\n章节：$chapterText\n格式：$format\n目录：$directory\n\n将在此目录中新建书名文件夹，重名自动编号。")
                    positiveButton(R.string.action_download) { viewModel.downloadBook(directory) }
                    neutralButton("保存设置") {
                        val options = if (book.isAudio) listOf("更改目录") else listOf("更改目录", "TXT", "EPUB", "PDF")
                        activity.selector("保存设置", options) { _, index ->
                            if (index == 0) selectDirectory() else {
                                viewModel.downloadFormat = options[index].lowercase()
                                show()
                            }
                        }
                    }
                    cancelButton()
                }
            } finally {
                preparing = false
            }
        }
    }

    private fun selectDirectory() {
        directorySelect.launch {
            mode = HandleFileContract.DIR_SYS
            title = "选择独立下载目录"
        }
    }
}
