package dev.ohaiibuzzle.rykenx3

import android.app.Application
import dev.ohaiibuzzle.rykenx3.service.MeterController

class RykenApp : Application() {
    val controller by lazy { MeterController(this) }
}
