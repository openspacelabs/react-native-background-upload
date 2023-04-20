package com.vydia.RNUploader

import android.content.Context
import net.gotev.uploadservice.HttpUploadRequest
import net.gotev.uploadservice.HttpUploadTask
import net.gotev.uploadservice.UploadTask
import net.gotev.uploadservice.data.NameValue
import net.gotev.uploadservice.data.UploadFile
import net.gotev.uploadservice.extensions.addHeader
import net.gotev.uploadservice.extensions.asciiBytes
import net.gotev.uploadservice.extensions.setOrRemove
import net.gotev.uploadservice.extensions.utf8Bytes
import net.gotev.uploadservice.network.BodyWriter
import net.gotev.uploadservice.network.HttpStack
import net.gotev.uploadservice.persistence.PersistableData
import net.gotev.uploadservice.protocols.multipart.MultipartUploadRequest
import java.io.FileNotFoundException

// =========================================================
// THE CODE BELOW IS COPIED FROM net.gotenv.uploadservice
// THERE IS NO CHANGE EXCEPT FOR ADDING DISCRETIONARY MODE
// =========================================================

private const val KEY_DISCRETIONARY = "wifiOnly"


/**
 * HTTP/Multipart upload request. This is the most common way to upload files on a server.
 * It's the same kind of request that browsers do when you use the &lt;form&gt; tag
 * @param context application context
 * @param serverUrl URL of the server side script that will handle the multipart form upload.
 * E.g.: http://www.yourcompany.com/your/script
 */
class UploadRequestMultipart(
  context: Context,
  serverUrl: String,
  private val wifiOnly: Boolean
) :
  HttpUploadRequest<UploadRequestMultipart>(context, serverUrl) {

  override val taskClass: Class<out UploadTask>
    get() = MultipartUploadTask::class.java


  override fun getAdditionalParameters(): PersistableData {
    return super.getAdditionalParameters().apply {
      putBoolean(KEY_DISCRETIONARY, wifiOnly)
    }
  }

  /**
   * Adds a file to this upload request.
   *
   * @param filePath path to the file that you want to upload
   * @param parameterName Name of the form parameter that will contain file's data
   * @param fileName File name seen by the server side script. If null, the original file name
   * will be used
   * @param contentType Content type of the file. If null or empty, the mime type will be
   * automatically detected. If fore some reasons autodetection fails,
   * `application/octet-stream` will be used by default
   * @return [MultipartUploadRequest]
   */
  @Throws(FileNotFoundException::class)
  @JvmOverloads
  fun addFileToUpload(
    filePath: String,
    parameterName: String,
    fileName: String? = null,
    contentType: String? = null
  ): UploadRequestMultipart {
    require(filePath.isNotBlank() && parameterName.isNotBlank()) {
      "Please specify valid filePath and parameterName. They cannot be blank."
    }

    files.add(UploadFile(filePath).apply {
      this.parameterName = parameterName
      this.contentType = if (contentType.isNullOrBlank()) {
        handler.contentType(context)
      } else {
        contentType
      }
      remoteFileName = if (fileName.isNullOrBlank()) {
        handler.name(context)
      } else {
        fileName
      }
    })

    return this
  }
}

/**
 * Implements an HTTP Multipart upload task.
 */
class MultipartUploadTask : HttpUploadTask() {

  companion object {
    private const val BOUNDARY_SIGNATURE = "-------RNUploader-------"
    private const val NEW_LINE = "\r\n"
    private const val TWO_HYPHENS = "--"
  }

  private val boundary = BOUNDARY_SIGNATURE + System.nanoTime()
  private val boundaryBytes = (TWO_HYPHENS + boundary + NEW_LINE).asciiBytes
  private val trailerBytes = (TWO_HYPHENS + boundary + TWO_HYPHENS + NEW_LINE).asciiBytes
  private val newLineBytes = NEW_LINE.utf8Bytes

  private val NameValue.multipartHeader: ByteArray
    get() = boundaryBytes + ("Content-Disposition: form-data; " +
      "name=\"$name\"$NEW_LINE$NEW_LINE$value$NEW_LINE").utf8Bytes

  private val UploadFile.multipartHeader: ByteArray
    get() = boundaryBytes + ("Content-Disposition: form-data; " +
      "name=\"$parameterName\"; " +
      "filename=\"$remoteFileName\"$NEW_LINE" +
      "Content-Type: $contentType$NEW_LINE$NEW_LINE").utf8Bytes

  private val UploadFile.totalMultipartBytes: Long
    get() = multipartHeader.size.toLong() + handler.size(context) + newLineBytes.size.toLong()

  private fun BodyWriter.writeRequestParameters() {
    httpParams.requestParameters.forEach {
      write(it.multipartHeader)
    }
  }

  private fun BodyWriter.writeFiles() {
    for (file in params.files) {
      if (!shouldContinue) break

      write(file.multipartHeader)
      writeStream(file.handler.stream(context))
      write(newLineBytes)
    }
  }

  private val requestParametersLength: Long
    get() = httpParams.requestParameters.sumOf { it.multipartHeader.size.toLong() }

  private val filesLength: Long
    get() = params.files.sumOf { it.totalMultipartBytes }

  override val bodyLength: Long
    get() = requestParametersLength + filesLength + trailerBytes.size

  override fun performInitialization() {
    httpParams.requestHeaders.apply {
      addHeader("Content-Type", "multipart/form-data; boundary=$boundary")
      addHeader("Connection", if (params.files.size <= 1) "close" else "Keep-Alive")
    }
  }

  override fun onWriteRequestBody(bodyWriter: BodyWriter) {
    // reset uploaded bytes when the body is ready to be written
    // because sometimes this gets invoked when network changes
    resetUploadedBytes()
    setAllFilesHaveBeenSuccessfullyUploaded(false)

    bodyWriter.apply {
      writeRequestParameters()
      writeFiles()
      write(trailerBytes)
    }
  }

  override fun upload(httpStack: HttpStack) {
    val wifiOnly = params.additionalParameters.getBoolean(KEY_DISCRETIONARY)
    val stack =
      if (wifiOnly) UploaderModule.wifiOnlyHttpStack
      else UploaderModule.httpStack
    // throw error to kick off the retry mechanism
    if (stack == null) throw Error("No available httpStack. WifiOnly: $wifiOnly")
    super.upload(httpStack)
  }
}


private const val PROPERTY_PARAM_NAME = "multipartParamName"
private const val PROPERTY_REMOTE_FILE_NAME = "multipartRemoteFileName"
private const val PROPERTY_CONTENT_TYPE = "multipartContentType"

private var UploadFile.parameterName: String?
  get() = properties[PROPERTY_PARAM_NAME]
  set(value) {
    properties.setOrRemove(PROPERTY_PARAM_NAME, value)
  }

private var UploadFile.remoteFileName: String?
  get() = properties[PROPERTY_REMOTE_FILE_NAME]
  set(value) {
    properties.setOrRemove(PROPERTY_REMOTE_FILE_NAME, value)
  }

private var UploadFile.contentType: String?
  get() = properties[PROPERTY_CONTENT_TYPE]
  set(value) {
    properties.setOrRemove(PROPERTY_CONTENT_TYPE, value)
  }
