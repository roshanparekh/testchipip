package sifive.blocks.devices.uart

import chisel3._
import chisel3.util._
import chisel3.core.{IntParam, StringParam}

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.{BaseSubsystem}
import freechips.rocketchip.config.{Field}

import sifive.blocks.devices.uart._
import testchipip.{SerialIO}

object UARTAdapterConsts {
  val DATA_WIDTH = 8
}
import UARTAdapterConsts._

/**
 * Module to connect with a DUT UART and converts the UART signal to/from DATA_WIDTH
 * packets.
 *
 * @param uartno the uart number
 * @param baudrate the uart baudrate to deserialize/serialize data
 */
class UARTAdapter(uartno: Int, baudrate: BigInt)(implicit p: Parameters) extends Module
{
  val io = IO(new Bundle {
    val uart = Flipped(new UARTPortIO)
  })

  val frequency = p(PeripheryBusKey).frequency
  val div = (p(PeripheryBusKey).frequency / baudrate).toInt

  val txfifo = Module(new Queue(UInt(DATA_WIDTH.W), 128))
  val rxfifo = Module(new Queue(UInt(DATA_WIDTH.W), 128))

  val uart_io = io.uart

  val sTxIdle :: sTxWait :: sTxData :: sTxBreak :: Nil = Enum(4)
  val txState = RegInit(sTxIdle)
  val txData = Reg(UInt(DATA_WIDTH.W))
  // iterate through bits in byte to deserialize
  val (txDataIdx, txDataWrap) = Counter(txState === sTxData && txfifo.io.enq.ready, DATA_WIDTH)
  // iterate using div to convert clock rate to baud
  val (txBaudCount, txBaudWrap) = Counter(txState === sTxWait && txfifo.io.enq.ready, div)
  val (txSlackCount, txSlackWrap) = Counter(txState === sTxIdle && uart_io.txd === 0.U && txfifo.io.enq.ready, 4)

  switch(txState) {
    is(sTxIdle) {
      when(txSlackWrap) {
        txData  := 0.U
        txState := sTxWait
      }
    }
    is(sTxWait) {
      when(txBaudWrap) {
        txState := sTxData
      }
    }
    is(sTxData) {
      when (txfifo.io.enq.ready) {
        txData := txData | (uart_io.txd << txDataIdx)
      }
      when(txDataWrap) {
        txState := Mux(uart_io.txd === 1.U, sTxIdle, sTxBreak)
      }.elsewhen(txfifo.io.enq.ready) {
        txState := sTxWait
      }
    }
    is(sTxBreak) {
      when(uart_io.txd === 1.U && txfifo.io.enq.ready) {
        txState := sTxIdle
      }
    }
  }

  txfifo.io.enq.bits  := txData
  txfifo.io.enq.valid := txDataWrap

  val sRxIdle :: sRxStart :: sRxData :: Nil = Enum(3)
  val rxState = RegInit(sRxIdle)
  // iterate using div to convert clock rate to baud
  val (rxBaudCount, rxBaudWrap) = Counter(txfifo.io.enq.ready, div)
  // iterate through bits in byte to deserialize
  val (rxDataIdx, rxDataWrap) = Counter(rxState === sRxData && txfifo.io.enq.ready && rxBaudWrap, DATA_WIDTH)

  uart_io.rxd := 1.U
  switch(rxState) {
    is(sRxIdle) {
      uart_io.rxd := 1.U
      when (rxBaudWrap && rxfifo.io.deq.valid) {
        rxState := sRxStart
      }
    }
    is(sRxStart) {
      uart_io.rxd := 0.U
      when(rxBaudWrap) {
        rxState := sRxData
      }
    }
    is(sRxData) {
      uart_io.rxd := (rxfifo.io.deq.bits >> rxDataIdx)(0)
      when(rxDataWrap && rxBaudWrap) {
        rxState := sRxIdle
      }
    }
  }
  rxfifo.io.deq.ready := (rxState === sRxData) && rxDataWrap && rxBaudWrap && txfifo.io.enq.ready

  val sim = Module(new SimUART(uartno))

  sim.io.clock := clock
  sim.io.reset := reset.asBool

  sim.io.serial.out.bits := txfifo.io.deq.bits
  sim.io.serial.out.valid := txfifo.io.deq.valid
  txfifo.io.deq.ready := sim.io.serial.out.ready

  rxfifo.io.enq.bits := sim.io.serial.in.bits
  rxfifo.io.enq.valid := sim.io.serial.in.valid
  sim.io.serial.in.ready := rxfifo.io.enq.ready
}

/**
 * Module to connect to a *.v blackbox that uses DPI calls to interact with the DUT UART.
 *
 * @param uartno the uart number
 */
class SimUART(uartno: Int) extends BlackBox(Map("UARTNO" -> IntParam(uartno))) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val serial = Flipped(new SerialIO(DATA_WIDTH))
  })

  addResource("/testchipip/vsrc/SimUART.v")
  addResource("/testchipip/csrc/SimUART.cc")
  addResource("/testchipip/csrc/uart.cc")
  addResource("/testchipip/csrc/uart.h")
}

//************************************************************************************
// Traits to add a SiFive Blocks UART to the DUT and optionally add UARTAdapters
// to the outer system to interact with the DUT UART.
//************************************************************************************

trait CanHavePeripheryUARTAdapterModuleImp extends HasPeripheryUARTModuleImp {
  implicit val p: Parameters
  val outer: HasPeripheryUART

  /**
   * Connect the DUT UARTs to a UARTAdapter in the outer system.
   */
  def connectSimUARTs() = {
    val defaultBaudRate = 115200 // matches with the sifive-blocks uart baudrate
    uart.zipWithIndex.foreach{ case (dut_io, i) =>
      val uart_sim = Module(new UARTAdapter(i, defaultBaudRate)(p))
      uart_sim.io.uart.txd := dut_io.txd
      dut_io.rxd := uart_sim.io.uart.rxd
    }
  }
}
