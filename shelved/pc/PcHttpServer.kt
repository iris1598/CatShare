package moe.reimu.catshare.pc

import android.content.Context
import android.util.Log
import android.util.Base64
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import moe.reimu.catshare.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec

/**
 * HTTPS API 1.0 server used when the phone is the sender for the Xiaomi PC
 * app (legacy MiDrop "PC mode").
 *
 * The PC connects to this server (port 9999) from the Wi-Fi hotspot created by
 * the phone and pulls the shared files:
 *   GET  /api/1.0/fileinfo?user=&task=
 *   GET  /api/1.0/file?user=&task=&id=
 *   POST /api/1.0/fileverify?user=&task=
 *   POST /api/1.0/report?user=&task=
 *   POST /api/1.0/taskcomplete?user=&task=
 */
class PcHttpServer(
    private val context: Context,
    private val session: PcSendSession,
) {
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    var port: Int = PcProtocol.HTTP_PORT
        private set

    fun start(): Boolean {
        return try {
            val keyStore = buildKeyStore(context)
                ?: throw IllegalStateException("cannot build MiDrop keystore")
            val engine = embeddedServer(Netty, configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "pc",
                    keyStorePassword = { KEYSTORE_PASSWORD.toCharArray() },
                    privateKeyPassword = { KEYSTORE_PASSWORD.toCharArray() },
                ) {
                    host = "0.0.0.0"
                    port = PcProtocol.HTTP_PORT
                }
                enableHttp2 = false
            }) {
                routing {
                    get("/api/1.0/fileinfo") {
                        handleFileInfo(call)
                    }
                    get("/api/1.0/file") {
                        handleFile(call)
                    }
                    post("/api/1.0/fileverify") {
                        handleFileVerify(call)
                    }
                    post("/api/1.0/report") {
                        handleReport(call)
                    }
                    post("/api/1.0/taskcomplete") {
                        handleTaskComplete(call)
                    }
                    get("/api/1.0/{tail...}") {
                        call.respondText(
                            errorJson(1, "HTTP method error"),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    }
                    post("/api/1.0/{tail...}") {
                        call.respondText(
                            errorJson(1, "HTTP method error"),
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                    }
                }
            }
            engine.start(wait = false)
            server = engine
            port = PcProtocol.HTTP_PORT
            Log.i(TAG, "PC HTTPS server started on $port")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start PC HTTPS server", e)
            false
        }
    }

    fun stop() {
        try {
            server?.stop(500, 1500)
        } catch (e: Throwable) {
            Log.e(TAG, "stop server failed", e)
        }
        server = null
    }

    private suspend fun handleFileInfo(call: io.ktor.server.application.ApplicationCall) {
        Log.i(TAG, "fileinfo request: ${call.request.queryParameters}")
        val files = JSONArray()
        session.files.forEachIndexed { index, file ->
            files.put(
                JSONObject()
                    .put("name", file.name)
                    .put("size", file.size)
                    .put("id", file.id)
                    .put("md5", file.md5 ?: "")
                    .put("type", file.mimeTypeCode)
                    .put("path", "")
            )
        }
        val body = JSONObject()
            .put("status", 0)
            .put("version", "1")
            .put("data", files)
        call.respondText(body.toString(), ContentType.Application.Json)
    }

    private suspend fun handleFile(call: io.ktor.server.application.ApplicationCall) {
        val params = call.request.queryParameters
        val id = params["id"] ?: ""
        val file = session.findFile(id)
        if (file == null) {
            call.respondText(errorJson(6, "file not found"), ContentType.Application.Json, HttpStatusCode.NotFound)
            return
        }
        val totalSize = file.file.length()
        var start = 0L
        var end = totalSize - 1
        val range = call.request.headers["Range"]
        if (range != null) {
            val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
            if (m != null) {
                start = m.groupValues[1].toLongOrNull() ?: 0L
                val last = m.groupValues[2].toLongOrNull()
                if (last != null) end = last.coerceAtMost(totalSize - 1)
            }
        }
        val length = end - start + 1
        call.response.header(
            "content-disposition",
            "attachment;filename=${file.name}"
        )
        call.response.header("Content-Length", length.toString())
        call.respondOutputStream(
            contentType = ContentType.Application.OctetStream,
            status = HttpStatusCode.OK,
            contentLength = length,
        ) {
            file.file.inputStream().use { input ->
                input.skip(start)
                copyLimited(input, this, length) { read -> session.onBytes(read) }
            }
        }
        session.onFileServed(file)
    }

    private suspend fun handleFileVerify(call: io.ktor.server.application.ApplicationCall) {
        val body = runCatching { call.receiveText() }.getOrNull() ?: ""
        Log.i(TAG, "fileverify body=$body")
        val (ok, reason) = session.verifyFile(body)
        val resp = if (ok) {
            JSONObject().put("status", 0).put("data", JSONObject().put("reason", ""))
        } else {
            JSONObject().put("status", 9).put("data", JSONObject().put("reason", reason))
        }
        call.respondText(resp.toString(), ContentType.Application.Json)
    }

    private suspend fun handleReport(call: io.ktor.server.application.ApplicationCall) {
        val body = runCatching { call.receiveText() }.getOrNull() ?: ""
        Log.i(TAG, "report body=$body")
        session.onReport(body)
        val resp = JSONObject()
            .put("status", 0)
            .put("data", JSONObject().put("action", 0))
        call.respondText(resp.toString(), ContentType.Application.Json)
    }

    private suspend fun handleTaskComplete(call: io.ktor.server.application.ApplicationCall) {
        val body = runCatching { call.receiveText() }.getOrNull() ?: ""
        Log.i(TAG, "taskcomplete body=$body")
        session.onTaskComplete(body)
        val resp = JSONObject()
            .put("status", 0)
            .put("data", JSONObject().put("action", 0))
        call.respondText(resp.toString(), ContentType.Application.Json)
    }

    private fun errorJson(status: Int, reason: String): String =
        JSONObject().put("status", status)
            .put("data", JSONObject().put("reason", reason))
            .toString()

    private fun copyLimited(
        input: InputStream,
        output: java.io.OutputStream,
        limit: Long,
        onRead: (Int) -> Unit,
    ) {
        val buffer = ByteArray(256 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
            onRead(read)
        }
        output.flush()
    }

    companion object {
        private const val TAG = "CatSharePc"
        private const val KEYSTORE_PASSWORD = "ih9gqpx"

        /**
         * Builds an in-memory PKCS12 keystore from the static MiDrop RSA key/cert
         * used by both the Xiaomi phone app and the PC app.
         */
        fun buildKeyStore(context: Context): KeyStore? {
            return try {
                val keyPem = context.resources.openRawResource(R.raw.mishare_key)
                    .bufferedReader().readText()
                val certPem = context.resources.openRawResource(R.raw.mishare_cert)
                    .bufferedReader().readText()

                val keyDer = pemToDer(keyPem)
                val certDer = pemToDer(certPem)
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyDer))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certDer.inputStream())

                val ks = KeyStore.getInstance("PKCS12")
                ks.load(null, null)
                ks.setKeyEntry("pc", key, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
                ks
            } catch (e: Throwable) {
                Log.e(TAG, "buildKeyStore failed", e)
                null
            }
        }

        private fun pemToDer(pem: String): ByteArray {
            val body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace(Regex("\\s"), "")
            return Base64.decode(body, Base64.DEFAULT)
        }

        fun md5(file: File): String? {
            return try {
                val digest = java.security.MessageDigest.getInstance("MD5")
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Throwable) {
                null
            }
        }
    }
}
