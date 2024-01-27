package testchipip.serdes

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._

trait SerialPhyParams {
  val phitWidth: Int
  val flitWidth: Int
  val asyncQueueSz: Int
  def genIO: Bundle
}

case class InternalSyncSerialPhyParams(
  phitWidth: Int = 4,
  flitWidth: Int = 16,
  freqMHz: Int = 100,
  asyncQueueSz: Int = 8) extends SerialPhyParams {
  def genIO = new InternalSyncPhitIO(phitWidth)
}

case class ExternalSyncSerialPhyParams(
  phitWidth: Int = 4,
  flitWidth: Int = 16,
  asyncQueueSz: Int = 8) extends SerialPhyParams {
  def genIO = new ExternalSyncPhitIO(phitWidth)
}

case class SourceSyncSerialPhyParams(
  phitWidth: Int = 4,
  flitWidth: Int = 14,
  freqMHz: Int = 100,
  asyncQueueSz: Int = 16) extends SerialPhyParams {
  def genIO = new SourceSyncPhitIO(phitWidth)
}

