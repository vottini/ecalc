package br.com.egretta.ecalc

import android.view.MotionEvent
import android.view.View.OnTouchListener
import android.view.View

class Colorfier(normalColor : Int, pressedColor : Int) : OnTouchListener {

    private val normalColor  = normalColor
    private val pressedColor = pressedColor

    private val longClickInterval : Long = 1000
    private var lastTouchStart : Long = 0
    var hasLongListener : Boolean = false

    private var touchConsumed : Boolean = false
    fun touchConsumed() : Boolean {
        return touchConsumed
    }

    @Override
    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                v?.setBackgroundColor(pressedColor)
                this.touchConsumed = false

                if (this.hasLongListener) {
                    this.lastTouchStart = System.currentTimeMillis()
                    return false
                }
            }

            MotionEvent.ACTION_UP -> {
                v?.setBackgroundColor(normalColor)

                if (this.hasLongListener) {
                    val now = System.currentTimeMillis()
                    if (now - lastTouchStart > longClickInterval) {
                        return false
                    }
                }

                this.touchConsumed = true
                v?.performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                v?.setBackgroundColor(normalColor)
            }
        }

        return true
    }
}
