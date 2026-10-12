package com.spaceaudits.stardeck

import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** RSA-SHA1 PKCS#1 v1.5 signature of the scope's challenge string, base64. */
fun sign(keyPem: String, challenge: String): String {
    val der = Base64.getMimeDecoder().decode(keyPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    val sig = Signature.getInstance("SHA1withRSA").apply { initSign(key); update(challenge.toByteArray()) }
    return Base64.getEncoder().encodeToString(sig.sign())
}

/** JSON-RPC control channel (TCP 4700). One request at a time. */
class Scope(host: String, keyPem: String) : Closeable {
    private val sock = Socket().apply {
        connect(InetSocketAddress(host, 4700), 5000)
        soTimeout = 10000
        tcpNoDelay = true
    }
    private val reader = sock.getInputStream().bufferedReader()
    private val out = sock.getOutputStream()
    private var id = 0

    init {
        val r = call("get_verify_str")
        if (r.optInt("code") != 103) { // 103: old firmware, no handshake
            val challenge = r.getJSONObject("result").getString("str")
            val v = call("verify_client", JSONObject().put("sign", sign(keyPem, challenge)).put("data", challenge))
            if (v.optInt("code", -1) != 0) throw IOException("verify_client failed: $v")
        }
    }

    @Synchronized
    fun call(method: String, params: Any? = null): JSONObject {
        id++
        val msg = JSONObject().put("id", id).put("method", method)
        if (params != null) msg.put("params", params)
        out.write("$msg\r\n".toByteArray())
        out.flush()
        while (true) { // skip unsolicited event lines
            val line = reader.readLine() ?: throw IOException("scope closed the connection")
            val v = try { JSONObject(line) } catch (e: JSONException) { continue }
            if (v.optInt("id", -1) == id) return v
        }
    }

    override fun close() = sock.close()
}
