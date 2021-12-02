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
import android.content.Intent
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.camera.core.AspectRatio
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.android.camerax.video.R
import com.example.android.camerax.video.databinding.FragmentCameraBinding
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.navigation.fragment.navArgs
import com.android.volley.AuthFailureError
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
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

    private val args : CameraFragmentArgs by navArgs()

    // UI with ViewBinding
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var userID : String

    private lateinit var userPhotouri : Uri

    private lateinit var audioMediaRecorder : MediaRecorder

    private var finalRecordState : Boolean = false

    private var isAudio : Boolean = false

    private lateinit var audioOutputFile : File

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
                resetUIAndState()
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
        val fileOutput = FileOutputOptions.Builder(file).build()
        // configure Recorder and Start recording to the mediaStoreOutput.
        activeRecording =
               videoCapture.output.prepareRecording(requireActivity(), fileOutput)
               .withEventListener(
                    mainThreadExecutor,
                    captureListener
               )
               .withAudioEnabled()
               .start()

        Log.i(TAG, "Recording started")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun audioStartRecording() {
        val name = "AudioRecording" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + "-raw.mp3"
        audioOutputFile = File(requireContext().getExternalFilesDir(null), name)
        audioMediaRecorder.setOutputFile(audioOutputFile)
        audioMediaRecorder.prepare()
        audioMediaRecorder.start()
    }

    private fun audioStopRecording() {
        audioMediaRecorder.stop()
        audioMediaRecorder.release()
        uploadVideo(audioOutputFile.toUri(), true)
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
            uploadVideo(event.outputResults.outputUri, false)
        }
    }

    private fun uploadVideo(videoURI : Uri, isAudio : Boolean) {
        val uploadReference: String = if (isAudio) {
            "videos/"+userID+"/"+System.currentTimeMillis()+"-raw.mp3"
        } else {
            "videos/"+userID+"/"+System.currentTimeMillis()+"-raw.mp4"
        }
        val storageReference = FirebaseStorage.getInstance()
            .getReference(uploadReference)
        storageReference.putFile(videoURI).addOnCompleteListener {
            getVidLink(uploadReference, isAudio)
            Toast.makeText(requireContext(),
                "uploaded video to firebase", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initialize UI. Preview and Capture actions are configured in this function.
     * Note that preview and capture are both initialized either by UI or CameraX callbacks
     * (only except the very 1st time upon entering to this fragment in onViewCreated()
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        fragmentCameraBinding.captureButton.setOnClickListener {
            finalRecordState = isAudio
            fragmentCameraBinding.cameraVideo?.isEnabled = false
            fragmentCameraBinding.cameraAudio?.isEnabled = false
            if (!finalRecordState) {
                if (!this::recordingState.isInitialized || recordingState is VideoRecordEvent.Finalize) {
                    fragmentCameraBinding.stopButton.visibility = View.VISIBLE
                    fragmentCameraBinding.captureButton.visibility = View.INVISIBLE
                    startRecording()
                }
            }
            else {
                fragmentCameraBinding.stopButton.visibility = View.VISIBLE
                fragmentCameraBinding.captureButton.visibility = View.INVISIBLE
                audioStartRecording()
            }
        }
        fragmentCameraBinding.stopButton.setOnClickListener {
            // stopping
            fragmentCameraBinding.captureButton.visibility = View.VISIBLE
            fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
            fragmentCameraBinding.cameraVideo?.isEnabled = true
            fragmentCameraBinding.cameraAudio?.isEnabled = true
            if (!finalRecordState) {
                if (activeRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }
                val recording = activeRecording
                if (recording != null) {
                    recording.stop()
                    activeRecording = null
                }
            }
            else {
                audioStopRecording()
            }
        }

        audioMediaRecorder = MediaRecorder()
        audioMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        audioMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        audioMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

        fragmentCameraBinding.cameraVideo?.setOnClickListener {
            fragmentCameraBinding.imageView6?.setImageResource(R.drawable.outline_videocam_white_24)
            fragmentCameraBinding.cameraVideo!!.setTextColor(Color.WHITE)
            val colorr = resources.getColor(R.color.unselectedText)
            fragmentCameraBinding.cameraAudio!!.setTextColor(colorr)
            fragmentCameraBinding.previewcontainer?.visibility = View.VISIBLE
            fragmentCameraBinding.audiocontainer?.visibility = View.INVISIBLE
            isAudio = false
        }

        fragmentCameraBinding.cameraAudio?.setOnClickListener {
            fragmentCameraBinding.imageView6?.setImageResource(R.drawable.outline_mic_white_24)
            val colorr = resources.getColor(R.color.unselectedText)
            fragmentCameraBinding.cameraVideo!!.setTextColor(colorr)
            fragmentCameraBinding.cameraAudio!!.setTextColor(Color.WHITE)
            fragmentCameraBinding.previewcontainer?.visibility = View.INVISIBLE
            fragmentCameraBinding.audiocontainer?.visibility = View.VISIBLE
            isAudio = true
        }
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
                    //fragmentCameraBinding.captureButton.setImageResource(R.drawable.ic_pause)
                    fragmentCameraBinding.captureButton.visibility = View.INVISIBLE
                    fragmentCameraBinding.stopButton.isEnabled = true
                    fragmentCameraBinding.previewcontainer?.setCardBackgroundColor(resources.getColor(R.color.redRecording))
                }
                is VideoRecordEvent.Finalize-> {
                    fragmentCameraBinding.previewcontainer?.setCardBackgroundColor(resources.getColor(R.color.yellowRecording))
                    fragmentCameraBinding.captureButton.visibility = View.VISIBLE
                    fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    Log.e(TAG, "Error(Unknown Event) from Recorder")
                    return
                }
        }
        val stats = event.recordingStats
        val min = java.util.concurrent.TimeUnit.NANOSECONDS.toMinutes(stats.recordedDurationNanos)
        val stmin = if (min < 1) {"0"} else {"$min"}
        val sec = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        val stsec = if (sec < 10) {"0${sec}"} else {"$sec"}
        fragmentCameraBinding.textView2?.text = "${stmin}:${stsec}"
    }

    /**
     * ResetUI (restart):
     *    in case binding failed, let's give it another change for re-try. In future cases
     *    we might fail and user get notified on the status
     */
    @SuppressLint("SetTextI18n")
    private fun resetUIAndState() {
        lifecycleScope.launch(Dispatchers.Main) {
            fragmentCameraBinding.captureButton.visibility = View.VISIBLE
            fragmentCameraBinding.stopButton.visibility = View.INVISIBLE
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
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.Main) {
            if(enumerationDeferred != null ) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            val crntUser = FirebaseAuth.getInstance().currentUser
            if (crntUser != null) {
                userPhotouri = crntUser.photoUrl!!
                Glide.with(requireContext())
                    .load(userPhotouri)
                    .into(fragmentCameraBinding.imageView!!)
                //fragmentCameraBinding.imageView?.setImageURI(userPhotouri)
            }
            initializeUI()
            val auth = Firebase.auth
            val currentUser = auth.currentUser
            //fragmentCameraBinding.textView2?.text = currentUser?.displayName
            userID = args.userID
            bindCaptureUsecase()
        }
    }

    private fun getVidLink(path: String, isAudio: Boolean) {
        val postUrl = "https://api.popcornmeet.com/v1/messages"
        val requestQueue = Volley.newRequestQueue(context)
        val postData = JSONObject()
        try {
            postData.put("path", path)
            if (isAudio) {
                postData.put("mode", "microphone")
            }
            else {
                postData.put("mode", "camera")
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        val requestBody : String = postData.toString()
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
                return mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $userID"
                )
            }

            override fun getBodyContentType(): String {
                return "application/json; charset=utf-8"
            }

            @Throws(AuthFailureError::class)
            override fun getBody(): ByteArray? {
                return try {
                    requestBody.toByteArray(charset("utf-8"))
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