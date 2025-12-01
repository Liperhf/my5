package com.example.my5.model

data class Point(val x: Double, val y: Double)

data class Line(val start: Point, val end: Point)

data class Rectangle(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    
    fun contains(point: Point): Boolean {
        return point.x in left..right && point.y in top..bottom
    }
}

data class Polygon(val points: List<Point>) {
    val edges: List<Line> = points.zipWithNext().map { (p1, p2) -> Line(p1, p2) } + 
        Line(points.last(), points.first())
}
