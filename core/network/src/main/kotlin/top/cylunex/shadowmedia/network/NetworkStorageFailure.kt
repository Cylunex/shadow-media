package top.cylunex.shadowmedia.network

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.hierynomus.mssmb2.SMBApiException

enum class NetworkStorageFailure { TRANSPORT, ACCESS, MISSING, OTHER }
class NetworkStorageFailureException(val kind: NetworkStorageFailure, cause: Throwable) : IOException("网络存储读取失败", cause)
internal fun storageFailure(error: Exception): NetworkStorageFailureException {
    if (error is NetworkStorageFailureException) return error
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    val status = causes.filterIsInstance<SMBApiException>().firstOrNull()?.status?.name
    val kind = when {
        status in setOf("STATUS_ACCESS_DENIED", "STATUS_LOGON_FAILURE", "STATUS_ACCOUNT_DISABLED", "STATUS_PASSWORD_EXPIRED") -> NetworkStorageFailure.ACCESS
        status in setOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE") -> NetworkStorageFailure.MISSING
        status in setOf("STATUS_NETWORK_NAME_DELETED", "STATUS_CONNECTION_DISCONNECTED", "STATUS_NETWORK_UNREACHABLE", "STATUS_IO_TIMEOUT", "STATUS_USER_SESSION_DELETED") -> NetworkStorageFailure.TRANSPORT
        causes.any { it is SocketException || it is SocketTimeoutException || it is UnknownHostException } -> NetworkStorageFailure.TRANSPORT
        else -> NetworkStorageFailure.OTHER
    }
    return NetworkStorageFailureException(kind, error)
}
