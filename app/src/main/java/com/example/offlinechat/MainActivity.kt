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
                connected.add(endpointId)
                mySymbol = if (pendingRequests.contains(endpointId)) "O" else "X"
                myTurn = mySymbol == "X"
                addSystem("Підключено: ${peers[endpointId] ?: "?"}")
            } else {
                addSystem("Не вдалося підключитися")
            }
            pendingRequests.remove(endpointId)
            refreshPeers()
            updateGameInfo()
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            addSystem("Відключено: ${peers[endpointId] ?: "?"}")
            refreshPeers()
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            peers[endpointId] = info.endpointName
            refreshPeers()
        }

        override fun onEndpointLost(endpointId: String) {
            peers.remove(endpointId)
            refreshPeers()
        }
    }

    // ---------- Nearby: отримання даних ----------

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val sender = peers[endpointId] ?: "?"
            runOnUiThread {
                when (payload.type) {
                    Payload.Type.BYTES -> handleMessage(sender, String(payload.asBytes() ?: return@runOnUiThread))
                    Payload.Type.FILE -> {
                        val src = payload.asFile()?.asJavaFile() ?: return@runOnUiThread
                        val dst = File(filesDir, "img_${System.currentTimeMillis()}.jpg")
                        src.renameTo(dst)
                        addMessage("$sender:")
                        addImage(dst.path)
                    }
                    else -> {}
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleMessage(sender: String, msg: String) {
        val type = msg.substringBefore("|")
        val body = msg.substringAfter("|", "")
        when (type) {
            "T" -> addMessage("$sender: $body")
            "P" -> addMessage("$sender надсилає фото...")
            "G" -> {
                val index = body.toIntOrNull() ?: return
                if (index in 0..8 && board[index].isEmpty()) {
                    board[index] = if (mySymbol == "X") "O" else "X"
                    myTurn = true
                    redrawBoard()
                }
            }
            "R" -> resetGame(send = false)
        }
    }

    // ---------- Надсилання ----------

    private fun sendAll(msg: String) {
        connected.forEach { connections.sendPayload(it, Payload.fromBytes(msg.toByteArray())) }
    }

    private fun sendText(text: String) {
        if (text.isBlank()) return
        if (connected.isEmpty()) { addSystem("Спершу підключіться до когось"); return }
        sendAll("T|$text")
        addMessage("Я: $text")
    }

    private fun sendPhoto(uri: android.net.Uri) {
        if (connected.isEmpty()) { addSystem("Спершу підключіться до когось"); return }
        val file = File(cacheDir, "out_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { src -> file.outputStream().use { src.copyTo(it) } }
        sendAll("P|$myName")
        connected.forEach { connections.sendPayload(it, Payload.fromFile(file)) }
        addMessage("Я:")
        addImage(file.path)
    }

    // ---------- Гра ----------

    private fun onCellTap(i: Int) {
        if (gameOver || !myTurn || board[i].isNotEmpty() || connected.isEmpty()) return
        board[i] = mySymbol
        myTurn = false
        sendAll("G|$i")
        redrawBoard()
    }

    private fun resetGame(send: Boolean) {
        board.fill("")
        gameOver = false
        myTurn = mySymbol == "X"
        if (send) sendAll("R|")
        redrawBoard()
    }

    private fun redrawBoard() {
        for (i in 0..8) cells[i].text = board[i]
        val winner = winnerOf()
        gameOver = winner != null || board.none { it.isEmpty() }
        updateGameInfo(winner)
    }

    private fun winnerOf(): String? {
        val lines = listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
        for (l in lines) {
            val (a, b, c) = l
            if (board[a].isNotEmpty() && board[a] == board[b] && board[b] == board[c]) return board[a]
        }
        return null
    }

    private fun updateGameInfo(winner: String? = winnerOf()) {
        gameInfo.text = when {
            connected.isEmpty() -> "Гра доступна після підключення"
            winner != null -> if (winner == mySymbol) "Ви перемогли!" else "Переміг суперник"
            gameOver -> "Нічия"
            myTurn -> "Ваш хід (${mySymbol})"
            else -> "Чекаємо ходу суперника"
        }
    }

    // ---------- Інтерфейс ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val nameInput = EditText(this).apply { hint = "Ваше ім'я"; setText(myName) }
        val startBtn = Button(this).apply { text = "Почати пошук і прийом" }
        status = TextView(this).apply { text = "Не підключено" }
        peerBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        gameInfo = TextView(this).apply { gravity = Gravity.CENTER }
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 3 }
        for (i in 0..8) {
            val b = Button(this).apply {
                text = ""
                textSize = 22f
                setOnClickListener { onCellTap(i) }
            }
            cells.add(b)
            grid.addView(b, GridLayout.LayoutParams().apply { width = 200; height = 200 })
        }
        val resetBtn = Button(this).apply { text = "Нова гра"; setOnClickListener { resetGame(send = true) } }

        chatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { addView(chatBox) }

        val emojiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("😀", "❤️", "👍", "😂", "😢").forEach { e ->
            emojiRow.addView(Button(this).apply { text = e; setOnClickListener { sendText(e) } })
        }

        input = EditText(this).apply { hint = "Повідомлення" }
        val sendRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendRow.addView(Button(this).apply {
            text = "Надіслати"
            setOnClickListener { sendText(input.text.toString()); input.setText("") }
        })
        val photoBtn = Button(this).apply { text = "Фото"; setOnClickListener { pickImage.launch("image/*") } }

        root.addView(nameInput)
        root.addView(startBtn)
        root.addView(status)
        root.addView(peerBox)
        root.addView(gameInfo)
        root.addView(grid)
        root.addView(resetBtn)
        root.addView(chatScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(emojiRow)
        root.addView(sendRow)
        root.addView(photoBtn)
        setContentView(root)

        updateGameInfo()

        startBtn.setOnClickListener {
            myName = nameInput.text.toString().ifBlank { "Я" }
            if (!hasPermissions()) {
                ActivityCompat.requestPermissions(this, requiredPermissions(), 1)
            } else {
                startNearby()
            }
        }
    }

    private fun startNearby() {
        val strategy = Strategy.P2P_CLUSTER
        connections.startAdvertising(
            myName, serviceId, connectionCb,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
        connections.startDiscovery(
            serviceId, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
        status.text = "Шукаємо людей поруч..."
    }

    private fun refreshPeers() {
        peerBox.removeAllViews()
        if (peers.isEmpty()) {
            peerBox.addView(TextView(this).apply { text = "Поруч нікого не знайдено" })
        }
        peers.forEach { (id, name) ->
            val isConnected = connected.contains(id)
            peerBox.addView(Button(this).apply {
                text = if (isConnected) "$name (підключено)" else "Підключитися до $name"
                isEnabled = !isConnected
                setOnClickListener {
                    pendingRequests.add(id)
                    connections.requestConnection(myName, id, connectionCb)
                }
            })
        }
        status.text = "Підключено: ${connected.size}"
    }

    private fun addMessage(text: String) {
        chatBox.addView(TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, 8, 0, 8) })
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addSystem(text: String) {
        chatBox.addView(TextView(this).apply {
            this.text = "— $text —"
            gravity = Gravity.CENTER
            textSize = 13f
        })
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addImage(path: String) {
        val bmp = BitmapFactory.decodeFile(path) ?: return
        chatBox.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            maxHeight = 400
            setPadding(0, 8, 0, 8)
        })
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- Дозволи ----------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.NEARBY_WIFI_DEVICES
        return list.toTypedArray()
    }

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && hasPermissions()) startNearby()
        else addSystem("Потрібні дозволи, щоб знаходити людей поруч")
    }

    override fun onDestroy() {
        connections.stopAllEndpoints()
        connections.stopAdvertising()
        connections.stopDiscovery()
        super.onDestroy()
    }
}
