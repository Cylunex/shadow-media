package com.shadow.media.reading

import kotlinx.coroutines.CancellationException

internal enum class ReaderOpenStage(val message: String) {
    LOCAL_COPY("无法准备本地阅读副本，请检查文件和存储空间"),
    POSITION("无法恢复阅读位置，请从目录重新选择章节"),
    PUBLICATION("无法解析图书内容，请检查文件格式或内容完整性"),
    PREFERENCES("无法应用阅读排版设置，书籍和进度已保留"),
    NAVIGATOR("无法打开阅读页面，书籍和进度已保留"),
}

internal class ReaderOpeningException(val stage: ReaderOpenStage, cause: Exception) : Exception(stage.message, cause)

/** Keep the exact cause for diagnosis while displaying the failed reading step to the user. */
internal suspend fun <T> readerOpeningStep(stage: ReaderOpenStage, block: suspend () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw ReaderOpeningException(stage, failure)
}
