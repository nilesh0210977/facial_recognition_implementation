package com.example.gatepass

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class RegisterActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var ivCapturedPhoto: ImageView
    private lateinit var photoUri: Uri
    private lateinit var db: FirebaseFirestore
    private lateinit var currentPhotoPath: String
    private lateinit var faceNetInterpreter: Interpreter
    private var faceEmbedding: FloatArray? = null
    private val embeddingDim = 512 // Embedding dimension, adjust based on your model

    companion object {
        const val REQUEST_IMAGE_CAPTURE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register_activity)

        etUsername = findViewById(R.id.etUsername)
        ivCapturedPhoto = findViewById(R.id.ivCapturedPhoto)
        db = FirebaseFirestore.getInstance()

        // Initialize FaceNet model
        val faceNetModel = FileUtil.loadMappedFile(this, "facenet_model.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(4) // Adjust based on your device's capabilities
        }
        faceNetInterpreter = Interpreter(faceNetModel, options)

        findViewById<Button>(R.id.btnCapturePhoto).setOnClickListener {
            capturePhoto()
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            registerUser()
        }
    }

    private fun capturePhoto() {
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
            ivCapturedPhoto.setImageURI(photoUri)
            processCapturedPhoto()
        }
    }

    private fun processCapturedPhoto() {
        try {
            val image = InputImage.fromFilePath(this, photoUri)

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            val detector = FaceDetection.getClient(options)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val face = faces[0]
                        val faceBitmap = extractFaceBitmap(BitmapFactory.decodeFile(currentPhotoPath), face.boundingBox)
                        faceEmbedding = getFaceEmbedding(faceBitmap)
                        Toast.makeText(this, "Face detected and processed", Toast.LENGTH_SHORT).show()
                    } else {
                        faceEmbedding = null
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

    private fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, 160, 160, false)

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

    private fun extractFaceBitmap(fullBitmap: Bitmap, boundingBox: android.graphics.Rect): Bitmap {
        val left = maxOf(boundingBox.left, 0)
        val top = maxOf(boundingBox.top, 0)
        val right = minOf(boundingBox.right, fullBitmap.width)
        val bottom = minOf(boundingBox.bottom, fullBitmap.height)
        return Bitmap.createBitmap(fullBitmap, left, top, right - left, bottom - top)
    }

    private fun registerUser() {
        val username = etUsername.text.toString().trim()
        if (username.isEmpty()) {
            Toast.makeText(this, "Enter a username", Toast.LENGTH_SHORT).show()
            return
        }
        if (faceEmbedding == null) {
            Toast.makeText(this, "Please capture a photo with a face before registering", Toast.LENGTH_SHORT).show()
            return
        }

        val userData = hashMapOf(
            "username" to username,
            "faceEmbedding" to faceEmbedding!!.toList(),
            "registrationDate" to FieldValue.serverTimestamp()
        )

        db.collection("users").document(username)
            .set(userData)
            .addOnSuccessListener {
                Toast.makeText(this, "User registered successfully", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, GatePassActivity::class.java)
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error registering user: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}