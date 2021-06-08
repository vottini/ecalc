package br.com.egretta.ecalc

import android.os.Bundle
import android.os.Handler
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.math.BigDecimal
import kotlin.math.sqrt

const val displayCapacity : Int = 20
const val blinkDelayMs : Long = 100L

val smallestNorm : BigDecimal = BigDecimal.valueOf(1e-10)
fun divide(a : BigDecimal, b : BigDecimal) : BigDecimal {
   return a.divide(b, displayCapacity, BigDecimal.ROUND_HALF_UP)
}

typealias MathOperation = (BigDecimal, BigDecimal) -> BigDecimal
val operations : Map <Int, MathOperation> = mapOf(
    R.id.buttonPlus   to {a, b -> a.add(b)},
    R.id.buttonMinus  to {a, b -> a.subtract(b)},
    R.id.buttonTimes  to {a, b -> a.multiply(b)},
    R.id.buttonDivide to {a, b -> divide(a, b)}
)

val numericButtons : Array <Int> = arrayOf(
    R.id.button0,
    R.id.button1,
    R.id.button2,
    R.id.button3,
    R.id.button4,
    R.id.button5,
    R.id.button6,
    R.id.button7,
    R.id.button8,
    R.id.button9
)

class MainActivity : WearableActivity() {

    var display: TextView? = null
    var registeredOperation: MathOperation? = null
    var previousOperation: MathOperation? = null
    var previousOperand: BigDecimal? = null

    var buffer: BigDecimal = BigDecimal.ZERO
    var memory: BigDecimal = BigDecimal.ZERO

    var displayData: IntArray = IntArray(displayCapacity)
    var displayDataCounter: Int = 0
    var inputCounter: Int = 0
    var radixIndex: Int = 0

    var status: Error = Error.NONE
    var showingResults: Boolean = false
    var decimalMode: Boolean = false
    var isNegative: Boolean = false

