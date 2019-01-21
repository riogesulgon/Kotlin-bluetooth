package com.rio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*

import sdk.Command
import sdk.PrinterCommand
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

// Defines several constants used when transmitting messages between the
// service and the UI.
const val MESSAGE_READ: Int = 0
const val STATE_NONE = 0       // we're doing nothing
const val STATE_LISTEN = 1     // now listening for incoming connections
const val STATE_CONNECTING = 2 // now initiating an outgoing connection
const val STATE_CONNECTED = 3  // now connected to a remote device
private const  val BIG5 = "BIG5"
// ... (Add other message types here as needed.)

private const val TAG = "BT"
class MainActivity : AppCompatActivity() {

    val mBluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mHandler = @SuppressLint("HandlerLeak")
    object : Handler() {
        override fun handleMessage(msg: Message) {

            when (msg.what) {
                Constants.MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    STATE_CONNECTED -> {
                        message.text = "Connected"
                    }
                    STATE_CONNECTING -> message.text = "Connecting"
                    STATE_LISTEN, STATE_NONE -> message.text = "Not connected"
                }
                Constants.MESSAGE_WRITE -> {
                    val writeBuf = msg.obj as ByteArray
                    // construct a string from the buffer
                    val writeMessage = String(writeBuf)
                    uiLog.append(  writeMessage)
                }
                Constants.MESSAGE_READ -> {

                    val readBuf = msg.obj as ByteArray
                    // construct a string from the valid bytes in the buffer
                    val readMessage = String(readBuf, 0, msg.arg1)
                    message.text = readMessage

                }
                Constants.MESSAGE_DEVICE_NAME -> {
                    // save the connected device's name
                    message.text = msg.data.getString(Constants.DEVICE_NAME)
                }
                Constants.MESSAGE_TOAST ->
                    message.text =  msg.data.getString(Constants.TOAST)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (mBluetoothAdapter == null) {
            message.text = getString(R.string.NO_BLUETOOTH)
        }


        val pairedDevices: Set<BluetoothDevice>? = mBluetoothAdapter?.bondedDevices

        val btDevice: BluetoothDevice? = pairedDevices?.find { it.name == "Richtech" }
        btDevice?.uuids?.forEach {
            Log.d(TAG, it.toString())
        }
        val uid = btDevice?.uuids?.first()?.toString()
        if (btDevice == null) {
            message.text = getString(R.string.NO_BLUETOOTH)
            return
        }
        val connect = ConnectThread(btDevice, uid)
        execute.setOnClickListener {
                connect.start()
        }

        test.setOnClickListener {

            connect.write(PrinterCommand.POS_Print_Text("Hello World", BIG5, 0, 0, 0, 0))
            connect.write(Command.LF)

        }
    }



    private inner class ConnectThread(device: BluetoothDevice, uid: String? ) : Thread() {

        val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(UUID.fromString( uid ?: "00001101-0000-1000-8000-00805f9b34fb"))
        }
         var mmInStream: InputStream? = null
         var mmOutStream: OutputStream? = null
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream


        override fun run() {
                // Cancel discovery because it otherwise slows down the connection.
                mBluetoothAdapter?.cancelDiscovery()

                mmSocket?.use { socket ->
                    // Connect to the remote device through the socket. This call blocks
                    // until it succeeds or throws an exception.
                    try {
                        socket.connect()
                        Log.d(TAG, "Connected")
                        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, STATE_CONNECTED,STATE_CONNECTED)
                            .sendToTarget()
                        mmInStream = socket.inputStream
                        mmOutStream = socket.outputStream

                        var numBytes: Int // bytes returned from read()
                        // Keep listening to the InputStream until an exception occurs.
                        while (true) {
                            // Read from the InputStream.
                            numBytes = try {
                                socket.inputStream.read(mmBuffer)
                            } catch (e: IOException) {
                                Log.d(TAG, "Input stream was disconnected", e)
                                break
                            }

                            // Send the obtained bytes to the UI activity.
                            val readMsg = mHandler.obtainMessage(
                                MESSAGE_READ, numBytes, -1,
                                mmBuffer
                            )
                            readMsg.sendToTarget()
                        }

                    } catch (e: IOException) {
                       Log.d(TAG, "Failed to connect")
                        throw(e)
                    }



                }
            }

            /**
             * Write to the connected OutStream.
             *
             * @param buffer The bytes to write
             */
            fun write(buffer: ByteArray) {
                try {
                    mmOutStream?.write(buffer)

                    // Share the sent message back to the UI Activity
                    mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget()
                } catch (e: IOException) {
                    Log.e(TAG, "Exception during write", e)
                }

            }
            // Closes the client socket and causes the thread to finish.
            fun cancel() {
                try {
                    mmSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Could not close the client socket", e)
                }
            }
        }
    }
