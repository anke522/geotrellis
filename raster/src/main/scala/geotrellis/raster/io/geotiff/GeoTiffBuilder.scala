/*
 * Copyright 2018 Azavea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.raster.io.geotiff

import geotrellis.raster.io.geotiff.compression.Compression
import geotrellis.raster._
import geotrellis.proj4.CRS
import geotrellis.vector.Extent
import geotrellis.util._
import spire.syntax.cfor._


trait GeoTiffBuilder[T <: CellGrid] {
  /** Make GeoTiff Tile from component segments.
    * Missing segments will be substituted with NODATA.
    * Segments must be keyed relative to (0, 0) offset.
    *
    * @param segments keyed by (column, row) in tile layout
    * @param tileLayout of the segments
    * @param cellType of desired tile
    * @param storageMethod for multiband tiles
    * @param compression method for segments
    */
  def makeTile(
    segments: Iterator[(Product2[Int, Int], T)],
    tileLayout: TileLayout,
    cellType: CellType,
    storageMethod: StorageMethod,
    compression: Compression
  ): T

  /** Abstracts over GeoTiff class constructor */
  def makeGeoTiff(
    tile: T,
    extent: Extent,
    crs: CRS,
    tags: Tags,
    options: GeoTiffOptions
  ): GeoTiff[T]

  def fromSegments(
    segments: Map[Product2[Int, Int], T],
    tileExtent: (Int, Int) => Extent,
    crs: CRS,
    options: GeoTiffOptions,
    tags: Tags = Tags.empty
  ): GeoTiff[T] = {
    val sample = segments.head._2
    val cellType = sample.cellType
    val tileCols = sample.cols
    val tileRows = sample.rows

    val firstKey = segments.head._1
    var colMin: Int = firstKey._1
    var rowMin: Int = firstKey._2
    var colMax: Int = firstKey._1
    var rowMax: Int = firstKey._2

    for ((col, row) <- segments.keys) {
      colMin = math.min(col, colMin)
      rowMin = math.min(row, rowMin)
      colMax = math.max(col, colMax)
      rowMax = math.max(row, rowMax)
    }

    val opts = options.copy(storageMethod = Tiled(tileCols, tileRows))

    val tile: T = makeTile(
      segments.iterator.map { case (key, tile) =>
        ((key._1 - colMin , key._2 - rowMin), tile)
      },
      TileLayout(0, 0, colMax - colMin, rowMax - rowMin),
      segments.head._2.cellType,
      options.storageMethod,
      options.compression)

    val extent = tileExtent(colMin, rowMin) combine tileExtent(colMax, rowMax)

    makeGeoTiff(tile, extent, crs, tags, opts)
  }
}

object GeoTiffBuilder {
  def apply[T <: CellGrid: GeoTiffBuilder] = implicitly[GeoTiffBuilder[T]]

  implicit val singlebandGeoTiffBuilder = new GeoTiffBuilder[Tile] {
    def makeTile(
      segments: Iterator[(Product2[Int, Int], Tile)],
      tileLayout: TileLayout,
      cellType: CellType,
      storageMethod: StorageMethod,
      compression: Compression
    ) = {
      val segmentLayout =
        GeoTiffSegmentLayout(
          totalCols = tileLayout.totalCols.toInt,
          totalRows = tileLayout.totalRows.toInt,
          storageMethod,
          PixelInterleave,
          BandType.forCellType(cellType))

      val segmentCount = tileLayout.layoutCols * tileLayout.layoutRows
      val compressor = compression.createCompressor(segmentCount)

      val segmentBytes = Array.ofDim[Array[Byte]](segmentCount)

      segments.foreach { case (key, tile) =>
        val layoutCol = key._1
        val layoutRow = key._2
        val index = tileLayout.layoutCols * layoutRow + layoutCol
        val bytes = tile.interpretAs(cellType).toBytes
        segmentBytes(index) = compressor.compress(bytes, index)
      }

      lazy val emptySegment =
        ArrayTile.empty(cellType, tileLayout.tileCols, tileLayout.tileRows).toBytes

      cfor (0)(_ < segmentBytes.length, _ + 1){ index =>
        if (null == segmentBytes(index)) {
          segmentBytes(index) = compressor.compress(emptySegment, index)
        }
      }

      GeoTiffTile(
        new ArraySegmentBytes(segmentBytes),
        compressor.createDecompressor,
        segmentLayout,
        compression,
        cellType)
    }

    def makeGeoTiff(
      tile: Tile,
      extent: Extent,
      crs: CRS,
      tags: Tags,
      options: GeoTiffOptions
    ) = SinglebandGeoTiff(tile, extent, crs, tags, options)
  }

  implicit val multibandGeoTiffBuilder = new GeoTiffBuilder[MultibandTile] {
    def makeTile(
      segments: Iterator[(Product2[Int, Int], MultibandTile)],
      tileLayout: TileLayout,
      cellType: CellType,
      storageMethod: StorageMethod,
      compression: Compression
    ) = {
      val buffered = segments.buffered
      val bandCount = buffered.head._2.bandCount
      val cols = tileLayout.tileCols
      val rows = tileLayout.tileRows

      val segmentLayout =
        GeoTiffSegmentLayout(
          totalCols = tileLayout.totalCols.toInt,
          totalRows = tileLayout.totalRows.toInt,
          storageMethod,
          PixelInterleave,
          BandType.forCellType(cellType))

      val segmentCount = tileLayout.layoutCols * tileLayout.layoutRows
      val compressor = compression.createCompressor(segmentCount)

      val segmentBytes = Array.ofDim[Array[Byte]](segmentCount)

      segmentLayout.interleaveMethod match {
        case PixelInterleave =>
          val byteCount = cellType.bytes

          buffered.foreach { case (key, tile) =>
            val layoutCol = key._1
            val layoutRow = key._2
            val index = tileLayout.layoutCols * layoutRow + layoutCol
            val bytes = GeoTiffSegment.pixelInterleave(tile.interpretAs(cellType))
            segmentBytes(index) = compressor.compress(bytes, index)
          }

          lazy val emptySegment =
            GeoTiffSegment.pixelInterleave(
              MultibandTile(
                Array.fill(bandCount)(ArrayTile.empty(cellType, cols, rows))))

          cfor(0)(_ < segmentBytes.length, _ + 1) { index =>
            if (null == segmentBytes(index))
              segmentBytes(index) = compressor.compress(emptySegment, index)
          }

        case BandInterleave =>
          throw new Exception("Band interleave construction is not supported yet.")
      }

      GeoTiffMultibandTile(
        new ArraySegmentBytes(segmentBytes),
        compressor.createDecompressor,
        segmentLayout,
        compression,
        bandCount,
        cellType)
    }

    def makeGeoTiff(
      tile: MultibandTile,
      extent: Extent,
      crs: CRS,
      tags: Tags,
      options: GeoTiffOptions
    ) = MultibandGeoTiff(tile, extent, crs, tags, options)
  }
}
