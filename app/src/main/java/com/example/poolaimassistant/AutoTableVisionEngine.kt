package com.example.poolaimassistant

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.Image
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

data class AutoAimResult(
    val tableDetected: Boolean,
    val tableBounds: RectF,
    val ballRadius: Float,
    val hasAimLine: Boolean,
    val cueStart: PointF = PointF(),
    val impactCenter: PointF = PointF(),
    val aimVector: PointF = PointF(),
    val isBallCollision: Boolean = false,
    val objectBallVector: PointF = PointF(),
    val cueDeflectionVector: PointF = PointF()
)

class AutoTableVisionEngine {

    private var isOpenCvInitialized = false
    private var cachedTableBounds: RectF? = null
    private var frameCounter = 0

    private val srcMat = Mat()
    private val hsvMat = Mat()
    private val maskMat = Mat()
    private val grayMat = Mat()
    private val linesMat = Mat()
    private val circlesMat = Mat()

    init {
        isOpenCvInitialized = OpenCVLoader.initLocal()
    }

    fun forceRecalibrateTable() {
        cachedTableBounds = null
    }

    fun analyzeFrame(image: Image): AutoAimResult {
        if (!isOpenCvInitialized) {
            isOpenCvInitialized = OpenCVLoader.initLocal()
            if (!isOpenCvInitialized) {
                return AutoAimResult(false, RectF(), 0f, false)
            }
        }

        val bitmap = imageToBitmap(image)
        Utils.bitmapToMat(bitmap, srcMat)

        if (cachedTableBounds == null || frameCounter % 45 == 0) {
            val detectedRect = autoDetectTablePlayfield(srcMat)
            if (detectedRect != null) {
                cachedTableBounds = detectedRect
            }
        }
        frameCounter++

        val bounds = cachedTableBounds ?: RectF(
            srcMat.cols() * 0.12f,
            srcMat.rows() * 0.16f,
            srcMat.cols() * 0.88f,
            srcMat.rows() * 0.84f
        )

        val dynamicBallRadius = (bounds.width() * 0.0142f).coerceAtLeast(14f)

        val left = bounds.left.toInt().coerceIn(0, srcMat.cols() - 2)
        val top = bounds.top.toInt().coerceIn(0, srcMat.rows() - 2)
        val width = bounds.width().toInt().coerceIn(1, srcMat.cols() - left)
        val height = bounds.height().toInt().coerceIn(1, srcMat.rows() - top)

        val roiRect = Rect(left, top, width, height)
        val tableRoi = Mat(srcMat, roiRect)

        Imgproc.cvtColor(tableRoi, grayMat, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.threshold(grayMat, maskMat, 240.0, 255.0, Imgproc.THRESH_BINARY)

        Imgproc.HoughLinesP(
            maskMat,
            linesMat,
            1.0,
            Math.PI / 180.0,
            22,
            40.0,
            14.0
        )

        if (linesMat.rows() == 0) {
            tableRoi.release()
            return AutoAimResult(
                tableDetected = true,
                tableBounds = bounds,
                ballRadius = dynamicBallRadius,
                hasAimLine = false
            )
        }

        var maxLen = 0.0
        var bestLine = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        for (i in 0 until linesMat.rows()) {
            val l = linesMat.get(i, 0)
            val len = hypot(l[2] - l[0], l[3] - l[1])
            if (len > maxLen) {
                maxLen = len
                bestLine = l
            }
        }

        val x1 = (bestLine[0] + roiRect.x).toFloat()
        val y1 = (bestLine[1] + roiRect.y).toFloat()
        val x2 = (bestLine[2] + roiRect.x).toFloat()
        val y2 = (bestLine[3] + roiRect.y).toFloat()

        Imgproc.GaussianBlur(grayMat, grayMat, Size(5.0, 5.0), 1.4)
        Imgproc.HoughCircles(
            grayMat,
            circlesMat,
            Imgproc.HOUGH_GRADIENT,
            1.2,
            (dynamicBallRadius * 1.4).toDouble(),
            100.0,
            20.0,
            (dynamicBallRadius * 0.7).toInt(),
            (dynamicBallRadius * 1.4).toInt()
        )

        var startPt = PointF(x1, y1)
        var endPt = PointF(x2, y2)
        var foundBallImpact = false
        var objVec = PointF()
        var tangentVec = PointF()

        for (i in 0 until circlesMat.cols()) {
            val c = circlesMat.get(0, i) ?: continue
            val cx = (c[0] + roiRect.x).toFloat()
            val cy = (c[1] + roiRect.y).toFloat()

            val distToP1 = hypot(cx - x1, cy - y1)
            val distToP2 = hypot(cx - x2, cy - y2)

            if (distToP1 < dynamicBallRadius * 1.9f) {
                startPt = PointF(x2, y2)
                endPt = PointF(cx, cy)
            } else if (distToP2 < dynamicBallRadius * 1.9f) {
                startPt = PointF(x1, y1)
                endPt = PointF(cx, cy)
            }
        }

        val dx = endPt.x - startPt.x
        val dy = endPt.y - startPt.y
        val norm = hypot(dx, dy).coerceAtLeast(0.001f)
        val aimDir = PointF(dx / norm, dy / norm)

        for (i in 0 until circlesMat.cols()) {
            val c = circlesMat.get(0, i) ?: continue
            val ox = (c[0] + roiRect.x).toFloat()
            val oy = (c[1] + roiRect.y).toFloat()
            val distFromGhost = hypot(ox - endPt.x, oy - endPt.y)

            if (distFromGhost in (dynamicBallRadius * 1.55f)..(dynamicBallRadius * 2.45f)) {
                foundBallImpact = true
                val odx = ox - endPt.x
                val ody = oy - endPt.y
                val onorm = hypot(odx, ody).coerceAtLeast(0.001f)
                objVec = PointF(odx / onorm, ody / onorm)

                val cross = aimDir.x * objVec.y - aimDir.y * objVec.x
                tangentVec = if (cross > 0f) {
                    PointF(objVec.y, -objVec.x)
                } else {
                    PointF(-objVec.y, objVec.x)
                }
                break
            }
        }

        tableRoi.release()

        return AutoAimResult(
            tableDetected = true,
            tableBounds = bounds,
            ballRadius = dynamicBallRadius,
            hasAimLine = true,
            cueStart = startPt,
            impactCenter = endPt,
            aimVector = aimDir,
            isBallCollision = foundBallImpact,
            objectBallVector = objVec,
            cueDeflectionVector = tangentVec
        )
    }

    private fun autoDetectTablePlayfield(frame: Mat): RectF? {
        Imgproc.cvtColor(frame, hsvMat, Imgproc.COLOR_RGB2HSV)

        val cx = hsvMat.cols() / 2
        val cy = hsvMat.rows() / 2
        if (cx < 30 || cy < 30) return null

        val centerPatch = hsvMat.submat(cy - 20, cy + 20, cx - 20, cx + 20)
        val meanHsv = Core.mean(centerPatch)
        centerPatch.release()

        val lowerFelt = Scalar(
            (meanHsv.`val`[0] - 20).coerceAtLeast(0.0),
            35.0,
            30.0
        )
        val upperFelt = Scalar(
            (meanHsv.`val`[0] + 20).coerceAtMost(180.0),
            255.0,
            255.0
        )

        Core.inRange(hsvMat, lowerFelt, upperFelt, maskMat)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(maskMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        var maxArea = 0.0
        var bestRect: Rect? = null
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                maxArea = area
                bestRect = Imgproc.boundingRect(contour)
            }
            contour.release()
        }

        val screenArea = frame.cols() * frame.rows()
        if (bestRect != null && maxArea > screenArea * 0.22) {
            return RectF(
                bestRect.x.toFloat(),
                bestRect.y.toFloat(),
                (bestRect.x + bestRect.width).toFloat(),
                (bestRect.y + bestRect.height).toFloat()
            )
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
