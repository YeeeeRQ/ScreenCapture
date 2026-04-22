package com.example.capture.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream

class FileManager(private val context: Context) {

    companion object {
        private const val TAG = RecordingConstants.TAG
    }

    fun createOutputFile(): File {
        val filesDir = context.filesDir
        Log.d(TAG, "Files dir: ${filesDir.absolutePath}")

        val screenRecordDir = File(filesDir, "screen_record")
        if (!screenRecordDir.exists()) {
            val created = screenRecordDir.mkdirs()
            Log.d(TAG, "Directory created: $created, exists: ${screenRecordDir.exists()}")
        }

        val timestamp = System.currentTimeMillis()
        val outputFile = File(screenRecordDir, "screen_$timestamp.mp4")

        try {
            val canWrite = outputFile.createNewFile()
            Log.d(TAG, "Output file created: $canWrite, path: ${outputFile.absolutePath}")
            if (outputFile.exists()) {
                outputFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating output file: ${e.message}")
        }

        Log.d(TAG, "Final output file: ${outputFile.absolutePath}")
        return outputFile
    }

    fun saveToPublicDirectory(sourceFile: File): File? {
        Log.d(TAG, "saveToPublicDirectory called")
        val timestamp = System.currentTimeMillis()
        val fileName = "ScreenRecord_$timestamp.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecord")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry!")
            return null
        }

        Log.d(TAG, "MediaStore URI created: $uri")

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            sourceFile.delete()

            Log.d(TAG, "Video saved to: $uri")
            Log.d(TAG, "Video path: ${Environment.DIRECTORY_MOVIES}/ScreenRecord/$fileName")

            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenRecord/$fileName")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR saving to public directory: ${e.message}")
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }

    fun saveBitmapToMediaStore(bitmap: Bitmap): Pair<Boolean, String?> {
        return try {
            val filename = "Screen_${System.currentTimeMillis()}.png"
            val relativePath = "Pictures/ScreenRecord"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                val filePath = "$relativePath/$filename"
                Log.d(TAG, "Screenshot saved: $filePath")
                Pair(true, filePath)
            } ?: Pair(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Save bitmap error: ${e.message}")
            Pair(false, null)
        }
    }
}