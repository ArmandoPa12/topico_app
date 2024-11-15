package com.example.app_topicos

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.app_topicos.AppService.AppOpeningService
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.dialogflow.v2.*
import java.util.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private val uuid = UUID.randomUUID().toString()
    private lateinit var recognizerIntent: Intent
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var sessionsClient: SessionsClient
    private lateinit var session: SessionName
    private val TAG = "MainActivity"
    private var showDialog = false
    private val captureInterval = 3000L // Intervalo de 3 segundos
    private var cameraIntent: Intent? = null
    private var isCapturing = false
    private val handler = Handler()
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private var isCameraInitialized = false




    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private const val CAMERA_REQUEST_CODE = 1001 // Puedes usar cualquier número
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAccessibilityPermission()
        checkPermissions()  // Verifica permisos de audio y cámara

        initializeDialogflow()
        initializeTextToSpeech()
        initializeSpeechRecognizer()
        initializeShakeService()
        // Inicia CameraX
        //startCamera()

        // Ejecutores para manejar hilos de CameraX
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Inicia la captura periódica de fotos
        startPeriodicCapture()

        if (showDialog) {
            showAccessibilityDialog()
        }

        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        btnSpeak.visibility = View.VISIBLE
        btnSpeak.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> startListening()
                android.view.MotionEvent.ACTION_UP -> speechRecognizer.stopListening()
            }
            true
        }
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun initializeDialogflow() {
        try {
            val stream = resources.openRawResource(R.raw.credenciales)
            val credentials = GoogleCredentials.fromStream(stream)
            val serviceAccountCredentials = credentials as? ServiceAccountCredentials
                ?: throw IllegalArgumentException("Credenciales no son de tipo ServiceAccount")

            val projectId = serviceAccountCredentials.projectId
            val settings = SessionsSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()

            sessionsClient = SessionsClient.create(settings)
            session = SessionName.of(projectId, uuid)

            Log.d(TAG, "Dialogflow inicializado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar Dialogflow: ${e.message}")
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "es-ES"
            ) // Configura el reconocimiento en español
        }
    }

    private fun initializeShakeService() {
        val shakeServiceIntent = Intent(this, ShakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(shakeServiceIntent)
        } else {
            startService(shakeServiceIntent)
        }
    }

    private fun startListening() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Habla ahora", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.get(0) ?: ""
                Log.d(TAG, "Texto reconocido: $spokenText")
                val openCameraCommands = listOf(
                    "abrir cámara",
                    "iniciar cámara",
                    "quiero la cámara",
                    "activa la cámara",
                    "prende la cámara",
                    "quiero abrir la cámara"
                )

                val closeCameraCommands = listOf(
                    "cerrar cámara",
                    "detener cámara",
                    "apaga la cámara",
                    "quiero cerrar la cámara",
                    "quiero detener la cámara"
                )

                // Si el usuario dice una frase para activar la cámara
                if (openCameraCommands.any { spokenText.equals(it, ignoreCase = true) }) {
                    startCamera()
                }
                // Si el usuario dice una frase para detener la cámara
                else if (closeCameraCommands.any { spokenText.equals(it, ignoreCase = true) }) {
                    stopCamera()
                } else {
                    sendToDialogflow(spokenText)
                }
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció ninguna coincidencia"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocimiento de voz está ocupado"
                    SpeechRecognizer.ERROR_CLIENT -> "Error del cliente de reconocimiento de voz"
                    else -> "Error en SpeechRecognizer: $error"
                }
                Log.e(TAG, errorMessage)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
        speechRecognizer.startListening(recognizerIntent)
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            startActivity(cameraIntent)
            startPeriodicCapture()
        } else {
            checkPermissions()
        }
    }

    private fun sendToDialogflow(text: String) {
        try {
            val textInput = TextInput.newBuilder().setText(text).setLanguageCode("es").build()
            val queryInput = QueryInput.newBuilder().setText(textInput).build()

            val request = DetectIntentRequest.newBuilder()
                .setSession(session.toString())
                .setQueryInput(queryInput)
                .build()

            val response = sessionsClient.detectIntent(request)
            val fulfillmentText = response.queryResult.fulfillmentText

            Log.d(TAG, "Respuesta de Dialogflow: $fulfillmentText")
            speak(fulfillmentText)
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar mensaje a Dialogflow: ${e.message}")
        }
    }

    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale("es", "ES")
        } else {
            Log.e(TAG, "Error al inicializar TextToSpeech")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            permissions.forEachIndexed { index, permission ->
                val isGranted = grantResults[index] == PackageManager.PERMISSION_GRANTED
                val message = if (isGranted) "concedido" else "denegado"
                Toast.makeText(this, "Permiso de $permission $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled(AppOpeningService::class.java)) {
            showDialog = true
        }
    }

    private fun isAccessibilityServiceEnabled(service: Class<out AccessibilityService>): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service.name) ?: false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permiso de Accesibilidad Requerido")
            .setMessage("Esta aplicación requiere acceso a los servicios de accesibilidad para funcionar correctamente. ¿Quieres activarlos ahora?")
            .setPositiveButton("Ir a Configuración") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    //?------------------
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                takePhotoAndSendToApi()
                handler.postDelayed(this, captureInterval) // Repetir cada 3 segundos
            }
        }
    }

    private fun startPeriodicCapture() {
        handler.post(object : Runnable {
            override fun run() {
                if (isCameraInitialized) {
                    takePhotoAndSendToApi()
                    handler.postDelayed(this, captureInterval)
                } else {
                    Log.d("CameraX", "La cámara no está inicializada aún")
                }
            }
        })
    }

    private fun stopPeriodicCapture() {
        isCapturing = false
        handler.removeCallbacks(captureRunnable)
    }

    private fun takePhotoAndSendToApi() {
        // Tomar una foto con CameraX
        val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile()).build()

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val bitmap = BitmapFactory.decodeFile(outputFileResults.savedUri?.path)
                    bitmap?.let { sendPhotoToApi(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Error al capturar la imagen: ${exception.message}", exception)
                }
            })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            val photo = data?.extras?.get("data") as Bitmap
            sendPhotoToApi(photo)
        }
    }

    private fun sendPhotoToApi(photo: Bitmap) {
        // Convierte el Bitmap a un ByteArrayOutputStream en formato JPEG
        val outputStream = ByteArrayOutputStream()
        photo.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        val photoData = outputStream.toByteArray()
        // Verifica el tamaño de los datos de la imagen
        Log.d("CameraX", "Tamaño de la imagen en bytes: ${photoData.size}")
        // Guarda la imagen temporalmente para verificar su captura
        val tempFile = File(cacheDir, "temp_photo.jpg")
        FileOutputStream(tempFile).use {
            it.write(photoData)
            it.flush()
        }
        Log.d("CameraX", "Imagen guardada temporalmente en: ${tempFile.absolutePath}")
        // Crear el cuerpo de la solicitud de la imagen como form-data
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "photo.jpg", // Nombre del archivo en la solicitud
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), photoData)
            )
            .build()

        // Crear la solicitud POST con la URL de la API
        val request = Request.Builder()
            .url("http://192.168.11.4:5000/predict") // Cambia la URL a la de tu API
            .post(requestBody)
            .build()

        // Crear el cliente de OkHttp para enviar la solicitud
        val client = OkHttpClient()

        // Enviar la solicitud en un hilo separado
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Manejo de error
                Log.e("CameraX", "Error al enviar la imagen: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d("CameraX", "Respuesta de la API: $responseBody")

                    // Parsear el JSON para obtener los valores de `confidence` y `predicted_label`
                    try {
                        val jsonObject = JSONObject(responseBody)
                        val confidence = jsonObject.getDouble("confidence")
                        val predictedLabel = jsonObject.getString("predicted_label")

                        // Construir el mensaje para speak
                        val message = "La probabilidad de que sea un billete de $predictedLabel es del ${"%.2f".format(confidence)} por ciento"
                        speak(message)
                    } catch (e: JSONException) {
                        Log.e("CameraX", "Error al parsear la respuesta de la API", e)
                        speak("Error al interpretar la respuesta de la API")
                    }
                } else {
                    // Manejo de error en la respuesta
                    Log.e("CameraX", "Error en la respuesta de la API: ${response.message}")
                    speak("Error en la conexion a la api")
                }
            }
        })
    }
    //?---------------------------------------------
    //************************************************************
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                // Indica que la cámara ha sido inicializada
                isCameraInitialized = true
                startPeriodicCapture()
            } catch (exc: Exception) {
                Log.e("CameraX", "Error al inicializar la cámara", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }
    //************************************************************
    private fun stopCamera() {
        if (isCapturing) {
            isCapturing = false
            handler.removeCallbacks(captureRunnable) // Detiene la captura periódica
        }

        // Libera los recursos de CameraX si es necesario
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // Detiene todos los casos de uso de CameraX
            isCameraInitialized = false // Cambia el estado para indicar que la cámara está desactivada
            Log.d("CameraX", "CameraX detenido y recursos liberados")
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onDestroy() {
        super.onDestroy()
        textToSpeech.shutdown()
        speechRecognizer.destroy()
    }
}