/**
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Simple app to demonstrate CameraX Video capturing with Recorder ( to local files ), with the
 * following simple control follow:
 *   - user starts capture.
 *   - this app disables all UI selections.
 *   - this app enables capture run-time UI (pause/resume/stop).
 *   - user controls recording with run-time UI, eventually tap "stop" to end.
 *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
 *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
 *   - this app starts VideoViewer fragment to view the captured result.
*/

package com.example.android.camerax.video.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import java.text.SimpleDateFormat
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.camera.core.AspectRatio
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.example.android.camerax.video.R
import com.example.android.camerax.video.databinding.FragmentCameraBinding
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.whenCreated
import com.android.volley.AuthFailureError
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.example.android.camerax.video.LoginFragment
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.UnsupportedEncodingException
import java.util.*

class CameraFragment : Fragment() {

    // UI with ViewBinding
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var userID : String

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var activeRecording: ActiveRecording? = null
    private lateinit var recordingState:VideoRecordEvent

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred:Deferred<Unit>? = null

    // main cameraX capture functions
    /**
     *   Always bind preview + video capture use case combinations in this sample
     *   (VideoCapture can work on its own).
     */
    private fun bindCaptureUsecase() {
        lifecycleScope.launch {
            val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            val preview = Preview.Builder().setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
                .build().apply {
                    setSurfaceProvider(fragmentCameraBinding.previewView.surfaceProvider)
                }

            val qualitySelector = QualitySelector
                .firstTry(QualitySelector.QUALITY_UHD)
                .thenTry(QualitySelector.QUALITY_FHD)
                .thenTry(QualitySelector.QUALITY_HD)
                .finallyTry(QualitySelector.QUALITY_SD,
                    QualitySelector.FALLBACK_STRATEGY_LOWER)


            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    requireParentFragment(),
                    cameraSelector,
                    videoCapture,
                    preview
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                resetUIandState()
            }
        }
    }

    /**
     * Kick start the video recording
     *   - config Recorder to capture to MediaStoreOutput
     *   - register RecordEvent Listener
     *   - apply audio request from user
     *   - start recording!
     * After this function, user could start/pause/resume/stop recording and application listens
     * to VideoRecordEvent for the current recording status.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = "CameraX-recording-" +
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + "-raw.mp4"
        val file = File(requireContext().getExternalFilesDir(null), name)
        val fileoutpuss = FileOutputOptions.Builder(file).build()
        // configure Recorder and Start recording to the mediaStoreOutput.
        activeRecording =
               videoCapture.output.prepareRecording(requireActivity(), fileoutpuss)
               .withEventListener(
                    mainThreadExecutor,
                    captureListener
               )
               .withAudioEnabled()
               .start()

        Log.i(TAG, "Recording started")
    }

    /**
     * CaptureEvent listener.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            uploadVideo(event.outputResults.outputUri)
        }
    }

    private fun uploadVideo(viduri : Uri) {
        val uploadref = "videos/"+userID+"/"+System.currentTimeMillis()+"-raw.mp4"
        val storageReference = FirebaseStorage.getInstance()
            .getReference(uploadref)
        storageReference.putFile(viduri).addOnCompleteListener {
            getVidLink(uploadref)
            //Log.w(TAG, it.result.storage.downloadUrl.toString())
            Toast.makeText(requireContext(), "uploaded video to firebase", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initialize UI. Preview and Capture actions are configured in this function.
     * Note that preview and capture are both initialized either by UI or CameraX callbacks
     * (only except the very 1st time upon entering to this fragment in onViewCreated()
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        // React to user touching the capture button
        fragmentCameraBinding.captureButton.setOnClickListener {
            if (!this::recordingState.isInitialized || recordingState is VideoRecordEvent.Finalize) {
                fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_pause)
                fragmentCameraBinding.stopButton.visibility = View.VISIBLE
                enableUI(false)
                startRecording()
            }
        }
        fragmentCameraBinding.stopButton.setOnClickListener {
            // stopping
            fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
            if (activeRecording == null || recordingState is VideoRecordEvent.Finalize) {
                return@setOnClickListener
            }

            val recording = activeRecording
            if (recording != null) {
                recording.stop()
                activeRecording = null
            }
            fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_start)
        }
        fragmentCameraBinding.captureStatus.text = getString(R.string.Idle)
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getName()
                    else event.getName()
        when (event) {
                is VideoRecordEvent.Status -> {
                    // placeholder: we update the UI with new status after this when() block,
                    // nothing needs to do here.
                }
                is VideoRecordEvent.Start -> {
                    fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_pause)
                    fragmentCameraBinding.captureButton.isEnabled = true
                    fragmentCameraBinding.stopButton.isEnabled = true
                }
                is VideoRecordEvent.Finalize-> {
                    fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_start)
                    fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    Log.e(TAG, "Error(Unknown Event) from Recorder")
                    return
                }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        fragmentCameraBinding.captureStatus.text=text
        Log.i(TAG, "recording event: $text")
    }

    /**
     * Enable/disable UI:
     *    User could select the capture parameters when recording is not in session
     *    Once recording is started, need to disable able UI to avoid conflict.
     */
    private fun enableUI(enable: Boolean) {
        fragmentCameraBinding.captureButton.isEnabled = enable
        fragmentCameraBinding.stopButton.isEnabled = enable
    }

    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    private fun resetUIandState() {
        lifecycleScope.launch(Dispatchers.Main) {
            enableUI(true)
            fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_start)
            fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
            fragmentCameraBinding.captureStatus.text = "Capture system reset due to binding failure," +
                "\nyou could retry with new settings."
            bindCaptureUsecase()
        }
    }

    // System function implementations
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    // system functions starts
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.Main) {
            if(enumerationDeferred != null ) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            initializeUI()
            val auth = Firebase.auth
            val crnt = auth.currentUser
            fragmentCameraBinding.textView2?.text = crnt?.displayName
            getUserID(crnt?.uid, crnt)
            bindCaptureUsecase()
        }
    }

    fun getUserID(id: String?, usr: FirebaseUser?) {
        val postUrl = "https://api.popcornmeet.com/v1/users/$id/authenticate"
        val requestQueue = Volley.newRequestQueue(context)
        val postData = JSONObject()
        try {
            postData.put("authType", "android")
            postData.put("email", usr?.email)
            postData.put("photoURL", usr?.photoUrl)
            postData.put("displayName", usr?.displayName)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val reqbod : String = postData.toString()
        val jsonObjectRequest: JsonObjectRequest = object : JsonObjectRequest(
            Method.POST, postUrl, null,
            Response.Listener { response -> userID = response.getString("id")},
            Response.ErrorListener { error -> error.printStackTrace() }) {
            @Throws(AuthFailureError::class)
            override fun getHeaders(): Map<String, String> {
                val headrs =
                    mapOf("x-api-key" to "7dc6cdc72d4ffe57966086235a91c6ee59dffa1f578c7647aa20eb3de5f0f0b7",
                        "Content-Type" to "application/json")
                return headrs
            }

            override fun getBodyContentType(): String {
                return "application/json; charset=utf-8"
            }

            @Throws(AuthFailureError::class)
            override fun getBody(): ByteArray? {
                return try {
                    reqbod.toByteArray(charset("utf-8"))
                } catch (uee : UnsupportedEncodingException) {
                    null
                }
            }
        }
        requestQueue.add(jsonObjectRequest)
    }

    fun getVidLink(path : String) {
        val postUrl = "https://api.popcornmeet.com/v1/messages"
        val requestQueue = Volley.newRequestQueue(context)
        val postData = JSONObject()
        try {
            postData.put("path", path)
            postData.put("mode", "camera")
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val reqbod : String = postData.toString()
        val jsonObjectRequest: JsonObjectRequest = object : JsonObjectRequest(
            Method.POST, postUrl, null,
            Response.Listener { response -> Log.w(TAG, response.getString("watchUrl"))
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, response.getString("watchUrl"))
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                startActivity(shareIntent)},
            Response.ErrorListener { error -> error.printStackTrace() }) {
            @Throws(AuthFailureError::class)
            override fun getHeaders(): Map<String, String> {
                val headrs =
                    mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $userID")
                return headrs
            }

            override fun getBodyContentType(): String {
                return "application/json; charset=utf-8"
            }

            @Throws(AuthFailureError::class)
            override fun getBody(): ByteArray? {
                return try {
                    reqbod.toByteArray(charset("utf-8"))
                } catch (uee : UnsupportedEncodingException) {
                    null
                }
            }
        }
        requestQueue.add(jsonObjectRequest)
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        const val DEFAULT_ASPECT_RATIO = AspectRatio.RATIO_16_9
        val TAG:String = CameraFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}

/**
 * A helper extended function to get the name(string) for the VideoRecordEvent.
 */
fun VideoRecordEvent.getName() : String {
    return when (this) {
        is VideoRecordEvent.Status -> "Status"
        is VideoRecordEvent.Start -> "Started"
        is VideoRecordEvent.Finalize-> "Finalized"
        is VideoRecordEvent.Pause -> "Paused"
        is VideoRecordEvent.Resume -> "Resumed"
        else -> "Error(Unknown)"
    }
}