    private val auxFunctions: Map<Int, () -> Unit> = mapOf(
        R.id.buttonMClear       to { this.memoryClear() },
        R.id.buttonMPlus        to { this.memoryAdd() },
        R.id.buttonMRecall      to { this.memoryRecall() },
        R.id.buttonPercent      to { this.putPercent() },
        R.id.buttonInverse      to { this.putInverse() },
        R.id.buttonOpposite     to { this.putOpposite() },
        R.id.buttonSquare       to { this.putSquare() },
        R.id.buttonSqrt         to { this.putSqrt() },
        R.id.buttonPI           to { this.putPI() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        this.display = findViewById<TextView>(R.id.display)

        for (i in numericButtons.indices) {
            val numericButton = findViewById<Button>(numericButtons[i])
            numericButton.setOnTouchListener(Colorfier(
                ContextCompat.getColor(applicationContext, R.color.numericNormal),
                ContextCompat.getColor(applicationContext, R.color.numericPressed)))

            numericButton.setOnClickListener { _: View ->

                // When a numeric button is pressed:
                // 1) if an error is being shown, then do nothing,
                // wait for the user to press clear or delete.
                // 2) If a result was showing, then clear the display
                // as the user will do other calculation, possibly
                // with something in the buffer. If too many digits
                // are in the display, then ignore more input.

                if (Error.NONE != status) return@setOnClickListener
                if (showingResults) clearData(false)
                if (displayDataCounter + 1 >= displayCapacity) {
                    return@setOnClickListener
                }

                // 3) Ignore zero when nothing was typed yet nor it
                // is in decimal mode. 4) Otherwise, insert the typed
                // digit in the display buffer

                if ((i == 0) and (!decimalMode)) {
                    if (0 == displayDataCounter) {
                        return@setOnClickListener
                    }
                }

                ++inputCounter;
                displayData[displayDataCounter++] = i
                if (!decimalMode) ++radixIndex
                refreshDisplay()
            }
        }

        val decimalButton = findViewById<Button>(R.id.buttonDecimal)
        decimalButton.setOnTouchListener(Colorfier(
            ContextCompat.getColor(applicationContext,R.color.numericNormal),
            ContextCompat.getColor(applicationContext,R.color.numericPressed)))

        decimalButton.setOnClickListener { _: View ->
            if (this.showingResults) {
                this.clearData(false)
            }

            this.decimalMode = true
            this.refreshDisplay()
        }

        val deleteColorfier = Colorfier(
            ContextCompat.getColor(applicationContext, R.color.numericNormal),
            ContextCompat.getColor(applicationContext, R.color.deletePressed))

        deleteColorfier.hasLongListener = true
        val deleteClearButton = findViewById<Button>(R.id.buttonDEL)
        val mainActivity = this@MainActivity

        with (deleteClearButton) {
            setOnTouchListener(deleteColorfier)

            // delete
            setOnClickListener { _: View ->
                if ((mainActivity.showingResults) or (0 == mainActivity.inputCounter)) {
                    mainActivity.setData(BigDecimal.ZERO)
                    mainActivity.refreshDisplay()
                    return@setOnClickListener
                }

                if (mainActivity.decimalMode) {
                    if (mainActivity.radixIndex == mainActivity.displayDataCounter) {
                        mainActivity.decimalMode = false
                        mainActivity.refreshDisplay()
                        return@setOnClickListener
                    }
                }

                --mainActivity.inputCounter
                if (!mainActivity.decimalMode) --mainActivity.radixIndex
                mainActivity.displayData[--mainActivity.displayDataCounter] = 0
                mainActivity.refreshDisplay()
            }

            // clear
            setOnLongClickListener { _ : View ->
                if (deleteColorfier.touchConsumed()) {
                    return@setOnLongClickListener false
                }

                mainActivity.registeredOperation = null
                mainActivity.previousOperation = null
                mainActivity.status = Error.NONE

                mainActivity.clearData(true)
                mainActivity.blinkDisplay()
                mainActivity.clearOperationsColor()
                return@setOnLongClickListener true
            }
        }

        operations.forEach { (id, operation) ->
            val opButton = findViewById<Button>(id)
            val operationPressedButtonColor = ContextCompat.getColor(
                applicationContext, R.color.operationPressed)

            with (opButton) {
                setOnClickListener { _: View ->
                    if (Error.NONE != mainActivity.status) {
                        return@setOnClickListener
                    }

                    // when an operation is pressed and
                    // there is a previously registered
                    // operation and something typed,
                    // calculate it and replace the
                    // registered operation

                    mainActivity.registeredOperation?.let { op ->
                        if (mainActivity.inputCounter > 0) {
                            val operand2 = mainActivity.concatData().toBigDecimal()
                            mainActivity.compute(op, operand2)

                            mainActivity.registeredOperation = operation
                            return@setOnClickListener
                        }
                    }

                    // if there was no registered operation
                    // then save the operation typed in. If some digits
                    // were already typed, put them on buffer and wait
                    // for the second operand

                    mainActivity.registeredOperation = operation
                    if (mainActivity.displayDataCounter == 0) {
                        mainActivity.blinkDisplay()
                    }

                    else {
                        val currentValue = mainActivity.concatData().toBigDecimal()
                        mainActivity.pushBuffer(currentValue)
                    }
                }

                setOnTouchListener(View.OnTouchListener { v, event ->

                    if (v is Button) {
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            this@MainActivity.clearOperationsColor()
                            v.setTextColor(operationPressedButtonColor)
                            v.performClick()
                        }

                    }

                    return@OnTouchListener true
                })
            }
        }

        val equalsButton = findViewById<Button>(R.id.buttonEquals)
        equalsButton.setOnClickListener { _: View ->

            // when the equals button is pressed,
            // if nothing was typed and a previous operation
            // and operand exists, then repeat the operation

            if (this.inputCounter == 0) {
                this.previousOperation?.let { op ->
                    this.previousOperand?.let { operand ->
                        this.compute(op, operand)
                        return@setOnClickListener
                    }
                }
            }

            // if there is a registered operation
            // and something is on display, then do the
            // calculation as usual

            this.registeredOperation?.let { op ->
                if (this.displayDataCounter > 0) {
                    val operand2 = this.concatData().toBigDecimal()
                    this.clearOperationsColor()
                    this.compute(op, operand2)
                    return@setOnClickListener
                }
            }

            // otherwise, just save what is on
            // display in the buffer

            this.clearOperationsColor()
            val currentValue = this.concatData().toBigDecimal()
            this.pushBuffer(currentValue)
        }

        val dotButton = findViewById<Button>(R.id.buttonDot)
        dotButton.setOnClickListener { _: View ->
            this.showMenu(true)
        }

        val backButton = findViewById<Button>(R.id.buttonBack)
        backButton.setOnClickListener { _: View ->
            this.showMenu(false)
        }

        this.auxFunctions.forEach { (id, auxFunction) ->
            val functionButton = findViewById<TextView>(id)
            functionButton.setOnClickListener { _: View ->
                auxFunction()
                this.showingResults = true
                this.inputCounter = 1
                this.refreshDisplay()
                this.showMenu(false)
            }
        }
    }

