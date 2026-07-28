package com.nin0dev.vendroid.utils

import android.util.Log

object Logger {
    private const val TAG = "Vendroid"
    @JvmStatic
    fun e(message: String?) {
        Log.e(TAG, message.orEmpty())
        VDELog.log(VDELog.Level.ERROR, TAG, message.orEmpty())
    }

    @JvmStatic
    fun e(message: String?, e: Throwable?) {
        Log.e(TAG, message.orEmpty(), e)
        VDELog.log(VDELog.Level.ERROR, TAG, message.orEmpty(), e)
    }

    @JvmStatic
    fun w(message: String?) {
        Log.w(TAG, message.orEmpty())
        VDELog.log(VDELog.Level.WARN, TAG, message.orEmpty())
    }

    @JvmStatic
    fun i(message: String?) {
        Log.i(TAG, message.orEmpty())
        VDELog.log(VDELog.Level.INFO, TAG, message.orEmpty())
    }

    @JvmStatic
    fun d(message: String?) {
        Log.d(TAG, message.orEmpty())
        VDELog.log(VDELog.Level.DEBUG, TAG, message.orEmpty())
    }
}
