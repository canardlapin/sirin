package sirin.desktop

import java.nio.file.{Files,Path as FilePath}
import sirin.graphics.*
import sirin.graphics.desktop.TiledRenderer
import sirin.graphics.text.NativeFont
import sirin.render.{OffscreenGpu,PixelProgram}

class TiledRendererSuite extends munit.FunSuite:
  test("tiles match full GPU image across glyph ink, transforms, clips, images and isolated layers") {
    val face=NativeFont.open(FontData.copy(Files.readAllBytes(FilePath.of("../graphics/src/test/resources/fonts/NotoSans-Regular.ttf"))),18)
    val gpu=OffscreenGpu.open()
    try
      val run=face.shape("office",ShapingOptions(Script.Latin,TextDirection.LeftToRight,"en"))
      val image=ImageSnapshot.copyRgba8(2,2,Array[Byte](-1,0,0,-1,0,-1,0,-1,0,0,-1,-1,-1,-1,-1,-1))
      val drawing=Drawing.record { c =>
        c.fill(Path.rectangle(Rect(0,0,80,64)),Paint.Solid(Color(0.1,0.15,0.2)))
        c.clipped(Path.rectangle(Rect(3.25,1.5,72,60))) { c =>
          c.layer(0.65) { c =>
            c.fill(Path.rectangle(Rect(12.2,11.7,35,24)),Paint.Solid(Color(1,0.3,0.1,0.7)))
            c.fill(Path.rectangle(Rect(27.4,20.2,34,31)),Paint.Solid(Color(0.2,1,0.4,0.8)))
          }
          c.transformed(Affine(1.1,0.15,0.2,0.85,4.25,2.5)) { c =>
            c.glyphs(run,Point(1.5,26),Paint.Solid(Color(1,1,1)))
          }
          c.clipped(Path.build { p => p.moveTo(9,35);p.lineTo(73,31);p.lineTo(51,62);p.close() },FillRule.EvenOdd) { c =>
            c.transformed(Affine(1.02,0.1,-0.05,1,3,-1))(_.image(image.full,Rect(8.5,29.5,61,32),Sampling.Linear,0.8))
          }
        }
      }
      for scale <- Vector(1.0,2.0) do
        val prepared=DrawingPreparation.prepare(drawing,gpu.target((80*scale).toInt,(64*scale).toInt,scale)).toOption.get
        val reference=gpu.render(PixelProgram.compile(prepared)).pixelsCopy
        for edge <- Vector(16,23,32) do
          val tiled=TiledRenderer.render(gpu,prepared,edge)(() => false).image.pixelsCopy
          val differences=reference.indices.map(i => math.abs((reference(i)&255)-(tiled(i)&255)))
          println(s"tileEquivalence scale=$scale edge=$edge maxChannelError=${differences.max}")
          assert(differences.max<=1,s"scale=$scale edge=$edge maxChannelError=${differences.max} differentChannels=${differences.count(_>1)}")
    finally {gpu.close();face.close()}
  }
