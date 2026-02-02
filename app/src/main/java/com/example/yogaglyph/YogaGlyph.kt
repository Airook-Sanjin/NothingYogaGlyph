package com.example.yogaglyph

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

import kotlin.math.sqrt
import androidx.core.content.ContextCompat
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject
import com.nothing.ketchum.GlyphMatrixUtils
import com.example.yogaglyph.R
import com.example.yogaglyph.GlyphMatrixService
import com.nothing.ketchum.GlyphToy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class State{
    WAITING_FOR_SHAKE,
    HOLD_POSE
}

class YogaGlyph  : GlyphMatrixService("YogaGlyph") {
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var accelerometer: Sensor? = null;
    var holdjob: Job? = null
    var loopjob: Job? = null
    private val cooldownMs: Long = 5_000

    var state: State = State.WAITING_FOR_SHAKE
    private val glyphs = mutableListOf<Int>()
    lateinit var gm: GlyphMatrixManager


    override fun performOnServiceConnected(
        context: Context,
        glyphMatrixManager: GlyphMatrixManager
    ) {
        gm = glyphMatrixManager
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        state = State.WAITING_FOR_SHAKE

        val glyphTypedArray = context.resources.obtainTypedArray(R.array.yoga_glyphs)
        for (index in 0 until glyphTypedArray.length()) {
            var resId = glyphTypedArray.getResourceId(index, 0)
            glyphs.add(resId)
        }
        glyphTypedArray.recycle()

//      initial frame
        val textObject = GlyphMatrixObject.Builder().setImageSource(GlyphMatrixUtils.drawableToBitmap(
            ContextCompat.getDrawable(this,R.drawable.yoga_glyphpose)))
            .setScale(100)
            .setPosition(0, 0)
            .build()
        var frame = GlyphMatrixFrame.Builder().addTop(textObject).build(applicationContext)
        gm.setMatrixFrame(frame.render())











    }

    override fun onTouchPointLongPress() {
        super.onTouchPointLongPress()

        showShakeObject()

        sensorListener = object : SensorEventListener {


            override fun onSensorChanged(event: SensorEvent) {
                Log.d("YogaGlyph", "sensor tick x=${event.values[0]}")
                if (state != State.WAITING_FOR_SHAKE) return
                if (isShake(event)) {
                    Log.d("YogaGlyph", "SHAKE DETECTED")
                    onShakeDetected()
                }
            }

            override fun onAccuracyChanged(p0: Sensor, p1: Int) {}
        }

        sensorManager?.registerListener(
            sensorListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    override fun onShakeDetected() {
        super.onShakeDetected()
        Log.d("YogaGlyph", "onShakeDetected() called")
        state = State.HOLD_POSE
        var ranId = glyphs.random()

        val drawable = ContextCompat.getDrawable(applicationContext, ranId)
            ?: return
        val poseBitmap = GlyphMatrixUtils.drawableToBitmap(drawable)

//        Safe Copy of poseBitmap vvv
        var src = poseBitmap.copy(ARGB_8888,false)




        var srcW = src.width
        var srcH = src.height
        var scaledH = 25
        var scaledW = (srcW * scaledH) /srcH
        var scaledBitmap = Bitmap.createScaledBitmap(src,scaledW,scaledH,true)
        src = scaledBitmap.copy(ARGB_8888,false)

        srcW = src.width
        srcH = src.height

        Log.d("YogaGlyph", "srcW=$srcW srcH=$srcH")


        var offsetX = 25
        val targetOffsetX = (25 - srcW) / 2
        var offsetY = 0
        var srcPixels = IntArray(srcW*srcH)
        var dstPixels = IntArray(25*25)

        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val frameBitmap = android.graphics.Bitmap.createBitmap(25, 25, android.graphics.Bitmap.Config.ARGB_8888)

        val holdObject = GlyphMatrixObject.Builder()
            .setText("HOLD!")
            .setScale(10)
            .setPosition(3, 15)
            .build()

        loopjob?.cancel()
        loopjob = serviceScope.launch{
            while(offsetX >= targetOffsetX){
                dstPixels.fill(0x00000000)

                for (dY in 0 until 25){
                    for(dX in 0 until 25){
                        val sX = dX - offsetX
                        val sY = dY - offsetY

                        val dstIndex = dY * 25 + dX

                        if (sX in 0 until srcW && sY in 0 until srcH){
                            val srcIndex = sY *srcW +sX

                            val p = srcPixels[srcIndex]
                            val a = (p ushr 24) and 0xFF
                            dstPixels[dstIndex] = if (a > 0) 0xFFFFFFFF.toInt() else 0x00000000

                        }else{
                            dstPixels[dstIndex] = 0x00000000
                        }
                    }
                }

                frameBitmap.setPixels(dstPixels, 0, 25, 0, 0, 25, 25)

                val poseObject = GlyphMatrixObject.Builder()
                    .setImageSource(frameBitmap)
                    .setScale(100)
                    .setOrientation(0)
                    .setPosition(0, -5)
                    .setReverse(false)
                    .build()



                val frame = GlyphMatrixFrame.Builder()
                    .addTop(holdObject)
                    .addMid(poseObject)
                    .build(applicationContext)

                gm.setMatrixFrame(frame.render())

                offsetX -=1
                delay(60)
            }
        }





        holdjob?.cancel()
        holdjob = serviceScope.launch{
            delay(cooldownMs)
            loopjob?.cancel()
            showShakeObject()
            state=State.WAITING_FOR_SHAKE
        }

    }

    private fun showShakeObject(){
        val introObject = GlyphMatrixObject.Builder()
            .setText("Shake!")
            .setPosition(0, 10)
            .build()

        val frame = GlyphMatrixFrame.Builder()
            .addTop(introObject)
            .build(applicationContext)

        gm.setMatrixFrame(frame.render())
    }
    private fun isShake(event: SensorEvent): Boolean {
        val threshold = 3
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val movement = sqrt((x * x) + (y * y) + (z * z))
        val delta = abs(movement - 9.8)
        if (delta > threshold) {
            return true
        }
        return false
    }

    override fun performOnServiceDisconnected(context: Context) {
        super.performOnServiceDisconnected(context)
        sensorManager?.unregisterListener(sensorListener)
        loopjob?.cancel()
        holdjob?.cancel()
        serviceScope.cancel()


    }
}
