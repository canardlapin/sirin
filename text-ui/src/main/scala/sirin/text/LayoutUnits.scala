package sirin.text

import sirin as ui
import sirin.graphics as g

/** Explicit 1:1 logical-unit conversion. No display scale, rounding, color
  * premultiplication or font-size adjustment belongs at this boundary.
  */
private[sirin] object LayoutUnits:
  def point(value: ui.Point): g.Point = g.Point(value.x.toDouble, value.y.toDouble)
  def rect(value: g.Rect): ui.Rect = ui.Rect(ui.Point(ui.Lp(value.x), ui.Lp(value.y)),
    ui.Size(ui.Lp(value.width), ui.Lp(value.height)))
  def rect(value: ui.Rect): g.Rect = g.Rect(value.origin.x.toDouble, value.origin.y.toDouble,
    value.size.width.toDouble, value.size.height.toDouble)
  def color(value: ui.Color): g.Color =
    val argb = value.argb
    g.Color.rgba8((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255)

  def affinity(value: ui.TextAffinity): g.CaretAffinity = value match
    case ui.TextAffinity.Upstream => g.CaretAffinity.Upstream
    case ui.TextAffinity.Downstream => g.CaretAffinity.Downstream
  def affinity(value: g.CaretAffinity): ui.TextAffinity = value match
    case g.CaretAffinity.Upstream => ui.TextAffinity.Upstream
    case g.CaretAffinity.Downstream => ui.TextAffinity.Downstream

  def kind(value: g.CaretEdgeKind): ui.TextCaretKind = value match
    case g.CaretEdgeKind.ClusterEdge => ui.TextCaretKind.ClusterEdge
    case g.CaretEdgeKind.FontLigature => ui.TextCaretKind.FontLigature
    case g.CaretEdgeKind.Interpolated => ui.TextCaretKind.Interpolated
