// See LICENSE.HORIE_Tetsuya for license details.
package sifive.blocks.devices.vga

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

/** Size, location and contents of the VGA. */
case class VGAParams(
  address: BigInt = 0x10080000,
  size: Int = 0x80000)

class TLVGA(val base: BigInt, val size: Int, executable: Boolean = false, beatBytes: Int = 4,
  resources: Seq[Resource] = new SimpleDevice("vga", Seq("horie,vga")).reg("mem"))(implicit p: Parameters) extends LazyModule
{
  val node = TLManagerNode(Seq(TLManagerPortParameters(
    Seq(TLManagerParameters(
      address     = List(AddressSet(base, size-1)),
      resources   = resources,
      regionType  = RegionType.UNCACHEABLE,
      executable  = executable,
      supportsGet        = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      supportsPutFull    = TransferSizes(1, beatBytes),
      fifoId      = Some(0))),
    beatBytes = beatBytes)))

  lazy val module = new LazyModuleImp(this) {
    val width = 8 * beatBytes

    val (in, edge) = node.in(0)
    val addrBits = edge.addr_hi(in.a.bits.address - base.asUInt())(log2Ceil(size)-1, 0)
    val mem = Module(new Vram)

    // D stage registers from A
    val d_full      = RegInit(false.B)
    val d_ram_valid = RegInit(false.B) // true if we just read-out from SRAM
    val d_size      = Reg(UInt())
    val d_source    = Reg(UInt())
    val d_read      = Reg(Bool())
    val d_address   = Reg(UInt(addrBits.getWidth.W))
    val d_rmw_mask  = Reg(UInt(beatBytes.W))
    val d_rmw_data  = Reg(UInt(width.W))
    val d_poison    = Reg(Bool())

    // BRAM output
    val d_raw_data      = Wire(Bits(width.W))

    val d_wb = d_rmw_mask.orR
    val d_held_data = RegEnable(d_raw_data, d_ram_valid)

    in.d.bits.opcode  := Mux(d_read, TLMessages.AccessAckData, TLMessages.AccessAck)
    in.d.bits.param   := 0.U
    in.d.bits.size    := d_size
    in.d.bits.source  := d_source
    in.d.bits.sink    := 0.U
    in.d.bits.denied  := false.B
    in.d.bits.data    := Mux(d_ram_valid, d_raw_data, d_held_data)
    in.d.bits.corrupt := false.B

    // Formulate a response only when SRAM output is unused or correct
    in.d.valid := d_full
    in.a.ready := !d_full || (in.d.ready && !d_wb)

    val a_address = addrBits
    val a_read = in.a.bits.opcode === TLMessages.Get
    val a_data = in.a.bits.data
    val a_mask = in.a.bits.mask

    val a_ren = a_read

    when (in.d.fire()) { d_full := false.B }
    d_ram_valid := false.B
    d_rmw_mask  := 0.U
    when (in.a.fire()) {
      d_full      := true.B
      d_ram_valid := a_ren
      d_size      := in.a.bits.size
      d_source    := in.a.bits.source
      d_read      := a_read
      d_address   := a_address
      d_rmw_mask  := 0.U
      d_poison    := in.a.bits.corrupt
      when (!a_read) {
        d_rmw_mask := in.a.bits.mask
        d_rmw_data := in.a.bits.data
      }
    }

    // BRAM arbitration
    val a_fire = in.a.fire()
    val wen =  d_wb || (a_fire && !a_ren)
    val ren = !wen && a_fire

    val addr   = Mux(d_wb, d_address, a_address)
    val dat    = Mux(d_wb, d_rmw_data, a_data)
    val mask   = Mux(d_wb, d_rmw_mask, a_mask)

    mem.io.clkb  := clock
    mem.io.enb   := wen | ren
    mem.io.web   := Mux(wen, mask, 0.U)
    mem.io.addrb := addr
    mem.io.dinb  := dat
    d_raw_data   := mem.io.doutb

    // Tie off unused channels
    in.b.valid := false.B
    in.c.ready := true.B
    in.e.ready := true.B
  }
}

