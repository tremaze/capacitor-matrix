package de.tremaze.capacitor.matrix

import android.util.Log

class CapMatrix {

    fun echo(value: String?): String? {
        Log.i("Echo", value ?: "null")

        return value
    }
}
