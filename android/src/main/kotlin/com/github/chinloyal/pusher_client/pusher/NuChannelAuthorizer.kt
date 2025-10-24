package com.github.chinloyal.pusher_client.pusher

import android.util.Log
import com.pusher.client.Authorizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Custom Authorizer that handles asynchronous authentication
 * by blocking the thread using CountDownLatch until the HTTP response is received.
 * 
 * This is necessary because pusher-java-client 2.4.0 requires synchronous authorize()
 * but our authentication flow is asynchronous (HTTP request).
 * 
 * Related issue: https://github.com/pusher/pusher-websocket-java/issues/340
 */
class NuChannelAuthorizer(
    private val authEndpoint: String,
    private val headers: Map<String, String>
) : Authorizer {
    
    private val client = OkHttpClient()
    
    override fun authorize(channelName: String, socketId: String): String {
        val latch = CountDownLatch(1)
        var authResult: String? = null
        var error: Exception? = null
        
        // Create form-encoded request body
        val formBody = "socket_id=$socketId&channel_name=$channelName"
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val requestBody = formBody.toRequestBody(mediaType)
        
        // Build request with headers
        val requestBuilder = Request.Builder()
            .url(authEndpoint)
            .post(requestBody)
        
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        
        val request = requestBuilder.build()
        
        // Execute async request
        Thread {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    authResult = response.body?.string()
                } else {
                    error = Exception("Auth failed with status ${response.code}: ${response.body?.string()}")
                }
            } catch (e: Exception) {
                error = e
                Log.e(LOG_TAG, "Authorization error", e)
            } finally {
                latch.countDown()
            }
        }.start()
        
        // BLOCK the thread and wait for response (max 10 seconds)
        val timedOut = !latch.await(10, TimeUnit.SECONDS)
        
        if (timedOut) {
            throw com.pusher.client.AuthorizationFailureException("Authorization timeout after 10 seconds")
        }
        
        if (error != null) {
            throw com.pusher.client.AuthorizationFailureException(error)
        }
        
        return authResult ?: throw com.pusher.client.AuthorizationFailureException("No auth data received")
    }
}
