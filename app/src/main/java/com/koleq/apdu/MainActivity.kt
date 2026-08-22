package com.koleq.apdu

import android.app.AlertDialog
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.text.Charsets.US_ASCII

private const val PREFS_NAME = "ApduPrefs"
private const val PREFS_KEY = "saved_presets"

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var logTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var apduInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var addPresetBtn: Button

    private var nfcAdapter: NfcAdapter? = null
    private var currentIsoDep: IsoDep? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class ApduPreset(val name: String, val hex: String) {
        override fun toString(): String = name
    }

    private val presetList = mutableListOf<ApduPreset>()
    private lateinit var spinnerAdapter: ArrayAdapter<ApduPreset>
    private var isSpinnerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logTextView = findViewById(R.id.logTextView)
        statusTextView = findViewById(R.id.statusTextView)
        apduInput = findViewById(R.id.apduInput)
        sendBtn = findViewById(R.id.sendBtn)
        presetSpinner = findViewById(R.id.presetSpinner)
        addPresetBtn = findViewById(R.id.addPresetBtn)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Load saved presets or load defaults if none exist
        loadPresets()

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presetList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = spinnerAdapter

        if (presetList.isNotEmpty()) {
            apduInput.setText(presetList[0].hex)
        }

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitialized && position < presetList.size) {
                    apduInput.setText(presetList[position].hex)
                }
                isSpinnerInitialized = true
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ISSUE 1 FIX: Long-clicking the spinner lets you delete the selected preset
        presetSpinner.setOnLongClickListener {
            val pos = presetSpinner.selectedItemPosition
            if (pos >= 0 && pos < presetList.size) {
                val removed = presetList[pos]

                // Keep at least one item or allow wiping all
                if (presetList.size <= 1) {
                    appendLog("Cannot delete the last remaining preset.\n")
                    return@setOnLongClickListener true
                }

                AlertDialog.Builder(this)
                    .setTitle("Delete Preset")
                    .setMessage("Do you want to delete '${removed.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        presetList.removeAt(pos)
                        spinnerAdapter.notifyDataSetChanged()
                        savePresets()
                        appendLog("--> Deleted preset: ${removed.name}\n")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        sendBtn.setOnClickListener {
            val hex = apduInput.text.toString().trim() //.replace(" ", "")
            if (hex.isNotEmpty()) {
                sendApduRealtime(hex)
            }
        }

        addPresetBtn.setOnClickListener {
            showAddPresetDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        val options = Bundle()
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
        nfcAdapter?.enableReaderMode(
            this, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            options
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        closeCardConnection()
    }

    private fun isPrintableAscii(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            // Check if it falls within standard readable ASCII range (space up to tilde) or common whitespace (newline/tab)
            if (v !in 32..126 && v != 10 && v != 13 && v != 9) {
                return false
            }
        }
        return true
    }

    private var hasCardSession = false

    override fun onTagDiscovered(tag: Tag?) {
        val isoDep = IsoDep.get(tag) ?: return
        currentIsoDep = isoDep

        try {
            isoDep.connect()
            isoDep.timeout = 5000
            hasCardSession = true // Flag we are live

            updateStatus(getString(R.string.status_card_connected_isodep))
            appendLog("=== Card Tapped & Connected ===\n")

            val hist = isoDep.historicalBytes
            if (hist != null && hist.isNotEmpty()) {
                if (isPrintableAscii(hist)) {
                    val histText = String(hist, US_ASCII)
                    appendLog("ATS: $histText\n")
                } else {
                    appendLog("ATS: ${bytesToHex(hist)}\n")
                }
            }
            appendLog("Session open. Ready for real-time commands.\n\n")

        } catch (e: Exception) {
            hasCardSession = false
            appendLog("Connection Error: ${e.message}\n")
            updateStatus(getString(R.string.status_connection_failed))
        }
    }

    val commandHeaders = mapOf(
        "cmd" to "00110000", // INS 0x11
        "counter_add" to "001201", // INS 0x12
        "counter_sub" to "001202", // INS 0x12
        "echo" to "00130000" // INS 0x13
    )

    private fun sendApduRealtime(hexApdu: String) {
        val isoDep = currentIsoDep

        if (!hasCardSession || isoDep == null) {
            appendLog("ERROR: No card connected. Tap card to phone first!\n")
            updateStatus(getString(R.string.status_no_card))
            return
        }

        Thread {
            appendLog("$hexApdu\n") // print the command to interpret
            var apduCommand = ""

            // interpreting nested commands
            if (hexApdu.contains("=")) {
                val hexSplit = hexApdu.split("=", limit = 2) // split command by =
                val command: String = commandHeaders[hexSplit[0]]!! // fill command from commandHeaders
                if (command.startsWith("0012") && hexSplit[1] != "") {
                    var number: Int? = null
                    try {
                        number = hexSplit[1].replace(" ", "").toInt()
                    } catch (_: NumberFormatException) {
                        appendLog("Err: Yeah bro numbers, learn them.\n")
                    }
                    if (number != null && number in -128..127) {
                        apduCommand = command + String.format("%02X", number and 0xFF)
                    }
                } else {
                    var strText = hexSplit[1]
                    if ((strText.startsWith("\"") && strText.endsWith("\"")) ||
                        (strText.startsWith("'") && strText.endsWith("'"))
                    ) {
                        strText = strText.substring(1, strText.length - 1)
                    }
                    val hexText = strText.toByteArray(US_ASCII)
                    apduCommand = bytesToHex(
                        hexToBytes(command) +
                                byteArrayOf(hexText.size.toByte()) + hexText
                    )
                }
            } else {
                apduCommand = hexApdu
            }

            if (apduCommand != "") {
                appendLog(">> $apduCommand\n")

                val response = try {
                    isoDep.transceive(hexToBytes(apduCommand))
                } catch (_: IOException) {
                    if (!isoDep.isConnected) {
                        isoDep.connect()
                    }
                    isoDep.transceive(hexToBytes(apduCommand))
                }

                // APDU RESPONSE
                if (response.size >= 2) {
                    val dataBytes = response.copyOfRange(0, response.size - 2)
                    val sw1 = response[response.size - 2]
                    val sw2 = response[response.size - 1]
                    val swHex = String.format("%02X%02X", sw1, sw2)

                    if (dataBytes.isNotEmpty()) {
                        // Match Python 'free' command check (Hex: 001100000466726565)
                        if (hexApdu.equals("001100000466726565", ignoreCase = true) && dataBytes.size >= 4) {
                            val freeEEPROM = ((dataBytes[0].toInt() and 0xFF) shl 8) or (dataBytes[1].toInt() and 0xFF)
                            val freeRAM = ((dataBytes[2].toInt() and 0xFF) shl 8) or (dataBytes[3].toInt() and 0xFF)
                            val freeEEPROMStr = if (freeEEPROM == 32767) ">= 32767 B" else "$freeEEPROM B"
                            appendLog("<< free EEPROM: $freeEEPROMStr, free RAM: $freeRAM B | SW: $swHex\n\n")
                        } else if (isPrintableAscii(dataBytes)) {
                            val textData = String(dataBytes, US_ASCII)
                            appendLog("<< $textData | SW: $swHex\n\n")
                        } else {
                            val dataHex = bytesToHex(dataBytes)
                            appendLog("<< $dataHex | SW: $swHex\n\n")
                        }
                    } else {
                        appendLog("<< SW: $swHex\n\n")
                    }
                } else {
                    appendLog("<< ${bytesToHex(response)}\n\n")
                }
            }
        }.start()
    }

    private fun closeCardConnection() {
        hasCardSession = false
        try {
            currentIsoDep?.close()
        } catch (_: Exception) {}
        currentIsoDep = null
        updateStatus(getString(R.string.status_card_disconnected))
    }

    private fun showAddPresetDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add APDU Preset")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val nameInput = EditText(this).apply {
            hint = "Preset Name (e.g. Read Record)"
        }
        val hexInput = EditText(this).apply {
            hint = "APDU Hex (e.g. 00B2010000)"
            setText(apduInput.text.toString())
        }

        layout.addView(nameInput)
        layout.addView(hexInput)
        builder.setView(layout)

        builder.setPositiveButton("Add") { _, _ ->
            val name = nameInput.text.toString().trim()
            val hex = hexInput.text.toString().trim().replace(" ", "")
            if (name.isNotEmpty() && hex.isNotEmpty()) {
                presetList.add(ApduPreset(name, hex))
                spinnerAdapter.notifyDataSetChanged()
                presetSpinner.setSelection(presetList.size - 1)
                savePresets() // ISSUE 2 FIX: Save immediately on add
                appendLog("--> Added preset: $name\n")
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun savePresets() {
        val jsonArray = JSONArray()
        for (preset in presetList) {
            val obj = JSONObject()
            obj.put("name", preset.name)
            obj.put("hex", preset.hex)
            jsonArray.put(obj)
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        prefs.edit {
            putString(PREFS_KEY, jsonArray.toString())
        }
    }

    private fun loadPresets() {
        presetList.clear()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val jsonString = prefs.getString(PREFS_KEY, null)

        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    presetList.add(ApduPreset(obj.getString("name"), obj.getString("hex")))
                }
            } catch (_: Exception) {}
        }

        // Fallback defaults if storage was empty, trailing zeros are not required
        if (presetList.isEmpty()) {
            presetList.add(ApduPreset("- custom -", ""))
            // select the applet I made called helloCard / helloApplet .koleq...1
            presetList.add(ApduPreset("select_helloApplet", "00A404000AF06B6F6C657100000001")) // full command
            // INS 0x10 hello
            presetList.add(ApduPreset("hello", "0010")) // full command
            // INS 0x11 ping or free
            presetList.add(ApduPreset("cmd_ping", "cmd=ping")) // interpreted command
            presetList.add(ApduPreset("cmd_free", "cmd=free")) // interpreted command
            // INS 0x12 add, sub, show, reset
            presetList.add(ApduPreset("counter_add=", "counter_add=")) // customizable command
            presetList.add(ApduPreset("counter_sub=", "counter_sub=")) //customizable command
            presetList.add(ApduPreset("counter_show", "001203")) // full command
            presetList.add(ApduPreset("counter_reset", "001204")) // full command
            // INS 0x13 echo
            presetList.add(ApduPreset("echo=\"\"", "echo=\"\"")) // also placeholder but suggesting you to use spaces
        }
    }

    private fun appendLog(message: String) {
        mainHandler.post {
            logTextView.append(message)
        }
    }

    private fun updateStatus(status: String) {
        mainHandler.post {
            statusTextView.text = status
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}