package com.vydia.RNUploader

import android.content.Context

class UploadRetry {
  companion object {
    private fun storage(context: Context) =
      context.getSharedPreferences("RNFileUpload-retries", Context.MODE_PRIVATE)

    fun set(context: Context, uploadId: String, count: Int) =
      storage(context).edit().putInt(uploadId, count).commit()

    fun get(context: Context, uploadId: String) =
      storage(context).getInt(uploadId, 0)


    fun clear(context: Context, uploadId: String) {
      storage(context).edit().remove(uploadId).commit()
    }
  }
}