package com.koleq.apdu

import android.app.AlertDialog
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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

    private val PREFS_NAME = "ApduPrefs"
    private val PREFS_KEY = "saved_presets"

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
            val hex = apduInput.text.toString().trim().replace(" ", "")
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

            updateStatus("CARD CONNECTED (IsoDep)")
            appendLog("=== Card Tapped & Connected ===\n")

            val hist = isoDep.historicalBytes
            if (hist != null && hist.isNotEmpty()) {
                if (isPrintableAscii(hist)) {
                    val histText = String(hist, Charsets.US_ASCII)
                    appendLog("ATS: $histText\n")
                } else {
                    appendLog("ATS: ${bytesToHex(hist)}\n")
                }
            }
            appendLog("Session open. Ready for real-time commands.\n\n")

        } catch (e: Exception) {
            hasCardSession = false
            appendLog("Connection Error: ${e.message}\n")
            updateStatus("Connection Failed")
        }
    }

    private fun sendApduRealtime(hexApdu: String) {
        val isoDep = currentIsoDep

        if (!hasCardSession || isoDep == null) {
            appendLog("ERROR: No card connected. Tap card to phone first!\n")
            updateStatus("Status: No card")
            return
        }

        Thread {
            try {
                appendLog(">> $hexApdu\n")
                val response = try {
                    isoDep.transceive(hexToBytes(hexApdu))
                } catch (e: IOException) {
                    if (!isoDep.isConnected) {
                        isoDep.connect()
                    }
                    isoDep.transceive(hexToBytes(hexApdu))
                }

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
                            val textData = String(dataBytes, Charsets.US_ASCII)
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

            } catch (e: IOException) {
                hasCardSession = false
                currentIsoDep = null
                appendLog("IO Error: ${e.message} (Card removed)\n")
                updateStatus("Status: No card")
            } catch (e: Exception) {
                appendLog("Error: ${e.message}\n")
            }
        }.start()
    }

    private fun closeCardConnection() {
        hasCardSession = false
        try {
            currentIsoDep?.close()
        } catch (_: Exception) {}
        currentIsoDep = null
        updateStatus("CARD DISCONNECTED")
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

    // ISSUE 2 FIX: Save presets to SharedPreferences using simple JSON serialization
    private fun savePresets() {
        val jsonArray = JSONArray()
        for (preset in presetList) {
            val obj = JSONObject()
            obj.put("name", preset.name)
            obj.put("hex", preset.hex)
            jsonArray.put(obj)
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_KEY, jsonArray.toString()).apply()
    }

    // ISSUE 2 FIX: Load saved presets on app startup
    private fun loadPresets() {
        presetList.clear()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        // Fallback defaults if storage was empty
        if (presetList.isEmpty()) {
            presetList.add(ApduPreset("Select Applet", "00A404000AF06B6F6C65710000000100"))
            presetList.add(ApduPreset("Test INS 0x10", "001000000C"))
        }
    }

    private fun appendLog(message: String) {
        mainHandler.post {
            logTextView.append(message)
        }
    }

    private fun updateStatus(status: String) {
        mainHandler.post {
            statusTextView.text = "Status: $status"
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