    private fun compute(op: MathOperation, operand2: BigDecimal) {

        // save operand and operation for
        // repeated presses on equal button

        this.previousOperand = operand2
        this.previousOperation = op

        val operand1 = this.buffer
        calculate(op, operand1, operand2)
    }

    private fun roundValue(value: BigDecimal): BigDecimal {
        val absoluteResult = value.abs()

        val nextInteger = absoluteResult.setScale(0, BigDecimal.ROUND_CEILING)
        if (absoluteResult.add(smallestNorm) > nextInteger) {
            return nextInteger.multiply(BigDecimal(value.signum()))
        }

        val prevInteger = absoluteResult.setScale(0, BigDecimal.ROUND_FLOOR)
        if (absoluteResult.subtract(smallestNorm) < prevInteger) {
            return prevInteger.multiply(BigDecimal(value.signum()))
        }

        return value
    }

    private fun calculate(
        op: MathOperation,
        op1: BigDecimal,
        op2: BigDecimal
    ) {
        try {
            val result = op(op1, op2)
            val sanedResult = this.roundValue(result)
            this.pushBuffer(sanedResult)
        }

        catch (e: ArithmeticException) {
            this.status = Error.ARITHMETIC
            this.refreshDisplay()
            return
        }
    }

    private fun concatData(): String {
        var result = ""
        var auxCounter = 0
        while (auxCounter < this.radixIndex) {
            result += this.displayData[auxCounter]
            ++auxCounter
        }

        if (this.decimalMode) {
            if (0 == auxCounter) {
                result = "0"
            }

            result += "."
            while (auxCounter < this.displayDataCounter) {
                result += this.displayData[auxCounter]
                ++auxCounter
            }
        }

        result = if (result.contentEquals("")) "0" else result
        if ((this.isNegative) and (!result.contentEquals("0"))) result = "-$result"
        return result
    }

    private fun refreshDisplay() {
        if (Error.NONE != this.status) {
            this.display?.text = getErrorString(this, this.status)
            this.blinkDisplay()
        }

        val result = this.concatData()
        this.display?.text = result
    }

    private fun pushBuffer(value: BigDecimal) {
        this.buffer = value.stripTrailingZeros()
        this.setData(this.buffer)
        this.refreshDisplay()
        this.blinkDisplay()
    }

    private fun clearData(clearBuffer: Boolean) {
        if (clearBuffer) {
            this.buffer = BigDecimal.ZERO
        }

        this.displayData = IntArray(displayCapacity)
        this.displayDataCounter = 0
        this.inputCounter = 0
        this.radixIndex = 0

        this.showingResults = false
        this.decimalMode = false
        this.isNegative = false
        this.refreshDisplay()
    }

    private fun blinkDisplay() {
        this.display?.let { display ->
            val value = display.text
            display.text = ""

            Handler().postDelayed(
                /* Runnable */ { display.text = value },
                blinkDelayMs
            )
        }
    }

