package com.vydia.RNUploader

import android.content.Context
import net.gotev.uploadservice.HttpUploadRequest
import net.gotev.uploadservice.HttpUploadTask
import net.gotev.uploadservice.UploadTask
import net.gotev.uploadservice.data.UploadFile
import net.gotev.uploadservice.extensions.addHeader
import net.gotev.uploadservice.logger.UploadServiceLogger
import net.gotev.uploadservice.network.BodyWriter
import net.gotev.uploadservice.network.HttpStack
import net.gotev.uploadservice.persistence.PersistableData
import net.gotev.uploadservice.protocols.binary.BinaryUploadRequest
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*

// =========================================================
// THE CODE BELOW IS COPIED FROM net.gotenv.uploadservice
// THERE IS NO CHANGE EXCEPT FOR ADDING DISCRETIONARY MODE
// =========================================================


private const val KEY_DISCRETIONARY = "wifiOnly"

class UploadRequestBinary(
  context: Context,
  serverUrl: String,
  private val wifiOnly: Boolean
) :
  HttpUploadRequest<UploadRequestBinary>(context, serverUrl) {

  override val taskClass: Class<out UploadTask>
    get() = BinaryUploadTask::class.java

  /**
   * Sets the file used as raw body of the upload request.
   *
   * @param path path to the file that you want to upload
   * @throws FileNotFoundException if the file to upload does not exist
   * @return [BinaryUploadRequest]
   */
  @Throws(IOException::class)
  fun setFileToUpload(path: String): UploadRequestBinary {
    files.clear()
    files.add(UploadFile(path))
    return this
  }

  override fun getAdditionalParameters(): PersistableData {
    return super.getAdditionalParameters().apply {
      putBoolean(KEY_DISCRETIONARY, wifiOnly)
    }
  }

  override fun addParameter(paramName: String, paramValue: String): UploadRequestBinary {
    logDoesNotSupportParameters()
    return this
  }

  override fun addArrayParameter(paramName: String, vararg array: String): UploadRequestBinary {
    logDoesNotSupportParameters()
    return this
  }

  override fun addArrayParameter(paramName: String, list: List<String>): UploadRequestBinary {
    logDoesNotSupportParameters()
    return this
  }

  override fun startUpload(): String {
    require(files.isNotEmpty()) { "Set the file to be used in the request body first!" }
    return super.startUpload()
  }

  private fun logDoesNotSupportParameters() {
    UploadServiceLogger.error(javaClass.simpleName, "N/A") {
      "This upload method does not support adding parameters"
    }
  }
}

class BinaryUploadTask : HttpUploadTask() {
  private val file by lazy { params.files.first().handler }

  override val bodyLength: Long
    get() = file.size(context)

  override fun performInitialization() {
    with(httpParams.requestHeaders) {
      if (none { it.name.lowercase(Locale.getDefault()) == "content-type" }) {
        addHeader("Content-Type", file.contentType(context))
      }
    }
  }

  override fun upload(httpStack: HttpStack) {
    val wifiOnly = params.additionalParameters.getBoolean(KEY_DISCRETIONARY)
    val stack =
      if (wifiOnly) UploaderModule.wifiOnlyHttpStack
      else UploaderModule.httpStack
    // throw error to kick off the retry mechanism
    if (stack == null) throw Error("No available httpStack. WifiOnly: $wifiOnly")
    super.upload(stack)
  }

  override fun onWriteRequestBody(bodyWriter: BodyWriter) {
    bodyWriter.writeStream(file.stream(context))
  }
}

