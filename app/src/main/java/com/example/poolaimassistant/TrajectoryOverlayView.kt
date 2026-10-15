package com.example.poolaimassistant

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class TrajectoryOverlayView(context: Context) : View(context) {

    private var currentState: AutoAimResult? = null

    var maxBounces: Int = 2
    var cushionRestitution: Float = 0.86f
    var spinFactor: Float = 0.0f

    private val spinCurvePath = Path()

    private val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 255, 200)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pocketZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 255, 100)
        style = Paint.Style.FILL
    }

    private val primaryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val objectBallLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
    }

    private val stunTangentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 215, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val spinCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 140, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun updateOverlay(result: AutoAimResult) {
        currentState = result
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = currentState ?: return
        if (!state.tableDetected) return

        canvas.drawRect(state.tableBounds, tableBorderPaint)
        drawPocketZones(canvas, state.tableBounds, state.ballRadius * 1.65f)

        if (!state.hasAimLine) return

        if (state.isBallCollision) {
            canvas.drawLine(
                state.cueStart.x, state.cueStart.y,
                state.impactCenter.x, state.impactCenter.y,
                primaryLinePaint
            )
            canvas.drawCircle(
                state.impactCenter.x, state.impactCenter.y,
                state.ballRadius, objectBallLinePaint
            )

            drawRayWithCompression(
                canvas,
                state.impactCenter,
                state.objectBallVector,
                state.tableBounds,
                state.ballRadius,
                1,
                objectBallLinePaint
            )

            drawRayWithCompression(
                canvas,
                state.impactCenter,
                state.cueDeflectionVector,
                state.tableBounds,
                state.ballRadius,
                0,
                stunTangentPaint
            )

            drawSpinDeflectionCurve(
                canvas,
                state.impactCenter,
                state.aimVector,
                state.cueDeflectionVector,
                state.ballRadius
            )
        } else {
            drawRayWithCompression(
                canvas,
                state.cueStart,
                state.aimVector,
                state.tableBounds,
                state.ballRadius,
                maxBounces,
                primaryLinePaint
            )
        }
    }

    private fun drawSpinDeflectionCurve(
        canvas: Canvas,
        p0: PointF,
        aimVec: PointF,
        tangentVec: PointF,
        radius: Float
    ) {
        val slideDist = radius * 3.4f
        val rollDist = radius * 9.5f

        val p1x = p0.x + tangentVec.x * slideDist
        val p1y = p0.y + tangentVec.y * slideDist

        val blendX = tangentVec.x + spinFactor * aimVec.x
        val blendY = tangentVec.y + spinFactor * aimVec.y
        val norm = hypot(blendX, blendY).coerceAtLeast(0.001f)

        val p2x = p1x + (blendX / norm) * rollDist
        val p2y = p1y + (blendY / norm) * rollDist

        spinCurvePath.reset()
        spinCurvePath.moveTo(p0.x, p0.y)
        spinCurvePath.quadTo(p1x, p1y, p2x, p2y)
        canvas.drawPath(spinCurvePath, spinCurvePaint)
    }

    private fun drawRayWithCompression(
        canvas: Canvas,
        origin: PointF,
        direction: PointF,
        bounds: RectF,
        radius: Float,
        bounces: Int,
        paint: Paint
    ) {
        val minX = bounds.left + radius
        val maxX = bounds.right - radius
        val minY = bounds.top + radius
        val maxY = bounds.bottom - radius

        var sx = origin.x
        var sy = origin.y
        var vx = direction.x
        var vy = direction.y

        for (i in 0..bounces) {
            val tx = when {
                vx > 0.0001f -> (maxX - sx) / vx
                vx < -0.0001f -> (minX - sx) / vx
                else -> Float.MAX_VALUE
            }
            val ty = when {
                vy > 0.0001f -> (maxY - sy) / vy
                vy < -0.0001f -> (minY - sy) / vy
                else -> Float.MAX_VALUE
            }

            val t = min(tx, ty)
            if (t == Float.MAX_VALUE || t < 0f) break

            val hitX = sx + vx * t
            val hitY = sy + vy * t

            canvas.drawLine(sx, sy, hitX, hitY, paint)
            canvas.drawCircle(hitX, hitY, radius, paint)

            val railFriction = 0.94f
            if (tx < ty) {
                vx = -vx * cushionRestitution
                vy *= railFriction
            } else {
                vy = -vy * cushionRestitution
                vx *= railFriction
            }

            val vNorm = hypot(vx, vy).coerceAtLeast(0.001f)
            vx /= vNorm
            vy /= vNorm

            sx = hitX
            sy = hitY
        }
    }

    private fun drawPocketZones(canvas: Canvas, bounds: RectF, pocketRadius: Float) {
        val midX = bounds.centerX()
        canvas.drawCircle(bounds.left, bounds.top, pocketRadius, pocketZonePaint)
        canvas.drawCircle(midX, bounds.top, pocketRadius, pocketZonePaint)
        canvas.drawCircle(bounds.right, bounds.top, pocketRadius, pocketZonePaint)
        canvas.drawCircle(bounds.left, bounds.bottom, pocketRadius, pocketZonePaint)
        canvas.drawCircle(midX, bounds.bottom, pocketRadius, pocketZonePaint)
        canvas.drawCircle(bounds.right, bounds.bottom, pocketRadius, pocketZonePaint)
    }
}
