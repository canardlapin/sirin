package sirin.graphics

enum ImageFormat:
  case Rgba8SrgbStraight

enum Sampling:
  case Nearest,
    Linear

/** Immutable, tightly packed RGBA8 sRGB pixels with straight alpha. Construction copies caller storage; exports copy as
  * well. Object identity identifies this exact snapshot, not a mutable asset slot or a caller-supplied revision.
  */
final class ImageSnapshot private (val width: Int, val height: Int, private val pixels: Array[Byte]):
  val format: ImageFormat = ImageFormat.Rgba8SrgbStraight
  def byteSize: Int = pixels.length
  def full: ImageRegion = ImageRegion(this, Rect(0, 0, width.toDouble, height.toDouble))

  def pixelsCopy: Array[Byte] = pixels.clone()

  def copyPixelsTo(destination: Array[Byte], offset: Int = 0): Unit =
    require(offset >= 0 && offset.toLong + byteSize <= destination.length, "pixel destination is too small")
    System.arraycopy(pixels, 0, destination, offset, byteSize)

  def pixel(x: Int, y: Int): Color =
    require(x >= 0 && x < width && y >= 0 && y < height, "pixel coordinates outside image")
    val index = (y * width + x) * 4
    Color.rgba8(pixels(index) & 0xff, pixels(index + 1) & 0xff, pixels(index + 2) & 0xff, pixels(index + 3) & 0xff)

object ImageSnapshot:
  /** Copies tightly packed rows, requiring exactly width * height * 4 bytes. The caller keeps source pixels stable for
    * the synchronous copy.
    */
  def copyRgba8(width: Int, height: Int, pixels: Array[Byte]): ImageSnapshot =
    val rowBytes = checkedRowBytes(width, height)
    require(pixels.length.toLong == rowBytes.toLong * height, "pixel length must match image dimensions")
    copyRgba8Rows(width, height, rowBytes, pixels)

  /** Copies pixel bytes only, omitting padding and trailing data. The last row need not have padding. The caller keeps
    * source pixels stable during copying. Length/stride calculations use Long before allocation.
    */
  def copyRgba8Rows(width: Int, height: Int, rowStride: Int, pixels: Array[Byte]): ImageSnapshot =
    val rowBytes = checkedRowBytes(width, height)
    require(rowStride >= rowBytes, "row stride is smaller than a pixel row")
    val sourceBytes = (height.toLong - 1) * rowStride + rowBytes
    require(sourceBytes <= pixels.length, "pixel rows exceed source storage")
    val packed = new Array[Byte](rowBytes * height)
    var y = 0
    while y < height do
      System.arraycopy(pixels, y * rowStride, packed, y * rowBytes, rowBytes)
      y += 1
    new ImageSnapshot(width, height, packed)

  private def checkedRowBytes(width: Int, height: Int): Int =
    require(width > 0 && height > 0, "image dimensions must be positive")
    val rowBytes = width.toLong * 4
    require(rowBytes <= Int.MaxValue && rowBytes * height <= Int.MaxValue, "packed pixels exceed supported array size")
    rowBytes.toInt

/** Source crop in pixel-edge coordinates; fractional crops are preserved. */
final case class ImageRegion(image: ImageSnapshot, source: Rect):
  require(source.width > 0 && source.height > 0, "image source must have positive dimensions")
  require(
    source.x >= 0 && source.y >= 0 && source.x + source.width <= image.width &&
      source.y + source.height <= image.height,
    "image source outside snapshot"
  )
