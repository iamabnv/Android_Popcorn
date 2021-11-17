package com.example.android.camerax.video

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.android.volley.Request
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

import com.android.volley.toolbox.JsonObjectRequest

import com.android.volley.toolbox.Volley

import com.android.volley.Response
import org.json.JSONException
import org.json.JSONObject
import com.android.volley.AuthFailureError
import com.google.firebase.auth.FirebaseUser
import java.io.UnsupportedEncodingException


class LoginFragment : Fragment() {

    private lateinit var auth: FirebaseAuth

    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var loginView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("165757444706-37s3mb6hfj1594mq5gfb0f6sq8klsrp4.apps.googleusercontent.com")
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(activity, gso)

        auth = Firebase.auth
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onStart() {
        super.onStart()
        val crntUser = auth.currentUser
        if (crntUser != null) {
            //Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                //LoginFragmentDirections.actionLoginFragmentToCameraFragment()
            //
            //volleyGet()
            volleyPost(crntUser.uid, crntUser)
        }
        loginView.findViewById<Button>(R.id.Login_SignIn).setOnClickListener {
            signIn()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        //_fragmentLoginBinding = FragmentLoginBinding.inflate(inflater, container, false)
        loginView = inflater.inflate(R.layout.fragment_login, container, false)
        return loginView
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        activity?.let {
            auth.signInWithCredential(credential)
                .addOnCompleteListener(it) { task ->
                    if (task.isSuccessful) {
                        // Sign in success, update UI with the signed-in user's information
                        Log.d(TAG, "signInWithCredential:success")
                        //Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                          //  LoginFragmentDirections.actionLoginFragmentToCameraFragment()
                        //)
                        //volleyGet()
                        volleyPost(auth.currentUser?.uid, auth.currentUser)
                    } else {
                        // If sign in fails, display a message to the user.
                        Log.w(TAG, "signInWithCredential:failure", task.exception)
                    }
                }
        }
    }

    fun volleyGet() {
        val url = "https://reqres.in/api/users?page=2"
        val requestQueue = Volley.newRequestQueue(context)
        val jsonObjectRequest = JsonObjectRequest(Request.Method.GET, url, null, {
            response ->
            try {
                var jsonArray = response.getJSONArray("data")
                for (i in 0 until jsonArray.length()) {
                    var jsonObj = jsonArray.getJSONObject(i)
                    Log.w(TAG, jsonObj.getString("email"))
                }
            } catch (e : JSONException) {
                e.printStackTrace()
            }
        }, {
            error ->  error.printStackTrace()
        })
        requestQueue.add(jsonObjectRequest)
    }

    fun volleyhost(id : String) {
        val url = "https://api.popcornmeet.com/v1/users/$id/authenticate"
        val requestQueue = Volley.newRequestQueue(context)
        val jsonObjectRequest = JsonObjectRequest(Request.Method.POST, url, null, { 
            response ->  Log.w(TAG, response.toString())
        }, { 
            error ->  error.printStackTrace()

        })
        requestQueue.add(jsonObjectRequest)
    }

    fun volleyPost(id: String?, usr: FirebaseUser?) {
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
            Response.Listener { response -> Log.w(TAG, response.toString()) },
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


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Google Sign In was successful, authenticate with Firebase
                val account = task.getResult(ApiException::class.java)!!
                Log.d(TAG, "firebaseAuthWithGoogle:" + account.id)
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                // Google Sign In failed, update UI appropriately
                Log.w(TAG, "Google sign in failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "GoogleActivity"
        private const val RC_SIGN_IN = 9001
    }

}