    private fun showMenu(show: Boolean) {
        val extraOperationsMenu = findViewById<View>(R.id.extraOperations)
        val yOffset = extraOperationsMenu.height.toFloat()
        val yInitial = if (show) -yOffset else 0f
        val yFinal = if (show) 0f else -yOffset

        extraOperationsMenu.startAnimation(
            TranslateAnimation(0f, 0f, yInitial, yFinal).apply {
                fillAfter = show
                duration = 200
            }
        )
    }

    private fun betweenLimits(value: BigDecimal): Boolean {
        val resultStr = value.toPlainString()
        if (resultStr.length < displayCapacity) {
            return true
        }

        val rdxIndex = resultStr.indexOf('.')
        if ((-1 != rdxIndex) and (rdxIndex < displayCapacity)) {
            return true
        }

        return false
    }

    private fun setData(value: BigDecimal) {
        if (!this.betweenLimits(value)) {
            this.status = Error.OFFLIMITS
            return
        }

        this.displayDataCounter = 0
        this.inputCounter = 0
        this.radixIndex = 0

        this.showingResults = true
        this.decimalMode = false
        this.isNegative = false

        var srcIdx: Int = 0
        val valueStr = value.toPlainString()
        while (this.displayDataCounter < this.displayData.size) {
            if (srcIdx >= valueStr.length) {
                break
            }

            val idx = srcIdx++
            val character = valueStr[idx]

            if (character.isDigit()) {
                this.displayData[this.displayDataCounter] = character.toString().toInt()
                if (!this.decimalMode) ++this.radixIndex
                ++this.displayDataCounter
            }

            else {
                if ('-' == character) this.isNegative = true
                if ('.' == character) this.decimalMode = true
            }
        }
    }

    //--------------------------------------------------------------------------

    private fun putPI() {
        this.setData(BigDecimal(Math.PI))
    }

    private fun putInverse() {
        val value = this.concatData().toBigDecimal()
        if (BigDecimal.ZERO == value) {
            this.status = Error.ARITHMETIC
            return
        }

        val result = divide(BigDecimal.ONE, value)
        val sanedResult = this.roundValue(result)
        this.setData(sanedResult.stripTrailingZeros())
    }

    private fun putOpposite() {
        val value = this.concatData().toBigDecimal()
        val result = value.multiply(BigDecimal(-1.0))
        this.setData(result.stripTrailingZeros())
    }

    private fun putSquare() {
        val value = this.concatData().toBigDecimal()
        val result = value.multiply(value)
        val sanedResult = this.roundValue(result)
        this.setData(sanedResult.stripTrailingZeros())
    }

    private fun putSqrt() {
        val value = this.concatData().toBigDecimal()
        if (value < BigDecimal.ZERO) {
            this.status = Error.DOMAIN
            return
        }

        val doubleResult = sqrt(value.toDouble())
        val result = BigDecimal(doubleResult).stripTrailingZeros()
        val sanedResult = this.roundValue(result)
        this.setData(sanedResult)
    }

    private fun putPercent() {
        val value = this.concatData().toBigDecimal()
        val result = this.buffer.times(divide(value, BigDecimal(100.0)))
        this.setData(result.stripTrailingZeros())
    }

    //--------------------------------------------------------------------------

    private fun memoryAdd() {
        val value = this.concatData().toBigDecimal()
        this.memory = this.memory.add(value)
    }

    private fun memoryRecall() {
        this.setData(this.memory.stripTrailingZeros())
    }

    private fun memoryClear() {
        this.memory = BigDecimal.ZERO
    }

    //-------------------------------------------------------------------------

    private fun clearOperationsColor() {
        val operationNormalButtonColor = ContextCompat.getColor(
            applicationContext,R.color.operationNormal)

        operations.forEach { (id, _) ->
            val opButton = findViewById<Button>(id)
            opButton.setTextColor(operationNormalButtonColor)
        }
    }
}
