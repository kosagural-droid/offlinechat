package com.example.offlinechat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private val serviceId = "com.example.offlinechat"
    private val connections by lazy { Nearby.getConnectionsClient(this) }

    private var myName = "Я"
    private val peers = mutableMapOf<String, String>()
    private val connected = mutableSetOf<String>()
    private val pendingRequests = mutableSetOf<String>()

    // Гра
    private val board = Array(9) { "" }
    private var mySymbol = "X"
    private var myTurn = false
    private var gameOver = false
    private val cells = mutableListOf<Button>()

    private lateinit var status: TextView
    private lateinit var peerBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var gameInfo: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendPhoto(it) }
    }

    // ---------- Nearby: з'єднання ----------

    private val connectionCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Запит на з'єднання від ${info.endpointName}")
                .setMessage("Код підтвердження: ${info.authenticationDigits}\n\nПеревірте, що код однаковий на обох телефонах.")
                .setPositiveButton("Підключити") { _, _ ->
                    connections.acceptConnection(endpointId, payloadCallback)
                }
                .setNegativeButton("Відхилити") { _, _ ->
                    connections.rejectConnection(endpointId)
                }
                .setCancelable(false)
                .show()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connec
