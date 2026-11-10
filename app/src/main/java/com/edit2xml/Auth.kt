package com.edit2xml

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Auth {
    /** path: "signUp" or "signInWithPassword". Real Firebase Auth REST API. Returns the account email. */
    fun call(path: String, email: String, pass: String): Result<String> = runCatching {
        val key = BuildConfig.FIREBASE_API_KEY
        if (key.isEmpty()) error("NOKEY")
        val c = URL("https://identitytoolkit.googleapis.com/v1/accounts:$path?key=$key")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("email", email).put("password", pass).put("returnSecureToken", true).toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) {
            val m = JSONObject(text).optJSONObject("error")?.optString("message") ?: "ERROR"
            error(m.substringBefore(" "))
        }
        JSONObject(text).getString("email")
    }
}
