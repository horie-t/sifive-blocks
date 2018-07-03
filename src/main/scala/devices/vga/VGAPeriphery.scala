// See LICENSE.HORIE_Tetsuya for license details.
package sifive.blocks.devices.vga

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}

case object PeripheryVGAKey extends Field[Seq[VGAParams]]

trait HasPeripheryVGA { this: BaseSubsystem =>
  private val vgaParams = p(PeripheryVGAKey)

  val vgas = vgaParams.zipWithIndex.map { case(params, i) =>
    val name = Some(s"vga_$i")
    val vga = LazyModule(new TLVGA(params.address, params.size, false, sbus.control_bus.beatBytes))
    sbus.control_bus.toVariableWidthSlave(name){ vga.node }
    vga
  }
}

trait HasPeripheryVGAModuleImp extends LazyModuleImp {
  val outer: HasPeripheryVGA
}
