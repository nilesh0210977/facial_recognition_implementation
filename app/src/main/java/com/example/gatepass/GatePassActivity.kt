package com.example.gatepass

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import com.google.firebase.firestore.FirebaseFirestore
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class GatePassActivity : AppCompatActivity() {

    private lateinit var ivGatePass: ImageView
    private lateinit var photoUri: Uri
    private lateinit var currentPhotoPath: String
    private lateinit var db: FirebaseFirestore

    private lateinit var faceNetInterpreter: Interpreter
    private val embeddingDim = 512

    companion object {
        const val REQUEST_IMAGE_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gate_pass_activity)


        val faceNetModel = FileUtil.loadMappedFile(this, "facenet_model.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4) // Adjust based on your device's capabilities
        }
        faceNetInterpreter = Interpreter(faceNetModel, options)


        ivGatePass = findViewById(R.id.ivGatePass)
        db = FirebaseFirestore.getInstance()

        findViewById<Button>(R.id.btnGenerateGatePass).setOnClickListener {
            captureNewPhoto()
        }
    }



    private fun getFaceEmbedding(face: Face): FloatArray {
        val bitmap = extractFaceBitmap(face)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 160, 160, false)

        val inputBuffer = ByteBuffer.allocateDirect(1 * 160 * 160 * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until 160) {
            for (x in 0 until 160) {
                val px = scaledBitmap.getPixel(x, y)
                inputBuffer.putFloat(((px shr 16 and 0xFF) - 128f) / 128f)
                inputBuffer.putFloat(((px shr 8 and 0xFF) - 128f) / 128f)
                inputBuffer.putFloat(((px and 0xFF) - 128f) / 128f)
            }
        }

        val outputBuffer = ByteBuffer.allocateDirect(1 * embeddingDim * 4).order(ByteOrder.nativeOrder())
        faceNetInterpreter.run(inputBuffer, outputBuffer)

        val embedding = FloatArray(embeddingDim)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(embedding)
        return embedding
    }

    private fun extractFaceBitmap(face: Face): Bitmap {
        // This is a placeholder implementation. You need to replace this with actual face extraction logic.
        val imageBitmap = BitmapFactory.decodeFile(currentPhotoPath)
        val faceRect = face.boundingBox

        // Ensure the rect is within the bounds of the bitmap
        val left = maxOf(faceRect.left, 0)
        val top = maxOf(faceRect.top, 0)
        val right = minOf(faceRect.right, imageBitmap.width)
        val bottom = minOf(faceRect.bottom, imageBitmap.height)

        return Bitmap.createBitmap(imageBitmap, left, top, right - left, bottom - top)
    }

    private fun captureNewPhoto() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Toast.makeText(this, "Error creating file: ${ex.message}", Toast.LENGTH_SHORT).show()
            null
        }
        photoFile?.also {
            photoUri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                it
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            processNewPhoto(photoUri)
        } else {
            Toast.makeText(this, "Image capture failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processNewPhoto(photoUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, photoUri)

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()

            val detector = FaceDetection.getClient(options)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        verifyUser(face)
                    } else {
                        Toast.makeText(this, "No face detected", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Face detection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verifyUser(face: Face) {
        val currentEmbedding = getFaceEmbedding(face)

        db.collection("users").document("hello")
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val storedEmbedding = document.get("faceEmbedding") as? List<Float>
                    if (storedEmbedding != null && faceMatchesStoredFeatures(currentEmbedding, storedEmbedding)) {
                        generateUniqueCode()
                    } else {
                        Toast.makeText(this, "Verification failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Error getting user data: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun faceMatchesStoredFeatures(currentEmbedding: FloatArray, storedEmbedding: List<Float>): Boolean {
        if (currentEmbedding.size != storedEmbedding.size) return false

        var distance = 0f
        for (i in currentEmbedding.indices) {
            val diff = currentEmbedding[i] - storedEmbedding[i]
            distance += diff * diff
        }
        distance = sqrt(distance)

        // Threshold for face match, adjust based on your requirements
        val threshold = 0.6f
        return distance < threshold
    }

    private fun generateUniqueCode() {
        val uniqueCode = UUID.randomUUID().toString()
        displayQRCode(uniqueCode)
    }

    private fun displayQRCode(code: String) {
        val bitmap = generateQRCodeBitmap(code)
        ivGatePass.setImageBitmap(bitmap)
    }

    private fun generateQRCodeBitmap(text: String): Bitmap {
        val width = 512
        val height = 512
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}